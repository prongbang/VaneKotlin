package com.inteniquetic.vanekotlin

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The device-real check for the caller-supplied DNS resolver through the
 * packaged `libvane.so`: the foreign-trait callback crosses UniFFI's
 * generated call sites on a real device, `resolve` is invoked with the URL
 * host, and the address it returns is where the connection actually goes —
 * proven by a local listener observing the bytes arrive.
 *
 * `.invalid` never resolves (RFC 2606) and the system resolver is never
 * consulted once a resolver is installed, so the accepted connection can
 * only have come from the resolver's answer. The listener speaks no TLS, so
 * the request itself must fail — after the connect, which is the part under
 * test. `http1Only` keeps the path deterministic, mirroring
 * [TcpCustomRootTrustInstrumentedTest].
 */
@RunWith(AndroidJUnit4::class)
class VaneDnsResolverInstrumentedTest {

    @Test
    fun theResolverIsConsultedAndItsAddressReceivesTheConnection() = runBlocking {
        // 127.0.0.1 explicitly: `getLoopbackAddress` may prefer ::1, and the
        // resolver's answer — the address under test — is the IPv4 literal.
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val accepted = CountDownLatch(1)
        val acceptor = thread {
            runCatching {
                server.accept().use {
                    accepted.countDown()
                    it.getInputStream().read()
                }
            }
        }
        val resolved = CopyOnWriteArrayList<String>()
        val client = createVaneClient(
            VaneConfigurationBuilder().http1Only().timeout(10u).build()
        )
        client.setDnsResolver(object : VaneDnsResolver {
            override fun resolve(host: String): List<String> {
                resolved.add(host)
                return listOf("127.0.0.1")
            }
        })

        val failure = runCatching {
            client.getRequest("https://vane-dns-probe.invalid:${server.localPort}/")
        }.exceptionOrNull()

        assertEquals(listOf("vane-dns-probe.invalid"), resolved)
        assertTrue(
            "the resolver's address never received the connection; " +
                "request outcome: ${failure?.message ?: "success"}",
            accepted.await(5, TimeUnit.SECONDS)
        )
        assertNotNull("a plain-TCP listener cannot complete TLS", failure)
        server.close()
        acceptor.join(5_000)
    }
}
