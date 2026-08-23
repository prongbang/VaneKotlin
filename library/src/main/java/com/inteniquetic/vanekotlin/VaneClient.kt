package com.inteniquetic.vanekotlin

import android.content.Context
import android.util.Log
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// MARK: - Kotlin Extensions and Helpers

// Shared, immutable Json instances — building one per call is pure overhead.
// `internal` + @PublishedApi because the public inline `json()` reads it.
@PublishedApi
internal val vaneJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val vanePrettyJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

object Vane {
    private var isLoaded = false
    private var trustReady = false

    /**
     * Loads the native library. [VaneInitProvider] has normally already run
     * [initialize] with a `Context` before any application code, so this is
     * only kept for callers that already invoke it from `Application.onCreate`.
     */
    fun initialize() = initialize(null)

    /**
     * Loads the native library and, on the first call that supplies a
     * [Context], gives the TCP transport the platform trust store it verifies
     * certificates against.
     *
     * Only needed by hand if the merged manifest lost [VaneInitProvider] — the
     * error raised by an uninitialized TCP request says so and names this call.
     * HTTP/3 works without any of this.
     */
    @Synchronized
    fun initialize(context: Context?) {
        if (!isLoaded) {
            try {
                System.loadLibrary("vane")
                isLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                Log.e("Vane", "FAIL: vane not found ❌", e)
                return
            }
        }
        if (trustReady || context == null) return
        trustReady = try {
            // applicationContext: the verifier keeps a global ref for the life
            // of the process, so handing it an Activity would leak one.
            VaneNative.initAndroid(context.applicationContext)
        } catch (e: UnsatisfiedLinkError) {
            // Expected on a --no-default-features build, whose libvane.so has no
            // TCP transport and so exports no initAndroid. Not retried either
            // way; a TCP request on a build that *does* have one still reports
            // the missing init itself, so this cannot mask a real failure.
            Log.i("Vane", "libvane.so exports no initAndroid; assuming HTTP/3-only build", e)
            true
        }
        if (!trustReady) {
            Log.e("Vane", "Vane TCP transport unavailable: trust store init failed")
        }
    }
}

enum class HttpMethod(val value: String) {
    GET("GET"),
    POST("POST"),
    PUT("PUT"),
    DELETE("DELETE"),
    PATCH("PATCH"),
    HEAD("HEAD"),
    OPTIONS("OPTIONS")
}

// MARK: - Coroutine Extensions

suspend fun VaneClient.getAsync(url: String): VaneResponse {
    return withContext(Dispatchers.IO) {
        getRequest(url)
    }
}

suspend fun VaneClient.postAsync(url: String, body: ByteArray? = null): VaneResponse {
    return withContext(Dispatchers.IO) {
        postRequest(url, body)
    }
}

suspend fun VaneClient.putAsync(url: String, body: ByteArray? = null): VaneResponse {
    return withContext(Dispatchers.IO) {
        putRequest(url, body)
    }
}

suspend fun VaneClient.deleteAsync(url: String): VaneResponse {
    return withContext(Dispatchers.IO) {
        deleteRequest(url)
    }
}

suspend fun VaneClient.patchAsync(url: String, body: ByteArray? = null): VaneResponse {
    return withContext(Dispatchers.IO) {
        patchRequest(url, body)
    }
}

suspend fun VaneClient.executeAsync(request: VaneRequest): VaneResponse {
    return withContext(Dispatchers.IO) {
        executeRequest(request)
    }
}

/**
 * Best-effort warm-up of the client's one-time setup and connection cost, so
 * the first real request doesn't pay it — on Android the TCP transport's
 * first request otherwise carries ~1 s of trust-store and runtime setup.
 * Call it once, early (e.g. from `Application.onCreate` scope); it never
 * throws, and repeat calls are cheap.
 *
 * [url] picks the origin to pre-connect (HTTP/3) or probe (TCP); when null,
 * the client's `baseUrl` is used. With neither, only construction is warmed.
 * See the core `warmup` docs for the per-protocol-mode details.
 */
suspend fun VaneClient.warmupAsync(url: String? = null) {
    withContext(Dispatchers.IO) {
        warmup(url)
    }
}

// MARK: - Streaming

/**
 * A response whose headers have arrived and whose body is still streaming.
 *
 * [head] is the familiar [VaneResponse] — status, headers, final URL, cookies,
 * negotiated protocol — with [VaneResponse.body] empty by contract: [body]
 * delivers it instead.
 *
 * [body] is cold, single-collection and demand-driven: each chunk is pulled
 * off the native transport only when the collector asks for it — there is no
 * producer coroutine and no buffer — so a slow collector stalls the sender
 * through QUIC/TCP flow control instead of buffering without bound. Chunk
 * boundaries carry no meaning. A failure after the headers surfaces as an
 * error on this flow, not
 * as a failed request. Cancelling the collecting coroutine cancels the
 * request's token first — that is what interrupts a pull parked in the FFI —
 * then closes the native stream, discarding its connection; only a body
 * collected to the end returns the connection to the pool.
 *
 * Always collect (or cancel a collection of) [body]: an abandoned,
 * never-collected body holds its connection until the garbage collector
 * reclaims the native stream. Collecting a second time throws
 * [IllegalStateException].
 */
class VaneStreamingResponse(
    val head: VaneResponse,
    val body: Flow<ByteArray>,
)

/**
 * Like [executeAsync], but resumes as soon as the final response's headers
 * are in, with the body left to stream; see [VaneStreamingResponse].
 *
 * Everything up to the headers behaves exactly like [executeAsync]: the same
 * redirect chain, retry policy, HTTP/3-to-TCP fallback, cookies, pins and
 * deadline. Differences, all deliberate:
 *
 * - [VaneRequest.responseBodyPath] is refused by the core: the stream
 *   replaces the file escape hatch.
 * - Progress callbacks are meaningless here: the chunks themselves are the
 *   download progress.
 * - [VaneRequest.cancelTokenId] composes: cancelling that token aborts the
 *   header phase, or fails the body flow mid-stream. Cancelling the body
 *   collection cancels it too. When the request carries no token, the
 *   wrapper runs one internally so cancelling a parked read stays prompt.
 */
suspend fun VaneClient.executeStreaming(request: VaneRequest): VaneStreamingResponse {
    val ownedToken = if (request.cancelTokenId == null) VaneCancelToken() else null
    val effectiveRequest =
        if (ownedToken != null) request.copy(cancelTokenId = ownedToken.id) else request
    val cancelNative: () -> Unit =
        if (ownedToken != null) {
            { ownedToken.cancel() }
        } else {
            val callerTokenId = effectiveRequest.cancelTokenId!!
            ({ VaneCancelTokenBridge.cancel(callerTokenId) })
        }
    val (inner, head) = try {
        withContext(Dispatchers.IO) {
            val stream = executeStreamingRequest(effectiveRequest)
            stream to stream.head()
        }
    } catch (t: Throwable) {
        ownedToken?.close()
        throw t
    }
    return VaneStreamingResponse(
        head = head,
        body = streamingBodyFlow(
            stream = inner,
            cancelToken = cancelNative,
            releaseToken = { ownedToken?.close() },
        ),
    )
}

/**
 * Bridges one native response stream into a cold, single-collection,
 * demand-driven [Flow]. The shape carries the two invariants; refactor with
 * care:
 *
 * - **No read-ahead, by construction.** There is no producer coroutine and
 *   no channel: each chunk is one [readChunkCancellably] call made from the
 *   collector's own loop, so the core is never asked for bytes the collector
 *   has not asked for — and a core that is not pulled does not read the
 *   socket, which stalls the sender through QUIC/TCP flow control.
 *   Re-introducing a `flowOn`/`buffer` producer looks equivalent and is not,
 *   twice over: it adds run-ahead the size of the buffer, and — the trap —
 *   `flowOn`'s `collect` is a `coroutineScope` that will not unwind on
 *   cancellation until its producer child returns from the parked FFI read,
 *   while the `onCompletion` cancel that would release that read only runs
 *   after the unwind. Cancellation then deadlocks until the read times out,
 *   and a failing producer can overtake an in-flight chunk out-of-band.
 * - **Token before close.** A pull parked in the FFI holds the native
 *   stream's lock, and `closeStream` waits on that lock; only cancelling the
 *   request's token makes a parked pull return. [readChunkCancellably] fires
 *   the token the moment collection is cancelled, and the `finally` here —
 *   which structurally cannot run until the pull has returned — is the only
 *   place that closes.
 */
internal fun streamingBodyFlow(
    stream: VaneResponseStreamInterface,
    cancelToken: () -> Unit,
    releaseToken: () -> Unit = {},
): Flow<ByteArray> {
    val collected = AtomicBoolean(false)
    return flow {
        check(collected.compareAndSet(false, true)) {
            "A streaming response body can be collected only once"
        }
        try {
            while (true) emit(readChunkCancellably(stream, cancelToken) ?: break)
        } finally {
            // After EOF the connection is already pooled and after a failure
            // already discarded — closeStream is an idempotent no-op there.
            // After a cancellation it is what discards the connection.
            // NonCancellable because this must still run when the collector
            // was cancelled; IO because closeStream is a blocking native call.
            withContext(NonCancellable + Dispatchers.IO) {
                stream.closeStream()
                (stream as? Disposable)?.destroy()
                releaseToken()
            }
        }
    }
}

/**
 * One blocking pull, parked on [Dispatchers.IO], that a cancelled collector
 * can always interrupt promptly.
 */
private suspend fun readChunkCancellably(
    stream: VaneResponseStreamInterface,
    cancelToken: () -> Unit,
): ByteArray? = coroutineScope {
    // runCatching so a stream failure travels back as a value: an async that
    // throws would instead fail this scope out-of-band and could overtake a
    // chunk already handed to the collector but not yet processed.
    val read = async(Dispatchers.IO) { runCatching { stream.readChunk() } }
    try {
        read.await().getOrThrow()
    } catch (cancellation: CancellationException) {
        // Cancelled while the read is parked in the FFI: only the native
        // token makes the parked call return. Fire it BEFORE this scope
        // waits for the read coroutine on the way out — that wait is also
        // what guarantees close (in the caller's finally) runs strictly
        // after the read has let go of the stream's lock.
        cancelToken()
        throw cancellation
    }
}

// MARK: - Upload (request-body) streaming

/**
 * Like [executeAsync], but the request body is streamed from [body] instead
 * of being held in memory: chunks are pushed into the core one blocking
 * write at a time, and when the transport's send window and the core's
 * 256 KiB buffer are full that write parks — so [body]'s emission is what
 * stalls, and Kotlin-side buffering is bounded at the single chunk in
 * flight. Chunk boundaries carry no meaning.
 *
 * [contentLength] of a non-null `n` sends `Content-Length: n` and enforces
 * exactly `n` bytes (finishing at any other count fails the request); null
 * streams without a declared length (chunked on HTTP/1.1, plain frames on
 * h2/HTTP/3).
 *
 * A streamed body is one-shot, which buys these documented differences from
 * [executeAsync]:
 * - **No retry.** The request runs exactly one attempt per transport,
 *   whatever the retry configuration says.
 * - **Body-keeping redirects are refused** (307/308 on any method, 301/302
 *   on GET): the 3xx comes back as the response, carrying
 *   `vane-redirect-refused: streamed-body`. Hops that drop the body (303,
 *   301/302 on other methods) are followed as a bodyless GET.
 * - **HTTP/3-to-TCP fallback happens only before the first consumed body
 *   byte** — after that the HTTP/3 error is reported instead.
 * - **The whole upload must fit the request timeout.** On TCP the body send
 *   and the response headers share one deadline (reqwest wraps both in a
 *   single timeout), and HTTP/3 runs the same shared deadline — callers
 *   moving large bodies set [VaneRequestBuilder.timeout] accordingly.
 *
 * A failure of [body] itself aborts the request and is rethrown here in
 * place of the `Cancelled` that abort induces. A write the core fails is
 * not double-reported: the exception thrown by this call is authoritative.
 * Cancelling the calling coroutine frees the native stream from a
 * never-parked path (releasing a parked write) and thereby aborts the
 * request at its next body pull; attach a [VaneCancelToken] as well for a
 * prompt abort in every request phase. One stream feeds exactly one
 * request; each call creates its own.
 */
suspend fun VaneClient.executeAsync(
    request: VaneRequest,
    body: Flow<ByteArray>,
    contentLength: ULong? = null,
): VaneResponse = withStreamedBody(body, contentLength) { id ->
    executeAsync(request.copy(bodyStreamId = id))
}

/**
 * Bridges one caller-supplied body flow into a native body stream around
 * [execute] — [streamingBodyFlow]'s mirror image, with `free` in the role
 * the cancel token plays there. The shape carries the invariants; refactor
 * with care:
 *
 * - **No write-ahead, by construction.** There is no channel and no
 *   producer buffer: the writer collects [source] and makes one
 *   [blockingBodyStreamCall] per element, so a chunk is not asked for (a
 *   cold flow's `emit` does not resume) until the previous chunk's blocking
 *   write has returned — and past the core's 256 KiB buffer that write only
 *   returns as the transport drains, which stalls the source through
 *   QUIC/TCP flow control. Re-introducing a `buffer`/`channelFlow` stage
 *   looks equivalent and is not: it adds run-ahead the size of its buffer.
 * - **Free from a never-parked path.** A writer parked inside the blocking
 *   write is released only by [VaneBodyStreamBridge.free] (or the core's
 *   own request-release latch); anything that waits for the parked write
 *   before freeing deadlocks. [blockingBodyStreamCall] fires the free the
 *   moment its caller is cancelled, and the writer's `finally` — which
 *   cannot run while its own call is still parked — frees on every other
 *   terminal. After a clean finish that free only drops the id (queued
 *   bytes still drain), so it is unconditional.
 * - **The execute result is authoritative.** A write the core fails carries
 *   the same error the request fails with, so the writer stops quietly
 *   instead of re-reporting it; only a failure of [source] itself is
 *   recorded, and it replaces the `Cancelled` its abort induces.
 */
internal suspend fun <T> withStreamedBody(
    source: Flow<ByteArray>,
    contentLength: ULong?,
    execute: suspend (bodyStreamId: ULong) -> T,
): T {
    val id = VaneBodyStreamBridge.create(contentLength)
    var sourceFailure: Throwable? = null
    try {
        return coroutineScope {
            val writer = launch {
                try {
                    source.collect { chunk ->
                        blockingBodyStreamCall(id) { VaneBodyStreamBridge.write(id, chunk) }
                    }
                    blockingBodyStreamCall(id) { VaneBodyStreamBridge.finish(id) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (core: VaneException) {
                    // The request failed (or refused the stream); the execute
                    // result tells that story — reporting it here too would
                    // double-report, and racing ahead of a chunk already
                    // handed off is exactly the flowOn trap the download
                    // wrapper documents.
                } catch (failure: Throwable) {
                    sourceFailure = failure
                } finally {
                    // Every writer terminal frees: after a clean finish this
                    // only drops the id, before one it is the abort that
                    // fails the request. Never parked here — a call still in
                    // flight structurally keeps this finally from running.
                    VaneBodyStreamBridge.free(id)
                }
            }
            try {
                execute(id)
            } finally {
                // The request settled, whatever the source is doing: a
                // source idle between chunks (or one that never ends) must
                // not keep the writer alive. A writer parked in a write is
                // released by the free this cancellation reaches.
                writer.cancel()
            }
        }
    } catch (failure: Throwable) {
        if (failure !is CancellationException) {
            // The source's own error is the story, not the synthetic
            // Cancelled its abort induced on the request.
            sourceFailure?.let { throw it }
        }
        throw failure
    } finally {
        // Backstop for cancellation between create and the writer's launch;
        // idempotent everywhere else.
        VaneBodyStreamBridge.free(id)
    }
}

/**
 * One blocking body-stream call, parked on [Dispatchers.IO], that a
 * cancelled writer can always interrupt promptly — [readChunkCancellably]'s
 * twin, with [VaneBodyStreamBridge.free] in the token's role.
 */
private suspend fun blockingBodyStreamCall(
    id: ULong,
    call: () -> Unit,
): Unit = coroutineScope {
    // runCatching so a core failure travels back as a value: an async that
    // throws would instead fail this scope out-of-band.
    val work = async(Dispatchers.IO) { runCatching(call) }
    try {
        work.await().getOrThrow()
    } catch (cancellation: CancellationException) {
        // Cancelled while the call is parked in the FFI: only freeing the
        // stream makes a parked write return. Fire it BEFORE this scope
        // waits for the worker on the way out — that wait is also what
        // guarantees the writer's finally runs strictly after the call let
        // go.
        VaneBodyStreamBridge.free(id)
        throw cancellation
    }
}

// MARK: - Retrofit-style Interface

typealias VaneRequestInterceptor = suspend (VaneRequest) -> VaneRequest
typealias VaneResponseInterceptor = suspend (VaneResponse) -> VaneResponse
typealias VaneErrorInterceptor = suspend (Throwable) -> VaneResponse?
typealias VaneProgressCallback = (transferred: ULong, total: ULong) -> Unit

data class VaneMultipartFile(
    val fieldName: String,
    val bytes: ByteArray,
    val fileName: String = fieldName,
    val contentType: String = "application/octet-stream"
)

internal object VaneProgressBridge {
    var create: () -> ULong = { createProgress() }
    var snapshot: (ULong) -> VaneProgressSnapshot = { id -> progressSnapshotById(id) }
    var free: (ULong) -> Unit = { id -> freeProgress(id) }

    fun reset() {
        create = { createProgress() }
        snapshot = { id -> progressSnapshotById(id) }
        free = { id -> freeProgress(id) }
    }
}

internal object VaneCancelTokenBridge {
    var create: () -> ULong = { createCancelToken() }
    var cancel: (ULong) -> Unit = { id -> cancelById(id) }
    var free: (ULong) -> Unit = { id -> freeCancelToken(id) }

    fun reset() {
        create = { createCancelToken() }
        cancel = { id -> cancelById(id) }
        free = { id -> freeCancelToken(id) }
    }
}

internal object VaneBodyStreamBridge {
    var create: (ULong?) -> ULong = { contentLength -> createBodyStream(contentLength) }
    var write: (ULong, ByteArray) -> Unit = { id, chunk -> writeBodyStreamChunk(id, chunk) }
    var finish: (ULong) -> Unit = { id -> finishBodyStream(id) }
    var free: (ULong) -> Unit = { id -> freeBodyStream(id) }

    fun reset() {
        create = { contentLength -> createBodyStream(contentLength) }
        write = { id, chunk -> writeBodyStreamChunk(id, chunk) }
        finish = { id -> finishBodyStream(id) }
        free = { id -> freeBodyStream(id) }
    }
}

/**
 * Cancels an in-flight request from any thread.
 *
 * The native token is created eagerly at construction — unlike Dart, whose
 * token reaches the core over an async platform channel and therefore latches
 * a cancel issued before registration — so [cancel] always reaches the core
 * immediately.
 *
 * Attach it with [VaneRequestBuilder.cancelToken], or set [id] as
 * `VaneRequest.cancelTokenId` when building requests by hand. A cancelled
 * token stays cancelled, so reuse on a second request aborts that one too.
 * Call [close] (or use `use { }`) when done; the core never reuses ids, so
 * double-close and cancel-after-close are safe no-ops.
 */
class VaneCancelToken : AutoCloseable {
    val id: ULong = VaneCancelTokenBridge.create()

    @Volatile
    var isCancelled: Boolean = false
        private set

    fun cancel() {
        isCancelled = true
        VaneCancelTokenBridge.cancel(id)
    }

    override fun close() {
        VaneCancelTokenBridge.free(id)
    }
}

class VaneSession(
    private val configuration: VaneClientConfig = createDefaultConfig(),
    requestInterceptors: List<VaneRequestInterceptor> = emptyList(),
    responseInterceptors: List<VaneResponseInterceptor> = emptyList(),
    errorInterceptors: List<VaneErrorInterceptor> = emptyList()
) {
    private val requestInterceptors = requestInterceptors.toMutableList()
    private val responseInterceptors = responseInterceptors.toMutableList()
    private val errorInterceptors = errorInterceptors.toMutableList()
    private var transportExecutor: (suspend (VaneRequest) -> VaneResponse)? = null
    private var streamingTransportExecutor:
        (suspend (VaneRequest) -> VaneStreamingResponse)? = null

    private val client: VaneClient by lazy {
        createVaneClient(configuration)
    }

    private val json = vaneJson

    internal constructor(
        configuration: VaneClientConfig,
        requestInterceptors: List<VaneRequestInterceptor> = emptyList(),
        responseInterceptors: List<VaneResponseInterceptor> = emptyList(),
        errorInterceptors: List<VaneErrorInterceptor> = emptyList(),
        // Before transportExecutor so existing trailing-lambda call sites keep
        // binding the lambda to transportExecutor.
        streamingTransportExecutor: (suspend (VaneRequest) -> VaneStreamingResponse)? = null,
        transportExecutor: suspend (VaneRequest) -> VaneResponse
    ) : this(configuration, requestInterceptors, responseInterceptors, errorInterceptors) {
        this.transportExecutor = transportExecutor
        this.streamingTransportExecutor = streamingTransportExecutor
    }

    // MARK: - Request Building

    fun request(url: String, method: HttpMethod = HttpMethod.GET): VaneRequestBuilder {
        return VaneRequestBuilder(url, method, json, ::execute)
    }

    fun addRequestInterceptor(interceptor: VaneRequestInterceptor): VaneSession {
        requestInterceptors += interceptor
        return this
    }

    fun addResponseInterceptor(interceptor: VaneResponseInterceptor): VaneSession {
        responseInterceptors += interceptor
        return this
    }

    fun addErrorInterceptor(interceptor: VaneErrorInterceptor): VaneSession {
        errorInterceptors += interceptor
        return this
    }

    fun clearInterceptors(): VaneSession {
        requestInterceptors.clear()
        responseInterceptors.clear()
        errorInterceptors.clear()
        return this
    }

    fun setCertificatePins(host: String, pins: List<String>): VaneSession {
        client.setCertificatePins(host, pins)
        return this
    }

    fun addCertificatePin(host: String, pin: String): VaneSession {
        client.addCertificatePin(host, pin)
        return this
    }

    fun clearCertificatePins(host: String): VaneSession {
        client.clearCertificatePins(host)
        return this
    }

    /**
     * Best-effort warm-up of the underlying client; see
     * [VaneClient.warmupAsync]. Creates the native client if this session has
     * not made a request yet — that construction is part of what gets warmed.
     * Never throws.
     */
    suspend fun warmup(url: String? = null) {
        client.warmupAsync(url)
    }

    // MARK: - Direct Methods

    suspend fun get(url: String): VaneResponse {
        return request(url, HttpMethod.GET).execute()
    }

    suspend fun post(url: String, body: ByteArray? = null): VaneResponse {
        val builder = request(url, HttpMethod.POST)
        if (body != null) builder.body(body)
        return builder.execute()
    }

    suspend fun put(url: String, body: ByteArray? = null): VaneResponse {
        val builder = request(url, HttpMethod.PUT)
        if (body != null) builder.body(body)
        return builder.execute()
    }

    suspend fun delete(url: String): VaneResponse {
        return request(url, HttpMethod.DELETE).execute()
    }

    suspend fun patch(url: String, body: ByteArray? = null): VaneResponse {
        val builder = request(url, HttpMethod.PATCH)
        if (body != null) builder.body(body)
        return builder.execute()
    }

    suspend inline fun <reified T> postJson(url: String, body: T): VaneResponse {
        return request(url, HttpMethod.POST)
            .jsonBody(body)
            .execute()
    }

    suspend fun postForm(url: String, fields: Map<String, String>): VaneResponse {
        return request(url, HttpMethod.POST)
            .formBody(fields)
            .execute()
    }

    suspend fun uploadFile(
        url: String,
        path: String,
        method: HttpMethod = HttpMethod.POST,
        onUploadProgress: VaneProgressCallback? = null,
        onDownloadProgress: VaneProgressCallback? = null
    ): VaneResponse {
        val builder = request(url, method)
            .bodyFile(path)
        if (onUploadProgress != null) builder.onUploadProgress(onUploadProgress)
        if (onDownloadProgress != null) builder.onDownloadProgress(onDownloadProgress)
        return builder.execute()
    }

    suspend fun download(
        url: String,
        outputPath: String,
        onDownloadProgress: VaneProgressCallback? = null
    ): VaneResponse {
        val builder = request(url, HttpMethod.GET)
            .downloadToFile(outputPath)
        if (onDownloadProgress != null) builder.onDownloadProgress(onDownloadProgress)
        return builder.execute()
    }

    /**
     * Like [execute], but resumes as soon as the final response's headers are
     * in, with the body left to stream; see [VaneStreamingResponse].
     *
     * Request interceptors run; response and error interceptors do NOT — an
     * interceptor written against a buffered [VaneResponse] cannot rewrite a
     * body that has not arrived. Validate status off the head, e.g.
     * `response.head.validateStatus()`. The other deltas from [execute] are
     * documented on [VaneClient.executeStreaming].
     */
    suspend fun executeStreaming(request: VaneRequest): VaneStreamingResponse {
        var interceptedRequest = request
        for (interceptor in requestInterceptors.toList()) {
            interceptedRequest = interceptor(interceptedRequest)
        }
        val transport = streamingTransportExecutor
            ?: { req -> client.executeStreaming(req) }
        return transport(interceptedRequest)
    }

    suspend fun execute(request: VaneRequest): VaneResponse {
        var interceptedRequest = request
        for (interceptor in requestInterceptors.toList()) {
            interceptedRequest = interceptor(interceptedRequest)
        }

        return try {
            var response = (transportExecutor ?: { req -> client.executeAsync(req) })(interceptedRequest)
            for (interceptor in responseInterceptors.toList()) {
                response = interceptor(response)
            }
            response
        } catch (throwable: Throwable) {
            for (interceptor in errorInterceptors.toList()) {
                val response = interceptor(throwable)
                if (response != null) {
                    var interceptedResponse: VaneResponse = response
                    for (responseInterceptor in responseInterceptors.toList()) {
                        interceptedResponse = responseInterceptor(interceptedResponse)
                    }
                    return interceptedResponse
                }
            }

            throw throwable
        }
    }
}

// MARK: - Request Builder

class VaneRequestBuilder internal constructor(
    private val url: String,
    private val method: HttpMethod,
    val json: Json,
    private val executor: suspend (VaneRequest) -> VaneResponse
) {
    private var headers = mutableMapOf<String, String>()
    private var queryParams = mutableMapOf<String, String>()
    var body: ByteArray? = null
    private var bodyFilePath: String? = null
    private var bodyStreamSource: Flow<ByteArray>? = null
    private var bodyStreamContentLength: ULong? = null
    private var responseBodyPath: String? = null
    private var uploadProgress: VaneProgressCallback? = null
    private var downloadProgress: VaneProgressCallback? = null
    private var cancelTokenId: ULong? = null
    private var timeoutSeconds: ULong? = null
    private var followRedirects = true

    // MARK: - Builder Methods

    fun headers(headers: Map<String, String>): VaneRequestBuilder {
        this.headers.putAll(headers)
        return this
    }

    fun header(key: String, value: String): VaneRequestBuilder {
        headers[key] = value
        return this
    }

    fun queryParams(params: Map<String, String>): VaneRequestBuilder {
        queryParams.putAll(params)
        return this
    }

    fun queryParam(key: String, value: String): VaneRequestBuilder {
        queryParams[key] = value
        return this
    }

    fun body(body: ByteArray): VaneRequestBuilder {
        this.body = body
        bodyFilePath = null
        bodyStreamSource = null
        return this
    }

    fun bodyFile(path: String): VaneRequestBuilder {
        body = null
        bodyFilePath = path
        bodyStreamSource = null
        return this
    }

    /**
     * Streams the request body from [source] instead of holding it in
     * memory. The body shapes are mutually exclusive: this clears [body] and
     * [bodyFile], and either of those clears this. Ceilings and the abort
     * contract are documented on the client-level overload,
     * [VaneClient.executeAsync] — in one line: no retry, body-keeping
     * redirects come back refused, HTTP/3-to-TCP fallback only before the
     * first consumed byte, and the whole upload must fit [timeout].
     */
    fun bodyStream(source: Flow<ByteArray>, contentLength: ULong? = null): VaneRequestBuilder {
        bodyStreamSource = source
        bodyStreamContentLength = contentLength
        body = null
        bodyFilePath = null
        return this
    }

    fun downloadToFile(path: String): VaneRequestBuilder {
        responseBodyPath = path
        return this
    }

    fun onUploadProgress(callback: VaneProgressCallback): VaneRequestBuilder {
        uploadProgress = callback
        return this
    }

    fun onDownloadProgress(callback: VaneProgressCallback): VaneRequestBuilder {
        downloadProgress = callback
        return this
    }

    fun cancelToken(token: VaneCancelToken): VaneRequestBuilder {
        cancelTokenId = token.id
        return this
    }

    fun multipart(
        fields: Map<String, String> = emptyMap(),
        files: List<VaneMultipartFile> = emptyList()
    ): VaneRequestBuilder {
        val boundary = "vane-${System.nanoTime()}"
        val chunks = mutableListOf<ByteArray>()
        fun append(value: String) {
            chunks += value.toByteArray(Charsets.UTF_8)
        }

        for ((key, value) in fields.toSortedMap()) {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"$key\"\r\n\r\n")
            append(value)
            append("\r\n")
        }

        for (file in files) {
            append("--$boundary\r\n")
            append(
                "Content-Disposition: form-data; name=\"${file.fieldName}\"; filename=\"${file.fileName}\"\r\n"
            )
            append("Content-Type: ${file.contentType}\r\n\r\n")
            chunks += file.bytes
            append("\r\n")
        }

        append("--$boundary--\r\n")
        val totalSize = chunks.sumOf { it.size }
        val multipartBody = ByteArray(totalSize)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(multipartBody, offset)
            offset += chunk.size
        }

        body(multipartBody)
        defaultHeader("Content-Type", "multipart/form-data; boundary=$boundary")
        return this
    }

    fun textBody(
        text: String,
        charset: Charset = Charsets.UTF_8,
        contentType: String = "text/plain; charset=utf-8"
    ): VaneRequestBuilder {
        body(text.toByteArray(charset))
        defaultHeader("Content-Type", contentType)
        return this
    }

    inline fun <reified T> jsonBody(obj: T): VaneRequestBuilder {
        val jsonString = json.encodeToString(obj)
        body(jsonString.toByteArray())
        defaultHeader("Content-Type", "application/json")
        return this
    }

    fun formBody(fields: Map<String, String>): VaneRequestBuilder {
        body(
            fields.entries
                .sortedBy { it.key }
                .joinToString("&") { (key, value) ->
                    "${formEncode(key)}=${formEncode(value)}"
                }
                .toByteArray(Charsets.UTF_8)
        )
        defaultHeader("Content-Type", "application/x-www-form-urlencoded")
        return this
    }

    fun timeout(seconds: ULong): VaneRequestBuilder {
        timeoutSeconds = seconds
        return this
    }

    fun followRedirects(follow: Boolean): VaneRequestBuilder {
        followRedirects = follow
        return this
    }

    // MARK: - Execution

    suspend fun execute(): VaneResponse = coroutineScope {
        val progressId = if (uploadProgress != null || downloadProgress != null) {
            VaneProgressBridge.create()
        } else {
            null
        }
        val progressJob = progressId?.let { id ->
            launch(Dispatchers.Default) {
                while (isActive) {
                    val progress = VaneProgressBridge.snapshot(id)
                    uploadProgress?.invoke(progress.uploadSent, progress.uploadTotal)
                    downloadProgress?.invoke(progress.downloadReceived, progress.downloadTotal)
                    if (progress.done) break
                    delay(100)
                }
            }
        }
        val request = VaneRequest(
            url = url,
            method = method.value,
            headers = headers,
            queryParams = queryParams,
            body = body,
            bodyFilePath = bodyFilePath,
            responseBodyPath = responseBodyPath,
            cancelTokenId = cancelTokenId,
            progressId = progressId,
            timeoutSeconds = timeoutSeconds,
            followRedirects = followRedirects
        )
        val streamedSource = bodyStreamSource
        try {
            if (streamedSource == null) {
                executor(request)
            } else {
                withStreamedBody(streamedSource, bodyStreamContentLength) { id ->
                    executor(request.copy(bodyStreamId = id))
                }
            }
        } finally {
            if (progressId != null) {
                progressJob?.cancelAndJoin()
                val progress = VaneProgressBridge.snapshot(progressId)
                uploadProgress?.invoke(progress.uploadSent, progress.uploadTotal)
                downloadProgress?.invoke(progress.downloadReceived, progress.downloadTotal)
                VaneProgressBridge.free(progressId)
            }
        }
    }

    suspend fun validateStatus(range: UIntRange = 200u..299u): VaneResponse {
        return execute().validateStatus(range)
    }

    suspend fun responseBytes(): ByteArray {
        return validateStatus().body
    }

    suspend inline fun <reified T> responseJson(): T {
        val response = validateStatus()

        return json.decodeFromString<T>(String(response.body))
    }

    suspend fun responseString(): String {
        val response = validateStatus()

        return String(response.body)
    }

    @PublishedApi
    internal fun defaultHeader(key: String, value: String) {
        if (headers.keys.none { it.equals(key, ignoreCase = true) }) {
            headers[key] = value
        }
    }

    private fun formEncode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}

// MARK: - Configuration Builder

class VaneConfigurationBuilder {
    private var config = createDefaultConfig()

    fun baseUrl(url: String): VaneConfigurationBuilder {
        config.baseUrl = url
        return this
    }

    fun defaultHeaders(headers: Map<String, String>): VaneConfigurationBuilder {
        config.defaultHeaders = headers.toMutableMap()
        return this
    }

    fun dnsOverrides(overrides: Map<String, String>): VaneConfigurationBuilder {
        config.dnsOverrides = overrides.toMutableMap()
        return this
    }

    fun dnsOverride(host: String, ipAddress: String): VaneConfigurationBuilder {
        config.dnsOverrides = config.dnsOverrides.toMutableMap().apply {
            put(host, ipAddress)
        }
        return this
    }

    fun proxy(url: String, authorization: String? = null): VaneConfigurationBuilder {
        config.proxyUrl = url
        config.proxyAuthorization = authorization
        return this
    }

    fun proxyAuthorization(authorization: String?): VaneConfigurationBuilder {
        config.proxyAuthorization = authorization
        return this
    }

    fun certificatePins(pins: Map<String, List<String>>): VaneConfigurationBuilder {
        config.certificatePins = pins.toMutableMap()
        return this
    }

    fun certificatePin(host: String, pins: List<String>): VaneConfigurationBuilder {
        config.certificatePins = config.certificatePins.toMutableMap().apply {
            put(host, pins)
        }
        return this
    }

    fun cookiesEnabled(enabled: Boolean = true): VaneConfigurationBuilder {
        config.cookiesEnabled = enabled
        return this
    }

    fun cookiePersistencePath(path: String?): VaneConfigurationBuilder {
        config.cookiePersistencePath = path
        return this
    }

    fun connectionPooling(
        enabled: Boolean = true,
        maxIdleConnections: ULong = 4UL,
        idleTimeoutSeconds: ULong = 30UL
    ): VaneConfigurationBuilder {
        config.connectionPoolEnabled = enabled
        config.maxIdleConnections = maxIdleConnections
        config.connectionIdleTimeoutSeconds = idleTimeoutSeconds
        return this
    }

    fun retry(
        maxAttempts: ULong,
        initialDelayMillis: ULong = 100UL,
        maxDelayMillis: ULong = 1_000UL,
        retryUnsafeMethods: Boolean = false
    ): VaneConfigurationBuilder {
        config.retryMaxAttempts = maxAttempts
        config.retryInitialDelayMillis = initialDelayMillis
        config.retryMaxDelayMillis = maxDelayMillis
        config.retryUnsafeMethods = retryUnsafeMethods
        return this
    }

    fun bodyLimits(
        maxRequestBodyBytes: ULong,
        maxResponseBodyBytes: ULong
    ): VaneConfigurationBuilder {
        config.maxRequestBodyBytes = maxRequestBodyBytes
        config.maxResponseBodyBytes = maxResponseBodyBytes
        return this
    }

    fun timeout(seconds: ULong): VaneConfigurationBuilder {
        config.timeoutSeconds = seconds
        return this
    }

    fun userAgent(agent: String): VaneConfigurationBuilder {
        config.userAgent = agent
        return this
    }

    fun protocolMode(mode: VaneProtocolMode): VaneConfigurationBuilder {
        config.protocolMode = mode
        return this
    }

    fun http3ThenHttp2ThenHttp1(): VaneConfigurationBuilder {
        config.protocolMode = VaneProtocolMode.HTTP3_THEN_HTTP2_THEN_HTTP1
        return this
    }

    fun http3Only(): VaneConfigurationBuilder {
        config.protocolMode = VaneProtocolMode.HTTP3_ONLY
        return this
    }

    fun http2ThenHttp1(): VaneConfigurationBuilder {
        config.protocolMode = VaneProtocolMode.HTTP2_THEN_HTTP1
        return this
    }

    fun http2Only(): VaneConfigurationBuilder {
        config.protocolMode = VaneProtocolMode.HTTP2_ONLY
        return this
    }

    fun http1Only(): VaneConfigurationBuilder {
        config.protocolMode = VaneProtocolMode.HTTP1_ONLY
        return this
    }

    fun followRedirects(follow: Boolean): VaneConfigurationBuilder {
        config.followRedirects = follow
        return this
    }

    fun maxRedirects(value: UInt): VaneConfigurationBuilder {
        config.maxRedirects = value
        return this
    }

    fun tlsMinVersion(value: VaneTlsVersion): VaneConfigurationBuilder {
        config.tlsMinVersion = value
        return this
    }

    fun tlsMaxVersion(value: VaneTlsVersion): VaneConfigurationBuilder {
        config.tlsMaxVersion = value
        return this
    }

    fun customRootCertificates(pems: List<String>): VaneConfigurationBuilder {
        config.customRootCertificates = pems.toList()
        return this
    }

    fun clientCertificate(certificatePem: String, privateKeyPem: String): VaneConfigurationBuilder {
        config.clientCertificate = VaneClientCertificate(
            certificatePem = certificatePem,
            privateKeyPem = privateKeyPem
        )
        return this
    }

    fun build(): VaneClientConfig {
        return config
    }
}

// MARK: - Custom Exceptions

class VaneHttpException(
    message: String,
    val statusCode: UShort,
    val response: VaneResponse
) : Exception(message)

class VaneNetworkException(
    message: String,
    val errorType: String,
    cause: Throwable? = null
) : Exception(message, cause)

// MARK: - Extensions

val VaneResponse.isSuccessful: Boolean
    get() = isSuccess

fun VaneResponse.validateStatus(range: UIntRange = 200u..299u): VaneResponse {
    if (statusCode.toUInt() !in range) {
        throw VaneHttpException(
            message = "Request failed with status $statusCode",
            statusCode = statusCode,
            response = this
        )
    }
    return this
}

val VaneResponse.text: String
    get() = String(body)

inline fun <reified T> VaneResponse.json(): T {
    return vaneJson.decodeFromString<T>(String(body))
}

val VaneResponse.prettyJson: String?
    get() = try {
        vanePrettyJson.encodeToString(Json.parseToJsonElement(String(body)))
    } catch (e: Exception) {
        null
    }

/**
 * First-wins map view of [VaneResponse.headers] — the first occurrence of a
 * name wins, matching the core's redirect rule for `location` (RFC 9110
 * §10.2.2). Consumers that need every duplicate read the ordered list itself.
 */
val VaneResponse.headerMap: Map<String, String>
    get() {
        val map = LinkedHashMap<String, String>()
        for (header in headers) {
            if (header.name !in map) map[header.name] = header.value
        }
        return map
    }

/** Every `set-cookie` value the server sent, in arrival order. */
val VaneResponse.setCookie: List<String>
    get() = headers.filter { it.name == "set-cookie" }.map { it.value }

// MARK: - Retrofit-style Service Interface

interface VaneService {
    suspend fun get(url: String): VaneResponse
    suspend fun post(url: String, body: ByteArray? = null): VaneResponse
    suspend fun put(url: String, body: ByteArray? = null): VaneResponse
    suspend fun delete(url: String): VaneResponse
    suspend fun patch(url: String, body: ByteArray? = null): VaneResponse
}

class VaneServiceImpl(private val session: VaneSession) : VaneService {
    override suspend fun get(url: String): VaneResponse = session.get(url)
    override suspend fun post(url: String, body: ByteArray?): VaneResponse = session.post(url, body)
    override suspend fun put(url: String, body: ByteArray?): VaneResponse = session.put(url, body)
    override suspend fun delete(url: String): VaneResponse = session.delete(url)
    override suspend fun patch(url: String, body: ByteArray?): VaneResponse = session.patch(url, body)
}

// MARK: - Annotation-based Service (Retrofit-style)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class GET(val value: String)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class POST(val value: String)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class PUT(val value: String)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class DELETE(val value: String)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class PATCH(val value: String)

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Path(val value: String)

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Query(val value: String)

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Header(val value: String)

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Body

// MARK: - Example Service Interface

interface ApiService {
    @GET("/users")
    suspend fun getUsers(): List<User>

    @GET("/users/{id}")
    suspend fun getUser(@Path("id") id: String): User

    @POST("/users")
    suspend fun createUser(@Body user: User): User

    @PUT("/users/{id}")
    suspend fun updateUser(@Path("id") id: String, @Body user: User): User

    @DELETE("/users/{id}")
    suspend fun deleteUser(@Path("id") id: String): VaneResponse

    @GET("/search")
    suspend fun searchUsers(@Query("q") query: String): List<User>
}

@Serializable
data class User(
    val id: String? = null,
    val name: String,
    val email: String
)

// MARK: - Usage Examples

/*
// Basic usage
val session = VaneSession()
val response = session.get("https://api.example.com/users")

// With configuration
val config = VaneConfigurationBuilder()
    .baseUrl("https://api.example.com")
    .defaultHeaders(mapOf("Authorization" to "Bearer token"))
    .timeout(30u)
    .build()

val session = VaneSession(config)

// Request builder pattern
val users = session.request("/users")
    .header("Accept", "application/json")
    .queryParam("page", "1")
    .responseJson<List<User>>()

// POST with JSON
val newUser = User(name = "John", email = "john@example.com")
val response = session.request("/users", HttpMethod.POST)
    .jsonBody(newUser)
    .execute()

// Using in ViewModel
class UserViewModel : ViewModel() {
    private val apiService = VaneServiceImpl(VaneSession(config))

    fun loadUsers() {
        viewModelScope.launch {
            try {
                val response = apiService.get("/users")
                val users = response.json<List<User>>()
                // Update UI
            } catch (e: VaneHttpException) {
                // Handle HTTP error
            } catch (e: Exception) {
                // Handle other errors
            }
        }
    }
}
*/
