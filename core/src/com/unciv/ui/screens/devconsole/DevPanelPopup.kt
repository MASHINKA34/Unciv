package com.unciv.ui.screens.devconsole

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.stats.Stat
import com.unciv.models.translations.tr
import com.unciv.online.DevPanelActions
import com.unciv.online.OnlineLogTags
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Log

/**
 * Unciv Online: the developer panel of developer mode games - forms for the frequent sandbox actions on top of the
 * developer console. The actions themselves are in [DevPanelActions] (logging and notifications to all players).
 *
 * Works on the tile selected on the map: close the panel, tap a tile, open it again.
 * Actions are only enabled in the player's own turn - only the current player's game state is uploaded with the turn.
 */
class DevPanelPopup(private val screen: WorldScreen) : Popup(screen, Scrollability.All) {

    private enum class Tab(val title: String) {
        Units("Units"), Cities("Cities"), Civilization("Civilization"), Diplomacy("Diplomacy"), Tile("Tile"), Console("Console")
    }

    private val gameInfo = screen.gameInfo
    private val ruleset = gameInfo.ruleset
    private val panelActions = DevPanelActions(gameInfo, screen.viewingCiv)
    private val canAct = screen.canChangeState

    private val tabButtons = Table()
    private val content = Table()
    private val statusLabel = "".toLabel(Color.GOLD).apply { wrap = true }
    private var currentTab = Tab.Units

    // Created once, so choices are kept while switching tabs and after actions
    private val livingCivs = gameInfo.civilizations.filter { !it.isSpectator && !it.isDefeated() }
    private fun civSelect(civs: List<Civilization>) =
        TranslatedSelectBox(civs.map { it.civName }, screen.viewingCiv.civName)
    private val unitCivSelect = civSelect(livingCivs)
    private val cityCivSelect = civSelect(livingCivs.filter { it.isMajorCiv || it.isCityState })
    private val empireCivSelect = civSelect(livingCivs)
    private val warCivSelect1 = civSelect(livingCivs)
    private val warCivSelect2 = civSelect(livingCivs).apply {
        livingCivs.firstOrNull { it != screen.viewingCiv }?.let { setSelected(it.civName) }
    }
    private fun sortedSelect(names: Collection<String>) = TranslatedSelectBox(names.sortedBy { it.tr(hideIcons = true) }, "")
    private val unitSelect = sortedSelect(ruleset.units.keys)
    private val buildingSelect = sortedSelect(ruleset.buildings.keys)
    private val techSelect = sortedSelect(ruleset.technologies.keys)
    private val resourceSelect = sortedSelect(ruleset.tileResources.keys)
    private val statSelect = TranslatedSelectBox(listOf(Stat.Gold, Stat.Culture, Stat.Science, Stat.Faith).map { it.name }, Stat.Gold.name)
    private fun amountField(default: String) = UncivTextField("Amount", default).apply {
        textFieldFilter = TextField.TextFieldFilter.DigitsOnlyFilter()
        maxLength = 7
    }
    private val statAmount = amountField("1000")
    private val resourceAmount = amountField("1")
    private val xpAmount = amountField("30")
    private val commandField = UncivTextField("Console command")
    private val consoleExecutor by lazy { DevConsolePopup(screen, showPopup = false) }

    init {
        add("Developer panel".toLabel(fontSize = Constants.headingFontSize)).row()
        add(describeSelectedTile().toLabel().apply { wrap = true }).width(stageWidth()).row()
        if (!canAct) add("Available only in your turn".toLabel(Color.RED)).row()
        tabButtons.defaults().pad(3f)
        add(tabButtons).row()
        addSeparator()
        content.defaults().pad(4f).left()
        add(content).growX().row()
        add(statusLabel).width(stageWidth()).row()
        addCloseButton()
        showTab(Tab.Units)
        open(force = true)
    }

    private fun stageWidth() = (stageToShowOn.width * 0.8f).coerceAtMost(900f)

    //region Selection on the map

    private fun selectedTile(): Tile? = screen.mapHolder.selectedTile?.position()?.let { gameInfo.tileMap[it] }

    private fun selectedUnit(): MapUnit? {
        val tile = selectedTile() ?: return null
        val units = tile.getUnits().toList()
        val selectedId = screen.bottomUnitTable.selectedUnit?.id
        return units.firstOrNull { it.id == selectedId } ?: units.firstOrNull()
    }

    private fun selectedCity(): City? = selectedTile()?.getCity()?.takeIf { it.getCenterTile() == selectedTile() }

    private fun describeSelectedTile(): String {
        val tile = selectedTile() ?: return "No tile selected: close the panel, tap a tile on the map and open the panel again".tr()
        val parts = mutableListOf("{Selected tile}: ${tile.position.x},${tile.position.y} {${tile.baseTerrain}}".tr())
        tile.improvement?.let { parts += it.tr() }
        selectedCity()?.let { parts += "{City}: {${it.name}} ({${it.civ.civName}})".tr() }
        for (unit in tile.getUnits()) parts += "{${unit.name}} ({${unit.civ.civName}})".tr()
        return parts.joinToString(", ")
    }

    //endregion
    //region Tabs

    private fun showTab(tab: Tab) {
        currentTab = tab
        tabButtons.clear()
        for (t in Tab.entries) {
            val button = t.title.toTextButton()
            if (t == tab) button.color = Color.GOLD
            button.onClick { showTab(t) }
            tabButtons.add(button)
        }
        content.clear()
        when (tab) {
            Tab.Units -> buildUnitsTab()
            Tab.Cities -> buildCitiesTab()
            Tab.Civilization -> buildCivilizationTab()
            Tab.Diplomacy -> buildDiplomacyTab()
            Tab.Tile -> buildTileTab()
            Tab.Console -> buildConsoleTab()
        }
        pack()
        fitOrCenterContentIntoVisibleArea()
    }

    private fun Table.labeled(label: String, actor: com.badlogic.gdx.scenes.scene2d.Actor) {
        add(label.toLabel())
        add(actor).growX().row()
    }

    /** An action button: disabled outside the player's turn; runs [action], shows the result and refreshes the map */
    private fun actionButton(text: String, action: () -> Unit): TextButton {
        val button = text.toTextButton()
        if (!canAct) {
            button.disable()
            return button
        }
        button.onClick { runAction(text, action) }
        return button
    }

    private fun runAction(name: String, action: () -> Unit) {
        try {
            action()
            statusLabel.setText("{Done}: {$name}".tr())
            statusLabel.color = Color.GREEN
            screen.shouldUpdate = true
        } catch (ex: DevPanelActions.DevPanelException) {
            statusLabel.setText(ex.message?.tr() ?: "")
            statusLabel.color = Color.RED
        } catch (ex: Exception) {
            Log.error(OnlineLogTags.devPanel, "Developer panel action '$name' failed", ex)
            statusLabel.setText("{Error}: ${ex::class.simpleName} ${ex.message ?: ""}".tr())
            statusLabel.color = Color.RED
        }
        showTab(currentTab) // the selected tile may have changed (a new unit or city)
    }

    private fun civOf(select: TranslatedSelectBox): Civilization =
        livingCivs.first { it.civName == select.selected.value }

    private fun requireTile(): Tile = selectedTile() ?: throw DevPanelActions.DevPanelException("Select a tile on the map first")
    private fun requireUnit(): MapUnit = selectedUnit() ?: throw DevPanelActions.DevPanelException("There is no unit on the selected tile")
    private fun requireCity(): City = selectedCity() ?: throw DevPanelActions.DevPanelException("Select the center tile of a city first")
    private fun amountOf(field: UncivTextField): Int = field.text.toIntOrNull()?.takeIf { it > 0 }
        ?: throw DevPanelActions.DevPanelException("Enter a positive number")

    private fun buildUnitsTab() = with(content) {
        labeled("Nation:", unitCivSelect)
        labeled("Unit:", unitSelect)
        add(actionButton("Create unit on the selected tile") {
            panelActions.spawnUnit(civOf(unitCivSelect), unitSelect.selected.value, requireTile())
        }).colspan(2).row()
        addSeparator(colSpan = 2)
        val unit = selectedUnit()
        add((if (unit == null) "There is no unit on the selected tile".tr()
            else "{Unit on the tile}: {${unit.name}} ({${unit.civ.civName}}), ${unit.health} HP, ${unit.promotions.XP} XP".tr()).toLabel())
            .colspan(2).row()
        val buttons = Table().apply { defaults().pad(3f) }
        buttons.add(actionButton("Heal") { panelActions.healUnit(requireUnit()) })
        buttons.add(actionButton("Restore movement") { panelActions.restoreMovement(requireUnit()) })
        buttons.add(actionButton("Remove unit") { panelActions.removeUnit(requireUnit()) })
        add(buttons).colspan(2).row()
        val xpRow = Table().apply { defaults().pad(3f) }
        xpRow.add("XP:".toLabel())
        xpRow.add(xpAmount).width(120f)
        xpRow.add(actionButton("Add XP") { panelActions.addXp(requireUnit(), amountOf(xpAmount)) })
        add(xpRow).colspan(2).row()
    }

    private fun buildCitiesTab() = with(content) {
        labeled("Nation:", cityCivSelect)
        add(actionButton("Found a city on the selected tile") {
            panelActions.foundCity(civOf(cityCivSelect), requireTile())
        }).colspan(2).row()
        addSeparator(colSpan = 2)
        val city = selectedCity()
        if (city == null) {
            add("Select the center tile of a city first".toLabel()).colspan(2).row()
        } else {
            add("{City}: {${city.name}} ({${city.civ.civName}})".toLabel()).colspan(2).row()
        }
        labeled("Building:", buildingSelect)
        val buttons = Table().apply { defaults().pad(3f) }
        buttons.add(actionButton("Add building") { panelActions.addBuilding(requireCity(), buildingSelect.selected.value) })
        buttons.add(actionButton("Remove building") { panelActions.removeBuilding(requireCity(), buildingSelect.selected.value) })
        buttons.add(actionButton("Remove city") { panelActions.removeCity(requireCity()) })
        add(buttons).colspan(2).row()
    }

    private fun buildCivilizationTab() = with(content) {
        labeled("Nation:", empireCivSelect)
        addSeparator(colSpan = 2)
        labeled("Stat:", statSelect)
        add(amountRow(statAmount,
            { panelActions.addStat(civOf(empireCivSelect), Stat.valueOf(statSelect.selected.value), amountOf(statAmount)) },
            { panelActions.addStat(civOf(empireCivSelect), Stat.valueOf(statSelect.selected.value), -amountOf(statAmount)) }
        )).colspan(2).row()
        addSeparator(colSpan = 2)
        labeled("Resource:", resourceSelect)
        add(amountRow(resourceAmount,
            { panelActions.addResource(civOf(empireCivSelect), resourceSelect.selected.value, amountOf(resourceAmount)) },
            { panelActions.addResource(civOf(empireCivSelect), resourceSelect.selected.value, -amountOf(resourceAmount)) }
        )).colspan(2).row()
        addSeparator(colSpan = 2)
        labeled("Technology:", techSelect)
        val techButtons = Table().apply { defaults().pad(3f) }
        techButtons.add(actionButton("Give technology") { panelActions.addTechnology(civOf(empireCivSelect), techSelect.selected.value) })
        techButtons.add(actionButton("Take technology") { panelActions.removeTechnology(civOf(empireCivSelect), techSelect.selected.value) })
        techButtons.add(actionButton("Give all technologies") { panelActions.addAllTechnologies(civOf(empireCivSelect)) })
        add(techButtons).colspan(2).row()
    }

    private fun amountRow(field: UncivTextField, give: () -> Unit, take: () -> Unit): Table {
        val row = Table().apply { defaults().pad(3f) }
        row.add("Amount:".toLabel())
        row.add(field).width(140f)
        row.add(actionButton("Give", give))
        row.add(actionButton("Take", take))
        return row
    }

    private fun buildDiplomacyTab() = with(content) {
        labeled("Side 1:", warCivSelect1)
        labeled("Side 2:", warCivSelect2)
        val buttons = Table().apply { defaults().pad(3f) }
        buttons.add(actionButton("Declare war") { panelActions.declareWar(civOf(warCivSelect1), civOf(warCivSelect2)) })
        buttons.add(actionButton("Make peace") { panelActions.makePeace(civOf(warCivSelect1), civOf(warCivSelect2)) })
        add(buttons).colspan(2).row()
    }

    private fun buildTileTab() = with(content) {
        add(describeSelectedTile().toLabel().apply { wrap = true }).width(stageWidth()).colspan(2).row()
        add(actionButton("Remove improvement") { panelActions.removeImprovement(requireTile()) }).colspan(2).row()
    }

    private fun buildConsoleTab() = with(content) {
        add("Any developer console command, e.g. civ addtechnology Russia Writing".toLabel(fontSize = 14).apply { wrap = true })
            .width(stageWidth()).colspan(2).row()
        add(commandField).growX()
        val runButton = "Run".toTextButton()
        if (!canAct) runButton.disable()
        else runButton.onClick { runConsoleCommand(commandField.text) }
        add(runButton).row()
        val openConsole = "Open the console with hints".toTextButton()
        if (!canAct) openConsole.disable()
        else openConsole.onClick {
            close()
            screen.openDeveloperConsole()
        }
        add(openConsole).colspan(2).row()
    }

    /** Console answers stay untranslated English, like in the console itself (and may contain square brackets) */
    private fun runConsoleCommand(command: String) {
        if (command.isBlank()) {
            statusLabel.setText("Enter a command".tr())
            statusLabel.color = Color.RED
            return
        }
        val response = consoleExecutor.executeCommand(command)
        statusLabel.setText(response.message?.takeIf { it.isNotBlank() } ?: (if (response.isOK) "{Done}: $command".tr() else "Invalid command".tr()))
        statusLabel.color = response.color
        showTab(currentTab)
    }

    //endregion
}
