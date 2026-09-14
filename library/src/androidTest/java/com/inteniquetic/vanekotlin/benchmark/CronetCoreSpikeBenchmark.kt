package com.inteniquetic.vanekotlin.benchmark

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inteniquetic.vanekotlin.VaneConfigurationBuilder
import com.inteniquetic.vanekotlin.VaneHeader
import com.inteniquetic.vanekotlin.VaneProtocolMode
import com.inteniquetic.vanekotlin.VaneRequest
import com.inteniquetic.vanekotlin.VaneResponse
import com.inteniquetic.vanekotlin.VaneSession
import com.inteniquetic.vanekotlin.createDefaultConfig
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.junit.Test
import org.junit.runner.RunWith

// ponytail: GET-only, no body, no pins — measures the transport swap and nothing else.
internal class CronetSpikeTransport(private val engine: CronetEngine) {
    private val executor = Executors.newSingleThreadExecutor()

    suspend fun execute(request: VaneRequest): VaneResponse = suspendCancellableCoroutine { cont ->
        val body = ByteArrayOutputStream()
        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(r: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) =
                r.followRedirect()

            override fun onResponseStarted(r: UrlRequest, info: UrlResponseInfo) =
                r.read(ByteBuffer.allocateDirect(64 * 1024))

            override fun onReadCompleted(r: UrlRequest, info: UrlResponseInfo, buffer: ByteBuffer) {
                buffer.flip()
                val chunk = ByteArray(buffer.remaining())
                buffer.get(chunk)
                body.write(chunk)
                buffer.clear()
                r.read(buffer)
            }

            override fun onSucceeded(r: UrlRequest, info: UrlResponseInfo) {
                cont.resume(
                    VaneResponse(
                        statusCode = info.httpStatusCode.toUShort(),
                        headers = info.allHeadersAsList.map { VaneHeader(it.key.lowercase(), it.value) },
                        body = body.toByteArray(),
                        bodyFilePath = null,
                        isSuccess = info.httpStatusCode in 200..299,
                        url = info.url,
                        httpVersion = null,
                        remoteIp = null,
                    )
                )
            }

            override fun onFailed(r: UrlRequest, info: UrlResponseInfo?, error: CronetException) =
                cont.resumeWithException(error)

            override fun onCanceled(r: UrlRequest, info: UrlResponseInfo?) {
                cont.cancel()
            }
        }
        val builder = engine.newUrlRequestBuilder(request.url, callback, executor)
            .setHttpMethod(request.method)
        request.headers.forEach { (name, value) -> builder.addHeader(name, value) }
        val urlRequest = builder.build()
        cont.invokeOnCancellation { urlRequest.cancel() }
        urlRequest.start()
    }
}

@RunWith(AndroidJUnit4::class)
class CronetCoreSpikeBenchmark {
    @Test
    fun sessionOverCronetAgainstRawCronetAndRust() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("VANE_BENCH_URL") ?: "https://cloudflare-quic.com/"
        val rounds = args.getString("VANE_BENCH_ROUNDS")?.toInt() ?: 6
        val perRound = args.getString("VANE_BENCH_REQUESTS")?.toInt() ?: 10
        val host = URI(url).host
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        fun engine() = CronetEngine.Builder(context)
            .enableQuic(true).enableHttp2(true).addQuicHint(host, 443, 443).build()

        val raw = CronetSpikeTransport(engine())
        val viaSession = CronetSpikeTransport(engine())
        val sessionCronet = VaneSession(createDefaultConfig()) { req -> viaSession.execute(req) }
        val sessionRust = VaneSession(
            VaneConfigurationBuilder().protocolMode(VaneProtocolMode.HTTP3_ONLY).build()
        )
        val rawRequest = VaneRequest(
            url = url, method = "GET", headers = emptyMap(), queryParams = emptyMap(),
            body = null, bodyFilePath = null, responseBodyPath = null,
            cancelTokenId = null, progressId = null, timeoutSeconds = 30u, followRedirects = true,
        )

        val contenders = listOf<Pair<String, suspend () -> Unit>>(
            "raw-cronet" to { raw.execute(rawRequest) },
            "session-cronet" to { sessionCronet.get(url) },
            "session-rust" to { sessionRust.get(url) },
        )
        val samples = contenders.associate { it.first to mutableListOf<Double>() }

        repeat(5) { contenders.forEach { (_, fire) -> fire() } }
        repeat(rounds) { round ->
            val order = contenders.drop(round % contenders.size) + contenders.take(round % contenders.size)
            order.forEach { (name, fire) ->
                repeat(perRound) {
                    val start = System.nanoTime()
                    fire()
                    samples.getValue(name) += (System.nanoTime() - start) / 1e6
                }
            }
        }

        val p50 = samples.mapValues { (_, v) -> v.sorted()[v.size / 2] }
        p50.forEach { (name, ms) -> Log.i(TAG, "p50 %-15s %.1f ms".format(name, ms)) }
        Log.i(TAG, "G1 session-cronet - raw-cronet = %.1f ms".format(p50.getValue("session-cronet") - p50.getValue("raw-cronet")))
        Log.i(TAG, "G2 session-rust - session-cronet = %.1f ms".format(p50.getValue("session-rust") - p50.getValue("session-cronet")))
    }

    private companion object {
        const val TAG = "CRONETSPIKE"
    }
}
