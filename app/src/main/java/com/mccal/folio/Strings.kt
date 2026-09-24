package com.mccal.folio

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Folio's text, for code that builds a sentence outside a composable. The app reads it from resources
 * ([Context.strings]); plain unit tests pass the English strings.xml instead, so the wording stays testable.
 */
internal interface Strings {
    fun get(@StringRes id: Int, vararg args: Any): String
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String
}

internal fun Context.strings(): Strings = object : Strings {
    override fun get(id: Int, vararg args: Any) = getString(id, *args)
    override fun plural(id: Int, count: Int, vararg args: Any) = resources.getQuantityString(id, count, *args)
}
