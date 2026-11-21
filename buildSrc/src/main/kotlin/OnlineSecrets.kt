@file:Suppress("InvalidPackageDeclaration")
package com.unciv.build

import java.io.File
import java.util.Properties

/**
 * Unciv Online: build-time secrets (default server URL, shared server key, release signing).
 *
 * The secrets live in a local properties file that is never committed. Its location is taken from
 * the environment variable `UNCIV_ONLINE_SECRETS`, or from `unciv.online.secrets` in the
 * (git-ignored) `local.properties`. Both files are read as UTF-8, so non-ASCII paths work.
 *
 * Expected keys: `server.url`, `shared.key`, `release.storeFile`, `release.storePassword`,
 * `release.keyAlias`, `release.keyPassword`.
 */
object OnlineSecrets {
    const val ENV_VAR = "UNCIV_ONLINE_SECRETS"
    const val LOCAL_PROPERTY = "unciv.online.secrets"

    /** Used when no secrets file is configured - a debug build still compiles, but cannot reach any server. */
    const val PLACEHOLDER_SERVER_URL = "https://unciv-online.invalid"

    /** @return the configured secrets file (may not exist), or null if none is configured */
    fun locate(rootDir: File): File? {
        val fromEnv = System.getenv(ENV_VAR)
        if (!fromEnv.isNullOrBlank()) return File(fromEnv.trim())

        val localProperties = File(rootDir, "local.properties")
        if (!localProperties.isFile) return null
        val properties = Properties()
        localProperties.reader(Charsets.UTF_8).use { properties.load(it) }
        return properties.getProperty(LOCAL_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) }
    }

    /** @return the loaded secrets, or null when no secrets file is configured or it does not exist */
    fun load(rootDir: File): Properties? {
        val file = locate(rootDir) ?: return null
        if (!file.isFile) return null
        val properties = Properties()
        file.reader(Charsets.UTF_8).use { properties.load(it) }
        return properties
    }

    /** Human-readable description of where secrets are looked up, for build log and error messages. */
    fun describeLookup(rootDir: File): String {
        val file = locate(rootDir)
        return when {
            file == null -> "no secrets file configured (set $ENV_VAR or '$LOCAL_PROPERTY' in local.properties)"
            !file.isFile -> "secrets file not found: ${file.path}"
            else -> "secrets file: ${file.path}"
        }
    }
}
