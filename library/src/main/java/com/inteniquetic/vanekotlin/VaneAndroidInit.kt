package com.inteniquetic.vanekotlin

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

/**
 * Native entry points that UniFFI does not generate.
 *
 * UniFFI reaches `libvane.so` through JNA, which `dlopen`s it outside the JVM's
 * native-method registry — so an `external fun` here only resolves after a
 * separate `System.loadLibrary("vane")`, which [Vane.initialize] performs.
 */
internal object VaneNative {
    /**
     * Hands Rust the application [Context] that `rustls-platform-verifier`
     * verifies TLS certificates through, once per process.
     *
     * Returns false if the JNI handshake failed. Failure is reported rather
     * than thrown: the caller runs during app startup, and a dead TCP
     * transport must not become a dead app.
     */
    external fun initAndroid(context: Context): Boolean
}

/**
 * Performs [Vane.initialize] before the consuming app's `Application.onCreate`,
 * so the TCP transport (HTTP/2 and HTTP/1.1) works with no setup at all.
 *
 * Android verifies certificates through the platform trust store over JNI,
 * which has to be given a `Context` before the first TLS handshake. Getting one
 * without changing Vane's public API means the library has to find it itself,
 * and a `ContentProvider` is the platform's own hook for that: the manifest
 * merger installs it into every consumer automatically, and it is created
 * before any application code runs.
 *
 * A bare `ContentProvider` rather than `androidx.startup` because that library
 * is a wrapper over this exact mechanism — a whole dependency to obtain a
 * `Context` already available for free.
 */
class VaneInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { Vane.initialize(it) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
