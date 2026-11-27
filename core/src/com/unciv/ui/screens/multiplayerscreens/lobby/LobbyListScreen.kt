package com.unciv.ui.screens.multiplayerscreens.lobby

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.models.translations.tr
import com.unciv.online.DeveloperMode
import com.unciv.online.LobbyClient
import com.unciv.online.LobbyInfo
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.newgamescreen.NewGameScreen
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
 * Unciv Online: "Multiplayer" -> "Lobby". Lists open lobbies on our server (name, host, players/seats),
 * lets the player join one or create their own. Refreshes itself while it is the visible screen.
 */
class LobbyListScreen : PickerScreen() {
    private val tag = LobbyHelpers.tag
    private val listTable = Table().apply { defaults().pad(5f).fillX() }
    private val statusLabel = "".toLabel(Color.LIGHT_GRAY).apply { wrap = true }
    private val nameLabel = "".toLabel()
    private var lobbies: List<LobbyInfo> = emptyList()
    private var selectedId: String? = null
    private var pollJob: Job? = null

    init {
        setDefaultCloseAction()
        closeButton.setText("Back".tr())

        topTable.defaults().pad(5f)
        topTable.add("Lobby".toLabel(fontSize = Constants.headingFontSize)).padTop(15f).row()

        val nameTable = Table().apply { defaults().pad(5f) }
        nameTable.add(nameLabel)
        nameTable.add("Change name".toTextButton().apply {
            onClick { LobbyHelpers.ensurePlayerName(this@LobbyListScreen, force = true) { updateNameLabel() } }
        })
        topTable.add(nameTable).row()
        topTable.add(statusLabel).width(stage.width * 0.8f).row()
        topTable.add(ScrollPane(listTable).apply { setScrollingDisabled(true, false) }).width(stage.width * 0.8f).growY().row()

        val createButton = "Create lobby".toTextButton().apply { color = Color.GREEN }
        createButton.onClick {
            Log.info(tag, "Lobby list: create lobby pressed")
            game.pushScreen { NewGameScreen(lobbyMode = true) }
        }
        rightSideGroup.addActorAt(0, createButton)

        rightSideButton.setText("Enter lobby".tr())
        rightSideButton.disable()
        rightSideButton.onClick { enterSelected() }

        updateNameLabel()
        statusLabel.setText("Loading...".tr())
        startPolling()
    }

    private fun updateNameLabel() {
        nameLabel.setText("Your name:".tr() + " " + LobbyClient.playerName())
    }

    private fun startPolling() {
        pollJob = Concurrency.run("LobbyListPoll") {
            Log.info(tag, "Lobby list polling started (every %dms)", LobbyHelpers.LIST_POLL_MS)
            while (isActive) {
                if (game.screen === this@LobbyListScreen) refreshOnce()
                delay(LobbyHelpers.LIST_POLL_MS)
            }
        }
    }

    private suspend fun refreshOnce() {
        try {
            val fresh = LobbyClient.list()
            Concurrency.runOnGLThread { showLobbies(fresh) }
        } catch (ex: Exception) {
            val message = LobbyHelpers.errorText(ex)
            Concurrency.runOnGLThread { statusLabel.setText(message) }
        }
    }

    private fun showLobbies(fresh: List<LobbyInfo>) {
        val myId = LobbyClient.userId()
        // Only open lobbies (started games reach the members by themselves); the player's own lobby first, then the newest
        lobbies = fresh.filter { it.isOpen }
            .sortedWith(compareByDescending<LobbyInfo> { it.isMember(myId) }.thenByDescending { it.createdAt })
        statusLabel.setText(if (lobbies.isEmpty()) "No open lobbies yet. Create one!".tr() else "")
        if (lobbies.none { it.id == selectedId }) selectedId = null

        listTable.clear()
        for (lobby in lobbies) {
            val devMarker = if (DeveloperMode.LOBBY_SUMMARY_LINE in lobby.settings) DeveloperMode.markerSuffix() else ""
            val text = lobby.name + devMarker + "\n" + "Host:".tr() + " " + lobby.hostName + "   " +
                "Players:".tr() + " ${lobby.players.size}/${lobby.maxPlayers}" +
                (if (lobby.isMember(myId)) "   " + "(you are in this lobby)".tr() else "")
            val button = TextButton(text, BaseScreen.skin)
            button.label.setAlignment(com.badlogic.gdx.utils.Align.left)
            if (lobby.id == selectedId) button.color = Color.GOLD
            button.onClick { select(lobby) }
            listTable.add(button).growX().row()
        }
        updateSelection()
    }

    private fun select(lobby: LobbyInfo) {
        selectedId = lobby.id
        showLobbies(lobbies)
    }

    private fun updateSelection() {
        val lobby = lobbies.firstOrNull { it.id == selectedId }
        if (lobby == null) {
            rightSideButton.disable()
            descriptionLabel.setText("")
            return
        }
        val myId = LobbyClient.userId()
        val canEnter = lobby.isMember(myId) || lobby.players.size < lobby.maxPlayers
        if (canEnter) rightSideButton.enable() else rightSideButton.disable()
        val details = StringBuilder()
        details.appendLine(lobby.name)
        for (line in lobby.settings) details.appendLine(line.tr())
        details.appendLine("Players:".tr() + " " + lobby.players.joinToString { it.name })
        if (!canEnter) details.appendLine("The lobby is full".tr())
        descriptionLabel.setText(details.toString().trim())
    }

    private fun enterSelected() {
        val lobby = lobbies.firstOrNull { it.id == selectedId } ?: return
        LobbyHelpers.ensurePlayerName(this) {
            val popup = Popup(this)
            popup.addGoodSizedLabel(Constants.working).row()
            popup.open(force = true)
            Log.info(tag, "Lobby list: entering lobby %s '%s'", lobby.id, lobby.name)
            Concurrency.run("LobbyJoin") {
                try {
                    val joined = LobbyClient.join(lobby.id)
                    launchOnGLThread {
                        popup.close()
                        game.pushScreen { LobbyScreen(joined) }
                    }
                } catch (ex: Exception) {
                    val (message) = LoadGameScreen.getLoadExceptionMessage(ex, "Could not enter the lobby")
                    launchOnGLThread { popup.reuseWith(message, true) }
                }
            }
        }
    }

    override fun resume() {
        super.resume()
        updateNameLabel()
    }

    override fun dispose() {
        pollJob?.cancel()
        Log.info(tag, "Lobby list closed, polling stopped")
        super.dispose()
    }
}
