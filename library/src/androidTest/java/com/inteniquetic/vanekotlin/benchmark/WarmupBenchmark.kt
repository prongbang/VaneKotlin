// Cold-start benchmark for `warmup()`: the number it exists to move is the
// TCP transport's first request on a fresh process (0.87–1.07 s in
// ProtocolMatrixBenchmark on the API 35 emulator), which lazily builds the
// tokio runtime, TLS config and platform trust verifier — and pays the trust
// store's first-verification cost — inside that request.
//
//   VANE_TEST_BASE_URL=https://cloudflare-quic.com VaneKotlin/bench-warmup.sh
//
// Deliberately a separate file from ProtocolMatrixBenchmark: its `cold`
// column keeps meaning "first request, no warmup" — this adds the warmed
// variant beside it instead of redefining it.
//
// Cold is a once-per-process fact (conscrypt/trust-store init is
// process-global), so a number here is only valid when its test is the first
// vane activity in the process. bench-warmup.sh therefore runs each @Test in
// its own `am instrument` invocation, several times; running the whole class
// in one process would make every result after the first quietly warm.
//
// Output: one `WARMUP_BENCH ...` line per run appended to
// files/vane-warmup-bench.txt (pulled by the script) and mirrored to logcat.
package com.inteniquetic.vanekotlin.benchmark

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inteniquetic.vanekotlin.VaneClient
import com.inteniquetic.vanekotlin.VaneConfigurationBuilder
import com.inteniquetic.vanekotlin.VaneProtocolMode
import com.inteniquetic.vanekotlin.createVaneClient
import java.io.File
import java.net.InetAddress
import java.net.URI
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WarmupBenchmark {

    private fun baseUrl(): String {
        val base = (InstrumentationRegistry.getArguments().getString("VANE_TEST_BASE_URL") ?: "")
            .trimEnd('/')
        assumeTrue(
            "Set VANE_TEST_BASE_URL=https://<origin> as an instrumentation " +
                "argument (VaneKotlin/bench-warmup.sh does).",
            base.startsWith("https://"),
        )
        return base
    }

    private fun freshClient(mode: VaneProtocolMode): VaneClient = createVaneClient(
        VaneConfigurationBuilder()
            .protocolMode(mode)
            .timeout(30uL)
            .build(),
    )

    /** One cold measurement: optionally `warmup(url)` (timed), then the first
     * real request (timed). Every stage runs on this fresh client in this
     * fresh process. */
    private fun measure(name: String, mode: VaneProtocolMode, warmFirst: Boolean) {
        val base = baseUrl()
        val url = "$base/"
        // Same resolver pre-warm as ProtocolMatrixBenchmark, so the numbers
        // isolate the client stack rather than the emulator's DNS proxy.
        InetAddress.getAllByName(URI(url).host)

        val client = freshClient(mode)
        try {
            val warmupNanos = if (warmFirst) {
                val start = System.nanoTime()
                client.warmup(url)
                System.nanoTime() - start
            } else {
                null
            }
            val start = System.nanoTime()
            val response = client.getRequest(url)
            val firstNanos = System.nanoTime() - start
            check(response.statusCode.toInt() == 200) {
                "$name: HTTP ${response.statusCode} from $url"
            }

            val line = "WARMUP_BENCH test=$name" +
                " warmup_ms=" + (warmupNanos?.let { String.format("%.2f", it / 1e6) } ?: "-") +
                " first_request_ms=" + String.format("%.2f", firstNanos / 1e6) +
                " proto=${response.httpVersion}"
            Log.i("WarmupBench", line)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.filesDir, "vane-warmup-bench.txt").appendText(line + "\n")
        } finally {
            client.destroy()
        }
    }

    @Test
    fun tcpCold() = measure("tcpCold", VaneProtocolMode.HTTP2_ONLY, warmFirst = false)

    @Test
    fun tcpColdAfterWarmup() =
        measure("tcpColdAfterWarmup", VaneProtocolMode.HTTP2_ONLY, warmFirst = true)

    @Test
    fun h3Cold() = measure("h3Cold", VaneProtocolMode.HTTP3_ONLY, warmFirst = false)

    @Test
    fun h3ColdAfterWarmup() =
        measure("h3ColdAfterWarmup", VaneProtocolMode.HTTP3_ONLY, warmFirst = true)
}
