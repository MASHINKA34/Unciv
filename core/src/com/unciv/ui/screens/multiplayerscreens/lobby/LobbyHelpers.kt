package com.unciv.ui.screens.multiplayerscreens.lobby

import com.unciv.UncivGame
import com.unciv.logic.UncivShowableException
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.translations.tr
import com.unciv.online.LobbyClient
import com.unciv.online.DeveloperMode
import com.unciv.online.LobbyInfo
import com.unciv.online.OnlineServer
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Log
import java.util.concurrent.ConcurrentHashMap

/** Unciv Online: shared bits of the lobby screens */
object LobbyHelpers {
    val tag = OnlineServer.tag

    /** Polling intervals: the lobby list and an open lobby (the host's poll is also its heartbeat for the server) */
    const val LIST_POLL_MS = 3_000L
    const val LOBBY_POLL_MS = 2_000L

    /**
     * Game settings chosen by the host, per lobby id. Kept in memory only: after an app restart the host
     * can still close the lobby, but has to create a new one to start a game.
     */
    val hostSetups = ConcurrentHashMap<String, GameSetupInfo>()

    /** Runs [action] once the player has a name; asks for it on the first lobby visit or when [force]d */
    fun ensurePlayerName(screen: BaseScreen, force: Boolean = false, action: () -> Unit) {
        val current = LobbyClient.playerName()
        if (current.isNotEmpty() && !force) return action()

        val popup = Popup(screen)
        popup.addGoodSizedLabel("Enter your name for the lobby").colspan(2).row()
        val nameField = UncivTextField("Your name", current)
        nameField.maxLength = LobbyClient.MAX_PLAYER_NAME
        popup.add(nameField).colspan(2).width(screen.stage.width / 2).pad(10f).row()
        popup.addCloseButton()
        popup.addOKButton(validate = { nameField.text.trim().isNotEmpty() }) {
            val name = nameField.text.trim().take(LobbyClient.MAX_PLAYER_NAME)
            UncivGame.Current.settings.multiplayer.userName = name
            UncivGame.Current.settings.save()
            Log.info(tag, "Lobby player name set: '%s' (was '%s')", name, current)
            action()
        }
        popup.equalizeLastTwoButtonWidths()
        popup.open(force = true)
        screen.stage.keyboardFocus = nameField
    }

    /** A short translated message for a failed lobby call (no stack traces in the UI; they are in the log) */
    fun errorText(ex: Throwable): String {
        if (ex !is UncivShowableException) Log.warn(tag, "Lobby call failed unexpectedly", ex)
        return if (ex is UncivShowableException) ex.localizedMessage
            else "Lobby error: [${ex.message ?: ex::class.simpleName}]".tr()
    }

    /** Short, translatable description of the host's settings for the members ("{Difficulty}: {Prince}") */
    fun settingsSummary(setup: GameSetupInfo): List<String> {
        val parameters = setup.gameParameters
        val map = setup.mapParameters
        val lines = mutableListOf<String>()
        lines += "{Base Ruleset}: {${parameters.baseRuleset}}"
        if (parameters.mods.isNotEmpty()) lines += "{Mods:} " + parameters.mods.joinToString()
        lines += "{Difficulty}: {${parameters.difficulty}}"
        lines += "{Game Speed}: {${parameters.speed}}"
        lines += if (map.name.isNotEmpty()) "{Map file}: " + map.name
            else "{World Size}: {${map.mapSize.name}}, {${map.type}}"
        val aiCount = parameters.players.count { it.playerType == PlayerType.AI }
        if (aiCount > 0) lines += "{AI players}: $aiCount"
        if (parameters.developerMode) lines += DeveloperMode.LOBBY_SUMMARY_LINE
        if (parameters.developerMode && parameters.debugWorld) lines += DeveloperMode.LOBBY_SUMMARY_DEBUG_WORLD_LINE
        return lines
    }

    /** Number of seats = Human slots in the host's setup (decision 2026-11-21) */
    fun seatCount(setup: GameSetupInfo): Int = setup.gameParameters.players.count { it.playerType == PlayerType.Human }

    /**
     * The setup the game is really started with: lobby members fill the Human slots in order (host first,
     * then by join time), Human slots nobody took are dropped, AI slots stay as the host set them.
     * Does not change [base], so a failed start can be retried.
     */
    fun buildGameSetup(base: GameSetupInfo, lobby: LobbyInfo): GameSetupInfo {
        val setup = GameSetupInfo(base)
        setup.mapFile = base.mapFile
        setup.gameParameters.isOnlineMultiplayer = true
        val members = lobby.players.sortedBy { if (it.userId == lobby.hostId) 0 else 1 } // stable: keeps join order
        var memberIndex = 0
        val players = ArrayList<Player>()
        for (slot in base.gameParameters.players) {
            if (slot.playerType != PlayerType.Human) {
                players += Player(slot.chosenCiv, slot.playerType, "")
                continue
            }
            val member = members.getOrNull(memberIndex) ?: continue // empty seat - dropped
            memberIndex++
            players += Player(slot.chosenCiv, PlayerType.Human, member.userId)
        }
        setup.gameParameters.players = players
        Log.info(tag, "Lobby %s game setup: %s", lobby.id,
            players.joinToString { p -> "${p.chosenCiv}/${p.playerType}" + (lobby.players.firstOrNull { it.userId == p.playerId }?.let { "(${it.name})" } ?: "") })
        return setup
    }
}
