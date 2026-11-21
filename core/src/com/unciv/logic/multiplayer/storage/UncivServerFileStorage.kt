package com.unciv.logic.multiplayer.storage

import com.badlogic.gdx.Net
import com.badlogic.gdx.utils.Base64Coder
import com.unciv.online.OnlineServer
import com.unciv.utils.Log
import com.unciv.utils.debug
import java.io.InputStream

/**
 * File storage on an APIv1 server (UncivServer).
 * Unciv Online: every request also carries the shared key ([OnlineServer.withKey]); an unreachable server
 * becomes [com.unciv.online.MultiplayerServerOfflineException], a rejected key [com.unciv.online.OnlineKeyRejectedException].
 */
object UncivServerFileStorage : FileStorage {
    var authHeader: Map<String, String>? = null
    var serverUrl: String = ""
    var timeout: Int = 30000

    private val tag = OnlineServer.tag

    private fun headers() = OnlineServer.withKey(authHeader)

    override fun saveFileData(fileName: String, data: String) {
        val url = fileUrl(fileName)
        val start = System.currentTimeMillis()
        Log.info(tag, "Upload start: file=%s size=%d chars url=%s", fileName, data.length, url)
        SimpleHttp.sendRequest(Net.HttpMethods.PUT, url, content=data, timeout=timeout, header=headers()) {
                success, result, code ->
            if (!success) {
                Log.warn(tag, "Upload failed: file=%s code=%s in %dms reason=%s", fileName, code, System.currentTimeMillis() - start, result.take(500))
                OnlineServer.classifyFailure("Upload $fileName", url, code, result)?.let { throw it }
                when (code) {
                    401 -> throw MultiplayerAuthException(Exception(result))
                    else -> throw Exception("$code $result")
                }
            }
            Log.info(tag, "Upload done: file=%s code=%s in %dms", fileName, code, System.currentTimeMillis() - start)
        }
    }

    override fun loadFileData(fileName: String): InputStream {
        val url = fileUrl(fileName)
        val start = System.currentTimeMillis()
        try {
            val response = SimpleHttp.openStream(Net.HttpMethods.GET, url, "", timeout, headers())
            debug("Download headers received: file=%s code=%s in %dms", fileName, response.code, System.currentTimeMillis() - start)
            return response.body
        } catch (ex: SimpleHttp.HttpStatusException) {
            if (ex.code == 404) {
                Log.info(tag, "Download: file=%s not found on server (404) in %dms", fileName, System.currentTimeMillis() - start)
                throw MultiplayerFileNotFoundException(Exception(ex.message))
            }
            Log.warn(tag, "Download failed: file=%s code=%s in %dms reason=%s", fileName, ex.code, System.currentTimeMillis() - start, ex.message.take(500))
            OnlineServer.classifyFailure("Download $fileName", url, ex.code, ex.message, ex.cause)?.let { throw it }
            throw Exception(ex.message, ex)
        }
    }

    override fun getFileMetaData(fileName: String): FileMetaData {
        TODO("Not yet implemented")
    }

    override fun deleteFile(fileName: String) {
        val url = fileUrl(fileName)
        Log.info(tag, "Delete: file=%s url=%s", fileName, url)
        SimpleHttp.sendRequest(Net.HttpMethods.DELETE, url, content="", timeout=timeout, header=headers()) {
                success, result, code ->
            if (!success) {
                Log.warn(tag, "Delete failed: file=%s code=%s reason=%s", fileName, code, result.take(500))
                OnlineServer.classifyFailure("Delete $fileName", url, code, result)?.let { throw it }
                when (code) {
                    404 -> throw MultiplayerFileNotFoundException(Exception(result))
                    else -> throw Exception(result)
                }
            }
        }
    }

    override fun authenticate(userId: String, password: String): Boolean {
        var authenticated = false
        val preEncodedAuthValue = "$userId:$password"
        authHeader = mapOf("Authorization" to "Basic ${Base64Coder.encodeString(preEncodedAuthValue)}")
        val url = "$serverUrl/auth"
        SimpleHttp.sendGetRequest(url, timeout=timeout, header=headers()) {
                success, result, code ->
            if (!success) {
                Log.warn(tag, "Authentication failed: userId=%s passwordSet=%s code=%s reason=%s", userId, password.isNotEmpty(), code, result.take(500))
                authHeader = null
                OnlineServer.classifyFailure("Authenticate", url, code, result)?.let { throw it }
                when (code) {
                    401 -> throw MultiplayerAuthException(Exception(result))
                    else -> throw Exception(result)
                }
            } else {
                Log.info(tag, "Authentication ok: userId=%s code=%s", userId, code)
                authenticated = true
            }
        }
        return authenticated
    }

    override fun checkAuthStatus(userId: String, password: String): AuthStatus {
        var authStatus = AuthStatus.UNKNOWN
        val preEncodedAuthValue = "$userId:$password"
        authHeader = mapOf("Authorization" to "Basic ${Base64Coder.encodeString(preEncodedAuthValue)}")
        SimpleHttp.sendGetRequest("$serverUrl/auth", timeout = timeout, header = headers()) { _, result, code ->
            authStatus = when {
                code == 200 -> AuthStatus.VERIFIED
                code == 204 -> AuthStatus.UNREGISTERED
                OnlineServer.isBadKeyResponse(code, result) -> AuthStatus.UNKNOWN
                code == 401 -> AuthStatus.UNAUTHORIZED
                else -> AuthStatus.UNKNOWN
            }
            Log.info(tag, "Auth status: userId=%s code=%s -> %s", userId, code, authStatus)
        }
        return authStatus
    }

    override fun setPassword(newPassword: String): Boolean {
        if (authHeader == null)
            return false

        var setSuccessful = false
        val url = "$serverUrl/auth"
        SimpleHttp.sendRequest(Net.HttpMethods.PUT, url, content=newPassword, timeout=timeout, header=headers()) {
                success, result, code ->
            if (!success) {
                Log.warn(tag, "Set password failed: code=%s reason=%s", code, result.take(500))
                OnlineServer.classifyFailure("Set password", url, code, result)?.let { throw it }
                when (code) {
                    401 -> throw MultiplayerAuthException(Exception(result))
                    else -> throw Exception(result)
                }
            } else {
                Log.info(tag, "Set password ok: code=%s", code)
                setSuccessful = true
            }
        }
        return setSuccessful
    }

    private fun fileUrl(fileName: String) = "$serverUrl/files/$fileName"
}
