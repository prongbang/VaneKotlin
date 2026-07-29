// SPDX-License-Identifier: MIT OR Apache-2.0
package org.rustls.platformverifier

/**
 * Stands in for the `BuildConfig` AGP generates for the upstream
 * rustls-platform-verifier Android module.
 *
 * Vendoring only the one source file means that class does not exist here —
 * the `BuildConfig` this module generates belongs to
 * `com.inteniquetic.vanekotlin`. `TEST` is false for exactly the same reason it
 * is false in the artifact upstream publishes: the branches it guards install
 * mock trust roots and exist only for the verifier's own test suite. Keeping
 * the symbol rather than deleting those branches keeps CertificateVerifier.kt
 * a clean diff against upstream.
 */
internal object BuildConfig {
    const val TEST = false
}
