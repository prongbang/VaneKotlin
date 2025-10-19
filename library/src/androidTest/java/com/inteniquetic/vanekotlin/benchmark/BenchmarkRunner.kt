package com.inteniquetic.vanekotlin.benchmark

import com.inteniquetic.vanekotlin.VaneClient
import com.inteniquetic.vanekotlin.VaneResponse
import kotlinx.coroutines.*
import kotlin.system.measureNanoTime
import java.lang.Runtime
import kotlin.math.max
import kotlin.to

object BenchmarkRunner {

    private const val BASE_URL = "http://127.0.0.1:8000"
    private const val WARMUPS = 10
    private const val ITERATIONS = 100

    // Measure current memory usage
    private fun currentMemoryUsage(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private suspend fun runBenchmark(
        summaryName: String,
        labelPrefix: String,
        iterations: Int,
        warmups: Int = 0,
        operations: List<Pair<String, suspend () -> Any>>
    ) {
        val results = mutableListOf<Triple<String, Double, Long?>>()

        for ((label, action) in operations) {
            // Warmups
            repeat(warmups) { action() }

            val memBefore = currentMemoryUsage()
            val elapsed = measureNanoTime {
                repeat(iterations) {
                    runBlocking { action() }
                }
            }
            val memAfter = currentMemoryUsage()

            val bytesUsed = max(0, memAfter - memBefore)
            val nsPerOp = elapsed.toDouble() / iterations

            results += Triple(label, nsPerOp, bytesUsed / iterations)
        }

        println("\n$summaryName summary:")
        for ((label, nsPerOp, bytesPerOp) in results) {
            val name = "Benchmark${labelPrefix}${label}".padEnd(24, ' ')
            val totalSec = nsPerOp * ITERATIONS / 1_000_000_000.0
            val bytesColumn = if (bytesPerOp != null && bytesPerOp > 0) {
                String.format("%9.1f B/op", bytesPerOp.toDouble())
            } else {
                "   n/a B/op"
            }

            println(
                String.format(
                    "%s%6d\t%9.0f ns/op\t%s\t(total %.3fs)",
                    name, ITERATIONS, nsPerOp, bytesColumn, totalSec
                )
            )
        }
    }

    // 🦀 Benchmark Vane
    suspend fun benchmarkVane(vane: VaneClient) {
        val operations = listOf(
            "GET" to suspend { vane.getRequest("/get") },
            "POST" to suspend {
                vane.postRequest(
                    "/post",
                    """{"message":"post"}""".toByteArray()
                )
            },
            "PUT" to suspend {
                vane.putRequest(
                    "/put",
                    """{"message":"put"}""".toByteArray()
                )
            },
            "PATCH" to suspend {
                vane.patchRequest(
                    "/patch",
                    """{"message":"patch"}""".toByteArray()
                )
            },
            "DELETE" to suspend { vane.deleteRequest("/delete") }
        )

        runBenchmark(
            summaryName = "Vane benchmark",
            labelPrefix = "Vane",
            iterations = ITERATIONS,
            warmups = WARMUPS,
            operations = operations
        )
    }

    // ☕ Benchmark Retrofit2
    suspend fun benchmarkRetrofit(api: RetrofitService) {
        val operations = listOf(
            "GET" to suspend { api.get() },
            "POST" to suspend { api.post(mapOf("message" to "post")) },
            "PUT" to suspend { api.put(mapOf("message" to "put")) },
            "PATCH" to suspend { api.patch(mapOf("message" to "patch")) },
            "DELETE" to suspend { api.delete() }
        )

        runBenchmark(
            summaryName = "Retrofit2 benchmark",
            labelPrefix = "Retrofit",
            iterations = ITERATIONS,
            warmups = WARMUPS,
            operations = operations
        )
    }
}