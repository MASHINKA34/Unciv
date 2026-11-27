package com.unciv.logic

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.stats.Stat
import com.unciv.online.DevPanelActions
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.RedirectOutput
import com.unciv.testing.RedirectPolicy
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Unciv Online: developer mode - game options and the developer panel actions. Output is shown to check the log lines. */
@RunWith(BaseTestRunner::class)
@RedirectOutput(RedirectPolicy.Show)
class DeveloperModeTests {
    private val game = TestGame()
    private lateinit var player: Civilization
    private lateinit var friend: Civilization
    private lateinit var actions: DevPanelActions

    @Before
    fun setUp() {
        game.makeHexagonalMap(5)
        game.gameInfo.gameParameters.developerMode = true
        player = game.addCiv(isPlayer = true)
        friend = game.addCiv(isPlayer = true)
        game.addCity(player, game.getTile(HexCoord.Zero))
        game.addCity(friend, game.getTile(4, 0))
        // A separate current player: meeting civs as the current player touches the game settings (not set up in tests)
        val currentPlayer = game.addCiv()
        game.gameInfo.currentPlayerCiv = currentPlayer
        game.gameInfo.currentPlayer = currentPlayer.civID
        actions = DevPanelActions(game.gameInfo, player)
    }

    private fun Civilization.devNotifications() = notifications.filter { "(developer panel)" in it.text }

    @Test
    fun `developer options are copied with the parameters but not into the next new game`() {
        val parameters = GameParameters().apply { developerMode = true; debugWorld = true }
        val clone = parameters.clone()
        assertTrue(clone.developerMode && clone.debugWorld)

        val nextGame = GameSetupInfo(game.gameInfo)
        assertFalse(nextGame.gameParameters.developerMode)
        assertFalse(nextGame.gameParameters.debugWorld)
    }

    @Test
    fun `spawning a unit of another nation notifies every human player`() {
        val unit = actions.spawnUnit(friend, "Warrior", game.getTile(1, 1))

        assertEquals(friend, unit.civ)
        assertEquals(1, player.devNotifications().size)
        assertEquals(1, friend.devNotifications().size)
        val text = friend.devNotifications().first().text
        assertTrue(text, text.startsWith("[${player.civName}] (developer panel): [created unit [Warrior] for [${friend.civName}]"))
    }

    @Test
    fun `founding a city of another nation`() {
        val city = actions.foundCity(friend, game.getTile(-3, 0))
        assertEquals(friend, city.civ)
        assertThrows(DevPanelActions.DevPanelException::class.java) { actions.foundCity(player, game.getTile(-3, 0)) }
    }

    @Test
    fun `gold can be given and taken`() {
        val before = friend.gold
        actions.addStat(friend, Stat.Gold, 500)
        actions.addStat(friend, Stat.Gold, -200)
        assertEquals(before + 300, friend.gold)
    }

    @Test
    fun `an ordinary resource is a permanent supply from the developer panel`() {
        actions.addResource(player, "Iron", 3)
        assertEquals(3, player.getResourceAmount("Iron"))
        assertTrue(player.detailedCivResources.any { it.origin == Civilization.DEVELOPER_PANEL_RESOURCE_ORIGIN && it.amount == 3 })

        actions.addResource(player, "Iron", -5)
        assertEquals(-2, player.getResourceAmount("Iron"))

        val clone = player.clone()
        assertEquals(-2, clone.developerResourceSupply["Iron"])
    }

    @Test
    fun `a stockpiled resource goes into the stockpile`() {
        val stockpiled = game.createResource("Stockpiled").apply { resourceType = com.unciv.models.ruleset.tile.ResourceType.Strategic }
        actions.addResource(player, stockpiled.name, 7)
        assertEquals(7, player.resourceStockpiles[stockpiled.name])
        assertEquals(0, player.developerResourceSupply[stockpiled.name])
    }

    @Test
    fun `all technologies`() {
        actions.addAllTechnologies(player)
        assertTrue(game.ruleset.technologies.keys.all { player.tech.isResearched(it) })
        assertTrue(player.popupAlerts.isEmpty())
    }

    @Test
    fun `a technology can be taken away`() {
        actions.addTechnology(player, "Writing")
        assertTrue(player.tech.isResearched("Writing"))
        actions.removeTechnology(player, "Writing")
        assertFalse(player.tech.isResearched("Writing"))
    }

    @Test
    fun `war and peace between any two sides`() {
        actions.declareWar(player, friend)
        assertTrue(player.isAtWarWith(friend))
        actions.makePeace(player, friend)
        assertFalse(player.isAtWarWith(friend))
        assertThrows(DevPanelActions.DevPanelException::class.java) { actions.makePeace(player, friend) }
    }

    @Test
    fun `buildings - a repeatable one gains and loses one copy`() {
        val city = player.cities.first()
        actions.addBuilding(city, "Hookah Lounge")
        actions.addBuilding(city, "Hookah Lounge")
        assertEquals(2, city.cityConstructions.getBuiltCount("Hookah Lounge"))
        actions.removeBuilding(city, "Hookah Lounge")
        assertEquals(1, city.cityConstructions.getBuiltCount("Hookah Lounge"))

        actions.addBuilding(city, "Monument")
        assertThrows(DevPanelActions.DevPanelException::class.java) { actions.addBuilding(city, "Monument") }
    }

    @Test
    fun `units can be healed, promoted, refreshed and removed`() {
        val unit = actions.spawnUnit(player, "Warrior", game.getTile(1, 0))
        unit.health = 10
        unit.currentMovement = 0f
        actions.healUnit(unit)
        actions.addXp(unit, 30)
        actions.restoreMovement(unit)
        assertEquals(100, unit.health)
        assertEquals(30, unit.promotions.XP)
        assertEquals(unit.getMaxMovement().toFloat(), unit.currentMovement, 0.01f)

        actions.removeUnit(unit)
        assertTrue(unit.isDestroyed)
    }

    @Test
    fun `an improvement can be removed`() {
        val tile = game.getTile(1, 0)
        tile.setImprovement("Farm")
        actions.removeImprovement(tile)
        assertEquals(null, tile.improvement)
        assertThrows(DevPanelActions.DevPanelException::class.java) { actions.removeImprovement(tile) }
    }

    @Test
    fun `console commands are reported to all players with brackets replaced`() {
        actions.reportConsoleCommand("civ activatetrigger \"Instantly provides [1] [Iron]\"", null)
        val text = friend.devNotifications().single().text
        assertNotNull(text)
        assertTrue(text, text.endsWith("[ran console command [civ activatetrigger \"Instantly provides (1) (Iron)\"]]"))
    }
}
