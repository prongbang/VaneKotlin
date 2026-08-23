package com.inteniquetic.vanekotlin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Collections
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the one composition no other test spans: the wrapper's demand-driven
 * pull loop ([withStreamedBody] via [VaneClient.executeAsync]) feeding the
 * REAL core's blocking `write_body_stream_chunk` while a REAL transport
 * drains it. The core proves backpressure with its own harnesses, and the
 * JVM tests prove the pull loop against `FakeBodyStreamCore` — but a fake
 * acks writes instantly, so a wrapper that buffers the whole source and a
 * lockstep wrapper look identical there. Only a live drain separates them.
 *
 * The drain gauge is the core's own progress counter, read FRESH at every
 * pull through a test-owned [createProgress] id — never the production
 * 100 ms poller, whose staleness at WAN throughput would spuriously fail a
 * correct wrapper. The core stores `uploadSent` synchronously inside the
 * transports' drain paths, so at any instant it understates queue-drain by
 * at most two 64 KiB chunks, deterministically, on both transports.
 *
 * Mutant killed: a wrapper that collects the flow into memory (or through
 * any unbounded intermediate buffer, or fires writes concurrently instead
 * of awaiting each). Its 64 pulls complete at memory speed before the live
 * handshake can carry a body byte, so pulls from index ~9 on violate the
 * bound by hundreds of KiB. A correct wrapper cannot violate it at ANY
 * network speed: pull k happens only after write k-1 returned, whose
 * admission required queued < 256 KiB (lib.rs:260), so
 * `producedBefore = k*C <= uploadSent + 256 KiB + 3C` by code order — the
 * asserted 512 KiB adds one more chunk of margin. Not a race.
 *
 * Live and therefore gated, the same shape as [ExampleInstrumentedTest]:
 * runs only when VANE_TEST_BASE_URL names an https origin (pie.dev is the
 * usual one), and skips cleanly otherwise.
 */
@RunWith(AndroidJUnit4::class)
class VaneUploadBackpressureLiveInstrumentedTest {

    companion object {
        private const val BASE_URL_ARG = "VANE_TEST_BASE_URL"

        /** 64 KiB per pull — the same chunk size every sibling suite uses. */
        private const val CHUNK = 64 * 1024

        /** 64 chunks = 4 MiB, 16x the core's 256 KiB buffer. */
        private const val CHUNKS = 64

        /**
         * 256 KiB core buffer + 4 chunks: the admission-arithmetic bound
         * (256 KiB + 3C) plus one chunk of margin. One-sided — production
         * may lag drain arbitrarily on a slow network without failing.
         */
        private const val ALLOWANCE = 512L * 1024

        private fun baseUrlOrSkip(): String {
            val args = InstrumentationRegistry.getArguments()
            val baseUrl = args.getString(BASE_URL_ARG)
                ?: args.getString("baseUrl")
                ?: System.getenv(BASE_URL_ARG)
                ?: ""
            assumeTrue(
                "Set $BASE_URL_ARG=https://<host> as an instrumentation argument to run live Vane tests.",
                baseUrl.startsWith("https://")
            )
            return baseUrl.trimEnd('/')
        }
    }

    @Test
    fun aLiveStreamedUploadNeverRunsAheadOfTheTransport() = runBlocking {
        val base = baseUrlOrSkip()
        val config = VaneConfigurationBuilder()
            .baseUrl(base)
            .timeout(60u)
            .build()
        val client = createVaneClient(config)
        val chunk = ByteArray(CHUNK) { 'a'.code.toByte() }
        // A test-owned progress id: set on the request so the core reports
        // into it, snapshotted synchronously before each pull. Concurrent
        // snapshot-during-execute is exactly what the production poller does.
        val pid = createProgress()
        try {
            // (bytes produced before this pull, uploadSent at that moment) —
            // recorded here, asserted only after the response settles, so an
            // assertion failure can never wedge the upload mid-flight.
            val records = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
            // Demand-driven by construction: the flow builder's emit resumes
            // only after collect's blocking write returns, so each record is
            // taken strictly after the previous chunk was admitted.
            val source = flow {
                repeat(CHUNKS) { k ->
                    records.add(k.toLong() * CHUNK to progressSnapshotById(pid).uploadSent.toLong())
                    emit(chunk)
                }
            }
            val request = VaneRequest(
                url = "/post",
                method = "POST",
                headers = emptyMap(),
                queryParams = emptyMap(),
                body = null,
                bodyFilePath = null,
                responseBodyPath = null,
                cancelTokenId = null,
                progressId = pid,
                timeoutSeconds = 60u,
                followRedirects = true
            )
            val response = client.executeAsync(
                request,
                source,
                contentLength = (CHUNKS * CHUNK).toULong()
            )

            // The invariant is transport-agnostic: deliberately no assert on
            // httpVersion, which would only add an unrelated flake.
            assertEquals(200, response.statusCode.toInt())
            assertTrue(response.isSuccess)
            // Teeth: an early-failing or short-circuited upload cannot pass
            // vacuously — the source must have been pulled to completion.
            assertEquals(CHUNKS, records.size)
            records.forEachIndexed { k, (producedBefore, sent) ->
                assertTrue(
                    "pull $k ran ahead of the transport: $producedBefore bytes produced " +
                        "with only $sent sent — ${producedBefore - sent - ALLOWANCE} bytes " +
                        "past the $ALLOWANCE-byte allowance",
                    producedBefore <= sent + ALLOWANCE
                )
            }
        } finally {
            freeProgress(pid)
        }
    }
}
