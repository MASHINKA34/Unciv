package com.unciv.logic.city

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Unciv Online: the Hookah Lounge of the Gods & Kings ruleset - a repeatable building providing tradeable Hookahs (+1 Happiness each) */
@RunWith(BaseTestRunner::class)
class HookahLoungeTests {
    private val game = TestGame()
    private lateinit var civ: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game.makeHexagonalMap(4)
        civ = game.addCiv(isPlayer = true)
        city = game.addCity(civ, game.getTile(0, 0))
        // A separate current player: meeting civs as the current player touches the game settings (not set up in tests)
        val currentPlayer = game.addCiv()
        game.gameInfo.currentPlayerCiv = currentPlayer
        game.gameInfo.currentPlayer = currentPlayer.civID
    }

    private val hookahLounge get() = game.ruleset.buildings["Hookah Lounge"]!!

    @Test
    fun `available from the start, costs 60 and needs no technology`() {
        assertEquals(60, hookahLounge.cost)
        assertEquals(null, hookahLounge.requiredTech)
        assertTrue(hookahLounge.isRepeatable())
        assertTrue(hookahLounge.isBuildable(city.cityConstructions))
    }

    @Test
    fun `built twice gives x2, two Hookahs, +2 Happiness and stays buildable`() {
        val happinessBefore = civ.getHappiness()

        city.cityConstructions.completeConstruction(hookahLounge)
        city.cityConstructions.completeConstruction(hookahLounge)
        civ.updateStatsForNextTurn()

        assertEquals(2, city.cityConstructions.getBuiltCount("Hookah Lounge"))
        assertEquals(2, civ.getResourceAmount("Hookahs"))
        assertEquals(happinessBefore + 2, civ.getHappiness())
        assertTrue(hookahLounge.isBuildable(city.cityConstructions))
        assertTrue(hookahLounge.shouldBeDisplayed(city.cityConstructions, city.cityConstructions.constructionQueue))
    }

    @Test
    fun `selling Hookahs moves the happiness to the buyer`() {
        val buyer = game.addCiv(isPlayer = true)
        game.addCity(buyer, game.getTile(3, 3))
        civ.diplomacyFunctions.makeCivilizationsMeet(buyer)
        repeat(3) { city.cityConstructions.completeConstruction(hookahLounge) }
        civ.updateStatsForNextTurn()
        buyer.updateStatsForNextTurn()
        val sellerHappiness = civ.getHappiness()
        val buyerHappiness = buyer.getHappiness()

        val trade = TradeLogic(civ, buyer)
        trade.currentTrade.ourOffers.add(TradeOffer("Hookahs", TradeOfferType.Strategic_Resource, 2, duration = 30))
        trade.acceptTrade()
        civ.updateStatsForNextTurn()
        buyer.updateStatsForNextTurn()

        assertEquals(1, civ.getResourceAmount("Hookahs"))
        assertEquals(2, buyer.getResourceAmount("Hookahs"))
        assertEquals(sellerHappiness - 2, civ.getHappiness())
        assertEquals(buyerHappiness + 2, buyer.getHappiness())
    }

    @Test
    fun `AI pays 100 gold per deal and stops buying at 2 Hookahs`() {
        val ai = game.addCiv()
        game.addCity(ai, game.getTile(3, 3))
        civ.diplomacyFunctions.makeCivilizationsMeet(ai)
        val offer = TradeOffer("Hookahs", TradeOfferType.Strategic_Resource, 1, duration = 30)

        // The engine converts non-gold offers to "today's gold" with the AI's gold inflation (about 1.0 early in the game)
        val expected = (100 / TradeEvaluation().getGoldInflation(ai)).toInt()
        assertEquals(expected, TradeEvaluation().evaluateBuyCostWithInflation(offer, ai, civ, Trade()))

        val aiCity = ai.cities.first()
        repeat(2) { aiCity.cityConstructions.completeConstruction(hookahLounge) }
        assertEquals(0, TradeEvaluation().evaluateBuyCostWithInflation(offer, ai, civ, Trade()))
    }
}
