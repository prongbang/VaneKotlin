package com.inteniquetic.vanekotlin

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM-side tests for [withStreamedBody], against a fake body-stream core
 * installed through [VaneBodyStreamBridge] that mirrors the real contract:
 * `write` PARKS its thread while the stream's buffer is full, and only
 * `free` (or the request's own death) releases a parked write — `free` must
 * therefore be reachable from a path that is never itself parked, or
 * cancelling an upload hangs forever. That teardown ordering is design risk
 * #2 of the upload plan and is asserted here so a wrapper refactor cannot
 * silently break it.
 *
 * What these tests pin — and what they do not: they pin the WRAPPER's
 * write-pacing, teardown ordering, and error routing against the bridge
 * seam. The generated UniFFI call beneath the bridge and the core's own
 * release latch are outside this file; the core side is covered by the Rust
 * suite (`streamed_upload_freed_mid_flight_cancels_the_request` and
 * friends), and the bridge defaults are one-line delegations to the
 * generated functions.
 */
class VaneUploadStreamingTest {
    /**
     * The four bridge calls, with the real core's blocking semantics:
     * `write` waits for a test-granted permit (an unanswered permit IS a
     * write parked against a full send window) unless [autoAckWrites];
     * `free` latches, releases any parked write with the same `Cancelled`
     * the real registry raises, and counts only once — the real registry
     * drops the id, so a second free does not exist as an event.
     */
    private class FakeBodyStreamCore(
        private val autoAckWrites: Boolean = false,
        private val writeFailure: VaneException? = null,
    ) {
        val id: ULong = 77u
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val writesStarted = AtomicInteger(0)
        val writeParked = CountDownLatch(1)
        private val freedLatch = CountDownLatch(1)
        private val lock = Object()
        private var permits = 0
        private var freed = false
        var finishCalls = 0
            private set
        var freeCalls = 0
            private set

        fun install() {
            VaneBodyStreamBridge.create = { _ ->
                events.add("create")
                id
            }
            VaneBodyStreamBridge.write = { _, chunk -> write(chunk) }
            VaneBodyStreamBridge.finish = { _ ->
                synchronized(lock) { finishCalls += 1 }
                events.add("finish")
            }
            VaneBodyStreamBridge.free = { _ -> free() }
        }

        fun ackOneWrite() = synchronized(lock) {
            permits += 1
            lock.notifyAll()
        }

        /** The induced request abort: parks until `free`, like a transport
         * whose next pull finds the latch. Bounded so a lost free FAILS the
         * test instead of hanging it. */
        suspend fun awaitFreedThenThrowCancelled(): Nothing {
            var waited = 0
            while (freedLatch.count > 0 && waited < 5_000) {
                delay(10)
                waited += 10
            }
            check(freedLatch.count == 0L) { "the request was never aborted — free never fired" }
            throw VaneException.Cancelled("Request was cancelled")
        }

        private fun write(chunk: ByteArray) {
            writesStarted.incrementAndGet()
            writeFailure?.let {
                events.add("writeFailed")
                throw it
            }
            if (autoAckWrites) {
                events.add("write")
                return
            }
            synchronized(lock) {
                if (permits == 0 && !freed) {
                    events.add("writeParked")
                    writeParked.countDown()
                }
                val deadline = System.currentTimeMillis() + 5_000
                while (permits == 0 && !freed) {
                    val remaining = deadline - System.currentTimeMillis()
                    check(remaining > 0) {
                        "parked write was never released — the wrapper lost the free path"
                    }
                    lock.wait(remaining)
                }
                if (freed) {
                    events.add("writeReleasedByFree")
                    throw VaneException.Cancelled("Request body stream was freed before finish()")
                }
                permits -= 1
                events.add("write")
            }
        }

        private fun free() {
            synchronized(lock) {
                if (freed) return
                freed = true
                freeCalls += 1
                events.add("free")
                lock.notifyAll()
            }
            freedLatch.countDown()
        }
    }

    @After
    fun resetBridge() = VaneBodyStreamBridge.reset()

    /**
     * Design risk #2: cancelling an upload whose write is parked in the FFI
     * must reach `free` from a never-parked path — free is the only thing
     * that releases the write — and return promptly. The naive spelling
     * (`withContext(Dispatchers.IO) { write() }`, no free on the
     * cancellation path) parks forever: cancellation just waits for the
     * blocked call, and the free that would release it sits behind that
     * wait.
     */
    @Test
    fun cancellingAnUploadWhoseWriteIsParkedFreesTheStreamPromptly() {
        val fake = FakeBodyStreamCore()
        fake.install()
        runBlocking {
            // Off the runBlocking thread: the blocking latch await below must
            // not starve the upload out of ever running.
            val upload = launch(Dispatchers.Default) {
                withStreamedBody(
                    source = flow { while (true) emit(ByteArray(1)) },
                    contentLength = null,
                ) { _ ->
                    fake.awaitFreedThenThrowCancelled()
                }
            }
            assertTrue(
                "the writer never reached its blocking write",
                fake.writeParked.await(2, TimeUnit.SECONDS),
            )
            val cancelMillis = measureTimeMillis {
                withTimeout(2_000) { upload.cancelAndJoin() }
            }
            assertTrue(
                "cancel took ${cancelMillis}ms — free did not reach the parked write",
                cancelMillis < 1_500,
            )
        }
        assertEquals(
            listOf("create", "writeParked", "free", "writeReleasedByFree"),
            fake.events.toList(),
        )
        assertEquals("the registry frees an id exactly once", 1, fake.freeCalls)
    }

    /**
     * The write direction's backpressure discriminator, the twin of the
     * download side's read-ahead test: the SOURCE'S PRODUCTION COUNT must
     * stay in lockstep with acknowledged writes. An implementation that
     * buffers (a `buffer`/`channelFlow` stage, or writes launched without
     * being awaited) lets the counting flow race ahead — the unbounded
     * Kotlin-side buffer the design forbids. Ends cleanly to also pin the
     * terminal order: every chunk written once, then finish, then free, and
     * the execute result comes back.
     */
    @Test
    fun theSourceIsHeldToOneWriteInFlightAndACleanFinishStillFrees() = runBlocking {
        val fake = FakeBodyStreamCore()
        fake.install()
        var produced = 0
        val source = flow {
            repeat(12) {
                produced += 1
                emit(ByteArray(1))
            }
        }
        val upload = launch(Dispatchers.Default) {
            val response = withStreamedBody(source, contentLength = null) { id ->
                assertEquals(fake.id, id)
                // The request outlives the writer, then settles cleanly.
                var waited = 0
                while (fake.finishCalls == 0 && waited < 5_000) {
                    delay(10)
                    waited += 10
                }
                "response"
            }
            assertEquals("response", response)
        }
        for (acked in 0 until 12) {
            // Wait for exactly the one in-flight write, then look for
            // run-ahead before releasing it.
            var waited = 0
            while (fake.writesStarted.get() < acked + 1 && waited < 5_000) {
                delay(10)
                waited += 10
            }
            assertEquals(
                "writes must not run ahead of acks — backpressure lost",
                acked + 1,
                fake.writesStarted.get(),
            )
            // Give an eager producer every chance to run ahead before
            // looking: the counting flow may sit one emit past the chunk in
            // flight, never further.
            delay(20)
            assertTrue(
                "the source produced $produced chunks with only $acked acked — " +
                    "Kotlin-side buffering",
                produced <= acked + 2,
            )
            fake.ackOneWrite()
        }
        withTimeout(5_000) { upload.join() }
        assertEquals(12, fake.writesStarted.get())
        assertEquals(1, fake.finishCalls)
        assertEquals("a clean finish still releases the id", 1, fake.freeCalls)
        assertEquals(
            "finish must come after the last write, free after finish",
            listOf("finish", "free"),
            fake.events.toList().takeLast(2),
        )
        assertTrue("nothing aborts on a clean run", fake.events.none { it == "writeReleasedByFree" })
    }

    /**
     * A failure of the caller's own source aborts the upload; the abort
     * fails the request as `Cancelled`, and the source's error — the actual
     * story — must replace that induced error on the way out.
     */
    @Test
    fun aSourceFailureAbortsTheUploadAndReplacesTheInducedCancelled() = runBlocking {
        val fake = FakeBodyStreamCore(autoAckWrites = true)
        fake.install()
        val failure = IllegalStateException("app source failed")
        val thrown = runCatching {
            withStreamedBody(
                source = flow {
                    emit(ByteArray(4))
                    throw failure
                },
                contentLength = null,
            ) { _ ->
                fake.awaitFreedThenThrowCancelled()
            }
        }.exceptionOrNull()
        assertTrue("expected the source's own error, got $thrown", thrown === failure)
        assertEquals("an errored body must never finish", 0, fake.finishCalls)
        assertEquals("the abort is the free", 1, fake.freeCalls)
    }

    /**
     * A write the core fails carries the request's own error; the writer
     * must stop quietly — no re-report, no failure of the scope — and the
     * execute result stays authoritative. A dead upload must also stop
     * pulling from the source.
     */
    @Test
    fun aCoreFailedWriteStopsTheWriterQuietlyAndTheExecuteResultIsAuthoritative() = runBlocking {
        val fake = FakeBodyStreamCore(
            writeFailure = VaneException.Timeout("request timed out"),
        )
        fake.install()
        var produced = 0
        val thrown = runCatching {
            withStreamedBody(
                source = flow {
                    repeat(50) {
                        produced += 1
                        emit(ByteArray(1))
                    }
                },
                contentLength = null,
            ) { _ ->
                fake.awaitFreedThenThrowCancelled()
            }
        }.exceptionOrNull()
        assertTrue(
            "the execute result is authoritative; got $thrown",
            thrown is VaneException.Cancelled,
        )
        assertTrue(
            "a dead upload must stop pulling from the source, produced $produced",
            produced <= 2,
        )
        assertEquals(0, fake.finishCalls)
        assertEquals(1, fake.freeCalls)
    }

    /**
     * The builder route: `bodyStream` must reach the transport as a
     * `bodyStreamId` on the request, with the writer running alongside —
     * through the session executor, so interceptors compose.
     */
    @Test
    fun builderBodyStreamCarriesTheNativeIdThroughTheSessionExecutor() = runBlocking {
        val fake = FakeBodyStreamCore(autoAckWrites = true)
        fake.install()
        var captured: VaneRequest? = null
        val session = VaneSession(
            configuration = testConfig(),
            transportExecutor = { request ->
                captured = request
                // The transport drains the upload before answering.
                var waited = 0
                while (fake.finishCalls == 0 && waited < 5_000) {
                    delay(10)
                    waited += 10
                }
                VaneResponse(
                    statusCode = 200u,
                    headers = emptyList(),
                    body = ByteArray(0),
                    bodyFilePath = null,
                    isSuccess = true,
                    url = "https://example.com/upload",
                )
            },
        )
        val response = session.request("https://example.com/upload", HttpMethod.POST)
            .bodyStream(flowOf(ByteArray(3)), contentLength = 3u)
            .execute()
        assertTrue(response.isSuccess)
        assertEquals(fake.id, captured?.bodyStreamId)
        assertNull("a streamed body must not also ride the buffered field", captured?.body)
        assertEquals(1, fake.writesStarted.get())
        assertEquals(1, fake.finishCalls)
        assertEquals(1, fake.freeCalls)
        if (fake.events.toList() != listOf("create", "write", "finish", "free")) {
            fail("unexpected event order: ${fake.events.toList()}")
        }
    }

    /** Built by hand, not via the generated `createDefaultConfig()`: these
     * are JVM tests with no native library to serve that call. */
    private fun testConfig() = VaneClientConfig(
        baseUrl = null,
        defaultHeaders = emptyMap(),
        dnsOverrides = emptyMap(),
        certificatePins = emptyMap(),
        cookiesEnabled = false,
        cookiePersistencePath = null,
        connectionPoolEnabled = false,
        maxIdleConnections = 4u,
        connectionIdleTimeoutSeconds = 30u,
        retryMaxAttempts = 1u,
        retryInitialDelayMillis = 100u,
        retryMaxDelayMillis = 1_000u,
        retryUnsafeMethods = false,
        maxRequestBodyBytes = 64u * 1024u * 1024u,
        maxResponseBodyBytes = 64u * 1024u * 1024u,
        timeoutSeconds = 30u,
        followRedirects = true,
        userAgent = "Vane/Test",
        protocolMode = VaneProtocolMode.HTTP3_ONLY,
        proxyUrl = null,
        proxyAuthorization = null,
    )
}
