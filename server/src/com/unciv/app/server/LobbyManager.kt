package com.unciv.app.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/*
 * Unciv Online: pre-game lobbies.
 *
 * A host creates a lobby (name, seats, short settings summary), friends see it in the list and join,
 * the host starts it: the client creates and uploads the game with the members' user IDs, then reports
 * the game ID here. Every member's client then adds the game to its multiplayer list - live while polling
 * the lobby, or later via /lobby-games (that list survives server restarts, lobbies themselves do not).
 *
 * Wire format must match core/src/com/unciv/online/LobbyClient.kt.
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
    /** [LobbyManager.STATUS_OPEN], [LobbyManager.STATUS_STARTED] or [LobbyManager.STATUS_CLOSED] */
    val status: String,
    val gameId: String? = null,
    /** Translatable summary of the game settings chosen by the host (ruleset, difficulty, map...) */
    val settings: List<String> = emptyList(),
    val createdAt: Long = 0,
    /** Why a closed lobby was closed: "host-closed", "host-left", "host-timeout", "replaced" */
    val closeReason: String? = null,
)

@Serializable
data class CreateLobbyRequest(val name: String, val playerName: String, val maxPlayers: Int, val settings: List<String> = emptyList())

@Serializable
data class JoinLobbyRequest(val playerName: String)

@Serializable
data class StartLobbyRequest(val gameId: String)

@Serializable
data class LobbyGame(val gameId: String, val lobbyName: String, val hostName: String, val startedAt: Long)

@Serializable
data class LobbyError(val error: String, val message: String)

/** Result of a lobby operation: either a value or an HTTP status with a machine-readable error code */
sealed class LobbyResult<out T> {
    data class Ok<T>(val value: T) : LobbyResult<T>()
    data class Fail(val status: Int, val error: String, val message: String) : LobbyResult<Nothing>()
}

class LobbyManager(
    private val gamesFile: File,
    private val log: Logger,
    private val hostTimeoutMs: Long = HOST_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val STATUS_OPEN = "open"
        const val STATUS_STARTED = "started"
        const val STATUS_CLOSED = "closed"

        /** An open lobby closes when the host has not polled it for this long (decision 2026-11-21: 10 min) */
        const val HOST_TIMEOUT_MS = 10 * 60_000L
        /** Started and closed lobbies stay visible this long so members' clients notice the change */
        const val FINISHED_RETENTION_MS = 10 * 60_000L
        const val MIN_PLAYERS_TO_START = 2
        const val MAX_PLAYERS = 32
        const val MAX_LOBBY_NAME = 40
        const val MAX_PLAYER_NAME = 24
        const val MAX_SETTINGS_LINES = 20
        const val MAX_SETTINGS_LINE = 120
        const val MAX_GAMES_PER_USER = 50
    }

    private class Lobby(
        val id: String,
        val name: String,
        val hostId: String,
        val maxPlayers: Int,
        val settings: List<String>,
        val createdAt: Long,
    ) {
        val players = mutableListOf<LobbyPlayer>()
        var status = STATUS_OPEN
        var gameId: String? = null
        var closeReason: String? = null
        var hostLastSeen = createdAt
        var statusChangedAt = createdAt

        fun hostName() = players.firstOrNull { it.userId == hostId }?.name ?: ""
        fun isMember(userId: String) = players.any { it.userId == userId }
        fun toInfo() = LobbyInfo(
            id = id, name = name, hostId = hostId, hostName = hostName(), maxPlayers = maxPlayers,
            players = players.toList(), status = status, gameId = gameId, settings = settings,
            createdAt = createdAt, closeReason = closeReason,
        )
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()
    private val lobbies = LinkedHashMap<String, Lobby>()
    private val userGames = mutableMapOf<String, MutableList<LobbyGame>>()

    init {
        loadGames()
    }

    // region queries

    /** Open lobbies plus any lobby the caller is in (so a member sees "started"/"closed" for their own lobby) */
    fun list(callerId: String): List<LobbyInfo> = synchronized(lock) {
        lobbies.values.filter { it.status == STATUS_OPEN || it.isMember(callerId) }.map { it.toInfo() }
    }

    fun get(callerId: String, lobbyId: String): LobbyResult<LobbyInfo> = synchronized(lock) {
        val lobby = lobbies[lobbyId] ?: return notFound(lobbyId)
        if (lobby.hostId == callerId) lobby.hostLastSeen = clock()
        LobbyResult.Ok(lobby.toInfo())
    }

    fun gamesFor(callerId: String): List<LobbyGame> = synchronized(lock) { userGames[callerId]?.toList() ?: emptyList() }

    // endregion
    // region changes

    fun create(callerId: String, request: CreateLobbyRequest): LobbyResult<LobbyInfo> = synchronized(lock) {
        val name = request.name.trim().take(MAX_LOBBY_NAME)
        val playerName = request.playerName.trim().take(MAX_PLAYER_NAME)
        if (name.isEmpty()) return bad("Lobby name is empty")
        if (playerName.isEmpty()) return bad("Player name is empty")
        if (request.maxPlayers !in MIN_PLAYERS_TO_START..MAX_PLAYERS)
            return bad("maxPlayers must be $MIN_PLAYERS_TO_START..$MAX_PLAYERS, got ${request.maxPlayers}")

        removeFromOtherLobbies(callerId, keepLobbyId = null)
        val now = clock()
        val lobby = Lobby(
            id = UUID.randomUUID().toString(), name = name, hostId = callerId, maxPlayers = request.maxPlayers,
            settings = request.settings.take(MAX_SETTINGS_LINES).map { it.take(MAX_SETTINGS_LINE) }, createdAt = now,
        )
        lobby.players += LobbyPlayer(callerId, playerName)
        lobbies[lobby.id] = lobby
        log.info("Lobby created: id={} name='{}' host={} hostName='{}' seats={} settings={}",
            lobby.id, name, callerId, playerName, lobby.maxPlayers, lobby.settings)
        LobbyResult.Ok(lobby.toInfo())
    }

    fun join(callerId: String, lobbyId: String, request: JoinLobbyRequest): LobbyResult<LobbyInfo> = synchronized(lock) {
        val lobby = lobbies[lobbyId] ?: return notFound(lobbyId)
        val playerName = request.playerName.trim().take(MAX_PLAYER_NAME)
        if (playerName.isEmpty()) return bad("Player name is empty")
        val existing = lobby.players.indexOfFirst { it.userId == callerId }
        if (existing >= 0) {
            // Re-joining is idempotent; it also refreshes a changed name
            lobby.players[existing] = LobbyPlayer(callerId, playerName)
            log.info("Lobby join (already member): id={} user={} name='{}' status={}", lobbyId, callerId, playerName, lobby.status)
            return LobbyResult.Ok(lobby.toInfo())
        }
        if (lobby.status != STATUS_OPEN) {
            log.info("Lobby join refused: id={} user={} status={}", lobbyId, callerId, lobby.status)
            return LobbyResult.Fail(409, "lobby-not-open", "The lobby is no longer open")
        }
        if (lobby.players.size >= lobby.maxPlayers) {
            log.info("Lobby join refused: id={} user={} full {}/{}", lobbyId, callerId, lobby.players.size, lobby.maxPlayers)
            return LobbyResult.Fail(409, "lobby-full", "The lobby is full")
        }
        removeFromOtherLobbies(callerId, keepLobbyId = lobbyId)
        lobby.players += LobbyPlayer(callerId, playerName)
        log.info("Lobby join: id={} user={} name='{}' players={}/{}", lobbyId, callerId, playerName, lobby.players.size, lobby.maxPlayers)
        LobbyResult.Ok(lobby.toInfo())
    }

    /** A member leaves; the host leaving closes the lobby. Leaving a started or closed lobby changes nothing. */
    fun leave(callerId: String, lobbyId: String): LobbyResult<LobbyInfo> = synchronized(lock) {
        val lobby = lobbies[lobbyId] ?: return notFound(lobbyId)
        if (lobby.status != STATUS_OPEN) {
            log.info("Lobby leave ignored: id={} user={} status={}", lobbyId, callerId, lobby.status)
            return LobbyResult.Ok(lobby.toInfo())
        }
        if (lobby.hostId == callerId) {
            closeLobby(lobby, "host-left")
        } else if (lobby.players.removeAll { it.userId == callerId }) {
            log.info("Lobby leave: id={} user={} players={}/{}", lobbyId, callerId, lobby.players.size, lobby.maxPlayers)
        } else {
            log.info("Lobby leave: id={} user={} was not a member", lobbyId, callerId)
        }
        LobbyResult.Ok(lobby.toInfo())
    }

    fun close(callerId: String, lobbyId: String): LobbyResult<LobbyInfo> = synchronized(lock) {
        val lobby = lobbies[lobbyId] ?: return notFound(lobbyId)
        if (lobby.hostId != callerId) return notHost(lobbyId, callerId, "close")
        if (lobby.status == STATUS_OPEN) closeLobby(lobby, "host-closed")
        else log.info("Lobby close ignored: id={} status={}", lobbyId, lobby.status)
        LobbyResult.Ok(lobby.toInfo())
    }

    fun start(callerId: String, lobbyId: String, request: StartLobbyRequest): LobbyResult<LobbyInfo> = synchronized(lock) {
        val lobby = lobbies[lobbyId] ?: return notFound(lobbyId)
        if (lobby.hostId != callerId) return notHost(lobbyId, callerId, "start")
        val gameId = request.gameId.trim()
        if (runCatching { UUID.fromString(gameId) }.isFailure) return bad("gameId is not a UUID: '$gameId'")
        if (lobby.status == STATUS_STARTED && lobby.gameId == gameId) return LobbyResult.Ok(lobby.toInfo())
        if (lobby.status != STATUS_OPEN) {
            log.info("Lobby start refused: id={} status={}", lobbyId, lobby.status)
            return LobbyResult.Fail(409, "lobby-not-open", "The lobby is no longer open")
        }
        if (lobby.players.size < MIN_PLAYERS_TO_START) {
            log.info("Lobby start refused: id={} players={} < {}", lobbyId, lobby.players.size, MIN_PLAYERS_TO_START)
            return LobbyResult.Fail(409, "not-enough-players", "At least $MIN_PLAYERS_TO_START players are needed to start")
        }
        val now = clock()
        lobby.status = STATUS_STARTED
        lobby.gameId = gameId
        lobby.statusChangedAt = now
        val record = LobbyGame(gameId = gameId, lobbyName = lobby.name, hostName = lobby.hostName(), startedAt = now)
        for (player in lobby.players) {
            val games = userGames.getOrPut(player.userId) { mutableListOf() }
            games.removeAll { it.gameId == gameId }
            games += record
            while (games.size > MAX_GAMES_PER_USER) games.removeAt(0)
        }
        log.info("Lobby started: id={} name='{}' game={} players={}", lobbyId, lobby.name, gameId,
            lobby.players.joinToString { "${it.name}(${it.userId})" })
        saveGames()
        LobbyResult.Ok(lobby.toInfo())
    }

    /** Closes lobbies whose host vanished and forgets finished ones. @return number of lobbies changed or removed */
    fun expire(): Int = synchronized(lock) {
        val now = clock()
        var changed = 0
        for (lobby in lobbies.values) {
            if (lobby.status == STATUS_OPEN && now - lobby.hostLastSeen > hostTimeoutMs) {
                log.info("Lobby expiry: id={} host={} silent for {}s", lobby.id, lobby.hostId, (now - lobby.hostLastSeen) / 1000)
                closeLobby(lobby, "host-timeout")
                changed++
            }
        }
        val iterator = lobbies.values.iterator()
        while (iterator.hasNext()) {
            val lobby = iterator.next()
            if (lobby.status != STATUS_OPEN && now - lobby.statusChangedAt > FINISHED_RETENTION_MS) {
                log.info("Lobby forgotten: id={} status={} reason={}", lobby.id, lobby.status, lobby.closeReason)
                iterator.remove()
                changed++
            }
        }
        changed
    }

    fun openLobbyCount(): Int = synchronized(lock) { lobbies.values.count { it.status == STATUS_OPEN } }

    // endregion
    // region helpers

    private fun closeLobby(lobby: Lobby, reason: String) {
        lobby.status = STATUS_CLOSED
        lobby.closeReason = reason
        lobby.statusChangedAt = clock()
        log.info("Lobby closed: id={} name='{}' reason={} players={}", lobby.id, lobby.name, reason, lobby.players.size)
    }

    /** One user, one open lobby: joining or creating another one takes them out of the old (closing it if they host it) */
    private fun removeFromOtherLobbies(userId: String, keepLobbyId: String?) {
        for (other in lobbies.values) {
            if (other.id == keepLobbyId || other.status != STATUS_OPEN || !other.isMember(userId)) continue
            if (other.hostId == userId) closeLobby(other, "replaced")
            else {
                other.players.removeAll { it.userId == userId }
                log.info("Lobby auto-leave: id={} user={} (joined another lobby)", other.id, userId)
            }
        }
    }

    private fun notFound(lobbyId: String): LobbyResult.Fail {
        log.info("Lobby not found: id={}", lobbyId)
        return LobbyResult.Fail(404, "not-found", "This lobby no longer exists")
    }

    private fun notHost(lobbyId: String, callerId: String, action: String): LobbyResult.Fail {
        log.warn("Lobby {} refused: id={} caller={} is not the host", action, lobbyId, callerId)
        return LobbyResult.Fail(403, "not-host", "Only the host can do this")
    }

    private fun bad(message: String): LobbyResult.Fail {
        log.warn("Lobby bad request: {}", message)
        return LobbyResult.Fail(400, "bad-request", message)
    }

    private fun loadGames() {
        if (!gamesFile.exists()) {
            log.info("Lobby games file {} does not exist yet - starting empty", gamesFile.absolutePath)
            return
        }
        try {
            val loaded = json.decodeFromString<Map<String, List<LobbyGame>>>(gamesFile.readText(Charsets.UTF_8))
            for ((user, games) in loaded) userGames[user] = games.toMutableList()
            log.info("Lobby games loaded from {}: {} users, {} records", gamesFile.absolutePath, userGames.size, userGames.values.sumOf { it.size })
        } catch (ex: Exception) {
            val broken = File(gamesFile.parentFile, gamesFile.name + ".broken-" + clock())
            log.error("Lobby games file {} is unreadable - moving it to {} and starting empty", gamesFile.absolutePath, broken.name, ex)
            gamesFile.renameTo(broken)
        }
    }

    private fun saveGames() {
        val start = System.currentTimeMillis()
        try {
            val text = json.encodeToString(userGames.mapValues { it.value.toList() })
            gamesFile.absoluteFile.parentFile?.mkdirs()
            val temp = File(gamesFile.absoluteFile.parentFile, gamesFile.name + ".tmp")
            temp.writeText(text, Charsets.UTF_8)
            Files.move(temp.toPath(), gamesFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            log.debug("Lobby games saved to {} ({} bytes) in {}ms", gamesFile.absolutePath, text.length, System.currentTimeMillis() - start)
        } catch (ex: Exception) {
            log.error("Lobby games save to {} failed - members may not see the new game after a restart", gamesFile.absolutePath, ex)
        }
    }

    // endregion
}
