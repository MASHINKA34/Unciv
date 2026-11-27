package com.unciv.online

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import com.unciv.logic.map.MirroringType
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.utils.Log

/**
 * Unciv Online: the "debug world" of developer mode games ([GameParameters.debugWorld]) - a sandbox to test units and
 * mechanics, for two sides (Human + Human over the network, or Human + AI).
 *
 * - [prepareSetup] before map generation: a fixed small generated map (same seed and parameters every time,
 *   mirrored left-right so both sides get the same land), no city-states, no barbarians;
 * - [apply] after the game is created (the host creates it, so it works over the network too): every side gets
 *   [CITIES_PER_SIDE] cities of population [CITY_POPULATION] with the hidden [PRODUCTION_BUILDING] (+1000% production),
 *   all technologies, [GOLD] gold and one of every unit its nation can build (its unique units instead of the replaced ones;
 *   no Great People, no spaceship parts, no units disabled by the game settings).
 *
 * Decisions of the user, 2026-11-27.
 */
object DebugWorldSetup {
    const val MAP_SEED = 20261127L
    const val MAP_WIDTH = 40
    const val MAP_HEIGHT = 24
    const val CITIES_PER_SIDE = 3
    const val CITY_POPULATION = 6
    const val GOLD = 10_000
    /** Hidden building of the Gods & Kings ruleset: +1000% production, unbuildable, not in the Civilopedia */
    const val PRODUCTION_BUILDING = "Debug Factory"
    /** Distance between the cities of one side, and the minimum to any other city */
    private const val MIN_CITY_DISTANCE = 4
    private const val MAX_CITY_DISTANCE = 8

    fun isRequested(parameters: GameParameters) = parameters.developerMode && parameters.debugWorld

    /** Replaces the map and the city-state / barbarian settings of [setup] by the fixed debug world ones */
    fun prepareSetup(setup: GameSetupInfo) {
        val parameters = setup.gameParameters
        val map = setup.mapParameters
        Log.info(OnlineLogTags.debugWorld, "Preparing the debug world: players=%s, chosen map type=%s file=%s, city-states=%d, barbarians off=%s",
            parameters.players.map { "${it.chosenCiv}/${it.playerType}" }, map.type, map.name, parameters.numberOfCityStates, parameters.noBarbarians)
        parameters.numberOfCityStates = 0
        parameters.randomNumberOfCityStates = false
        parameters.randomNumberOfPlayers = false
        parameters.noBarbarians = true
        parameters.ragingBarbarians = false
        setup.mapFile = null
        map.name = ""
        map.type = MapType.pangaea
        map.shape = MapShape.rectangular
        map.mapSize = MapSize(MAP_WIDTH, MAP_HEIGHT)
        map.worldWrap = false
        map.mirroring = MirroringType.leftright
        map.noRuins = true
        map.noNaturalWonders = true
        map.seed = MAP_SEED
        Log.info(OnlineLogTags.debugWorld, "Debug world map: %s %dx%d %s, mirroring %s, seed %d", map.type, MAP_WIDTH, MAP_HEIGHT, map.shape, map.mirroring, map.seed)
    }

    /** Sets up every major civ of the freshly started [gameInfo] - see the class description */
    fun apply(gameInfo: GameInfo) {
        val start = System.currentTimeMillis()
        val sides = gameInfo.civilizations.filter { it.isMajorCiv && !it.isSpectator }
        Log.info(OnlineLogTags.debugWorld, "Setting up the debug world for %d sides: %s", sides.size, sides.map { "${it.civID}/${it.playerType}" })
        for (civ in sides) setUpCities(gameInfo, civ)
        for (civ in sides) {
            addAllTechnologies(gameInfo, civ)
            civ.addGold(GOLD - civ.gold)
            addOneOfEveryUnit(gameInfo, civ)
            civ.cache.updateSightAndResources()
            civ.updateStatsForNextTurn()
        }
        Log.info(OnlineLogTags.debugWorld, "Debug world ready in %dms", System.currentTimeMillis() - start)
    }

    private fun setUpCities(gameInfo: GameInfo, civ: Civilization) {
        val startingUnits = civ.units.getCivUnits().toList()
        val startTile = startingUnits.firstOrNull { it.baseUnit.isCityFounder() }?.getTile() ?: startingUnits.firstOrNull()?.getTile()
        for (unit in startingUnits) {
            civ.units.removeUnit(unit)
            unit.removeFromTile()
        }
        if (startTile == null) {
            Log.warn(OnlineLogTags.debugWorld, "%s has no starting units - no cities in the debug world", civ.civID)
            return
        }
        Log.debug(OnlineLogTags.debugWorld, "%s: removed %d starting units, capital at %s", civ.civID, startingUnits.size, startTile.position)
        foundCity(civ, startTile)
        while (civ.cities.size < CITIES_PER_SIDE) {
            val site = findCitySite(gameInfo, civ)
            if (site == null) {
                Log.warn(OnlineLogTags.debugWorld, "%s: no free site for city %d of %d", civ.civID, civ.cities.size + 1, CITIES_PER_SIDE)
                break
            }
            foundCity(civ, site)
        }
    }

    private fun foundCity(civ: Civilization, tile: Tile) {
        civ.addCity(tile.position)
        val city = tile.getCity() ?: run {
            Log.warn(OnlineLogTags.debugWorld, "%s: founding a city at %s failed", civ.civID, tile.position)
            return
        }
        city.population.setPopulation(CITY_POPULATION)
        val factory = civ.gameInfo.ruleset.buildings[PRODUCTION_BUILDING]
        if (factory != null) city.cityConstructions.addBuilding(factory)
        else Log.warn(OnlineLogTags.debugWorld, "Building %s is missing from the ruleset - no production bonus", PRODUCTION_BUILDING)
        Log.info(OnlineLogTags.debugWorld, "%s: city %s at %s, population %d, coastal %s", civ.civID, city.name, tile.position,
            city.population.population, tile.isAdjacentToCoast())
    }

    /** A land tile near the side's own cities and away from every other city; coastal first while the side has no coastal city (ships) */
    private fun findCitySite(gameInfo: GameInfo, civ: Civilization): Tile? {
        val allCities = gameInfo.getCities().toList()
        val ownCenters = civ.cities.map { it.getCenterTile() }
        val candidates = gameInfo.tileMap.tileList.filter { tile ->
            tile.isLand && !tile.isImpassible() && !tile.isCityCenter() && tile.getOwner().let { it == null || it == civ } &&
                allCities.all { it.getCenterTile().aerialDistanceTo(tile) >= MIN_CITY_DISTANCE } &&
                ownCenters.any { it.aerialDistanceTo(tile) <= MAX_CITY_DISTANCE && it.getContinent() == tile.getContinent() }
        }.sortedBy { tile -> ownCenters.minOf { it.aerialDistanceTo(tile) } }
        val needsCoast = ownCenters.none { it.isAdjacentToCoast() }
        return (if (needsCoast) candidates.firstOrNull { it.isAdjacentToCoast() } else null) ?: candidates.firstOrNull()
    }

    private fun addAllTechnologies(gameInfo: GameInfo, civ: Civilization) {
        val missing = gameInfo.ruleset.technologies.keys.filter { !civ.tech.isResearched(it) }
        for (tech in missing) civ.tech.addTechnology(tech, false)
        civ.popupAlerts.clear()
        Log.debug(OnlineLogTags.debugWorld, "%s: added %d technologies", civ.civID, missing.size)
    }

    /** One of every unit the nation can build, in any era */
    fun unitsOfNation(gameInfo: GameInfo, civ: Civilization): List<BaseUnit> {
        val ruleset = gameInfo.ruleset
        val religion = gameInfo.isReligionEnabled()
        return ruleset.units.values
            .filter { it.uniqueTo == null || it.uniqueTo == civ.civID }
            .map { civ.getEquivalentUnit(it) }
            .distinct()
            .filter { unit ->
                !unit.hasUnique(UniqueType.GreatPerson) && !unit.hasUnique(UniqueType.SpaceshipPart) &&
                    !unit.isUnavailableBySettings(gameInfo) &&
                    // Unbuildable: Great People (excluded above) and the religious units bought with faith
                    (!unit.hasUnique(UniqueType.Unbuildable) || (religion && unit.hasUnique(UniqueType.ReligiousUnit)))
            }
            .sortedBy { it.name }
    }

    private fun addOneOfEveryUnit(gameInfo: GameInfo, civ: Civilization) {
        val units = unitsOfNation(gameInfo, civ)
        val cities: List<City> = civ.cities
        if (cities.isEmpty()) return
        var placed = 0
        val failed = mutableListOf<String>()
        for ((index, baseUnit) in units.withIndex()) {
            // Spread over the cities; naval units start at a coastal one
            val preferred = if (baseUnit.isWaterUnit) cities.filter { it.getCenterTile().isAdjacentToCoast() } + cities
                else cities.drop(index % cities.size) + cities.take(index % cities.size)
            val unit = preferred.firstNotNullOfOrNull { civ.units.placeUnitNearTile(it.location, baseUnit) }
            if (unit == null) failed += baseUnit.name
            else {
                unit.currentMovement = unit.getMaxMovement().toFloat()
                placed++
            }
        }
        Log.info(OnlineLogTags.debugWorld, "%s: placed %d of %d units of its nation", civ.civID, placed, units.size)
        if (failed.isNotEmpty()) Log.warn(OnlineLogTags.debugWorld, "%s: no place found for %d units: %s", civ.civID, failed.size, failed)
    }
}
