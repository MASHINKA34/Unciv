package com.unciv.online

import com.unciv.logic.GameInfo
import com.unciv.models.metadata.GameParameters
import com.unciv.models.translations.tr
import yairm210.purity.annotations.Readonly

/**
 * Unciv Online: shared helpers of the developer mode (sandbox games, [GameParameters.developerMode]).
 * The panel itself is [com.unciv.ui.screens.devconsole.DevPanelPopup].
 */
object DeveloperMode {
    /** The line the lobby summary carries for such a game - the lobby list marks the lobby by it */
    const val LOBBY_SUMMARY_LINE = "{Developer mode}"
    const val LOBBY_SUMMARY_DEBUG_WORLD_LINE = "{Debug world}"

    @Readonly fun isOn(gameInfo: GameInfo): Boolean = gameInfo.gameParameters.developerMode
    @Readonly fun isOn(parameters: GameParameters?): Boolean = parameters?.developerMode == true

    /** Appended to game and lobby names in lists, e.g. "Game of Kirill [Dev]" */
    fun markerSuffix(): String = " [" + "Dev game".tr() + "]"
}
