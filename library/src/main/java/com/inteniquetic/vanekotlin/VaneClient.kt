package com.inteniquetic.vanekotlin

import android.content.Context
import android.util.Log
import java.net.URLEncoder
import java.nio.charset.Charset
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    private val client: VaneClient by lazy {
        createVaneClient(configuration)
    }

    private val json = vaneJson

    internal constructor(
        configuration: VaneClientConfig,
        requestInterceptors: List<VaneRequestInterceptor> = emptyList(),
        responseInterceptors: List<VaneResponseInterceptor> = emptyList(),
        errorInterceptors: List<VaneErrorInterceptor> = emptyList(),
        transportExecutor: suspend (VaneRequest) -> VaneResponse
    ) : this(configuration, requestInterceptors, responseInterceptors, errorInterceptors) {
        this.transportExecutor = transportExecutor
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
    private var responseBodyPath: String? = null
    private var uploadProgress: VaneProgressCallback? = null
    private var downloadProgress: VaneProgressCallback? = null
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
        return this
    }

    fun bodyFile(path: String): VaneRequestBuilder {
        body = null
        bodyFilePath = path
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
            cancelTokenId = null,
            progressId = progressId,
            timeoutSeconds = timeoutSeconds,
            followRedirects = followRedirects
        )
        try {
            executor(request)
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
