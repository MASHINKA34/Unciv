package com.unciv.online

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.LocationAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.diplomacy.DeclareWarReason
import com.unciv.logic.civilization.diplomacy.WarType
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.stats.Stat
import com.unciv.utils.Log

/**
 * Unciv Online: the actions of the developer panel ([com.unciv.ui.screens.devconsole.DevPanelPopup]) on the game state.
 * UI-free so it can be tested.
 *
 * Every successful action is logged (tag `UncivDevPanel`) and reported to all human players of the game as the notification
 * "[civ] (developer panel): [what was done]", linked to the tile when there is one. The changes are part of the game state,
 * so they reach the server with the acting player's turn - the panel only allows actions in the player's own turn.
 *
 * Invalid input throws [DevPanelException] with a translatable message, nothing is changed then.
 *
 * @param actor the civilization of the player using the panel (named in notifications)
 */
class DevPanelActions(private val gameInfo: GameInfo, private val actor: Civilization) {

    class DevPanelException(message: String) : Exception(message)

    companion object {
        const val NOTIFICATION_ICON = "OtherIcons/Settings"
    }

    private val ruleset get() = gameInfo.ruleset

    private fun fail(message: String, logDetails: String): Nothing {
        Log.warn(OnlineLogTags.devPanel, "Turn %d, %s: action refused - %s", gameInfo.turns, actor.civID, logDetails)
        throw DevPanelException(message)
    }

    private fun Tile.label() = "${position.x},${position.y}"

    //region Units

    fun spawnUnit(civ: Civilization, unitName: String, tile: Tile): MapUnit {
        val baseUnit = ruleset.units[unitName] ?: fail("Unknown unit", "unknown unit '$unitName'")
        val unit = civ.units.placeUnitNearTile(tile.position, baseUnit)
            ?: fail("No free tile for this unit nearby", "no place for $unitName of ${civ.civID} near ${tile.position}")
        record("created unit [$unitName] for [${civ.civName}] at [${unit.getTile().label()}]", unit.getTile(),
            "spawnUnit unit=$unitName civ=${civ.civID} requestedTile=${tile.position} placedAt=${unit.getTile().position} unitId=${unit.id}")
        return unit
    }

    fun removeUnit(unit: MapUnit) {
        val tile = unit.getTile()
        val details = "removeUnit unit=${unit.name} civ=${unit.civ.civID} tile=${tile.position} unitId=${unit.id}"
        unit.destroy()
        record("removed unit [${unit.name}] of [${unit.civ.civName}] at [${tile.label()}]", tile, details)
    }

    fun healUnit(unit: MapUnit) {
        val before = unit.health
        unit.health = 100
        record("healed unit [${unit.name}] of [${unit.civ.civName}]", unit.getTile(),
            "healUnit unit=${unit.name} civ=${unit.civ.civID} health=$before->100")
    }

    fun addXp(unit: MapUnit, amount: Int) {
        if (amount <= 0) fail("Enter a positive number", "addXp amount=$amount")
        val before = unit.promotions.XP
        unit.promotions.XP += amount
        record("gave [$amount] XP to unit [${unit.name}] of [${unit.civ.civName}]", unit.getTile(),
            "addXp unit=${unit.name} civ=${unit.civ.civID} xp=$before->${unit.promotions.XP}")
    }

    fun restoreMovement(unit: MapUnit) {
        val before = unit.currentMovement
        unit.currentMovement = unit.getMaxMovement().toFloat()
        unit.attacksThisTurn = 0
        record("restored movement of unit [${unit.name}] of [${unit.civ.civName}]", unit.getTile(),
            "restoreMovement unit=${unit.name} civ=${unit.civ.civID} movement=$before->${unit.currentMovement}")
    }

    //endregion
    //region Cities

    fun foundCity(civ: Civilization, tile: Tile): City {
        if (!civ.isMajorCiv && !civ.isCityState) fail("Cities can only be founded for nations and city-states", "foundCity civ=${civ.civID} is neither")
        if (tile.isCityCenter()) fail("This tile already has a city", "foundCity tile=${tile.position} is a city center")
        if (tile.isWater || tile.isImpassible()) fail("A city cannot be founded on this tile", "foundCity tile=${tile.position} water=${tile.isWater}")
        civ.addCity(tile.position)
        val city = tile.getCity() ?: fail("A city cannot be founded on this tile", "foundCity tile=${tile.position}: no city after addCity")
        record("founded city [${city.name}] for [${civ.civName}] at [${tile.label()}]", tile,
            "foundCity civ=${civ.civID} city=${city.name} tile=${tile.position}")
        return city
    }

    fun removeCity(city: City) {
        val tile = city.getCenterTile()
        val details = "removeCity city=${city.name} civ=${city.civ.civID} tile=${tile.position}"
        val civName = city.civ.civName
        city.destroyCity(overrideSafeties = true)
        record("removed city [${city.name}] of [$civName]", tile, details)
    }

    /** Adds the building, or one more copy of a repeatable one */
    fun addBuilding(city: City, buildingName: String) {
        val building = ruleset.buildings[buildingName] ?: fail("Unknown building", "unknown building '$buildingName'")
        val constructions = city.cityConstructions
        if (constructions.isBuilt(buildingName) && !building.isRepeatable())
            fail("This building is already built", "addBuilding $buildingName already built in ${city.name}")
        constructions.addBuilding(building)
        record("added building [$buildingName] in [${city.name}]", city.getCenterTile(),
            "addBuilding city=${city.name} civ=${city.civ.civID} building=$buildingName count=${constructions.getBuiltCount(buildingName)}")
    }

    /** Removes the building, or one copy of a repeatable one */
    fun removeBuilding(city: City, buildingName: String) {
        val building = ruleset.buildings[buildingName] ?: fail("Unknown building", "unknown building '$buildingName'")
        val constructions = city.cityConstructions
        if (!constructions.isBuilt(buildingName)) fail("This building is not built in the city", "removeBuilding $buildingName not built in ${city.name}")
        constructions.removeBuildingCopy(building)
        record("removed building [$buildingName] in [${city.name}]", city.getCenterTile(),
            "removeBuilding city=${city.name} civ=${city.civ.civID} building=$buildingName countLeft=${constructions.getBuiltCount(buildingName)}")
    }

    //endregion
    //region Civilizations

    /** Gold, culture, science or faith; a negative [amount] takes it away */
    fun addStat(civ: Civilization, stat: Stat, amount: Int) {
        if (amount == 0) fail("Enter a positive number", "addStat amount=0")
        if (stat !in Stat.statsWithCivWideField) fail("This stat cannot be given", "addStat stat=$stat not civ-wide")
        civ.addStat(stat, amount)
        val action = if (amount > 0) "gave [$amount] [${stat.name}] to [${civ.civName}]" else "took [${-amount}] [${stat.name}] from [${civ.civName}]"
        record(action, null, "addStat civ=${civ.civID} stat=$stat amount=$amount")
    }

    /** Stockpiled resources go into the stockpile, all others into the permanent [Civilization.developerResourceSupply]; negative takes away */
    fun addResource(civ: Civilization, resourceName: String, amount: Int) {
        if (amount == 0) fail("Enter a positive number", "addResource amount=0")
        val resource = ruleset.tileResources[resourceName] ?: fail("Unknown resource", "unknown resource '$resourceName'")
        val where: String
        if (resource.isStockpiled) {
            civ.gainStockpiledResource(resource, amount)
            where = "stockpile=${civ.resourceStockpiles[resourceName]}"
        } else {
            civ.developerResourceSupply.add(resourceName, amount)
            if (civ.developerResourceSupply[resourceName] == 0) civ.developerResourceSupply.remove(resourceName)
            where = "developerSupply=${civ.developerResourceSupply[resourceName]}"
        }
        civ.cache.updateCivResources()
        val action = if (amount > 0) "gave [$amount] [$resourceName] to [${civ.civName}]" else "took [${-amount}] [$resourceName] from [${civ.civName}]"
        record(action, null, "addResource civ=${civ.civID} resource=$resourceName amount=$amount $where total=${civ.getResourceAmount(resourceName)}")
    }

    fun addTechnology(civ: Civilization, techName: String) {
        if (techName !in ruleset.technologies) fail("Unknown technology", "unknown tech '$techName'")
        if (civ.tech.isResearched(techName)) fail("Already researched", "addTechnology ${civ.civID} already has $techName")
        civ.tech.addTechnology(techName, false)
        civ.popupAlerts.clear()
        record("gave technology [$techName] to [${civ.civName}]", null, "addTechnology civ=${civ.civID} tech=$techName")
    }

    fun removeTechnology(civ: Civilization, techName: String) {
        if (techName !in ruleset.technologies) fail("Unknown technology", "unknown tech '$techName'")
        if (!civ.tech.isResearched(techName)) fail("Not researched", "removeTechnology ${civ.civID} does not have $techName")
        civ.tech.techsResearched.removeAll { it == techName } // as the console does: researchable techs can be there several times
        civ.tech.setTransients(civ)
        civ.cache.updateSightAndResources()
        record("took technology [$techName] from [${civ.civName}]", null, "removeTechnology civ=${civ.civID} tech=$techName")
    }

    fun addAllTechnologies(civ: Civilization) {
        val missing = ruleset.technologies.keys.filter { !civ.tech.isResearched(it) }
        for (tech in missing) civ.tech.addTechnology(tech, false)
        civ.popupAlerts.clear()
        civ.cache.updateSightAndResources()
        record("gave all technologies to [${civ.civName}]", null, "addAllTechnologies civ=${civ.civID} added=${missing.size}")
    }

    fun declareWar(civ: Civilization, otherCiv: Civilization) {
        checkTwoLivingCivs(civ, otherCiv)
        if (civ.knows(otherCiv) && civ.isAtWarWith(otherCiv)) fail("They are already at war", "declareWar ${civ.civID}/${otherCiv.civID} already at war")
        civ.getDiplomacyManagerOrMeet(otherCiv).declareWar(DeclareWarReason(WarType.DirectWar))
        record("declared war between [${civ.civName}] and [${otherCiv.civName}]", null, "declareWar civ=${civ.civID} other=${otherCiv.civID}")
    }

    fun makePeace(civ: Civilization, otherCiv: Civilization) {
        checkTwoLivingCivs(civ, otherCiv)
        if (!civ.knows(otherCiv) || !civ.isAtWarWith(otherCiv)) fail("They are not at war", "makePeace ${civ.civID}/${otherCiv.civID} not at war")
        civ.getDiplomacyManager(otherCiv)!!.makePeace()
        record("made peace between [${civ.civName}] and [${otherCiv.civName}]", null, "makePeace civ=${civ.civID} other=${otherCiv.civID}")
    }

    private fun checkTwoLivingCivs(civ: Civilization, otherCiv: Civilization) {
        if (civ == otherCiv) fail("Choose two different sides", "diplomacy with itself: ${civ.civID}")
        if (civ.isDefeated() || otherCiv.isDefeated()) fail("Both sides must be alive", "diplomacy with a defeated civ: ${civ.civID}/${otherCiv.civID}")
        if (civ.isSpectator || otherCiv.isSpectator) fail("Both sides must be alive", "diplomacy with a spectator")
    }

    //endregion
    //region Tiles

    fun removeImprovement(tile: Tile) {
        val improvement = tile.improvement ?: fail("There is no improvement on this tile", "removeImprovement tile=${tile.position}: none")
        if (tile.isCityCenter()) fail("The city center cannot be removed this way", "removeImprovement tile=${tile.position}: city center")
        tile.removeImprovement()
        tile.getCity()?.reassignPopulation()
        record("removed improvement [$improvement] at [${tile.label()}]", tile, "removeImprovement tile=${tile.position} improvement=$improvement")
    }

    //endregion

    /** A command typed into the console of a developer mode game (panel field or the console popup) */
    fun reportConsoleCommand(command: String, tile: Tile?) {
        // Square brackets would be taken for translation placeholders in the notification
        val shown = command.replace('[', '(').replace(']', ')')
        record("ran console command [$shown]", tile, "console command='$command' selectedTile=${tile?.position}")
    }

    private fun record(action: String, tile: Tile?, logDetails: String) {
        Log.info(OnlineLogTags.devPanel, "Turn %d, %s (player %s): %s", gameInfo.turns, actor.civID, actor.playerId, logDetails)
        val text = "[${actor.civName}] (developer panel): [$action]"
        var notified = 0
        for (civ in gameInfo.civilizations) {
            if (!civ.isHuman() || civ.isSpectator) continue
            if (tile != null) civ.addNotification(text, LocationAction(tile.position), NotificationCategory.General, NOTIFICATION_ICON)
            else civ.addNotification(text, NotificationCategory.General, NOTIFICATION_ICON)
            notified++
        }
        Log.debug(OnlineLogTags.devPanel, "Notified %d human players: %s", notified, text)
    }
}
