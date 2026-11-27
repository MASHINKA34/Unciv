package com.unciv.logic

import com.unciv.UncivGame
import com.unciv.models.metadata.GameSettings
import com.unciv.models.translations.Translations
import com.unciv.models.translations.tr
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Unciv Online: the nested notifications of the developer panel ("[civ] (developer panel): [action [with] [params]]") */
@RunWith(BaseTestRunner::class)
class DeveloperModeTranslationTests {

    @Before
    fun setUp() {
        // Needed for .tr() to work, as in TranslationTests
        UncivGame.Current = UncivGame()
        UncivGame.Current.settings = GameSettings()
        val translations = Translations()
        translations.readAllLanguagesTranslation()
        for ((key, value) in translations) UncivGame.Current.translations[key] = value
    }

    @Test
    fun `panel notifications translate to Russian`() {
        assertEquals("Россия (панель разработчика): создал юнит Воин (Россия) на клетке 2,2",
            "[Russia] (developer panel): [created unit [Warrior] for [Russia] at [2,2]]".tr("Russian"))
        assertEquals("Россия (панель разработчика): Россия: Кальяны +3",
            "[Russia] (developer panel): [gave [3] [Hookahs] to [Russia]]".tr("Russian"))
        assertEquals("Россия (панель разработчика): выполнил команду консоли civ addtechnology Russia Writing",
            "[Russia] (developer panel): [ran console command [civ addtechnology Russia Writing]]".tr("Russian"))
    }
}
