package com.unciv.ui.screens.multiplayerscreens.lobby

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.models.translations.tr
import com.unciv.online.LobbyClient
import com.unciv.online.LobbyException
import com.unciv.online.LobbyInfo
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.multiplayerscreens.MultiplayerHelpers
import com.unciv.ui.screens.multiplayerscreens.MultiplayerScreen
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

/**
 * Unciv Online: one lobby, for the host and for the members.
 * - Everyone sees the players and the host's game settings; the list refreshes every couple of seconds.
 * - Host: "Start the game" (from 2 players) creates the game with the members' user IDs, uploads it and
 *   reports it to the server; "Close lobby" closes it for everyone.
 * - Member: "Leave lobby". When the host starts, the game is added to the member's multiplayer list and
 *   a popup offers to play now or later.
 */
class LobbyScreen(initial: LobbyInfo) : PickerScreen() {
    private val tag = LobbyHelpers.tag
    private var lobby: LobbyInfo = initial
    private val myId = LobbyClient.userId()
    private val isHost get() = lobby.isHost(myId)
    private val setup get() = LobbyHelpers.hostSetups[lobby.id]

    private val titleLabel = Label(initial.name, BaseScreen.skin)
    private val statusLabel = "".toLabel(Color.LIGHT_GRAY).apply { wrap = true }
    private val playersTable = Table().apply { defaults().pad(4f).left() }
    private val settingsTable = Table().apply { defaults().pad(2f).left() }

    private var pollJob: Job? = null
    /** Stops reacting to polls while the host creates the game or after the lobby ended */
    @Volatile private var busy = false
    @Volatile private var finished = false
    /** A game created but not yet reported to the server - a retry only reports it again */
    private var createdGame: GameInfo? = null

    init {
        Log.info(tag, "Lobby screen opened: id=%s name='%s' host=%s isHost=%s players=%d/%d",
            lobby.id, lobby.name, lobby.hostId, isHost, lobby.players.size, lobby.maxPlayers)

        topTable.defaults().pad(5f)
        titleLabel.setFontSize(Constants.headingFontSize)
        topTable.add(titleLabel).padTop(15f).row()
        topTable.add(statusLabel).width(stage.width * 0.8f).row()
        val content = Table().apply { defaults().pad(10f).top() }
        content.add(playersTable).minWidth(stage.width * 0.3f)
        content.add(settingsTable).minWidth(stage.width * 0.3f)
        topTable.add(ScrollPane(content)).row()

        closeButton.setText((if (isHost) "Close lobby" else "Leave lobby").tr())
        closeButton.onActivation { onCloseOrLeave() }
        closeButton.keyShortcuts.add(KeyCharAndCode.BACK)

        if (isHost) {
            rightSideButton.setText("Start the game".tr())
            rightSideButton.onActivation { startGame() }
        } else {
            rightSideButton.isVisible = false
        }

        showLobby(lobby)
        startPolling()
    }

    // region polling and display

    private fun startPolling() {
        pollJob = Concurrency.run("LobbyPoll") {
            while (isActive && !finished) {
                delay(LobbyHelpers.LOBBY_POLL_MS)
                if (busy || finished) continue
                try {
                    val fresh = LobbyClient.get(lobby.id)
                    launchOnGLThread { onLobbyUpdate(fresh) }
                } catch (ex: LobbyException) {
                    if (ex.code == "not-found") launchOnGLThread { onLobbyGone("This lobby no longer exists") }
                    else launchOnGLThread { statusLabel.setText(ex.localizedMessage) }
                } catch (ex: Exception) {
                    val message = LobbyHelpers.errorText(ex)
                    launchOnGLThread { statusLabel.setText(message) }
                }
            }
        }
    }

    private fun onLobbyUpdate(fresh: LobbyInfo) {
        if (busy || finished) return
        val before = lobby
        lobby = fresh
        if (before.players.map { it.userId } != fresh.players.map { it.userId } || before.status != fresh.status)
            Log.info(tag, "Lobby %s update: status=%s players=%s", fresh.id, fresh.status, fresh.players.joinToString { it.name })
        when {
            fresh.isStarted && !isHost -> onStartedAsMember(fresh)
            fresh.isClosed -> onLobbyGone(
                if (fresh.closeReason == "host-timeout") "The host was away for too long, the lobby was closed"
                else "The host closed the lobby"
            )
            !fresh.isOpen -> showLobby(fresh)
            !fresh.isMember(myId) -> onLobbyGone("You are no longer in this lobby")
            else -> showLobby(fresh)
        }
    }

    private fun showLobby(lobby: LobbyInfo) {
        titleLabel.setText(lobby.name)

        playersTable.clear()
        playersTable.add(("Players:".tr() + " ${lobby.players.size}/${lobby.maxPlayers}").toLabel(fontSize = Constants.headingFontSize)).row()
        for (player in lobby.players) {
            var text = player.name
            if (player.userId == lobby.hostId) text += " " + "(host)".tr()
            if (player.userId == myId) text += " " + "(you)".tr()
            playersTable.add(Label(text, BaseScreen.skin)).row()
        }
        repeat(lobby.maxPlayers - lobby.players.size) {
            playersTable.add("Free seat".toLabel(Color.GRAY)).row()
        }

        settingsTable.clear()
        settingsTable.add("Game settings".toLabel(fontSize = Constants.headingFontSize)).row()
        for (line in lobby.settings) settingsTable.add(line.toLabel()).row()

        val enoughPlayers = lobby.players.size >= LobbyClient.MIN_PLAYERS_TO_START
        statusLabel.setText(
            when {
                isHost && setup == null -> "The game settings of this lobby were lost (the app was restarted). Close it and create a new one.".tr()
                isHost && !enoughPlayers -> "At least 2 players are needed to start".tr()
                isHost -> "Press Start when everyone is here".tr()
                else -> "Waiting for the host to start the game...".tr()
            }
        )
        if (isHost) {
            if (enoughPlayers && setup != null && !busy) rightSideButton.enable() else rightSideButton.disable()
        }
    }

    // endregion
    // region leaving

    private fun onCloseOrLeave() {
        if (finished) {
            game.popScreen()
            return
        }
        if (isHost) {
            ConfirmPopup(this, "Close the lobby for everyone?", "Close lobby") {
                runAndLeave("close") { LobbyClient.close(lobby.id) }
            }.open()
        } else {
            runAndLeave("leave") { LobbyClient.leave(lobby.id) }
        }
    }

    private fun runAndLeave(operation: String, call: () -> Unit) {
        busy = true
        val popup = Popup(this)
        popup.addGoodSizedLabel(Constants.working).row()
        popup.open(force = true)
        Concurrency.run("Lobby $operation") {
            try {
                call()
                Log.info(tag, "Lobby %s: %s done", lobby.id, operation)
                if (isHost) LobbyHelpers.hostSetups.remove(lobby.id)
                finished = true
                launchOnGLThread {
                    popup.close()
                    game.popScreen()
                }
            } catch (ex: Exception) {
                busy = false
                val message = LobbyHelpers.errorText(ex)
                launchOnGLThread { popup.reuseWith(message, true) }
            }
        }
    }

    private fun onLobbyGone(reason: String) {
        if (finished) return
        finished = true
        Log.info(tag, "Lobby %s ended for this player: %s", lobby.id, reason)
        if (isHost) LobbyHelpers.hostSetups.remove(lobby.id)
        val popup = Popup(this)
        popup.addGoodSizedLabel(reason).row()
        popup.addCloseButton { game.popScreen() }
        popup.open(force = true)
    }

    // endregion
    // region start

    private fun startGame() {
        val baseSetup = setup ?: return
        busy = true
        rightSideButton.disable()
        val popup = Popup(this)
        popup.addGoodSizedLabel("Creating the game...").row()
        popup.open(force = true)

        Concurrency.runOnNonDaemonThreadPool("LobbyStartGame") {
            val start = System.currentTimeMillis()
            try {
                var newGame = createdGame
                if (newGame == null) {
                    val fresh = LobbyClient.get(lobby.id)
                    lobby = fresh
                    if (!fresh.isOpen) throw LobbyException("lobby-not-open", "The lobby is no longer open")
                    if (fresh.players.size < LobbyClient.MIN_PLAYERS_TO_START)
                        throw LobbyException("not-enough-players", "At least 2 players are needed to start")

                    Log.info(tag, "Lobby %s: creating game for %d players", fresh.id, fresh.players.size)
                    val gameSetup = LobbyHelpers.buildGameSetup(baseSetup, fresh)
                    val generated = try {
                        GameStarter.startNewGame(gameSetup)
                    } catch (ex: Exception) {
                        Log.error("Lobby ${fresh.id}: map/game generation failed", ex)
                        launchOnGLThread {
                            popup.reuseWith("It looks like we can't make a map with the parameters you requested!", true)
                            busy = false
                            showLobby(lobby)
                        }
                        return@runOnNonDaemonThreadPool
                    }
                    generated.isUpToDate = true // we just made it - don't download it right after the upload
                    game.onlineMultiplayer.createGame(generated)
                    Log.info(tag, "Lobby %s: game %s generated and uploaded in %dms", fresh.id, generated.gameId, System.currentTimeMillis() - start)
                    renameLocalGame(generated.gameId, fresh.name)
                    LobbyClient.markGameSeen(generated.gameId)
                    createdGame = generated
                    newGame = generated
                }

                LobbyClient.start(lobby.id, newGame.gameId)
                Log.info(tag, "Lobby %s: start reported, game %s, total %dms", lobby.id, newGame.gameId, System.currentTimeMillis() - start)
                finished = true
                LobbyHelpers.hostSetups.remove(lobby.id)
                game.files.autosaves.requestAutoSave(newGame)
                val worldScreen = game.loadGame(newGame)
                worldScreen.autoSave()
            } catch (ex: Exception) {
                Log.warn(tag, "Lobby ${lobby.id}: start failed after ${System.currentTimeMillis() - start}ms (gameCreated=${createdGame != null})", ex)
                val message = if (ex is LobbyException || ex is com.unciv.logic.UncivShowableException) ex.localizedMessage
                    else LoadGameScreen.getLoadExceptionMessage(ex, "Could not upload game!").first
                launchOnGLThread {
                    popup.reuseWith(message, true)
                    busy = false
                    showLobby(lobby)
                }
            }
        }
    }

    /** The host's game is stored under its UUID by default - give it the lobby name instead */
    private fun renameLocalGame(gameId: String, lobbyName: String) {
        val files = game.onlineMultiplayer.multiplayerFiles
        val localGame = files.getGameByGameId(gameId) ?: return
        val newName = LobbyClient.uniqueGameName(lobbyName)
        files.changeGameName(localGame, newName) { ex ->
            if (ex != null) Log.warn(tag, "Could not rename local game $gameId to '$newName'", ex)
        }
    }

    private fun onStartedAsMember(fresh: LobbyInfo) {
        if (finished) return
        finished = true
        val gameId = fresh.gameId ?: return onLobbyGone("This lobby no longer exists")
        Log.info(tag, "Lobby %s started by the host: game %s", fresh.id, gameId)
        val popup = Popup(this)
        popup.addGoodSizedLabel("The game has started!").row()
        popup.addGoodSizedLabel(Constants.working).row()
        popup.open(force = true)

        Concurrency.run("LobbyAddGame") {
            try {
                val multiplayer = game.onlineMultiplayer
                if (multiplayer.multiplayerFiles.getGameByGameId(gameId) == null)
                    multiplayer.addGame(gameId, LobbyClient.uniqueGameName(fresh.name))
                LobbyClient.markGameSeen(gameId)
                val localGame = multiplayer.multiplayerFiles.getGameByGameId(gameId)
                Log.info(tag, "Lobby %s: game %s added to the multiplayer list as '%s'", fresh.id, gameId, localGame?.name)
                launchOnGLThread {
                    popup.clear()
                    popup.addGoodSizedLabel("The game has started!").row()
                    popup.addGoodSizedLabel("It is now in your list of multiplayer games.").row()
                    popup.addButton("Later") { popup.close(); backToMultiplayerScreen() }
                    popup.addButton("Play now") {
                        popup.close()
                        if (localGame != null) MultiplayerHelpers.loadMultiplayerGame(this@LobbyScreen, localGame)
                        else backToMultiplayerScreen()
                    }.apply { actor.color = Color.GREEN }
                    popup.equalizeLastTwoButtonWidths()
                }
            } catch (ex: Exception) {
                val message = LobbyHelpers.errorText(ex)
                Log.warn(tag, "Lobby %s: could not add game %s: %s", fresh.id, gameId, message)
                launchOnGLThread {
                    popup.reuseWith(message, true)
                }
            }
        }
    }

    /** Back to the multiplayer games list (pops this screen and the lobby list) */
    private fun backToMultiplayerScreen() {
        while (game.screen !is MultiplayerScreen && game.screen is PickerScreen && game.popScreen() != null) {
            // pop LobbyScreen, LobbyListScreen
        }
        (game.screen as? MultiplayerScreen)?.gameList?.update()
    }

    // endregion

    override fun dispose() {
        pollJob?.cancel()
        Log.info(tag, "Lobby screen %s closed, polling stopped", lobby.id)
        super.dispose()
    }
}
