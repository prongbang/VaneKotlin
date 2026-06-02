package com.inteniquetic.vanekotlin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// MARK: - Kotlin Extensions and Helpers

object Vane {
    private var isLoaded = false

    fun initialize() {
        if (isLoaded) return
        try {
            System.loadLibrary("vane")
            isLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            Log.e("Vane", "FAIL: vane not found ❌", e)
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

class VaneSession(
    private val configuration: VaneClientConfig = createDefaultConfig(),
    private val requestInterceptors: List<VaneRequestInterceptor> = emptyList(),
    private val responseInterceptors: List<VaneResponseInterceptor> = emptyList(),
    private val errorInterceptors: List<VaneErrorInterceptor> = emptyList()
) {
    private var transportExecutor: (suspend (VaneRequest) -> VaneResponse)? = null

    private val client: VaneClient by lazy {
        createVaneClient(configuration)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

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

    suspend fun execute(request: VaneRequest): VaneResponse {
        var interceptedRequest = request
        for (interceptor in requestInterceptors) {
            interceptedRequest = interceptor(interceptedRequest)
        }

        return try {
            var response = (transportExecutor ?: { req -> client.executeAsync(req) })(interceptedRequest)
            for (interceptor in responseInterceptors) {
                response = interceptor(response)
            }
            response
        } catch (throwable: Throwable) {
            for (interceptor in errorInterceptors) {
                val response = interceptor(throwable)
                if (response != null) {
                    var interceptedResponse: VaneResponse = response
                    for (responseInterceptor in responseInterceptors) {
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
        return this
    }

    inline fun <reified T> jsonBody(obj: T): VaneRequestBuilder {
        val jsonString = json.encodeToString(obj)
        this.body = jsonString.toByteArray()
        header("Content-Type", "application/json")
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

    suspend fun execute(): VaneResponse {
        val request = VaneRequest(
            url = url,
            method = method.value,
            headers = headers,
            queryParams = queryParams,
            body = body,
            timeoutSeconds = timeoutSeconds,
            followRedirects = followRedirects
        )
        return executor(request)
    }

    suspend inline fun <reified T> responseJson(): T {
        val response = execute()

        if (!response.isSuccess) {
            throw VaneHttpException(
                message = "Request failed with status ${response.statusCode}",
                statusCode = response.statusCode,
                response = response
            )
        }

        return json.decodeFromString<T>(String(response.body))
    }

    suspend fun responseString(): String {
        val response = execute()

        if (!response.isSuccess) {
            throw VaneHttpException(
                message = "Request failed with status ${response.statusCode}",
                statusCode = response.statusCode,
                response = response
            )
        }

        return String(response.body)
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

inline fun <reified T> VaneResponse.json(): T {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    return json.decodeFromString<T>(String(body))
}

val VaneResponse.prettyJson: String?
    get() = try {
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        json.encodeToString(Json.parseToJsonElement(String(body)))
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
