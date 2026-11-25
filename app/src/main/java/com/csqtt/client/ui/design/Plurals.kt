package com.csqtt.client.ui.design

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * The app's text is Russian only for now (D12). Android picks a plural's form by the phone's language, so on an English
 * phone «2» would take «новых ошибок»: the form is asked for with Russian's rules instead. One place to change when a
 * second language comes.
 */
internal fun russianPlural(context: Context, @PluralsRes id: Int, count: Int): String {
    val russian = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("ru")) }
    return context.createConfigurationContext(russian).resources.getQuantityString(id, count, count)
}

@Composable
internal fun pluralText(@PluralsRes id: Int, count: Int): String {
    val context = LocalContext.current
    return remember(context, id, count) { russianPlural(context, id, count) }
}
