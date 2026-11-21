package com.unciv.logic.multiplayer.storage

import com.unciv.Constants
import com.badlogic.gdx.utils.SerializationException
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.BackwardCompatibility.migrateCivID
import com.unciv.logic.GameInfo
import com.unciv.logic.GameInfoPreview
import com.unciv.logic.UncivKtor
import com.unciv.logic.files.FileConversions
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.ServerFeatureSet
import com.unciv.online.MultiplayerServerOfflineException
import com.unciv.online.OnlineServer
import com.unciv.utils.Log
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

/**
 * Allows access to games stored on a server for multiplayer purposes.
 * Defaults to using UncivGame.Current.settings.multiplayerServer if fileStorageIdentifier is not given.
 *
 * For low-level access only, use [UncivGame.onlineMultiplayer] on [UncivGame.Current] if you're looking to load/save a game.
 *
 * @param fileStorageIdentifier must be given if UncivGame.Current might not be initialized
 * @see FileStorage
 * @see UncivGame.settings.multiplayer.server
 */
@Suppress("RedundantSuspendModifier") // Methods can take a long time, so force users to use them in a coroutine to not get ANRs on Android
class MultiplayerServer(
    val fileStorageIdentifier: String? = null,
    private var authenticationHeader: Map<String, String>? = null
) {
    private var featureSet = ServerFeatureSet()
    fun getFeatureSet() = featureSet
    fun setFeatureSet(value: ServerFeatureSet) {
        if (featureSet != value) {
            UncivGame.Current.worldScreen?.chatButton?.refreshVisibility()
            featureSet = value
        }
    }

    fun getServerUrl() = fileStorageIdentifier ?: UncivGame.Current.settings.multiplayer.getServer()

    fun fileStorage(): FileStorage {
        val authHeader = if (authenticationHeader == null) {
            val settings = UncivGame.Current.settings.multiplayer
            mapOf("Authorization" to settings.getAuthHeader())
        } else authenticationHeader

        return if (getServerUrl() == Constants.dropboxMultiplayerServer) DropBox
        else UncivServerFileStorage.apply {
            serverUrl = this@MultiplayerServer.getServerUrl()
            this.authHeader = authHeader
        }
    }

    /**
     * Checks if the server is alive and sets the [serverFeatureSet] accordingly.
     * @return true if the server is alive, false otherwise
     */
    suspend fun checkServerStatus(): Boolean {
        val server = getServerUrl()
        val isAliveSuffix = "/isalive"
        val url = "$server$isAliveSuffix"
        val start = System.currentTimeMillis()
        Log.info(OnlineServer.tag, "Server check: GET %s (keyPresent=%s)", url, OnlineServer.hasSharedKey)
        val resp = try {
            OnlineServer.httpClient.get(url) {
                for ((name, value) in OnlineServer.keyHeader()) header(name, value)
            }
        } catch (ex: Exception) {
            val elapsed = System.currentTimeMillis() - start
            if (OnlineServer.isOfflineCause(ex)) {
                Log.warn(OnlineServer.tag, "Server check: %s unreachable after %dms - %s: %s", url, elapsed, ex::class.simpleName, ex.message)
                throw MultiplayerServerOfflineException(ex)
            }
            Log.warn(OnlineServer.tag, "Server check: $url failed after ${elapsed}ms", ex)
            throw ex
        }
        val elapsed = System.currentTimeMillis() - start
        Log.info(OnlineServer.tag, "Server check: %s -> HTTP %s in %dms", url, resp.status.value, elapsed)
        if (!resp.status.isSuccess()) {
            val body = try { resp.bodyAsText().take(500) } catch (_: Exception) { "" }
            OnlineServer.classifyFailure("Server check", url, resp.status.value, body)?.let { throw it }
        }

        if (resp.status.isSuccess()) {
            setFeatureSet(
                try {
                    json().fromJson(ServerFeatureSet::class.java, resp.bodyAsText())
                } catch (_: Exception) {
                    // The server does not support server feature set - not an error!
                    ServerFeatureSet()
                }
            )

            /**
             * The following block is an one time optimization that
             * ensures that we are not following redirects every time
             */
            val multiplayer = UncivGame.Current.settings.multiplayer
            if (server == multiplayer.getServer()) {
                multiplayer.setServer(
                    resp.request.url.toString().removeSuffix(isAliveSuffix)
                )
            }

            return true
        }

        return false
    }


    /**
     * @return true if the authentication was successful or the server does not support authentication.
     * @throws FileStorageRateLimitReached if the file storage backend can't handle any additional actions for a time
     * @throws MultiplayerAuthException if the authentication failed
     */
    fun authenticate(password: String?): Boolean {
        if (featureSet.authVersion == 0) return true

        val settings = UncivGame.Current.settings.multiplayer

        val success = fileStorage().authenticate(
            userId = settings.getUserId(), password = password ?: settings.getCurrentServerPassword() ?: ""
        )
        if (password != null && success) {
            settings.setCurrentServerPassword(password)
        }
        return success
    }

    /**
     * @return true if setting the password was successful, false otherwise.
     * @throws FileStorageRateLimitReached if the file storage backend can't handle any additional actions for a time
     * @throws MultiplayerAuthException if the authentication failed
     */
    fun setPassword(password: String): Boolean {
        if (featureSet.authVersion > 0 && fileStorage().setPassword(newPassword = password)) {
            UncivGame.Current.settings.multiplayer.setCurrentServerPassword(password)
            return true
        }

        return false
    }


    /**
     * @throws FileStorageRateLimitReached if the file storage backend can't handle any additional actions for a time
     * @throws MultiplayerAuthException if the authentication failed
     */
    suspend fun uploadGame(gameInfo: GameInfo, withPreview: Boolean) {
        val zippedGameInfo = UncivFiles.gameInfoToString(gameInfo, forceZip = true, updateChecksum = true)
        fileStorage().saveFileData(gameInfo.gameId, zippedGameInfo)

        // We upload the preview after the game because otherwise the following race condition will happen:
        // Current player ends turn -> Uploads Game Preview
        // Other player checks for updates -> Downloads Game Preview
        // Current player starts game upload
        // Other player sees update in preview -> Downloads game, gets old state
        // Current player finishes uploading game
        if (withPreview) {
            tryUploadGamePreview(gameInfo.asPreview())
        }
    }

    @Suppress("MemberVisibilityCanBePrivate")
    /**
     * Used to upload only the preview of a game. If the preview is uploaded together with (before/after)
     * the gameInfo, it is recommended to use tryUploadGame(gameInfo, withPreview = true)
     *
     * @throws FileStorageRateLimitReached if the file storage backend can't handle any additional actions for a time
     * @throws MultiplayerAuthException if the authentication failed
     *
     * @see uploadGame
     * @see GameInfo.asPreview
     */
    suspend fun tryUploadGamePreview(gameInfo: GameInfoPreview) {
        val zippedGameInfo = UncivFiles.gameInfoToString(gameInfo)
        fileStorage().saveFileData("${gameInfo.gameId}_Preview", zippedGameInfo)
    }

    /**
     * @throws FileStorageRateLimitReached if the file storage backend can't handle any additional actions for a time
     * @throws FileNotFoundException if the file can't be found
     */
    suspend fun tryDownloadGame(gameId: String): GameInfo {
        val gameInfo = UncivFiles.gameInfoFromStream { fileStorage().loadFileData(gameId) }
        gameInfo.gameParameters.multiplayerServerUrl = UncivGame.Current.settings.multiplayer.getServer()
        return gameInfo
    }


    /**
     * @throws FileStorageRateLimitReached if the file storage backend can't handle any additional actions for a time
     * @throws MultiplayerFileNotFoundException if the file can't be found
     */
    suspend fun downloadGame(gameId: String): GameInfo {
        val latestGame = tryDownloadGame(gameId)
        latestGame.isUpToDate = true
        return latestGame
    }

    /**
     * @throws FileStorageRateLimitReached if the file storage backend can't handle any additional actions for a time
     * @throws FileNotFoundException if the file can't be found
     */
    suspend fun tryDownloadGamePreview(gameId: String): GameInfoPreview {
        val preview = FileConversions.readJson(fileStorage().loadFileData("${gameId}_Preview"), GameInfoPreview::class.java)
            ?: throw SerializationException("Empty game preview for $gameId")
        preview.migrateCivID()
        return preview
    }
}
