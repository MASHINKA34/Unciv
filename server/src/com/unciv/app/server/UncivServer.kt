package com.unciv.app.server

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.AttributeKey
import io.ktor.utils.io.jvm.javaio.*
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections.synchronizedMap
import java.util.Collections.synchronizedSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal object UncivServer {
    @JvmStatic
    fun main(args: Array<String>) = UncivServerRunner().main(args)
}

@Serializable
data class IsAliveInfo(val authVersion: Int, val chatVersion: Int)

@Serializable
sealed class Message {
    @Serializable
    @SerialName("chat")
    data class Chat(
        val civName: String, val message: String, val gameId: String
    ) : Message()

    @Serializable
    @SerialName("join")
    data class Join(
        val gameIds: List<String>
    ) : Message()

    @Serializable
    @SerialName("leave")
    data class Leave(
        val gameIds: List<String>
    ) : Message()
}

// used when receiving a message
@Serializable
sealed class Response {
    @Serializable
    @SerialName("chat")
    data class Chat(
        val civName: String, val message: String, val gameId: String? = null
    ) : Response()

    @Serializable
    @SerialName("joinSuccess")
    data class JoinSuccess(
        val gameIds: List<String>
    ) : Response()

    @Serializable
    @SerialName("error")
    data class Error(
        val message: String
    ) : Response()
}

@OptIn(ExperimentalUuidApi::class)
private class WebSocketSessionManager {
    private val gameId2WSSessions = synchronizedMap(mutableMapOf<Uuid, MutableSet<DefaultWebSocketServerSession>>())
    private val wsSession2GameIds = synchronizedMap(mutableMapOf<DefaultWebSocketServerSession, MutableSet<Uuid>>())

    fun isSubscribed(session: DefaultWebSocketServerSession, gameId: Uuid): Boolean =
        gameId2WSSessions.getOrPut(gameId) { synchronizedSet(mutableSetOf()) }.contains(session)

    fun subscribe(session: DefaultWebSocketServerSession, gameIds: List<String>): List<String> {
        val uuids = gameIds.mapNotNull { it.toUuidOrNull() }

        wsSession2GameIds.getOrPut(session) { synchronizedSet(mutableSetOf()) }.addAll(uuids)
        for (uuid in uuids) {
            gameId2WSSessions.getOrPut(uuid) { synchronizedSet(mutableSetOf()) }.add(session)
        }

        return uuids.map { it.toString() }
    }

    fun unsubscribe(session: DefaultWebSocketServerSession, gameIds: List<String>) {
        val uuids = gameIds.mapNotNull { it.toUuidOrNull() }
        wsSession2GameIds[session]?.removeAll(uuids)
        for (uuid in uuids) {
            gameId2WSSessions[uuid]?.remove(session)
        }
    }

    suspend fun publish(gameId: Uuid, message: Response) {
        val sessions = gameId2WSSessions.getOrPut(gameId) { synchronizedSet(mutableSetOf()) }
        for (session in sessions) {
            if (!session.isActive) {
                sessions.remove(session)
                continue
            }
            session.sendSerialized(message)
        }
    }

    fun cleanupSession(session: DefaultWebSocketServerSession) {
        for (gameId in wsSession2GameIds.remove(session) ?: emptyList()) {
            val gameIds = gameId2WSSessions[gameId] ?: continue
            gameIds.remove(session)
            if (gameIds.isEmpty()) {
                gameId2WSSessions.remove(gameId)
            }
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
data class BasicAuthInfo(
    val userId: Uuid,
    val password: String,
)

/** Unciv Online: one logger for the whole server, configured by logback.xml (console + rotating files) */
internal val serverLog: Logger = LoggerFactory.getLogger("UncivServer")

/** Unciv Online: short id that ties together all log lines of one request */
private val RequestIdKey = AttributeKey<String>("UncivOnlineRequestId")
private val ApplicationCall.rid: String get() = attributes.getOrNull(RequestIdKey) ?: "-"
private fun newRequestId(): String =
    java.lang.Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong()).takeLast(8).padStart(8, '0')
/** Who sent the request: behind Tailscale serve/Funnel the socket peer is localhost, the real client is in X-Forwarded-For */
private fun ApplicationCall.clientDescription(): String {
    val forwarded = request.headers["X-Forwarded-For"]
    val peer = request.local.remoteAddress // IP, no reverse DNS lookup
    return if (forwarded.isNullOrBlank()) peer else "$peer (for $forwarded)"
}

/**
 * Checks if a [String] is a valid UUID
 */
@OptIn(ExperimentalUuidApi::class)
private fun String.toUuidOrNull() = try {
    Uuid.parse(this)
} catch (_: Throwable) {
    null
}

private class UncivServerRunner : CliktCommand() {
    private companion object {
        /**
         * Game data is compressed before upload, so this allows even very large saves while
         * preventing a single request from exhausting the server's disk space.
         */
        const val maxGameFileSizeBytes = 32 * 1024 * 1024
        const val maxWebSocketFrameSizeBytes = 64L * 1024L
        const val previewSuffix = "_Preview"
    }

    private val port by option(
        "-p", "-port",
        envvar = "UncivServerPort",
        help = "Server port"
    ).int().restrictTo(0..65535).default(8080)

    private val folder by option(
        "-f", "-folder",
        envvar = "UncivServerFolder",
        help = "Multiplayer file's folder"
    ).default("MultiplayerFiles")

    private val authV1Enabled by option(
        "-a", "-auth",
        envvar = "UncivServerAuth",
        help = "Enable Authentication"
    ).flag("-no-auth", default = true)

    private val chatV1Enabled by option(
        "-c", "-chat",
        envvar = "UncivServerChat",
        help = "Enable Authentication"
    ).flag("-no-chat", default = true)

    private val identifyOperators by option(
        "-i", "-Identify",
        envvar = "UncivServerIdentify",
        help = "Display each operation archive request IP to assist management personnel"
    ).flag("-no-Identify", default = false)

    // region Unciv Online options
    private val host by option(
        "-host", "--host",
        envvar = "UncivServerHost",
        help = "Address to listen on. Default 127.0.0.1: only this PC (Tailscale Funnel, emulator, adb reverse); 0.0.0.0 for LAN"
    ).default("127.0.0.1")

    private val keyFile by option(
        "-key-file", "--key-file",
        envvar = "UncivServerKeyFile",
        help = "File with the shared key: properties file with shared.key=..., or a text file with just the key"
    )

    private val keyValue by option(
        "-key", "--key",
        envvar = "UncivServerKey",
        help = "Shared key (prefer --key-file: command lines are visible to other programs)"
    )

    private val lobbyHostTimeoutSeconds by option(
        "-lobby-host-timeout", "--lobby-host-timeout",
        envvar = "UncivServerLobbyHostTimeout",
        help = "Seconds without a poll from the host before an open lobby is closed"
    ).int().restrictTo(10..86_400).default((LobbyManager.HOST_TIMEOUT_MS / 1000).toInt())

    private val lobbyGamesFileName by option(
        "-lobby-games-file", "--lobby-games-file",
        envvar = "UncivServerLobbyGames",
        help = "Where started lobby games are remembered for players who were offline"
    ).default("lobby-games.json")
    // endregion

    lateinit var isAliveInfo: IsAliveInfo
    private lateinit var sharedKey: String
    private lateinit var lobbyManager: LobbyManager
    private val lobbyJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun run() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            serverLog.error("Uncaught exception in thread {}", thread.name, throwable)
        }
        val version = UncivServer::class.java.`package`?.specificationVersion ?: "dev"
        serverLog.info("Unciv Online server {} starting: java={} os={} {} pid={} workDir={}",
            version, System.getProperty("java.version"), System.getProperty("os.name"), System.getProperty("os.arch"),
            ProcessHandle.current().pid(), File(".").absoluteFile.normalize())

        serverLog.info("Loading shared key: keyFile={} keyOption={}", keyFile ?: "-", if (keyValue == null) "-" else "given")
        sharedKey = try {
            keyValue?.trim()?.takeIf { it.isNotEmpty() } ?: keyFile?.let { OnlineSecurity.readKeyFile(File(it)) } ?: ""
        } catch (ex: Exception) {
            serverLog.error("Cannot read the shared key from {}: {}", keyFile, ex.message, ex)
            exitProcess(2)
        }
        if (sharedKey.isEmpty()) {
            serverLog.error("No shared key configured (use --key-file or UncivServerKeyFile) - refusing to start an open server")
            exitProcess(2)
        }
        serverLog.info("Shared key {}", OnlineSecurity.describe(sharedKey))

        isAliveInfo = IsAliveInfo(
            authVersion = if (authV1Enabled) 1 else 0,
            chatVersion = if (chatV1Enabled) 1 else 0,
        )
        serverLog.info("Config: host={} port={} folder={} auth={} chat={} identifyOperators={} lobbyHostTimeout={}s lobbyGamesFile={} logDir={} logLevel={}",
            host, port, File(folder).absolutePath, authV1Enabled, chatV1Enabled, identifyOperators, lobbyHostTimeoutSeconds,
            File(lobbyGamesFileName).absolutePath, System.getProperty("UNCIV_LOG_DIR") ?: System.getenv("UNCIV_LOG_DIR") ?: "logs",
            System.getProperty("LOG_LEVEL") ?: System.getenv("LOG_LEVEL") ?: "INFO")
        serverRun(port, folder)
    }

    // region Auth
    @OptIn(ExperimentalUuidApi::class)
    private val authMap: MutableMap<Uuid, String> = mutableMapOf()

    private val wsSessionManager = WebSocketSessionManager()

    @OptIn(ExperimentalUuidApi::class)
    private fun loadAuthFile() {
        val authFile = File("server.auth")
        val start = System.currentTimeMillis()
        if (!authFile.exists()) {
            serverLog.info("No server.auth file found at {}, creating one", authFile.absolutePath)
            authFile.createNewFile()
        } else {
            authMap.putAll(
                authFile.readLines().filter { it.isNotBlank() }.map { it.split(":") }
                    .associate { Uuid.parse(it[0]) to it[1] }
            )
            serverLog.info("Auth file {} loaded: {} users with a password in {}ms", authFile.absolutePath, authMap.size, System.currentTimeMillis() - start)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun saveAuthFile() {
        val authFile = File("server.auth")
        try {
            synchronized(authMap) {
                authFile.writeText(authMap.map { "${it.key}:${it.value}" }.joinToString("\n"))
            }
            serverLog.info("Auth file {} saved: {} users", authFile.absolutePath, authMap.size)
        } catch (ex: Exception) {
            serverLog.error("Auth file {} save failed", authFile.absolutePath, ex)
        }
    }

    /**
     * @return true if either auth is disabled, no password is set for the current player,
     * or the password is correct
     */
    private fun validateGameAccess(file: File, authInfo: BasicAuthInfo): Boolean {
        if (!file.exists())
            return true

        return validateAuth(authInfo)

        // TODO Check if the user is the current player and validate its password this requires decoding the game file
    }

    private fun validateAuth(authInfo: BasicAuthInfo): Boolean {
        if (!authV1Enabled) return true

        @OptIn(ExperimentalUuidApi::class) val password = authMap[authInfo.userId]
        return password == null || password == authInfo.password
    }

    /**
     * Multiplayer clients only store a game UUID, optionally followed by [_Preview].
     * Keeping this allow-list here prevents route parameters from escaping the configured
     * multiplayer directory through `..`, path separators, or platform-specific paths.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun String.isValidGameFileName(): Boolean {
        val gameId = removeSuffix(previewSuffix)
        return gameId.toUuidOrNull()?.toString() == gameId
    }

    /** Copies a request body without accepting an unbounded upload. */
    private fun copyGameFile(input: java.io.InputStream, output: java.io.OutputStream): Boolean {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytesRead: Int
        var totalBytes = 0L
        while (input.read(buffer).also { bytesRead = it } != -1) {
            totalBytes += bytesRead
            if (totalBytes > maxGameFileSizeBytes) return false
            output.write(buffer, 0, bytesRead)
        }
        return true
    }
    // endregion Auth

    private fun serverRun(serverPort: Int, fileFolderName: String) {
        val portStr: String = if (serverPort == 80) "" else ":$serverPort"
        val startedAt = System.currentTimeMillis()

        val file = File(fileFolderName)
        serverLog.info("Preparing game files folder {}", file.absolutePath)
        if (!file.exists()) {
            file.mkdirs()
            serverLog.info("Created game files folder {}", file.absolutePath)
        }
        // Load credentials before accepting connections. Starting first left a short window
        // where every valid UUID/password pair was treated as an unregistered account.
        if (authV1Enabled) loadAuthFile()

        serverLog.info("Loading lobby state from {}", File(lobbyGamesFileName).absolutePath)
        lobbyManager = LobbyManager(File(lobbyGamesFileName), serverLog, hostTimeoutMs = lobbyHostTimeoutSeconds * 1000L)
        val expiry = Executors.newSingleThreadScheduledExecutor { Thread(it, "lobby-expiry").apply { isDaemon = true } }
        expiry.scheduleAtFixedRate({
            try {
                val changed = lobbyManager.expire()
                if (changed > 0) serverLog.info("Lobby expiry run: {} lobbies closed or forgotten, {} open", changed, lobbyManager.openLobbyCount())
            } catch (ex: Throwable) {
                serverLog.error("Lobby expiry run failed", ex)
            }
        }, 30, 30, TimeUnit.SECONDS)

        serverLog.info("Starting HTTP server (Netty) on {}:{}", host, serverPort)
        val server = try {
            embeddedServer(Netty, port = serverPort, host = host) {
                install(ContentNegotiation) { json() }

                // Unciv Online: log every request with an id, client, status and duration
                intercept(ApplicationCallPipeline.Monitoring) {
                    val requestId = newRequestId()
                    call.attributes.put(RequestIdKey, requestId)
                    val start = System.nanoTime()
                    try {
                        proceed()
                    } catch (ex: Throwable) {
                        serverLog.error("[{}] {} {} from {} failed", requestId, call.request.httpMethod.value, call.request.uri, call.clientDescription(), ex)
                        throw ex
                    } finally {
                        val elapsedMs = (System.nanoTime() - start) / 1_000_000
                        val status = call.response.status()?.value
                        val line = "[{}] {} {} from {} -> {} in {}ms"
                        val args = arrayOf<Any?>(requestId, call.request.httpMethod.value, call.request.uri, call.clientDescription(), status ?: "-", elapsedMs)
                        if (status != null && status >= 500) serverLog.error(line, *args)
                        else if (status != null && status >= 400) serverLog.warn(line, *args)
                        else serverLog.info(line, *args)
                    }
                }

                // Unciv Online: no shared key, no service (also for /isalive and the chat WebSocket)
                intercept(ApplicationCallPipeline.Plugins) {
                    val provided = call.request.headers[OnlineSecurity.KEY_HEADER]
                    if (!OnlineSecurity.keyMatches(sharedKey, provided)) {
                        serverLog.warn("[{}] Rejected: {} shared key ({} {} from {}, user-agent '{}')", call.rid,
                            if (provided == null) "missing" else "wrong", call.request.httpMethod.value, call.request.uri,
                            call.clientDescription(), call.request.headers[HttpHeaders.UserAgent])
                        call.respondText("${OnlineSecurity.BAD_KEY_MARKER}: missing or wrong server key", status = HttpStatusCode.Unauthorized)
                        finish()
                    }
                }

                install(Authentication) {
                    basic {
                        realm = "Optional for /files, /auth and /chat"

                        @OptIn(ExperimentalUuidApi::class) validate {
                            return@validate try {
                                BasicAuthInfo(userId = Uuid.parse(it.name), password = it.password)
                            } catch (_: Throwable) {
                                null
                            }
                        }
                    }
                }

                if (chatV1Enabled) install(WebSockets) {
                    pingPeriod = 30.seconds
                    timeout = 60.seconds
                    maxFrameSize = maxWebSocketFrameSizeBytes
                    @OptIn(ExperimentalSerializationApi::class)
                    contentConverter = KotlinxWebsocketSerializationConverter(Json {
                        classDiscriminator = "type"
                        // DO NOT OMIT
                        // if omitted the "type" field will be missing from all outgoing messages
                        classDiscriminatorMode = ClassDiscriminatorMode.ALL_JSON_OBJECTS
                    })
                }

                routing {
                    get("/isalive") {
                        serverLog.debug("[{}] isalive request from {}", call.rid, call.clientDescription())
                        call.respond(isAliveInfo)
                    }

                    @OptIn(ExperimentalUuidApi::class) authenticate {
                        put("/files/{fileName}") {
                            val fileName = call.parameters["fileName"] ?: return@put call.respond(
                                HttpStatusCode.BadRequest, "Missing filename!"
                            )
                            if (!fileName.isValidGameFileName()) return@put call.respond(
                                HttpStatusCode.BadRequest, "Invalid game filename!"
                            )
                            if ((call.request.contentLength() ?: 0) > maxGameFileSizeBytes) return@put call.respond(
                                HttpStatusCode.PayloadTooLarge, "Game file is too large!"
                            )

                            val authInfo = call.principal<BasicAuthInfo>() ?: return@put call.respond(
                                HttpStatusCode.BadRequest, "Possibly malformed authentication header!"
                            )

                            // If IdentifyOperators is enabled an Operator IP is displayed
                            if (identifyOperators) {
                                serverLog.info("[{}] Receiving file: {} from user {} --Operation sourced from {}", call.rid, fileName, authInfo.userId, call.clientDescription())
                            } else {
                                serverLog.info("[{}] Receiving file: {} from user {}", call.rid, fileName, authInfo.userId)
                            }

                            val file = File(fileFolderName, fileName)
                            if (!validateGameAccess(file, authInfo)) {
                                serverLog.warn("[{}] Upload of {} refused: wrong password for user {}", call.rid, fileName, authInfo.userId)
                                return@put call.respond(HttpStatusCode.Unauthorized)
                            }

                            val written = withContext(Dispatchers.IO) {
                                val temporaryFile = File.createTempFile(".upload-", ".tmp", file.parentFile)
                                try {
                                    call.request.receiveChannel().toInputStream().use { input ->
                                        temporaryFile.outputStream().use { output -> copyGameFile(input, output) }
                                    }.also { completed ->
                                        if (completed) {
                                            Files.move(
                                                temporaryFile.toPath(), file.toPath(),
                                                StandardCopyOption.REPLACE_EXISTING
                                            )
                                        }
                                    }
                                } finally {
                                    // The move removes it on success; this handles an oversize or failed request.
                                    temporaryFile.delete()
                                }
                            }
                            if (!written) {
                                serverLog.warn("[{}] Upload of {} refused: larger than {} bytes", call.rid, fileName, maxGameFileSizeBytes)
                                return@put call.respond(HttpStatusCode.PayloadTooLarge, "Game file is too large!")
                            }
                            serverLog.info("[{}] Stored file {} ({} bytes)", call.rid, fileName, file.length())
                            call.respond(HttpStatusCode.OK)
                        }
                        get("/files/{fileName}") {
                            val fileName = call.parameters["fileName"] ?: return@get call.respond(
                                HttpStatusCode.BadRequest, "Missing filename!"
                            )
                            if (!fileName.isValidGameFileName()) return@get call.respond(
                                HttpStatusCode.BadRequest, "Invalid game filename!"
                            )

                            // If IdentifyOperators is enabled an Operator IP is displayed
                            if (identifyOperators) {
                                serverLog.info("[{}] File requested: {} --Operation sourced from {}", call.rid, fileName, call.clientDescription())
                            } else {
                                serverLog.debug("[{}] File requested: {}", call.rid, fileName)
                            }

                            val file = File(fileFolderName, fileName)
                            if (!file.exists()) {
                                serverLog.info("[{}] File {} not found", call.rid, fileName)
                                return@get call.respond(HttpStatusCode.NotFound, "File does not exist")
                            }

                            val fileText = withContext(Dispatchers.IO) { file.readText() }
                            serverLog.debug("[{}] Sending file {} ({} chars)", call.rid, fileName, fileText.length)
                            call.respondText(fileText)
                        }
                        if (authV1Enabled) {
                            get("/auth") {
                                val authInfo = call.principal<BasicAuthInfo>() ?: return@get call.respond(
                                    HttpStatusCode.BadRequest, "Possibly malformed authentication header!"
                                )

                                val result = when (authMap[authInfo.userId]) {
                                    null -> HttpStatusCode.NoContent
                                    authInfo.password -> HttpStatusCode.OK
                                    else -> HttpStatusCode.Unauthorized
                                }
                                serverLog.info("[{}] Auth check for user {}: {}", call.rid, authInfo.userId, when (result) {
                                    HttpStatusCode.NoContent -> "no password set"
                                    HttpStatusCode.OK -> "password ok"
                                    else -> "wrong password"
                                })
                                call.respond(result)
                            }
                            put("/auth") {
                                val authInfo = call.principal<BasicAuthInfo>() ?: return@put call.respond(
                                    HttpStatusCode.BadRequest, "Possibly malformed authentication header!"
                                )

                                val password = authMap[authInfo.userId]
                                if (password == null || password == authInfo.password) {
                                    val newPassword = call.receiveText()
                                    if (newPassword.length < 6) return@put call.respond(
                                        HttpStatusCode.BadRequest, "Password should be at least 6 characters long"
                                    )
                                    authMap[authInfo.userId] = newPassword
                                    serverLog.info("[{}] Password {} for user {}", call.rid, if (password == null) "set" else "changed", authInfo.userId)
                                    // Unciv Online: save right away - the PC may be switched off without a clean shutdown
                                    saveAuthFile()
                                    call.respond(HttpStatusCode.OK)
                                } else {
                                    serverLog.warn("[{}] Password change refused for user {}: wrong current password", call.rid, authInfo.userId)
                                    call.respond(HttpStatusCode.Unauthorized)
                                }
                            }
                        }

                        if (chatV1Enabled) webSocket("/chat") {
                            val authInfo = call.principal<BasicAuthInfo>()
                            if (authInfo == null) {
                                serverLog.warn("[{}] Chat refused: no authentication info", call.rid)
                                sendSerialized(Response.Error("No authentication info found!"))
                                return@webSocket close()
                            }

                            // Unciv Online: the shared key already keeps strangers out, so a player without a server
                            // password may chat too (upstream demanded a password, which our players never set).
                            // A player who did set a password still has to send the right one.
                            val serverPassword = authMap[authInfo.userId]
                            if (serverPassword != null && serverPassword != authInfo.password) {
                                serverLog.warn("[{}] Chat refused for user {}: wrong password", call.rid, authInfo.userId)
                                sendSerialized(Response.Error("Authentication failed!"))
                                return@webSocket close()
                            }
                            serverLog.info("[{}] Chat session opened for user {} ({})", call.rid, authInfo.userId,
                                if (serverPassword == null) "no password set, allowed by shared key" else "password ok")

                            try {
                                while (isActive) {
                                    when (val message = receiveDeserialized<Message>()) {
                                        is Message.Chat -> {
                                            val gameId = message.gameId.toUuidOrNull()
                                            if (gameId == null) {
                                                sendSerialized(
                                                    Response.Chat(
                                                        civName = "Server",
                                                        message = "Invalid gameId: '${message.gameId}'. Cannot relay the message!",
                                                    )
                                                )
                                                continue
                                            }

                                            if (wsSessionManager.isSubscribed(this, gameId)) {
                                                wsSessionManager.publish(
                                                    gameId = gameId, message = Response.Chat(
                                                        civName = message.civName,
                                                        message = message.message,
                                                        gameId = message.gameId,
                                                    )
                                                )
                                            } else {
                                                sendSerialized(Response.Error("You are not subscribed to this channel!"))
                                            }
                                        }

                                        is Message.Join -> {
                                            sendSerialized(
                                                Response.JoinSuccess(
                                                    gameIds = wsSessionManager.subscribe(
                                                        this, message.gameIds
                                                    )
                                                )
                                            )
                                        }

                                        is Message.Leave -> wsSessionManager.unsubscribe(this, message.gameIds)
                                    }
                                    yield()
                                }
                            } catch (err: Throwable) {
                                serverLog.info("[{}] Chat session of user {} closed due to {}: {}", call.rid, authInfo.userId, err::class.simpleName, err.message)
                                wsSessionManager.cleanupSession(this)
                            } finally {
                                serverLog.info("[{}] Chat session of user {} closed", call.rid, authInfo.userId)
                                wsSessionManager.cleanupSession(this)
                            }
                        }

                        lobbyRoutes()
                    }
                }
            }.start(wait = false)
        } catch (ex: Throwable) {
            serverLog.error("HTTP server failed to start on {}:{} (port busy? another server instance running?)", host, serverPort, ex)
            exitProcess(1)
        }

        serverLog.info("Server running on http://{}{} (started in {}ms)! Press Ctrl+C to stop", host, portStr, System.currentTimeMillis() - startedAt)
        Runtime.getRuntime().addShutdownHook(Thread {
            val stopStart = System.currentTimeMillis()
            serverLog.info("Shutdown requested (Ctrl+C, task stop, logoff or system shutdown) - stopping server")

            if (authV1Enabled) {
                saveAuthFile()
            }
            expiry.shutdownNow()
            server.stop(1, 5, TimeUnit.SECONDS)
            serverLog.info("Server stopped in {}ms", System.currentTimeMillis() - stopStart)
        })
        Thread.currentThread().join()
    }

    // region Unciv Online lobby routes

    /** The caller's user ID from the Basic auth header, after the usual password check; null = already answered */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun ApplicationCall.lobbyCaller(): String? {
        val authInfo = principal<BasicAuthInfo>()
        if (authInfo == null) {
            respond(HttpStatusCode.BadRequest, "Possibly malformed authentication header!")
            return null
        }
        if (!validateAuth(authInfo)) {
            serverLog.warn("[{}] Lobby request refused: wrong password for user {}", rid, authInfo.userId)
            respond(HttpStatusCode.Unauthorized)
            return null
        }
        return authInfo.userId.toString()
    }

    private suspend inline fun <reified T> ApplicationCall.receiveLobbyJson(): T? {
        val text = receiveText()
        return try {
            lobbyJson.decodeFromString<T>(text)
        } catch (ex: Exception) {
            serverLog.warn("[{}] Lobby request body is not valid {}: {} (body: {})", rid, T::class.simpleName, ex.message, text.take(300))
            respondText(lobbyJson.encodeToString(LobbyError("bad-request", "Malformed request body")),
                ContentType.Application.Json, HttpStatusCode.BadRequest)
            null
        }
    }

    private suspend inline fun <reified T> ApplicationCall.respondLobby(result: LobbyResult<T>) {
        when (result) {
            is LobbyResult.Ok -> respondText(lobbyJson.encodeToString(result.value), ContentType.Application.Json)
            is LobbyResult.Fail -> respondText(lobbyJson.encodeToString(LobbyError(result.error, result.message)),
                ContentType.Application.Json, HttpStatusCode.fromValue(result.status))
        }
    }

    private fun Route.lobbyRoutes() {
        get("/lobbies") {
            val caller = call.lobbyCaller() ?: return@get
            val lobbies = lobbyManager.list(caller)
            serverLog.debug("[{}] Lobby list for {}: {} lobbies", call.rid, caller, lobbies.size)
            call.respondText(lobbyJson.encodeToString(lobbies), ContentType.Application.Json)
        }
        post("/lobbies") {
            val caller = call.lobbyCaller() ?: return@post
            val request = call.receiveLobbyJson<CreateLobbyRequest>() ?: return@post
            serverLog.info("[{}] Lobby create by {}: name='{}' seats={}", call.rid, caller, request.name, request.maxPlayers)
            call.respondLobby(lobbyManager.create(caller, request))
        }
        get("/lobbies/{id}") {
            val caller = call.lobbyCaller() ?: return@get
            call.respondLobby(lobbyManager.get(caller, call.parameters["id"].orEmpty()))
        }
        post("/lobbies/{id}/join") {
            val caller = call.lobbyCaller() ?: return@post
            val request = call.receiveLobbyJson<JoinLobbyRequest>() ?: return@post
            call.respondLobby(lobbyManager.join(caller, call.parameters["id"].orEmpty(), request))
        }
        post("/lobbies/{id}/leave") {
            val caller = call.lobbyCaller() ?: return@post
            call.respondLobby(lobbyManager.leave(caller, call.parameters["id"].orEmpty()))
        }
        post("/lobbies/{id}/start") {
            val caller = call.lobbyCaller() ?: return@post
            val request = call.receiveLobbyJson<StartLobbyRequest>() ?: return@post
            call.respondLobby(lobbyManager.start(caller, call.parameters["id"].orEmpty(), request))
        }
        delete("/lobbies/{id}") {
            val caller = call.lobbyCaller() ?: return@delete
            call.respondLobby(lobbyManager.close(caller, call.parameters["id"].orEmpty()))
        }
        get("/lobby-games") {
            val caller = call.lobbyCaller() ?: return@get
            val games = lobbyManager.gamesFor(caller)
            serverLog.debug("[{}] Lobby games for {}: {}", call.rid, caller, games.map { it.gameId })
            call.respondText(lobbyJson.encodeToString(games), ContentType.Application.Json)
        }
    }

    // endregion
}
