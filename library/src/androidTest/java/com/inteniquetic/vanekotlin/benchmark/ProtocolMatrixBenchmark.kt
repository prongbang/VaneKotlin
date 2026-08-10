// Cross-client × protocol latency matrix on Android: vane (the shipped AAR +
// jniLibs), Cronet (embedded Chromium network stack — the only other HTTP/3
// speaker here), OkHttp, and Retrofit2-over-OkHttp (to show what the wrapper
// costs over its own engine) — each pinned to every HTTP version it can
// reach, in one process, against one endpoint, sequentially.
//
//   VANE_TEST_BASE_URL=https://cloudflare-quic.com VaneKotlin/bench-android.sh
//
// Methodology mirrors vane_benchmark/test/benchmark_test.dart exactly (that
// file is the reference; full caveats in VaneKotlin/BENCHMARK.md):
// - Per client: 1 cold request (reported alone), then warmup requests
//   (discarded), then rounds × requests measured requests. p50/p95 are
//   nearest-rank over the pooled measured samples, same formula as
//   vane-rs/examples/bench.rs.
// - The visiting order rotates by one each round so no client systematically
//   rides a warmer network than the others.
// - The proto column is read off each response (VaneResponse.httpVersion,
//   UrlResponseInfo.negotiatedProtocol, okhttp Response.protocol), never
//   assumed. A row that negotiates something other than its pin gets a NOTE;
//   a pinned cell that cannot reach its protocol is an ERROR row, never a
//   silent downgrade. Cells that cannot exist (OkHttp/Retrofit h3) are
//   printed as "unsupported" — that Vane and Cronet cover the h3 column and
//   the others do not IS a result.
// - Pooling/keep-alive is ON for every client (each one's default).
// - Emulator numbers go through the host's NAT: they are NOT device numbers
//   and not comparable in absolute terms to the Dart host-VM results.
//
// Knobs (instrumentation args, same names as the Dart harness's env vars):
// VANE_TEST_BASE_URL (required, https), VANE_BENCH_ROUNDS (3),
// VANE_BENCH_REQUESTS per round (10), VANE_BENCH_WARMUP (5).
//
// Output: a grouped-by-protocol table plus a combined table (stdout/logcat
// and files/vane-bench-android.txt), and a JSON twin in the SAME schema the
// Dart harness writes — plus "platform": "android" — at
// files/vane-bench-android.json, so all platforms merge into one chart.
package com.inteniquetic.vanekotlin.benchmark

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inteniquetic.vanekotlin.VaneClient
import com.inteniquetic.vanekotlin.VaneConfigurationBuilder
import com.inteniquetic.vanekotlin.VaneHttpVersion
import com.inteniquetic.vanekotlin.VaneProtocolMode
import com.inteniquetic.vanekotlin.createVaneClient
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url

/** Converter-less Retrofit surface: raw bytes, absolute URL, blocking Call —
 * so the retrofit rows measure Retrofit's own dispatch over the same OkHttp
 * sync path the okhttp rows use, not a converter or coroutine adapter. */
interface BenchService {
    @GET
    fun get(@Url url: String): Call<ResponseBody>
}

@RunWith(AndroidJUnit4::class)
class ProtocolMatrixBenchmark {

    /** One request's outcome; the surrounding clock does the timing. */
    private data class Shot(val status: Int, val bytes: Int, val proto: String)

    private class Contender(
        /** Unique display name; states the pinned config (e.g. `cronet (h2)`). */
        val name: String,
        /** Protocol group this row is pinned to: the table it belongs in. */
        val group: String,
        val fire: (String) -> Shot,
    ) {
        var coldNanos: Long? = null
        val rounds = mutableListOf<MutableList<Long>>()
        val protocols = sortedSetOf<String>()
        var bodyBytes: Int? = null
        var failure: Throwable? = null
        val pooled: List<Long> get() = rounds.flatten().sorted()
    }

    private companion object {
        const val GUARD_SECONDS = 30L
        val GROUPS = listOf("HTTP/1.1", "HTTP/2", "HTTP/3")

        /** Cells that cannot exist, printed per group so their absence is a
         * stated result rather than a silently missing row. */
        val UNSUPPORTED = listOf(
            Triple(
                "HTTP/3", "okhttp",
                "OkHttp speaks HTTP/1.1 and HTTP/2 only; it has no HTTP/3/QUIC transport",
            ),
            Triple(
                "HTTP/3", "retrofit2 (okhttp)",
                "Retrofit rides OkHttp, which has no HTTP/3/QUIC transport",
            ),
        )

        fun argInt(name: String, fallback: Int): Int =
            InstrumentationRegistry.getArguments().getString(name)?.toIntOrNull() ?: fallback

        fun vaneProto(version: VaneHttpVersion?): String = when (version) {
            VaneHttpVersion.HTTP10 -> "HTTP/1.0"
            VaneHttpVersion.HTTP11 -> "HTTP/1.1"
            VaneHttpVersion.HTTP2 -> "HTTP/2"
            VaneHttpVersion.HTTP3 -> "HTTP/3"
            null -> "unknown"
        }

        fun okhttpProto(protocol: Protocol): String = when (protocol) {
            Protocol.HTTP_1_0 -> "HTTP/1.0"
            Protocol.HTTP_1_1 -> "HTTP/1.1"
            Protocol.HTTP_2, Protocol.H2_PRIOR_KNOWLEDGE -> "HTTP/2"
            else -> protocol.toString()
        }

        /** Cronet reports ALPN-style strings ("h2", "http/1.1", "h3", legacy
         * "quic/…"); mapped onto the same labels as everyone else so the
         * pin-vs-observed comparison works. Unknown strings pass through raw. */
        fun cronetProto(negotiated: String?): String = when {
            negotiated.isNullOrEmpty() || negotiated == "unknown" -> "unknown"
            negotiated == "http/1.1" || negotiated == "http/1.0" ->
                "HTTP/" + negotiated.removePrefix("http/")
            negotiated == "h2" -> "HTTP/2"
            negotiated.startsWith("h3") || negotiated.startsWith("quic") -> "HTTP/3"
            else -> negotiated
        }

        /** Nearest-rank percentile over a sorted list — the exact formula
         * vane-rs/examples/bench.rs and the Dart harness use. */
        fun percentile(sorted: List<Long>, pct: Double): Long {
            if (sorted.isEmpty()) return 0L
            val rank = ((pct / 100.0) * (sorted.size - 1)).roundToInt()
            return sorted[rank.coerceIn(0, sorted.size - 1)]
        }

        /** Nanos → ms via whole microseconds, exactly the precision the Dart
         * harness's `inMicroseconds / 1000.0` emits, so JSONs diff cleanly. */
        fun msOf(nanos: Long): Double = (nanos / 1_000) / 1000.0

        fun fmtMs(nanos: Long?): String =
            if (nanos == null) "-" else String.format("%.2f", msOf(nanos))

        fun row(name: String, cells: List<String>): String =
            name.padEnd(22) + cells.joinToString("") { it.padStart(9) }
    }

    /** Blocking adapter over Cronet's callback API: collects the body, then
     * releases the caller. Cronet has no synchronous mode, so the measured
     * time includes its executor handoff — that is the API every app pays. */
    private class CronetShot : UrlRequest.Callback() {
        val body = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        var info: UrlResponseInfo? = null
        var error: CronetException? = null

        override fun onRedirectReceived(
            request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String,
        ) = request.followRedirect()

        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) =
            request.read(ByteBuffer.allocateDirect(64 * 1024))

        override fun onReadCompleted(
            request: UrlRequest, info: UrlResponseInfo, buffer: ByteBuffer,
        ) {
            buffer.flip()
            val chunk = ByteArray(buffer.remaining())
            buffer.get(chunk)
            body.write(chunk)
            buffer.clear()
            request.read(buffer)
        }

        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
            this.info = info
            done.countDown()
        }

        override fun onFailed(
            request: UrlRequest, info: UrlResponseInfo?, error: CronetException,
        ) {
            this.info = info
            this.error = error
            done.countDown()
        }

        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) =
            done.countDown()
    }

    @Test
    fun crossClientProtocolLatencyMatrix() {
        val args = InstrumentationRegistry.getArguments()
        val base = (args.getString("VANE_TEST_BASE_URL") ?: "").trimEnd('/')
        assumeTrue(
            "Set VANE_TEST_BASE_URL=https://<origin serving h1.1+h2+h3> as an " +
                "instrumentation argument (VaneKotlin/bench-android.sh does).",
            base.startsWith("https://"),
        )
        val url = "$base/"
        val host = URI(url).host
        val port = URI(url).port.let { if (it == -1) 443 else it }

        val roundCount = argInt("VANE_BENCH_ROUNDS", 3)
        val perRound = argInt("VANE_BENCH_REQUESTS", 10)
        val warmup = argInt("VANE_BENCH_WARMUP", 5)

        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // --- Clients, one per (client, pin) cell. All constructed up front so
        // "cold" means first request on an idle client, not construction.

        // Vane: the exact modes the Dart harness pins. h1/h2 ride the TCP
        // fallback transport (h2 = prior knowledge, like reqwest/rhttp's h2
        // pin); h3 dials QUIC directly. The jniLibs in the AAR are the default
        // tcp-fallback build, so all three exist.
        fun vaneAt(mode: VaneProtocolMode): VaneClient = createVaneClient(
            VaneConfigurationBuilder()
                .protocolMode(mode)
                .timeout(GUARD_SECONDS.toULong())
                .build(),
        )

        val vaneClients = mapOf(
            "h1" to vaneAt(VaneProtocolMode.HTTP1_ONLY),
            "h2" to vaneAt(VaneProtocolMode.HTTP2_ONLY),
            "h3" to vaneAt(VaneProtocolMode.HTTP3_ONLY),
        )

        // Cronet: h1.1/h2 pins are hard switches on the engine. h3 has no
        // "only" switch anywhere in Cronet's API — QUIC is enabled explicitly
        // plus a QUIC hint for the host so it races QUIC from the start; the
        // observed-protocol column polices what actually happened (early
        // requests may negotiate h2 before the QUIC session is up, which
        // shows up as a NOTE, never silently).
        val cronetExecutor = Executors.newSingleThreadExecutor()
        fun cronetAt(configure: CronetEngine.Builder.() -> CronetEngine.Builder): CronetEngine =
            CronetEngine.Builder(context).configure().build()

        val cronetEngines = mapOf(
            "h1.1" to cronetAt { enableQuic(false).enableHttp2(false) },
            "h2" to cronetAt { enableQuic(false).enableHttp2(true) },
            "h3" to cronetAt { enableQuic(true).enableHttp2(true).addQuicHint(host, port, port) },
        )

        fun cronetFire(engine: CronetEngine, requestUrl: String): Shot {
            val cb = CronetShot()
            val request = engine.newUrlRequestBuilder(requestUrl, cb, cronetExecutor).build()
            request.start()
            if (!cb.done.await(GUARD_SECONDS, TimeUnit.SECONDS)) {
                request.cancel()
                cb.done.await(5, TimeUnit.SECONDS)
                error("cronet: request exceeded the ${GUARD_SECONDS}s guard")
            }
            cb.error?.let { throw it }
            val info = cb.info ?: error("cronet: completed without response info")
            return Shot(info.httpStatusCode, cb.body.size(), cronetProto(info.negotiatedProtocol))
        }

        // OkHttp: h1.1 is a hard pin. OkHttp cannot pin h2-only over TLS — its
        // API requires HTTP_1_1 in the list — so the h2 cell is "h2 via ALPN,
        // h1.1 permitted", and the observed column verifies h2 actually
        // happened. callTimeout is the same 30s whole-call guard as everyone.
        fun okhttpAt(protocols: List<Protocol>): OkHttpClient = OkHttpClient.Builder()
            .protocols(protocols)
            .callTimeout(GUARD_SECONDS, TimeUnit.SECONDS)
            .build()

        val okClients = mapOf(
            "h1.1" to okhttpAt(listOf(Protocol.HTTP_1_1)),
            "h2" to okhttpAt(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)),
        )

        fun okhttpFire(client: OkHttpClient, requestUrl: String): Shot =
            client.newCall(Request.Builder().url(requestUrl).build()).execute().use { resp ->
                Shot(resp.code, resp.body!!.bytes().size, okhttpProto(resp.protocol))
            }

        // Retrofit2: over OkHttp clients pinned identically to the okhttp
        // rows but constructed separately, so each row owns its connection
        // pool (same as the Dart harness's separate vane-dio clients) and
        // retrofit's cold number is a real handshake, not okhttp's warm pool.
        // The delta against the okhttp rows is then exactly what the wrapper
        // costs.
        val retrofitClients = mapOf(
            "h1.1" to okhttpAt(listOf(Protocol.HTTP_1_1)),
            "h2" to okhttpAt(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)),
        )
        val retrofitServices = retrofitClients.mapValues { (_, client) ->
            Retrofit.Builder()
                .baseUrl("$base/")
                .client(client)
                .build()
                .create(BenchService::class.java)
        }

        fun retrofitFire(service: BenchService, requestUrl: String): Shot {
            val resp = service.get(requestUrl).execute()
            val bytes = resp.body()?.use { it.bytes().size }
                ?: resp.errorBody()?.use { it.bytes().size } ?: 0
            return Shot(resp.code(), bytes, okhttpProto(resp.raw().protocol))
        }

        // Grouped construction order = grouped tables read in run order; the
        // per-round rotation below undoes any ordering advantage.
        val contenders = listOf(
            Contender("vane (h1)", "HTTP/1.1") { u ->
                val r = vaneClients.getValue("h1").getRequest(u)
                Shot(r.statusCode.toInt(), r.body.size, vaneProto(r.httpVersion))
            },
            Contender("vane (h2)", "HTTP/2") { u ->
                val r = vaneClients.getValue("h2").getRequest(u)
                Shot(r.statusCode.toInt(), r.body.size, vaneProto(r.httpVersion))
            },
            Contender("vane (h3)", "HTTP/3") { u ->
                val r = vaneClients.getValue("h3").getRequest(u)
                Shot(r.statusCode.toInt(), r.body.size, vaneProto(r.httpVersion))
            },
            Contender("cronet (h1.1)", "HTTP/1.1") { u ->
                cronetFire(cronetEngines.getValue("h1.1"), u)
            },
            Contender("cronet (h2)", "HTTP/2") { u ->
                cronetFire(cronetEngines.getValue("h2"), u)
            },
            Contender("cronet (h3)", "HTTP/3") { u ->
                cronetFire(cronetEngines.getValue("h3"), u)
            },
            Contender("okhttp (h1.1)", "HTTP/1.1") { u ->
                okhttpFire(okClients.getValue("h1.1"), u)
            },
            Contender("okhttp (h2)", "HTTP/2") { u ->
                okhttpFire(okClients.getValue("h2"), u)
            },
            Contender("retrofit2 (h1.1)", "HTTP/1.1") { u ->
                retrofitFire(retrofitServices.getValue("h1.1"), u)
            },
            Contender("retrofit2 (h2)", "HTTP/2") { u ->
                retrofitFire(retrofitServices.getValue("h2"), u)
            },
        )

        fun timed(c: Contender): Long {
            val start = System.nanoTime()
            val shot = c.fire(url)
            val elapsed = System.nanoTime() - start
            check(shot.status == 200) { "${c.name}: HTTP ${shot.status} from $url" }
            c.protocols.add(shot.proto)
            if (c.bodyBytes == null) c.bodyBytes = shot.bytes
            return elapsed
        }

        try {
            // The first client to touch the host would otherwise pay the
            // resolver's cache miss inside its cold number. Vane and Cronet
            // still resolve in-process, but this warms the emulator's DNS
            // proxy (and the host resolver behind it) for everyone.
            InetAddress.getAllByName(host)

            // Phase 1 — cold first request (fresh client, no pooled
            // connection), then discarded warmups.
            for (c in contenders) {
                try {
                    c.coldNanos = timed(c)
                    repeat(warmup) { timed(c) }
                } catch (t: Throwable) {
                    c.failure = t
                }
            }

            // Phase 2 — measured rounds, visiting order rotated by one each
            // round.
            for (round in 0 until roundCount) {
                for (i in contenders.indices) {
                    val c = contenders[(i + round) % contenders.size]
                    if (c.failure != null) continue
                    val samples = mutableListOf<Long>()
                    try {
                        repeat(perRound) { samples.add(timed(c)) }
                    } catch (t: Throwable) {
                        c.failure = t
                    }
                    c.rounds.add(samples)
                }
            }
        } finally {
            vaneClients.values.forEach { it.destroy() }
            cronetEngines.values.forEach { it.shutdown() }
            cronetExecutor.shutdown()
            (okClients.values + retrofitClients.values).forEach {
                it.dispatcher.executorService.shutdown()
                it.connectionPool.evictAll()
            }
        }

        // ---- Report ----
        val out = StringBuilder()
        fun p(line: String = "") {
            out.appendLine(line)
        }

        val deviceLine = "android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) " +
            "${Build.MODEL} ${Build.SUPPORTED_ABIS.first()}"
        p(
            "android bench base_url=$base rounds=$roundCount " +
                "requests_per_round=$perRound warmup=$warmup date=${LocalDateTime.now()}",
        )
        p("host=$deviceLine")
        p(
            "CAVEAT: emulator networking rides the host's NAT/userspace stack — " +
                "these are emulator numbers, not device numbers, and not " +
                "comparable in absolute terms to the Dart host-VM results.",
        )

        val header = row(
            "client",
            listOf("proto", "cold_ms", "p50_ms", "p95_ms", "min_ms", "max_ms", "n", "bytes"),
        )

        fun printMeasured(c: Contender) {
            val pooled = c.pooled
            if (pooled.isEmpty()) {
                p(row(c.name, emptyList())) // name line, then the error
                p("  ERROR ${c.failure}")
                return
            }
            p(
                row(
                    c.name,
                    listOf(
                        c.protocols.joinToString("+"),
                        fmtMs(c.coldNanos),
                        fmtMs(percentile(pooled, 50.0)),
                        fmtMs(percentile(pooled, 95.0)),
                        fmtMs(pooled.first()),
                        fmtMs(pooled.last()),
                        "${pooled.size}",
                        "${c.bodyBytes}",
                    ),
                ),
            )
            if (c.failure != null) {
                p("  PARTIAL: later requests failed with ${c.failure}")
            }
            if (c.protocols.size != 1 || c.protocols.single() != c.group) {
                p("  NOTE: negotiated ${c.protocols.joinToString("+")}, not the pinned ${c.group}")
            }
        }

        // Primary view — one table per protocol, so the like-for-like
        // comparison is the first thing a reader sees.
        for (group in GROUPS) {
            p()
            p("== $group ==")
            p(header)
            contenders.filter { it.group == group }.forEach(::printMeasured)
            UNSUPPORTED.filter { it.first == group }.forEach { (_, name, reason) ->
                p("${name.padEnd(22)}   unsupported: $reason")
            }
        }

        // Secondary view — every measured row in one table, for
        // cross-protocol reading within a client.
        p()
        p("== all rows ==")
        p(header)
        contenders.forEach(::printMeasured)

        p()
        p("per-round p50_ms (drift check):")
        for (c in contenders.filter { it.rounds.isNotEmpty() }) {
            val cells = c.rounds.mapIndexed { i, r ->
                "r${i + 1}=${fmtMs(percentile(r.sorted(), 50.0))}"
            }
            p("  ${c.name.padEnd(22)}${cells.joinToString("  ")}")
        }
        p()
        p(
            "pooling/keep-alive: ON for every client (each one's default). " +
                "One emulator, one network, sequential requests — RTT-dominated, " +
                "not a lab. See VaneKotlin/BENCHMARK.md.",
        )

        // Machine-readable twin, SAME schema as the Dart harness (plus
        // "platform") so all platforms merge into one chart. Written BEFORE
        // the assertions so a run that fails a Vane cell still leaves its
        // evidence on disk.
        val metrics = JSONObject().apply {
            put("schema", 1)
            put("date", LocalDateTime.now().toString())
            put("base_url", base)
            put("host", deviceLine)
            put("platform", "android")
            put("rounds", roundCount)
            put("requests_per_round", perRound)
            put("warmup", warmup)
            put(
                "rows",
                JSONArray().apply {
                    contenders.forEach { c ->
                        val pooled = c.pooled
                        put(
                            JSONObject().apply {
                                put("client", c.name)
                                put("pinned_protocol", c.group)
                                put("observed_protocol", c.protocols.joinToString("+"))
                                // Every client here reports its negotiated
                                // protocol; the key is kept for schema parity
                                // with the Dart rows.
                                put("protocol_stated_not_observed", false)
                                put("cold_ms", c.coldNanos?.let { msOf(it) } ?: JSONObject.NULL)
                                put(
                                    "p50_ms",
                                    if (pooled.isEmpty()) JSONObject.NULL
                                    else msOf(percentile(pooled, 50.0)),
                                )
                                put(
                                    "p95_ms",
                                    if (pooled.isEmpty()) JSONObject.NULL
                                    else msOf(percentile(pooled, 95.0)),
                                )
                                put(
                                    "min_ms",
                                    if (pooled.isEmpty()) JSONObject.NULL else msOf(pooled.first()),
                                )
                                put(
                                    "max_ms",
                                    if (pooled.isEmpty()) JSONObject.NULL else msOf(pooled.last()),
                                )
                                put("n", pooled.size)
                                put("body_bytes", c.bodyBytes ?: JSONObject.NULL)
                                put(
                                    "round_p50_ms",
                                    JSONArray().apply {
                                        c.rounds.forEach {
                                            put(msOf(percentile(it.sorted(), 50.0)))
                                        }
                                    },
                                )
                                put("failure", c.failure?.toString() ?: JSONObject.NULL)
                            },
                        )
                    }
                },
            )
            put(
                "unsupported",
                JSONArray().apply {
                    UNSUPPORTED.forEach { (group, name, reason) ->
                        put(
                            JSONObject().apply {
                                put("protocol", group)
                                put("client", name)
                                put("reason", reason)
                            },
                        )
                    }
                },
            )
        }

        // Internal storage: bench-android.sh pulls these with `run-as` (the
        // test APK is debuggable), which works on every API level where
        // external storage would need permissions gymnastics.
        val jsonFile = context.filesDir.resolve("vane-bench-android.json")
        jsonFile.writeText(metrics.toString(2) + "\n")
        val txtFile = context.filesDir.resolve("vane-bench-android.txt")
        txtFile.writeText(out.toString())
        p("metrics written to ${jsonFile.absolutePath}")

        println(out.toString())

        // The benchmark exists to measure Vane; a run where any Vane cell
        // failed must be loud, not a quietly shorter table.
        for (c in contenders.filter { it.name.startsWith("vane") }) {
            assertNull(
                "${c.name} failed — does $base serve ${c.group}, and do the " +
                    "packaged jniLibs carry the default tcp-fallback build? " +
                    "(${c.failure})",
                c.failure,
            )
            assertEquals(roundCount * perRound, c.pooled.size)
        }
    }
}
