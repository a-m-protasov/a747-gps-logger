package io.github.a_m_protasov.a747

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's language, chosen in Settings. Android 13+ keeps it per app (also in the system's
 * app settings); older versions get it from a preference applied to each context.
 */
object AppLocale {
    /** The app's translations as language tags (matching localeFilters in build.gradle.kts). */
    val tags = listOf("en", "ru", "sr", "uk", "kk", "ka", "es", "pt-BR", "fr", "de", "it", "pl", "tr",
        "zh-CN", "ja", "ko", "ar", "hi", "id")

    /** The language's own name for itself. */
    fun name(tag: String): String {
        val l = Locale.forLanguageTag(tag)
        return l.getDisplayName(l).replaceFirstChar { it.titlecase(l) }
    }

    /** The chosen tag, or "" for the system's language. */
    fun current(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= 33) ctx.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
            .split(",").first().let { t -> tags.firstOrNull { Locale.forLanguageTag(it).language == Locale.forLanguageTag(t).language } ?: "" }
        else prefs(ctx).getString("language", "")!!

    /** Applies at once on Android 13+ (the activity is recreated); older versions need [android.app.Activity.recreate]. */
    fun set(ctx: Context, tag: String) {
        if (Build.VERSION.SDK_INT >= 33)
            ctx.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        else prefs(ctx).edit().putString("language", tag).commit()
    }

    /** Before Android 13: the context with the chosen language. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = prefs(base).getString("language", "")!!
        if (tag.isEmpty()) return base
        val l = Locale.forLanguageTag(tag)
        Locale.setDefault(l)
        val cfg = Configuration(base.resources.configuration).apply { setLocale(l) }
        return base.createConfigurationContext(cfg)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
