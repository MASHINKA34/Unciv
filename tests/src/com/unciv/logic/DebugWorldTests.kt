package com.unciv.logic

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.online.DebugWorldSetup
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.RedirectOutput
import com.unciv.testing.RedirectPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Unciv Online: the "debug world" of developer mode games, started through [GameStarter] like a real new game */
@RunWith(BaseTestRunner::class)
@RedirectOutput(RedirectPolicy.Show)
class DebugWorldTests {
    private lateinit var settingsBackup: GameSettings

    @Before
    fun setUp() {
        RulesetCache.loadRulesets(noMods = true)
        UncivGame.Current = UncivGame()
        UncivGame.Current.files = UncivFiles(Gdx.files)
        // GameStarter saves the new game setup into the general settings - keep the real ones, as GameSerializationTests does
        settingsBackup = UncivGame.Current.files.getGeneralSettings()
        UncivGame.Current.settings = GameSettings()
    }

    @After
    fun cleanup() {
        settingsBackup.save()
    }

    private fun startDebugWorld(second: Player): GameInfo {
        val parameters = GameParameters().apply {
            developerMode = true
            debugWorld = true
            numberOfCityStates = 6 // must be overridden
            players.clear()
            players.add(Player("Russia", PlayerType.Human))
            players.add(second)
        }
        val map = MapParameters().apply { mapSize = MapSize.Huge; seed = 1L } // must be overridden too
        return GameStarter.startNewGame(GameSetupInfo(parameters, map))
    }

    @Test
    fun `both sides get three big cities, all technologies, gold and their own units`() {
        val game = startDebugWorld(Player("Japan", PlayerType.Human))
        val sides = game.civilizations.filter { it.isMajorCiv }

        assertEquals(2, sides.size)
        assertTrue(game.civilizations.none { it.isCityState })
        assertTrue(game.gameParameters.noBarbarians)
        assertEquals(DebugWorldSetup.MAP_WIDTH, game.tileMap.mapParameters.mapSize.width)
        assertEquals(DebugWorldSetup.MAP_SEED, game.tileMap.mapParameters.seed)
        for (civ in sides) {
            assertEquals(civ.civID, DebugWorldSetup.CITIES_PER_SIDE, civ.cities.size)
            for (city in civ.cities) {
                assertEquals(DebugWorldSetup.CITY_POPULATION, city.population.population)
                assertTrue(city.cityConstructions.isBuilt(DebugWorldSetup.PRODUCTION_BUILDING))
            }
            assertTrue(game.ruleset.technologies.keys.all { civ.tech.isResearched(it) })
            assertEquals(DebugWorldSetup.GOLD, civ.gold)
            val expected = DebugWorldSetup.unitsOfNation(game, civ).map { it.name }.sorted()
            val actual = civ.units.getCivUnits().map { it.name }.toList().sorted()
            assertEquals("${civ.civID} should have one of every unit of its nation", expected, actual)
        }
        // Unique units: Japan's Samurai instead of the Longswordsman, Russia's Cossack instead of the Cavalry
        val japan = game.getCivilization("Japan")
        val russia = game.getCivilization("Russia")
        assertTrue(japan.units.getCivUnits().any { it.name == "Samurai" })
        assertFalse(japan.units.getCivUnits().any { it.name == "Longswordsman" })
        assertTrue(russia.units.getCivUnits().any { it.name == "Cossack" })
        assertFalse(russia.units.getCivUnits().any { it.name == "Great General" })
    }

    @Test
    fun `works with an AI opponent and the map is the same every time`() {
        val first = startDebugWorld(Player("Japan", PlayerType.AI))
        val second = startDebugWorld(Player("Japan", PlayerType.AI))

        assertEquals(DebugWorldSetup.CITIES_PER_SIDE, first.getCivilization("Japan").cities.size)
        assertEquals(first.tileMap.tileList.map { it.baseTerrain }, second.tileMap.tileList.map { it.baseTerrain })
    }
}
