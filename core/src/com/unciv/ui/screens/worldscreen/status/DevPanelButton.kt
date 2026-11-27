package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.online.OnlineLogTags
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.devconsole.DevPanelPopup
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Log

/**
 * Unciv Online: the "Developer panel" button of developer mode games (top right, next to the multiplayer status button):
 * the panel icon on the left, the red "Developer mode" line (it marks the game as a sandbox) and "Developer panel" on the right.
 * Opens [DevPanelPopup].
 */
class DevPanelButton(private val worldScreen: WorldScreen) : Button(BaseScreen.skin) {
    companion object {
        const val ICON = "OtherIcons/DevPanel"
    }

    init {
        if (ImageGetter.imageExists(ICON))
            add(ImageGetter.getImage(ICON)).size(40f).pad(6f, 10f, 6f, 4f)
        else
            Log.warn(OnlineLogTags.devPanel, "Image %s is missing from the atlas - the developer panel button shows text only", ICON)
        val texts = Table()
        texts.add("Developer mode".toLabel(Color.RED, fontSize = 14)).padTop(4f).row()
        texts.add("Developer panel".toLabel(fontSize = 18)).pad(2f, 0f, 6f, 0f)
        add(texts).padRight(10f)
        onClick {
            Log.info(OnlineLogTags.devPanel, "Developer panel opened by %s (turn %d, their turn: %s)",
                worldScreen.viewingCiv.civID, worldScreen.gameInfo.turns, worldScreen.canChangeState)
            DevPanelPopup(worldScreen)
        }
    }
}
