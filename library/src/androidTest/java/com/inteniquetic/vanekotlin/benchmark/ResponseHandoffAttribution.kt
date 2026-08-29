package com.inteniquetic.vanekotlin.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inteniquetic.vanekotlin.VaneConfigurationBuilder
import com.inteniquetic.vanekotlin.VaneProgressBridge
import com.inteniquetic.vanekotlin.VaneProtocolMode
import com.inteniquetic.vanekotlin.VaneSession
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Splits one request's wall time into "the core was still downloading" and
 * "the core had every byte and had not returned yet", on a device.
 *
 * Written 2026-08-30 because the device benchmark found vane ~20 ms behind
 * Cronet at p50 on a 126 KB body, ~4 ms behind on a 2.5 KB one — a cost that
 * tracks bytes transferred, not requests — and the same on HTTP/1.1, HTTP/2
 * and HTTP/3, which are two unrelated transport stacks. That narrows it to
 * what those paths share, but "narrows" is not "identifies", and this repo
 * has twice acted on an aggregate percentile and been wrong about the
 * mechanism. So: attribute, do not infer.
 *
 * The split needs no library change. `VaneProgressBridge` publishes received
 * bytes from inside the core, so a poller sampling it far faster than the
 * shipped 100 ms callback records when the last byte landed; the request call
 * returns later, and the difference is everything that happens after the
 * transport is done — response assembly, the UniFFI record, the FFI return,
 * and the Kotlin-side ByteArray.
 *
 * Reads as a benchmark, not a gate: it prints and asserts only that the
 * measurement is coherent, because a threshold here would encode today's
 * network as a requirement.
 */
@RunWith(AndroidJUnit4::class)
class ResponseHandoffAttribution {

    @Test
    fun whereTheTimeGoesBetweenTheLastByteAndTheReturn() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("VANE_TEST_BASE_URL")?.trimEnd('/')?.plus("/")
            ?: "https://cloudflare-quic.com/"
        val rounds = args.getString("VANE_BENCH_REQUESTS")?.toIntOrNull() ?: 12

        val report = StringBuilder()
        report.appendLine("url=$url rounds=$rounds")
        report.appendLine(
            "mode  bytes   total_ms  download_ms  handoff_ms  handoff_%  MB/s_handoff"
        )

        for (mode in listOf(
            VaneProtocolMode.HTTP1_ONLY,
            VaneProtocolMode.HTTP2_ONLY,
            VaneProtocolMode.HTTP3_ONLY,
        )) {
            // VaneSession, not the generated client: the request builder — and
            // therefore the progress id this measurement borrows — lives on the
            // wrapper. Same transport underneath.
            val client = VaneSession(
                VaneConfigurationBuilder().protocolMode(mode).timeout(30u).build()
            )
            // Warm the pool: a cold connection would put handshake time in the
            // download half and make the split meaningless.
            repeat(3) { runCatching { runBlocking { client.get(url) } } }

            val totals = ArrayList<Double>()
            val handoffs = ArrayList<Double>()
            var bytes = 0

            // The library owns the progress id, so borrow it at the seam that
            // exists for exactly this: VaneProgressBridge.create is a var. The
            // shipped callback polls every 100 ms, far too coarse for a request
            // this short, so the id is polled here instead, flat out.
            val realCreate = VaneProgressBridge.create
            val captured = AtomicReference<ULong?>(null)
            VaneProgressBridge.create = { realCreate().also { captured.set(it) } }
            try {
                repeat(rounds) {
                    captured.set(null)
                    val lastByteAt = AtomicLong(0)
                    val stop = AtomicLong(0)
                    val poller = thread(isDaemon = true) {
                        var id: ULong? = null
                        while (stop.get() == 0L) {
                            if (id == null) { id = captured.get(); continue }
                            val snap = runCatching { VaneProgressBridge.snapshot(id!!) }
                                .getOrNull() ?: break
                            if (snap.done ||
                                (snap.downloadTotal > 0uL &&
                                    snap.downloadReceived >= snap.downloadTotal)
                            ) {
                                lastByteAt.set(System.nanoTime())
                                break
                            }
                        }
                    }

                    val started = System.nanoTime()
                    val response = runBlocking {
                        client.request(url).onDownloadProgress { _, _ -> }.execute()
                    }
                    val returned = System.nanoTime()
                    stop.set(1)
                    poller.join(2_000)

                    bytes = response.body.size
                    val mark = lastByteAt.get()
                    if (mark != 0L && mark in started..returned) {
                        totals += (returned - started) / 1_000_000.0
                        handoffs += (returned - mark) / 1_000_000.0
                    }
                }
            } finally {
                VaneProgressBridge.create = realCreate
            }

            if (totals.isEmpty()) {
                report.appendLine("$mode  no sample — the poller never observed completion")
                continue
            }
            val total = totals.sorted()[totals.size / 2]
            val handoff = handoffs.sorted()[handoffs.size / 2]
            val mbps = if (handoff > 0) (bytes / 1_048_576.0) / (handoff / 1000.0) else 0.0
            report.appendLine(
                "%-5s %7d %9.2f %12.2f %11.2f %9.1f %13.1f".format(
                    when (mode) {
                        VaneProtocolMode.HTTP1_ONLY -> "h1"
                        VaneProtocolMode.HTTP2_ONLY -> "h2"
                        else -> "h3"
                    },
                    bytes, total, total - handoff, handoff, 100.0 * handoff / total, mbps
                )
            )
        }

        val text = report.toString()
        println(text)
        val out = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "vane-handoff-attribution.txt"
        )
        out.writeText(text)
        assertTrue("attribution produced no rows", text.lines().size > 2)
    }
}
