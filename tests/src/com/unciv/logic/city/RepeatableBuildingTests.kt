package com.unciv.logic.city

import com.unciv.logic.city.managers.CityConquestFunctions
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.RejectionReasonType
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.RedirectOutput
import com.unciv.testing.RedirectPolicy
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Unciv Online: [UniqueType.Repeatable] - "Can be constructed repeatedly". Output is shown so the mechanic's log lines can be checked. */
@RunWith(BaseTestRunner::class)
@RedirectOutput(RedirectPolicy.Show)
class RepeatableBuildingTests {
    private val game = TestGame()
    private lateinit var city: City

    @Before
    fun setUp() {
        game.makeHexagonalMap(3)
        val civ = game.addCiv(isPlayer = true)
        city = game.addCity(civ, game.getTile(HexCoord.Zero))
        // A separate current player, as in CityConquestFunctionsTest: meeting civs as the current player touches the game settings
        val currentPlayer = game.addCiv()
        game.gameInfo.currentPlayerCiv = currentPlayer
        game.gameInfo.currentPlayer = currentPlayer.civID
    }

    private fun createRepeatable(vararg uniques: String): Building =
        game.createBuilding(UniqueType.Repeatable.text, *uniques).apply { cost = 60 }

    @Test
    fun `building another copy raises the count and keeps it buildable`() {
        val building = createRepeatable()
        val constructions = city.cityConstructions

        building.construct(constructions)
        building.construct(constructions)

        assertEquals(2, constructions.getBuiltCount(building))
        assertTrue(constructions.isBuilt(building.name))
        assertTrue(building.getRejectionReasons(constructions).none { it.type == RejectionReasonType.AlreadyBuilt })
        assertTrue(building.isBuildable(constructions))
        assertTrue(building.shouldBeDisplayed(constructions, listOf(building.name)))
    }

    @Test
    fun `an ordinary building is still rejected once built`() {
        val building = game.createBuilding().apply { cost = 60 }
        building.construct(city.cityConstructions)

        assertEquals(1, city.cityConstructions.getBuiltCount(building))
        assertTrue(building.getRejectionReasons(city.cityConstructions).any { it.type == RejectionReasonType.AlreadyBuilt })
        assertFalse(building.shouldBeDisplayed(city.cityConstructions, listOf(building.name)))
    }

    @Test
    fun `every copy provides its resources`() {
        val building = createRepeatable("Provides [1] [Iron]")
        repeat(3) { building.construct(city.cityConstructions) }

        assertEquals(3, city.civ.getResourceAmount("Iron"))
    }

    @Test
    fun `every copy adds its stats`() {
        val building = createRepeatable()
        building.culture = 2f
        val cultureBefore = city.cityConstructions.getStats().totalStats[Stat.Culture] // the Palace gives culture too
        repeat(3) { building.construct(city.cityConstructions) }

        assertEquals(cultureBefore + 6f, city.cityConstructions.getStats().totalStats[Stat.Culture], 0.01f)
    }

    @Test
    fun `one-time effects fire on every construction`() {
        val stockpiled = game.createResource(UniqueType.Stockpiled.text).apply { resourceType = ResourceType.Strategic }
        val building = createRepeatable("Instantly provides [2] [${stockpiled.name}]")

        building.construct(city.cityConstructions)
        building.construct(city.cityConstructions)

        assertEquals(4, city.civ.resourceStockpiles[stockpiled.name])
    }

    @Test
    fun `can be queued several times`() {
        val building = createRepeatable()
        val constructions = city.cityConstructions

        assertTrue(constructions.canAddToQueue(building))
        constructions.addToQueue(building.name)
        assertTrue(constructions.canAddToQueue(building))
        constructions.addToQueue(building.name)

        assertEquals(2, constructions.constructionQueue.count { it == building.name })
    }

    @Test
    fun `selling removes one copy`() {
        val building = createRepeatable()
        repeat(3) { building.construct(city.cityConstructions) }
        val goldBefore = city.civ.gold

        city.sellBuilding(building)

        assertEquals(2, city.cityConstructions.getBuiltCount(building))
        assertEquals(goldBefore + building.cost / 10, city.civ.gold)
        assertTrue(city.hasSoldBuildingThisTurn)
    }

    @Test
    fun `removing the last copy removes the building`() {
        val building = createRepeatable()
        building.construct(city.cityConstructions)

        city.cityConstructions.removeBuildingCopy(building)

        assertFalse(city.cityConstructions.isBuilt(building.name))
        assertEquals(0, city.cityConstructions.getBuiltCount(building))
        assertFalse(building.name in city.cityConstructions.repeatableBuildingCounts)
    }

    @Test
    fun `copies are kept when the city constructions are cloned`() {
        val building = createRepeatable()
        repeat(4) { building.construct(city.cityConstructions) }

        val clone = city.cityConstructions.clone()

        assertEquals(4, clone.repeatableBuildingCounts[building.name])
    }

    @Test
    fun `copies counted by the building countable`() {
        val building = createRepeatable()
        repeat(3) { building.construct(city.cityConstructions) }

        val amount = com.unciv.models.ruleset.unique.Countables.getCountableAmount(
            "[${building.name}] Buildings", com.unciv.models.ruleset.unique.GameContext(city.civ))
        assertEquals(3, amount)
    }

    @Test
    fun `on capture every copy is checked separately and the rest go to the conqueror`() {
        val attacker = game.addCiv()
        val building = createRepeatable()
        repeat(50) { building.construct(city.cityConstructions) }

        CityConquestFunctions(city).puppetCity(attacker)

        val left = city.cityConstructions.getBuiltCount(building)
        assertEquals(attacker, city.civ)
        assertTrue("some copies should survive, left=$left", left in 1..49)
    }

    @Test
    fun `copies are never destroyed on capture with the matching unique`() {
        val attacker = game.addCiv()
        val building = createRepeatable(UniqueType.NotDestroyedWhenCityCaptured.text)
        repeat(5) { building.construct(city.cityConstructions) }

        CityConquestFunctions(city).puppetCity(attacker)

        assertEquals(5, city.cityConstructions.getBuiltCount(building))
    }
}
