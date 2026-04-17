package io.raaz.messenger.util

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import io.raaz.messenger.R

object FontProvider {

    private var danaTypeface: Typeface? = null

    fun get(context: Context): Typeface? {
        if (LocaleManager.getLanguage(context) != LocaleManager.LANG_FA) return null
        if (danaTypeface == null) {
            danaTypeface = ResourcesCompat.getFont(context, R.font.dana_regular)
        }
        return danaTypeface
    }

    fun getBold(context: Context): Typeface? {
        if (LocaleManager.getLanguage(context) != LocaleManager.LANG_FA) return null
        return ResourcesCompat.getFont(context, R.font.dana_bold)
    }

    fun getMedium(context: Context): Typeface? {
        if (LocaleManager.getLanguage(context) != LocaleManager.LANG_FA) return null
        return ResourcesCompat.getFont(context, R.font.dana_medium)
    }
}
