package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Disposable

class StatusButtons(
    val nextTurnButton: NextTurnButton
) : Table(), Disposable {
    var autoPlayStatusButton: AutoPlayStatusButton? = null
    var multiplayerStatusButton: MultiplayerStatusButton? = null
    var smallUnitButton: SmallUnitButton? = null
    /** Unciv Online: only in developer mode games */
    var devPanelButton: DevPanelButton? = null
    private val padXSpace = 10f
    private val padYSpace = 5f
    
    init {
        add(nextTurnButton)
    }
    
    fun update(verticalWrap: Boolean) {
        clear()
        if(verticalWrap) {
            add(nextTurnButton)
            smallUnitButton?.let {
                row()
                add(it).padTop(padYSpace).right()
            }
            autoPlayStatusButton?.let {
                row()
                add(it).padTop(padYSpace).right()
            }
            multiplayerStatusButton?.let {
                row()
                add(it).padTop(padYSpace).right()
            }
            devPanelButton?.let {
                row()
                add(it).padTop(padYSpace).right()
            }
        } else {
            devPanelButton?.let { add(it).padRight(padXSpace).top() }
            multiplayerStatusButton?.let { add(it).padRight(padXSpace).top() }
            autoPlayStatusButton?.let { add(it).padRight(padXSpace).top() }
            smallUnitButton?.let { add(it).padRight(padXSpace).top() }
            add(nextTurnButton)
        }
        pack()
    }

    override fun dispose() {
        autoPlayStatusButton?.dispose()
        multiplayerStatusButton?.dispose()
    }
}
