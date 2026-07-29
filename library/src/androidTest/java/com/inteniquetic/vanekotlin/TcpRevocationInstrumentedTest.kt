package com.inteniquetic.vanekotlin

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the revocation verdict in the vendored `CertificateVerifier`, which
 * reports only a definitive `REVOKED` as revocation and treats an
 * undeterminable status as valid.
 *
 * ## What is deliberately not asserted here
 *
 * There is no "a revoked certificate is refused" case, because on Android it
 * cannot be made to pass — and a test that appears to prove it would be worse
 * than none. Measured on this emulator:
 *
 *  - `revoked-rsa-dv.ssl.com` (genuinely revoked, and its CA still runs an OCSP
 *    responder) connects successfully.
 *  - It also connected with the upstream verdict mapping restored, and the
 *    `CertPathValidatorException` handler never ran in either case — so this
 *    is not a consequence of the local patch.
 *  - The reason: OCSP responder URLs are plain `http://` by design (RFC 6960),
 *    and `NetworkSecurityPolicy.isCleartextTrafficPermitted("ocsps.ssl.com")`
 *    is false, so the fetch fails. `PKIXRevocationChecker` runs with
 *    `SOFT_FAIL`, which forgives a failed fetch, so no exception is raised.
 *
 * Since `usesCleartextTraffic` defaults to false for apps targeting API 28+,
 * network-fetched OCSP is inoperative by default in modern Android apps.
 * Revocation is therefore only ever enforced from a *stapled* response — which
 * this patch still honours, since a staple that says revoked yields
 * `BasicReason.REVOKED`. Proving that end to end needs a server that staples a
 * revoked response, which no public host does; it is verified by inspection.
 */
@RunWith(AndroidJUnit4::class)
class TcpRevocationInstrumentedTest {

    private fun get(url: String) = runCatching {
        val config = VaneConfigurationBuilder().http1Only().timeout(30u).build()
        createVaneClient(config).getRequest(url)
    }

    /** Retried only for transport noise; see TcpTrustStoreInstrumentedTest. */
    private fun getWithRetries(url: String): Result<VaneResponse> {
        var last = get(url)
        repeat(2) { if (last.isFailure) last = get(url) }
        return last
    }

    /**
     * Certificates from CAs that retired OCSP in 2025 carry no responder URL,
     * so Android cannot determine revocation status and raises `UNSPECIFIED`.
     * These must connect: Let's Encrypt and Google Trust Services between them
     * issue for a large share of the web, and before this patch every one of
     * their sites was reported as revoked.
     */
    @Test
    fun certificatesWithoutAnOcspResponderAreAccepted() {
        for (url in listOf("https://www.cloudflare.com/", "https://badssl.com/")) {
            val result = getWithRetries(url)
            assertTrue(
                "$url should be accepted, last error: ${result.exceptionOrNull()?.message}",
                result.isSuccess
            )
        }
    }

    /** A valid certificate whose CA does still publish OCSP keeps working. */
    @Test
    fun validCertificateWithOcspStillSucceeds() {
        val result = getWithRetries("https://example.com/")
        assertEquals(
            "last error: ${result.exceptionOrNull()?.message}",
            200,
            result.getOrNull()?.statusCode?.toInt()
        )
    }
}
