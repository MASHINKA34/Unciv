package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.unciv.online.OnlineLogTags
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.devconsole.DevPanelPopup
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Log

/**
 * Unciv Online: the "Developer panel" button of developer mode games (top right, next to the multiplayer status button).
 * Its red "Developer mode" line also marks the game as a sandbox. Opens [DevPanelPopup].
 * TODO: an icon from the artist can replace the text later.
 */
class DevPanelButton(private val worldScreen: WorldScreen) : Button(BaseScreen.skin) {
    init {
        add("Developer mode".toLabel(Color.RED, fontSize = 14)).padTop(4f).row()
        add("Developer panel".toLabel(fontSize = 18)).pad(2f, 10f, 6f, 10f)
        onClick {
            Log.info(OnlineLogTags.devPanel, "Developer panel opened by %s (turn %d, their turn: %s)",
                worldScreen.viewingCiv.civID, worldScreen.gameInfo.turns, worldScreen.canChangeState)
            DevPanelPopup(worldScreen)
        }
    }
}
