package com.unciv.app.server

import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * Unciv Online: shared key check. Every request must carry the key baked into our APK in [KEY_HEADER],
 * otherwise the server answers 401 with [BAD_KEY_MARKER] in the body (the client shows "ask the host for the
 * current APK" instead of asking for a password). Must match core/src/com/unciv/online/OnlineServer.kt.
 */
object OnlineSecurity {
    const val KEY_HEADER = "X-Unciv-Online-Key"
    const val BAD_KEY_MARKER = "unciv-online-bad-key"

    /**
     * Reads the key from [file]: either a properties file with `shared.key=...` (our secrets.properties)
     * or a plain text file whose first non-empty line is the key. Read as UTF-8.
     */
    fun readKeyFile(file: File): String {
        require(file.isFile) { "Key file not found: ${file.absolutePath}" }
        val text = file.readText(Charsets.UTF_8)
        val properties = Properties()
        text.reader().use { properties.load(it) }
        val fromProperties = properties.getProperty("shared.key")?.trim()
        if (!fromProperties.isNullOrEmpty()) return fromProperties
        val plain = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        require(!plain.isNullOrEmpty() && !plain.contains('=')) { "No 'shared.key' found in ${file.absolutePath}" }
        return plain
    }

    /** Constant-time comparison, so response timing does not leak how much of a guessed key was right */
    fun keyMatches(expected: String, provided: String?): Boolean {
        if (provided == null) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), provided.toByteArray(Charsets.UTF_8))
    }

    /** For logs: never the key itself, only whether it is there and its length */
    fun describe(key: String?): String = if (key.isNullOrEmpty()) "missing" else "set (${key.length} chars)"
}
