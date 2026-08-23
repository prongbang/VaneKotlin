package com.inteniquetic.vanekotlin

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the JNA seam: the generated Kotlin wrappers running against the REAL
 * packaged `libvane.so` registry, plus the production glue's free-on-cancel
 * path ([withStreamedBody] → its blocking body-stream call) over the core's
 * real parked write.
 *
 * Every JVM body-stream test runs against `FakeBodyStreamCore` because the
 * `.so` cannot load on the JVM — so the generated stubs, the RustBuffer
 * error lift, and the core's parked-write-released-by-free mechanism execute
 * nowhere else on Android. CI's stale-`.so` gate is a byte diff
 * (release.yml), not a load; this class is the only place the packaged
 * library is actually loaded through the bindings.
 *
 * Deliberately offline: an unattached body stream never drains, so the
 * 256 KiB admission arithmetic is deterministic — the same shape as the Dart
 * reference test in `vane_flutter_ffi_test.dart` ("a parked native write is
 * released by free"). No fake is installed and no `reset()` is needed: the
 * [VaneBodyStreamBridge] defaults are the real generated functions, and
 * [VaneInitProvider] has loaded the `.so` before any test code runs.
 */
@RunWith(AndroidJUnit4::class)
class VaneBodyStreamInstrumentedTest {

    /**
     * All four generated calls round-trip through the real registry, and the
     * error lift works: `finish` on a freed id must come back as
     * [VaneException.InvalidRequest] carrying the core's message.
     *
     * The explicit [uniffiEnsureInitialized] is the load-time gate: it is
     * what initializes `IntegrityCheckingUniffiLib` (Vane.kt:634-638), whose
     * static init registers the `.so` and runs the contract-version (v30,
     * Vane.kt:881) and per-function checksum checks (Vane.kt:891, 27 of
     * them). Nothing else calls it — a bare wrapper call only initializes
     * `UniffiLib` (Vane.kt:707-709), which resolves symbols but skips the
     * checksums — so without this line a stale-but-linkable `.so` (rebuilt
     * without an API change's checksum bump) would sail through. With it,
     * any drift fails right here, which the byte-diff CI gate cannot do.
     */
    @Test
    fun realRegistryRoundTripAndFreeDropsTheId() {
        uniffiEnsureInitialized()
        val id = createBodyStream(null)
        writeBodyStreamChunk(id, byteArrayOf(1, 2, 3))
        finishBodyStream(id)
        freeBodyStream(id)
        // Free is documented as silent on an unknown id — the glue frees
        // unconditionally from several terminals, so this must never throw.
        freeBodyStream(id)
        val e = assertThrows(VaneException.InvalidRequest::class.java) {
            finishBodyStream(id)
        }
        // contains, never equality: the generated message renders as "v1=...".
        assertTrue(
            "Expected the core's unknown-id message, got: ${e.message}",
            e.message!!.contains("Unknown body stream id")
        )
    }

    /**
     * The declared-length contract is enforced by the real core, and the
     * failure crosses JNA as the right exception: a `ULong` content length
     * and a `ByteArray` chunk lower correctly, a write at exactly the
     * declared length is admitted, and one byte past it is refused with the
     * core's message (vane-rs/src/lib.rs:247-259). A generated write wrapper
     * that swallowed or mis-lifted the error dies here.
     */
    @Test
    fun declaredLengthIsEnforcedByTheRealCore() {
        val id = createBodyStream(4u)
        writeBodyStreamChunk(id, ByteArray(4))
        val e = assertThrows(VaneException.InvalidRequest::class.java) {
            writeBodyStreamChunk(id, ByteArray(1))
        }
        assertTrue(
            "Expected the core's declared-length message, got: ${e.message}",
            e.message!!.contains("declared length of 4")
        )
        freeBodyStream(id)
    }

    /**
     * The headline: cancelling an upload whose write is parked inside the
     * real native core frees the stream and releases the parked thread
     * promptly. The JVM twin (`VaneUploadStreamingTest`) proves the same
     * glue against a fake latch; only here does the wake travel through the
     * real path — cancel → the blocking call's catch fires
     * [VaneBodyStreamBridge.free] → the core latches `Cancelled` and
     * `notify_all`s (lib.rs free/fail) → the IO thread parked inside the
     * native write returns.
     *
     * A core mutant whose free drops the registry entry but skips the
     * fail/notify (the one seam no fake-based test can see) leaves the IO
     * thread parked forever; `job.join()` then blows its 5 s withTimeout and
     * the test fails cleanly — the upload runs in a scope detached from
     * [runBlocking], so the runner never waits on the wedged writer, and the
     * finally's freeBodyStream is a no-op in that mutant (the id is already
     * out of the registry; the parked thread leaks, the test still exits in
     * ~5 s). Against a glue mutant that lost the free-on-cancel, the finally
     * DOES hit a live registry entry, so it also unwedges the leaked thread
     * after the join timeout fails the test.
     */
    @Test
    fun cancellingWhileAWriteIsParkedInTheRealCoreFreesPromptly() = runBlocking {
        val writesStarted = AtomicInteger(0)
        val started = CompletableDeferred<ULong>()
        // flow is sequential: emit dispatches synchronously into collect's
        // blocking write, so the counter counts writes ENTERED — a parked
        // write keeps its emit from returning, and no later increment runs.
        val source = flow {
            while (true) {
                writesStarted.incrementAndGet()
                emit(ByteArray(64 * 1024))
            }
        }
        // Detached from runBlocking on purpose: a regressed hang must fail
        // this test's timeouts, never wedge the instrumentation runner.
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch {
            runCatching {
                withStreamedBody(source, null) { id ->
                    started.complete(id)
                    awaitCancellation()
                }
            }
        }
        try {
            val id = withTimeout(5_000) { started.await() }
            // 4 × 64 KiB = 262144 = BODY_STREAM_BUFFER_BYTES fills the queue
            // (admission is `queued < cap`, lib.rs:260); the 5th write sees a
            // full queue and parks in the native condvar wait.
            withTimeout(10_000) {
                while (writesStarted.get() < 5) delay(20)
            }
            delay(250)
            // Stable at exactly 5: nothing drains an unattached stream, so a
            // 6th write is impossible — the delay only confirms, never races.
            // A changed BODY_STREAM_BUFFER_BYTES or admission comparison
            // shifts this count, the same coupling the Dart reference carries.
            assertEquals(5, writesStarted.get())
            // → the blocking call's await() throws → its catch fires
            // VaneBodyStreamBridge.free(id) → the real freeBodyStream latches
            // Cancelled + notify_all → the parked IO thread wakes, and the
            // writer's finally + join complete. The withTimeout on join IS
            // the promptness gate.
            job.cancel()
            withTimeout(5_000) { job.join() }
            // The registry entry must be gone — the same witness the Dart
            // test uses via its vane_ffi_body_stream_finish probe.
            val e = assertThrows(VaneException.InvalidRequest::class.java) {
                finishBodyStream(id)
            }
            assertTrue(
                "Expected the core's unknown-id message, got: ${e.message}",
                e.message!!.contains("Unknown body stream id")
            )
        } finally {
            // Belt for the glue-regression case (free-on-cancel lost): this
            // hits the still-live registry entry and releases the parked
            // native thread after the failed join timeout. Silent when the
            // production path already freed — and a no-op (id unknown) in
            // the core mutant discussed above.
            if (started.isCompleted) freeBodyStream(started.await())
            job.cancel()
        }
    }
}
