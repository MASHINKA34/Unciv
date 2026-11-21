package com.unciv.online

import com.unciv.logic.UncivKtor
import com.unciv.logic.UncivShowableException
import com.unciv.utils.Log
import com.unciv.utils.Tag
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Unciv Online: everything the client needs to talk to our own server.
 *
 * - The default server URL and the shared key are baked in at build time ([OnlineBuildConfig]).
 * - Every request to the multiplayer server carries the key in [KEY_HEADER]; the server answers 401 without it.
 * - Network failures and proxy errors are mapped to [MultiplayerServerOfflineException] so the player sees
 *   "the server is off" instead of a stack trace: when the host PC is off, Tailscale Funnel cannot reach it.
 */
object OnlineServer {
    val tag = Tag("UncivOnline")

    /** Header with the shared key - must match the server (server/src/com/unciv/app/server/OnlineSecurity.kt) */
    const val KEY_HEADER = "X-Unciv-Online-Key"
    /** Marker the server puts into the 401 body when the shared key is missing or wrong */
    const val BAD_KEY_MARKER = "unciv-online-bad-key"

    const val SERVER_OFFLINE_MESSAGE = "The server is off: the host is offline. Try again later."
    const val BAD_KEY_MESSAGE = "This app version is not accepted by the server. Ask the host for the current APK."

    /**
     * HTTP client for our server: the shared Unciv client plus short timeouts and a single retry,
     * so "server is off" shows up in seconds instead of after several long attempts.
     */
    val httpClient: HttpClient by lazy {
        UncivKtor.client.config {
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 20_000
                socketTimeoutMillis = 20_000
            }
            install(HttpRequestRetry) {
                maxRetries = 1
                retryOnException()
            }
        }
    }

    val defaultServerUrl: String get() = OnlineBuildConfig.SERVER_URL
    val hasSharedKey: Boolean get() = OnlineBuildConfig.SHARED_KEY.isNotEmpty()

    /** The key header alone (empty when the build has no key) */
    fun keyHeader(): Map<String, String> =
        if (hasSharedKey) mapOf(KEY_HEADER to OnlineBuildConfig.SHARED_KEY) else emptyMap()

    /** [headers] plus the key header */
    fun withKey(headers: Map<String, String>?): Map<String, String> = headers.orEmpty() + keyHeader()

    /** 502/503/504 come from the reverse proxy (Tailscale serve/Funnel) when our server process is not running */
    fun isOfflineStatus(code: Int?): Boolean = code == 502 || code == 503 || code == 504

    /** True if the 401 was caused by the shared key, not by the player's password */
    fun isBadKeyResponse(code: Int?, body: String?): Boolean = code == 401 && body?.contains(BAD_KEY_MARKER) == true

    /**
     * True for failures where no HTTP answer arrived at all: refused or timed out connection, unknown host,
     * TLS handshake cut off by the relay, connection reset. Walks the cause chain.
     */
    fun isOfflineCause(throwable: Throwable?): Boolean {
        var current = throwable
        var depth = 0
        while (current != null && depth < 10) {
            if (current is ConnectException || current is SocketTimeoutException || current is UnknownHostException
                || current is NoRouteToHostException || current is SSLException || current is SocketException
                || current is EOFException
            ) return true
            // ktor wraps timeouts in its own types (ConnectTimeoutException, HttpRequestTimeoutException)
            val name = current::class.java.simpleName
            if (name == "ConnectTimeoutException" || name == "HttpRequestTimeoutException" || name == "UnresolvedAddressException")
                return true
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * Converts a failed HTTP call into the exception the UI should show, or returns null when the failure
     * is not about server availability or the key (the caller then keeps its own handling).
     * @param code HTTP status, null when no response arrived
     */
    fun classifyFailure(operation: String, url: String, code: Int?, body: String?, cause: Throwable? = null): UncivShowableException? {
        return when {
            code == null || isOfflineStatus(code) -> {
                Log.warn(tag, "%s failed: server unreachable url=%s code=%s reason=%s", operation, url, code, body ?: cause?.message)
                MultiplayerServerOfflineException(cause ?: Exception("$operation: $code $body"))
            }
            isBadKeyResponse(code, body) -> {
                Log.error(tag, "%s rejected: server does not accept our shared key url=%s (keyPresent=%s)", operation, url, hasSharedKey)
                OnlineKeyRejectedException(Exception("$operation: $code $body"))
            }
            else -> null
        }
    }
}

/** The multiplayer server cannot be reached: the host PC is off or the server is not running */
class MultiplayerServerOfflineException(cause: Throwable?) : UncivShowableException(OnlineServer.SERVER_OFFLINE_MESSAGE, cause)

/** The server rejected the shared key built into this APK (old APK after a key change) */
class OnlineKeyRejectedException(cause: Throwable?) : UncivShowableException(OnlineServer.BAD_KEY_MESSAGE, cause)
