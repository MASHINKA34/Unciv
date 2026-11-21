package com.unciv.online

import com.badlogic.gdx.Net
import com.unciv.UncivGame
import com.unciv.logic.UncivShowableException
import com.unciv.logic.multiplayer.storage.MultiplayerAuthException
import com.unciv.logic.multiplayer.storage.SimpleHttp
import com.unciv.utils.Log
import com.unciv.utils.debug
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/*
 * Unciv Online: client side of the pre-game lobby API of our server.
 * Wire format must match server/src/com/unciv/app/server/LobbyManager.kt.
 * All calls block - run them off the GL thread (Concurrency.run).
 */

@Serializable
data class LobbyPlayer(val userId: String, val name: String)

@Serializable
data class LobbyInfo(
    val id: String,
    val name: String,
    val hostId: String,
    val hostName: String,
    val maxPlayers: Int,
    val players: List<LobbyPlayer>,
    val status: String,
    val gameId: String? = null,
    val settings: List<String> = emptyList(),
    val createdAt: Long = 0,
    val closeReason: String? = null,
) {
    val isOpen get() = status == LobbyClient.STATUS_OPEN
    val isStarted get() = status == LobbyClient.STATUS_STARTED
    val isClosed get() = status == LobbyClient.STATUS_CLOSED
    fun isHost(userId: String) = hostId == userId
    fun isMember(userId: String) = players.any { it.userId == userId }
}

@Serializable
data class LobbyGame(val gameId: String, val lobbyName: String, val hostName: String = "", val startedAt: Long = 0)

@Serializable
private data class CreateLobbyRequest(val name: String, val playerName: String, val maxPlayers: Int, val settings: List<String>)

@Serializable
private data class JoinLobbyRequest(val playerName: String)

@Serializable
private data class StartLobbyRequest(val gameId: String)

@Serializable
private data class LobbyError(val error: String = "", val message: String = "")

/** A lobby operation was refused by the server; [code] is the server's error code, the message is translatable */
class LobbyException(val code: String, message: String) : UncivShowableException(message)

object LobbyClient {
    const val STATUS_OPEN = "open"
    const val STATUS_STARTED = "started"
    const val STATUS_CLOSED = "closed"

    const val MIN_PLAYERS_TO_START = 2
    const val MAX_PLAYER_NAME = 24
    const val MAX_LOBBY_NAME = 40

    private const val TIMEOUT_MS = 10_000
    private val tag = OnlineServer.tag
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val settings get() = UncivGame.Current.settings.multiplayer

    fun userId(): String = settings.getUserId()
    fun playerName(): String = settings.userName.trim()

    // region API

    fun list(): List<LobbyInfo> =
        json.decodeFromString(request("list", Net.HttpMethods.GET, "/lobbies", quiet = true))

    fun get(lobbyId: String): LobbyInfo =
        json.decodeFromString(request("poll", Net.HttpMethods.GET, "/lobbies/$lobbyId", quiet = true))

    fun create(name: String, maxPlayers: Int, settingsSummary: List<String>): LobbyInfo {
        val body = json.encodeToString(CreateLobbyRequest(name.take(MAX_LOBBY_NAME), playerName(), maxPlayers, settingsSummary))
        return json.decodeFromString(request("create", Net.HttpMethods.POST, "/lobbies", body))
    }

    fun join(lobbyId: String): LobbyInfo {
        val body = json.encodeToString(JoinLobbyRequest(playerName()))
        return json.decodeFromString(request("join", Net.HttpMethods.POST, "/lobbies/$lobbyId/join", body))
    }

    fun leave(lobbyId: String): LobbyInfo =
        json.decodeFromString(request("leave", Net.HttpMethods.POST, "/lobbies/$lobbyId/leave", "{}"))

    fun close(lobbyId: String): LobbyInfo =
        json.decodeFromString(request("close", Net.HttpMethods.DELETE, "/lobbies/$lobbyId"))

    fun start(lobbyId: String, gameId: String): LobbyInfo {
        val body = json.encodeToString(StartLobbyRequest(gameId))
        return json.decodeFromString(request("start", Net.HttpMethods.POST, "/lobbies/$lobbyId/start", body))
    }

    fun myGames(): List<LobbyGame> =
        json.decodeFromString(request("my games", Net.HttpMethods.GET, "/lobby-games", quiet = true))

    // endregion

    /**
     * Adds lobby games of this player that are not in the local multiplayer list yet (e.g. the player
     * was offline when the host pressed Start). A game the player deleted by hand is not re-added.
     * @return names under which games were added
     */
    suspend fun syncLobbyGames(): List<String> {
        val games = myGames()
        val multiplayer = UncivGame.Current.onlineMultiplayer
        val seen = settings.lobbyGamesSeen
        val added = mutableListOf<String>()
        for (game in games) {
            if (game.gameId in seen) continue
            if (multiplayer.multiplayerFiles.getGameByGameId(game.gameId) == null) {
                val name = uniqueGameName(game.lobbyName)
                Log.info(tag, "Lobby games: adding game %s from lobby '%s' as '%s'", game.gameId, game.lobbyName, name)
                multiplayer.addGame(game.gameId, name)
                added += name
            }
            seen.add(game.gameId)
        }
        if (games.isNotEmpty()) UncivGame.Current.settings.save()
        Log.info(tag, "Lobby games sync: %d on server, %d added", games.size, added.size)
        return added
    }

    /** Marks a lobby game as known, so [syncLobbyGames] will not add it again after the player deletes it */
    fun markGameSeen(gameId: String) {
        settings.lobbyGamesSeen.add(gameId)
        UncivGame.Current.settings.save()
    }

    /** A file-safe multiplayer game name based on [base] that is not used yet */
    fun uniqueGameName(base: String): String {
        val clean = base.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "Lobby" }
        val files = UncivGame.Current.onlineMultiplayer.multiplayerFiles
        if (files.getGameByName(clean) == null) return clean
        var index = 2
        while (files.getGameByName("$clean ($index)") != null) index++
        return "$clean ($index)"
    }

    private fun request(operation: String, method: String, path: String, body: String = "", quiet: Boolean = false): String {
        val url = settings.getServer().trimEnd('/') + path
        val headers = OnlineServer.withKey(
            mapOf(
                "Authorization" to settings.getAuthHeader(),
                "Content-Type" to "application/json; charset=utf-8",
                "Accept" to "application/json",
            )
        )
        val start = System.currentTimeMillis()
        if (!quiet) Log.info(tag, "Lobby %s: %s %s user=%s body=%s", operation, method, url, userId(), body.take(300))
        var success = false
        var text = ""
        var status: Int? = null
        SimpleHttp.sendRequest(method, url, body, TIMEOUT_MS, headers) { ok, result, code ->
            success = ok
            text = result
            status = code
        }
        val elapsed = System.currentTimeMillis() - start
        if (success) {
            if (quiet) debug("Lobby %s: %s %s -> %s in %dms (%d chars)", operation, method, path, status, elapsed, text.length)
            else Log.info(tag, "Lobby %s: -> %s in %dms (%d chars)", operation, status, elapsed, text.length)
            return text
        }

        Log.warn(tag, "Lobby %s failed: %s %s -> %s in %dms: %s", operation, method, url, status, elapsed, text.take(300))
        OnlineServer.classifyFailure("Lobby $operation", url, status, text)?.let { throw it }
        if (status == 401) throw MultiplayerAuthException(Exception(text))
        val error = try {
            json.decodeFromString<LobbyError>(text)
        } catch (_: Exception) {
            LobbyError("http-$status", text.take(200))
        }
        throw LobbyException(error.error, messageFor(error))
    }

    /** Server error codes -> translatable messages (android/assets/jsons/translations) */
    private fun messageFor(error: LobbyError): String = when (error.error) {
        "lobby-full" -> "The lobby is full"
        "lobby-not-open" -> "The lobby is no longer open"
        "not-enough-players" -> "At least 2 players are needed to start"
        "not-host" -> "Only the host can do this"
        "not-found" -> "This lobby no longer exists"
        else -> "Lobby error: [${error.message.ifEmpty { error.error }}]"
    }
}
