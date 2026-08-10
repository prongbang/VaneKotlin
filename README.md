# VaneKotlin

Android/Kotlin package for Vane's Rust HTTP/3 client core.

VaneKotlin currently uses the HTTP/3-only Rust transport and packages native
`libvane.so` files in the library module. See the repository root `README.md`
for the full cross-platform guide.

## Requirements

- Android `minSdk 33`
- Kotlin coroutines
- The `VaneKotlin/library` module

## Usage

```kotlin
import com.inteniquetic.vanekotlin.*

val config = VaneConfigurationBuilder()
    .baseUrl("https://api.example.com")
    .defaultHeaders(mapOf("Accept" to "application/json"))
    .cookiesEnabled(true)
    .cookiePersistencePath(context.filesDir.resolve("vane-cookies.txt").path)
    .connectionPooling(enabled = true, maxIdleConnections = 8u, idleTimeoutSeconds = 30u)
    .retry(maxAttempts = 3u)
    .timeout(30u)
    .http3Only()
    .build()

val session = VaneSession(config)

val users = session.request("/users")
    .queryParam("page", "1")
    .responseString()

val created = session.postJson("/users", mapOf("name" to "Ada"))
    .validateStatus()

val upload = session.uploadFile(
    "/upload",
    "/data/user/0/app/files/input.bin",
    onUploadProgress = { sent, total -> }
)

val download = session.download(
    "/reports/latest",
    "/data/user/0/app/files/report.json",
    onDownloadProgress = { received, total -> }
)

val multipart = session.request("/upload", HttpMethod.POST)
    .multipart(
        fields = mapOf("title" to "avatar"),
        files = listOf(
            VaneMultipartFile(
                fieldName = "photo",
                bytes = imageBytes,
                fileName = "me.jpg",
                contentType = "image/jpeg"
            )
        )
    )
    .execute()
```

## Performance Usage

- Create one `VaneSession` per API domain or DI scope and reuse it.
- Do not create a new `VaneSession` for every request; that loses connection
  pooling and cookie reuse.
- Use direct helpers such as `get`, `postJson`, `uploadFile`, and `download` for
  common requests.
- Use `request(...).header(...).queryParam(...)` only when a request needs
  custom per-request options.
- Add interceptors to the existing session when auth/logging behavior changes;
  this keeps the native client and connection pool alive.
- Add `onUploadProgress` / `onDownloadProgress` only when the UI needs progress.
- Use `download(..., outputPath)` or `downloadToFile` for large responses.
- Current multipart helpers build the body in memory, so keep multipart for
  small/medium payloads.

```kotlin
session
    .addRequestInterceptor { request ->
        request.copy(headers = request.headers + ("Authorization" to "Bearer $token"))
    }
    .addResponseInterceptor { response ->
        response
    }

session.clearInterceptors()
```

## Tests

```bash
./gradlew :library:testDebugUnitTest
```

## Benchmark

Cross-client × protocol latency matrix (vane vs Cronet vs OkHttp vs
Retrofit2, each pinned per HTTP version) on an emulator:

```bash
VANE_TEST_BASE_URL=https://cloudflare-quic.com ./bench-android.sh
```

Methodology, caveats and measured results: [BENCHMARK.md](BENCHMARK.md).
