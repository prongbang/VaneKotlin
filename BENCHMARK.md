# VaneKotlin Android benchmark

Client × protocol HTTP latency matrix on Android: **vane** (the shipped AAR +
jniLibs), **Cronet** (embedded Chromium network stack — the only other HTTP/3
speaker on Android), **OkHttp**, and **Retrofit2-over-OkHttp** (included to
show what the wrapper costs over its own engine) — each pinned to every HTTP
version it can reach, in one process, against one endpoint, sequentially, so
every comparison is like-for-like at the same protocol.

This is the Android sibling of `vane_benchmark/` (the Dart harness is the
reference implementation); methodology and JSON schema match it exactly so the
per-platform results merge into one chart.

The harness lives in
`library/src/androidTest/java/com/inteniquetic/vanekotlin/benchmark/ProtocolMatrixBenchmark.kt`.
The competing clients are `androidTestImplementation` dependencies, which
never reach the published AAR or its consumers — same rule as
`vane_benchmark` on the Dart side: do not move them to `implementation`.

## Run it

```sh
VANE_TEST_BASE_URL=https://cloudflare-quic.com VaneKotlin/bench-android.sh
```

That one command builds the androidTest APK, boots the first available AVD
headless if no device is connected, installs, runs the matrix via
`am instrument`, prints the grouped tables, and pulls the JSON metrics to
`vane_benchmark/results/android-latest.json` (gitignored scratch; override
with `VANE_BENCH_JSON`; a run worth keeping is copied to
`results/<date>-<label>.json` by hand). Knobs, forwarded as instrumentation
arguments under the same names as the Dart harness's env vars:
`VANE_BENCH_ROUNDS` (3), `VANE_BENCH_REQUESTS` per round (10),
`VANE_BENCH_WARMUP` (5). `ANDROID_HOME` defaults to
`$HOME/Library/Android/sdk` if unset.

The script uses `am instrument` rather than `connectedAndroidTest` because
Gradle uninstalls the test APK when it finishes, taking the result files with
it. Without `VANE_TEST_BASE_URL` the test is skipped (JUnit assumption), same
as every live-gated test in this repo.

## The matrix

| client | HTTP/1.1 | HTTP/2 | HTTP/3 |
|---|---|---|---|
| vane | `HTTP1_ONLY` | `HTTP2_ONLY` (prior knowledge) | `HTTP3_ONLY` |
| cronet | `enableHttp2(false)`, `enableQuic(false)` | `enableHttp2(true)`, `enableQuic(false)` | `enableQuic(true)` + QUIC hint |
| okhttp | `protocols([HTTP_1_1])` | `protocols([HTTP_2, HTTP_1_1])` (ALPN) | — no QUIC transport |
| retrofit2 | over okhttp, same pin | over okhttp, same pin | — rides OkHttp |

Pinning semantics, honestly stated:

- **vane h2** is HTTP/2 prior knowledge (same as reqwest's
  `http2_prior_knowledge()`, which is also what the Dart harness's vane and
  rhttp h2 pins do). **okhttp h2** cannot be pinned that hard over TLS — its
  API requires `HTTP_1_1` in the list — so that cell is "h2 via ALPN, h1.1
  permitted". **cronet h3** has no "only" switch anywhere in Cronet's API:
  QUIC is enabled explicitly plus a `addQuicHint` for the host so it races
  QUIC from the first request.
- Because pins can be soft, **the proto column is read off every response**
  (`VaneResponse.httpVersion`, `UrlResponseInfo.negotiatedProtocol`,
  `Response.protocol`), never assumed. A row that negotiates something other
  than its pin gets a NOTE line; a pinned cell that cannot reach its protocol
  is an ERROR row, never a silent downgrade. Cells that cannot exist are
  printed as `unsupported` — that vane and Cronet cover the h3 column and
  OkHttp/Retrofit do not IS a result.

## What it measures

Identical to the Dart harness: per client, one **cold** request on a fresh
client (reported alone), then warmup requests (discarded), then rounds ×
requests **measured** sequential GETs of `<base>/`. `p50`/`p95` are
nearest-rank over the pooled measured samples (same formula as
`vane-rs/examples/bench.rs`). The visiting order rotates by one each round;
DNS is resolved once before any client runs; status validation, byte
materialization and timing are done identically for every client; every
request has a 30 s guard; pooling/keep-alive is ON for every client (each
one's default). Per-client failures become ERROR rows and do not kill the
run. Each row owns its clients and connection pools — the retrofit rows use
their own identically-pinned OkHttp clients, so their cold is a real
handshake and their delta vs the okhttp rows is exactly the wrapper cost.

Client-surface notes: vane and OkHttp are called on their blocking paths
(`getRequest`, `Call.execute()`); Retrofit through a converter-less
`Call<ResponseBody>.execute()`; Cronet has no synchronous mode, so its rows
include the callback/executor handoff its API forces on every app.

## Caveats — read before quoting numbers

- **These are emulator numbers.** The emulator's network rides the host's
  NAT/userspace stack (including UDP for QUIC). They are NOT device numbers
  and are not comparable in absolute terms to the Dart host-VM numbers.
  One machine, one network, sequential requests — RTT-dominated, not a lab.
- **vane's TCP cold numbers include Android platform trust-store
  initialization** (the rustls-platform-verifier JNI round-trip on first TLS
  use), a once-per-process cost that lands in whichever TCP row runs first —
  0.4–1.0 s on the emulator. The h3 row doesn't pay it (QUIC verifies against
  the CA directory instead).
- JSON emitted by Android's `org.json` escapes `/` in strings; it parses
  identically to the Dart files.

## Results (2026-08-11, API 35 arm64 emulator on an M-series host, cloudflare-quic.com)

Full run kept at `vane_benchmark/results/2026-08-11-android-emulator-api35.json`.
jniLibs measured were built from vane-rs HEAD (includes the `61033a5` drive-loop
fix) with NDK 27.0.12077973.

```
== HTTP/1.1 ==
client                    proto  cold_ms   p50_ms   p95_ms   min_ms   max_ms        n    bytes
vane (h1)              HTTP/1.1   866.55    25.07    29.87    22.10    37.01       30   125961
cronet (h1.1)          HTTP/1.1    55.70    24.13    28.43    21.88    34.91       30   125961
okhttp (h1.1)          HTTP/1.1    67.32    23.51    28.23    21.20    33.22       30   125961
retrofit2 (h1.1)       HTTP/1.1    53.14    25.84    29.93    22.80    34.28       30   125961

== HTTP/2 ==
vane (h2)                HTTP/2   494.33    25.46    29.29    22.20    29.57       30   125959
cronet (h2)              HTTP/2    53.26    25.96    28.77    22.10    29.14       30   125959
okhttp (h2)              HTTP/2    56.47    26.07    31.40    24.08    34.29       30   125959
retrofit2 (h2)           HTTP/2    50.50    24.01    27.24    20.94    31.18       30   125959

== HTTP/3 ==
vane (h3)                HTTP/3    61.40    35.72    45.03    32.36    80.09       30   125959
cronet (h3)              HTTP/3    48.67    25.84    31.45    23.01    38.11       30   125959
okhttp                   unsupported: no HTTP/3/QUIC transport
retrofit2 (okhttp)       unsupported: rides OkHttp, no HTTP/3/QUIC transport
```

### Stability across 3 back-to-back runs (p50/p95 ms)

| client | run 1 | run 2 | run 3 |
|---|---|---|---|
| vane (h1) | 25.1 / 29.9 | 24.4 / 35.9 | 22.7 / 31.4 |
| vane (h2) | 25.5 / 29.3 | 24.2 / 30.4 | 21.6 / 26.4 |
| vane (h3) | 35.7 / 45.0 | 35.2 / 38.6 | 28.8 / 76.6 |
| cronet (h1.1) | 24.1 / 28.4 | 27.5 / 30.2 | 27.9 / 35.4 |
| cronet (h2) | 26.0 / 28.8 | 26.2 / 30.2 | 25.8 / 35.1 |
| cronet (h3) | 25.8 / 31.4 | 29.4 / 38.3 | 26.1 / 36.8 |
| okhttp (h1.1) | 23.5 / 28.2 | 26.5 / 32.6 | 24.9 / 31.2 |
| okhttp (h2) | 26.1 / 31.4 | 25.3 / 29.5 | 23.4 / 26.6 |
| retrofit2 (h1.1) | 25.8 / 29.9 | 25.2 / 30.5 | 27.2 / 30.3 |
| retrofit2 (h2) | 24.0 / 27.2 | 26.2 / 32.0 | 23.8 / 28.4 |

Read of the three runs, stated plainly:

- **HTTP/1.1 and HTTP/2: no stable ranking.** All four clients land within a
  ~2–5 ms band and the order shuffles run to run — parity within emulator
  noise. Vane placed first in 2 of 3 runs in both TCP groups, but the margins
  are noise-level. Retrofit costs nothing measurable over its own OkHttp at
  p50 — the wrapper is free at this request size.
- **HTTP/3: Cronet beat Vane in all three runs** — the one stable ranking in
  the matrix. p50 gap 2.7–9.9 ms, and Vane's h3 tail is worse every time
  (p95 38.6–76.6 vs Cronet's 31.4–38.3; run 3 saw a 97.6 ms max). Cronet's
  QUIC rides the same emulator NAT, so the emulator alone does not explain
  the gap. On this emulator Vane's h3 is also the slowest of Vane's own three
  pins — inverted from the Dart host-VM results, where h3 beat h1.
  **Diagnosed and fixed 2026-08-12 — see "The h3 gap to Cronet: a socket
  buffer" below.**
- **Vane's TCP cold is heavy on Android**: 0.87–1.07 s (h1) and 0.40–0.56 s
  (h2) across runs, vs 45–200 ms for everyone else — dominated by the
  once-per-process platform trust-store init described above. Vane's h3 cold
  (55–65 ms) is competitive. `warmup()` exists to move this cost off the
  first request — measured below.
- Every measured row negotiated exactly its pinned protocol in every run
  (`observed_protocol == pinned_protocol`, no NOTEs, n=30 everywhere).

## warmup(): the cold start, moved off the first request (2026-08-12)

`VaneClient.warmup(url)` pays the lazy setup at a moment the app chooses —
client construction (tokio runtime, TLS config, platform verifier), one TLS
handshake to the target whose NewSessionTickets are kept so the first real
connection can *resume* (a resumed handshake carries no certificate, which is
what skips Android's per-verification JNI cost), and on HTTP/3 a pre-connected
pooled QUIC connection. Best-effort by contract: it never throws, and an
`Http3Only` client never touches TCP machinery.

Measured by `benchmark/WarmupBenchmark` via `VaneKotlin/bench-warmup.sh`
(same emulator class and endpoint as the matrix above; each sample is a fresh
app process because the trust-store init is process-global — the matrix's
`cold` column keeps its meaning, this table adds the warmed variant beside
it). Three runs each, 2026-08-12, API 35 arm64 emulator, cloudflare-quic.com:

```
                         run1     run2     run3
tcp (h2) cold          761.64   775.64  1239.18   first request, no warmup
tcp (h2) after warmup    54.15    98.98    99.86   first request; warmup itself 833-837 (background)
h3 cold                  44.20    48.37    48.45   first request, no warmup
h3 after warmup          36.55    40.98    45.31   first request; warmup itself 13-24
```

Read: the unwarmed TCP cold is unchanged (0.76–1.24 s here, the matrix band),
and the first request after a background `warmup()` lands at 54–100 ms — the
same class as Cronet/OkHttp/Retrofit cold (45–200 ms). h3 cold is unaffected,
and warmed h3 rides the pre-connected pooled connection.

Two caveats, stated plainly:

- Resumption is the server's call. If it declines the ticket, the first
  request runs a full handshake and pays the per-verification cost again —
  observed once in an earlier 3-run set as a ~406 ms sample. Still ~2–3x
  better than no warmup, but not the 54–100 ms headline.
- The deeper number underneath: on this emulator **every full TLS
  verification through the platform verifier costs ~350–400 ms**, per
  handshake, not once — the verifier re-runs PKIX building and the
  revocation check per call (visible in the matrix as vane (h2)'s 494 ms
  cold in a process where vane (h1) had already run for minutes). warmup
  sidesteps it via resumption; fixing it at the source is a
  rustls-platform-verifier caching issue, adjacent to the #221 patch this
  repo already carries.

## The h3 gap to Cronet: a socket buffer (2026-08-12)

The one stable ranking in the matrix above — Cronet beating Vane at HTTP/3
in all three runs, with Vane carrying a 76–98 ms tail — was a kernel socket
buffer, not a protocol or stack difference.

Per-request attribution split every request at the response-HEADERS event.
TTFB was at parity or better (Vane 23.3 ms p50 vs Cronet 30.4 in the same
window); the entire gap sat in body transfer (p50 8.5 vs 3.3 ms, p95 55 vs
6 ms) — 3 of 30 requests stalled ~50 ms mid-body, each receiving one or two
extra packets, the signature of a loss recovered by timeout rather than
fast retransmit. The kernel then named the mechanism outright: `Udp:
RcvbufErrors` in `/proc/net/snmp` grew by exactly one drop per request
under Vane's traffic (+36 across 36 requests) and by zero under Cronet's.
Pooling reuse itself was clean — 1 handshake per run, 35/36 requests on the
pooled connection.

Why it drops: a pooled connection keeps the server's congestion window hot,
so each 126 KB response arrives as a single ~111-packet burst — and the
emulator's userspace NAT delivers that burst into the guest all at once,
where a real network would pace it across the path RTT. At ~2 KB of kernel
skb accounting per 1350-byte datagram, the burst costs ~256 KB against the
emulator's 224 KB default receive buffer (`rmem_default`), so the tail of
the flight is dropped at the socket. A tail-of-flight drop leaves no later
packet to reveal the gap, so recovery waits out the server's probe timeout
— tens of milliseconds — which is exactly the 76–98 ms max column above.
Cronet is immune because Chromium requests a 1 MB receive buffer on its
QUIC sockets (and drains with recvmmsg + UDP GRO).

The fix, in vane-rs: request a 1 MB `SO_RCVBUF` (Chromium's number) on
every QUIC UDP socket, best-effort; the kernel clamps to `rmem_max`
(256 KB → 512 KB effective on this emulator) and the buffer is a limit,
not an allocation, so idle pooled connections cost nothing. Measured
after: **zero RcvbufErrors across ~216 Vane h3 requests** in six
instrumented-and-matrix runs, and body-transfer p95 fell 55 → 8–17 ms.

Post-fix matrix, same emulator class and endpoint, four runs (p50/p95 ms);
run 4 measured the final shipped jniLibs; run 2 (the both-clients-clean run)
is kept at
`vane_benchmark/results/2026-08-12-android-emulator-api35-rcvbuf-fix.json`:

| run | vane (h3) | cronet (h3) |
|---|---|---|
| 1 | 25.49 / 32.07 | 50.17 / 167.80 |
| 2 | 25.14 / 29.66 | 26.17 / 32.47 |
| 3 | 38.08 / 77.64 | 27.92 / 34.03 |
| 4 | 25.84 / 31.21 | 34.02 / 49.84 |

No stable ranking survives. Vane's three clean runs sit at p50 25.1–25.8
(pre-fix: 28.8–35.7 with Cronet ahead in every run); runs 1/3/4 each show
one client catching a several-minute endpoint-weather window; instrumented
runs place those windows in TTFB (path RTT / server time, hitting both
clients alike — Cronet's own TTFB p50 swung 21.9 → 40.5 ms between runs
half an hour apart) with body transfer staying clean, and `RcvbufErrors`
stayed flat through all of them. The h3 group now behaves like the TCP groups: parity
within emulator noise. Residual and accepted: ~3 ms of body-side p50 vs
Cronet on this emulator, from one recv syscall per datagram vs Cronet's
batched drain — the upgrade path (recvmmsg/GRO) is noted in the core on
`read_quic_packets`.

Host macOS A/B of the same change (cloudflare-quic.com, 3 runs each side,
`examples/bench` warm pool=on): p50 23.6/30.3/23.8 → 21.5/20.7/36.2, body
p50 2.9–3.6 → 3.1–5.1 — unchanged within endpoint weather (the 36.2 run's
TTFB was elevated by the same amount). The host never dropped a packet in
any run: real-network pacing spreads the burst, and macOS's default socket
buffer (786 KB) absorbs what remains, which is why this only ever showed
up on Android.
