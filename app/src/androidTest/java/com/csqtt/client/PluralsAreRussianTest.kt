package com.csqtt.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.csqtt.client.ui.design.russianPlural
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's text is Russian only (D12), so a count takes Russian's form on a phone set to any language: Android itself
 * picks a plural's form by the phone's, and an English phone would say «2 новых ошибок».
 */
@RunWith(AndroidJUnit4::class)
class PluralsAreRussianTest {
    @Test
    fun aCountTakesRussiansFormWhateverThePhonesLanguage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val errors = listOf(1, 2, 5, 11, 21, 22, 25, 101).map { russianPlural(context, R.plurals.tab_logs_new_errors, it) }
        assertEquals(
            listOf(
                "1 новая ошибка", "2 новые ошибки", "5 новых ошибок", "11 новых ошибок", "21 новая ошибка",
                "22 новые ошибки", "25 новых ошибок", "101 новая ошибка",
            ),
            errors,
        )
        assertEquals("38 миллисекунд", russianPlural(context, R.plurals.ping_ms_spoken, 38))
        assertEquals("2 миллисекунды", russianPlural(context, R.plurals.ping_ms_spoken, 2))
    }
}
