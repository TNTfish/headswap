package com.yegorkapomidorka.headswap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLocaleTest {
    @Test fun explicitSimplifiedChineseUsesGlobalApps() {
        // Android 10-12 stores the full picker tag rather than a base language.
        assertTrue(AppLocale.usesGlobalAppsForLanguage("zh-CN"))
    }

    @Test fun systemChineseVariantsUseGlobalApps() {
        for (tag in listOf("zh", "zh-Hans-CN", "zh-TW")) {
            assertTrue(tag, AppLocale.usesGlobalAppsForLanguage(tag))
        }
    }

    @Test fun otherGlobalLanguagesKeepTheirAppSelection() {
        for (tag in listOf("ar", "ar-SA", "ar-u-nu-latn", "fr", "fr-FR")) {
            assertTrue(tag, AppLocale.usesGlobalAppsForLanguage(tag))
        }
    }

    @Test fun otherLanguagesKeepTheirAppSelection() {
        for (tag in listOf("en", "en-US", "ru", "ru-RU", "de")) {
            assertFalse(tag, AppLocale.usesGlobalAppsForLanguage(tag))
        }
        assertFalse(AppLocale.usesGlobalAppsForLanguage(null))
    }
}
