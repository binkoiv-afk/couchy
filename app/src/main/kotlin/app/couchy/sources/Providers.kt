package app.couchy.sources

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Legal services that have the title: opened in their own app when it is installed (phone or TV
 * version), otherwise on the web page that links to them.
 */
object Providers {
    private val apps = mapOf(
        "kinopoisk" to listOf("ru.kinopoisk", "ru.kinopoisk.tv"),
        "okko" to listOf("ru.more.play", "tv.okko.androidtv"),
        "ivi" to listOf("ru.ivi.client"),
        "start" to listOf("ru.start.androidmobile", "ru.start.androidtv"),
        "wink" to listOf("ru.rt.video.app.mobile", "ru.rt.video.app.tv"),
        "premier" to listOf("gpm.tnt_premier"),
        "kion" to listOf("ru.mts.mtstv", "ru.mts.mtstv.androidtv"),
        "amediateka" to listOf("com.amediateka.amediateka"),
        "netflix" to listOf("com.netflix.mediaclient", "com.netflix.ninja"),
        "youtube" to listOf("com.google.android.youtube", "com.google.android.youtube.tv"),
        "apple tv" to listOf("com.apple.atve.androidtv.appletv", "com.apple.atve.amazon.appletv"),
    )

    /** Russian names for what TMDB writes in English. */
    fun name(provider: String): String = when {
        provider.contains("kinopoisk", true) -> "Кинопоиск"
        provider.contains("okko", true) -> "Okko"
        provider.contains("ivi", true) -> "Иви"
        provider.contains("wink", true) -> "Wink"
        provider.contains("premier", true) -> "Premier"
        provider.contains("start", true) -> "Start"
        provider.contains("kion", true) -> "KION"
        provider.contains("amediateka", true) -> "Амедиатека"
        else -> provider
    }

    private fun packages(provider: String) = apps.entries.firstOrNull { provider.contains(it.key, ignoreCase = true) }?.value.orEmpty()

    fun installed(context: Context, provider: String): Boolean = launch(context, provider) != null

    private fun launch(context: Context, provider: String): Intent? = packages(provider).firstNotNullOfOrNull { p ->
        context.packageManager.getLaunchIntentForPackage(p) ?: context.packageManager.getLeanbackLaunchIntentForPackage(p)
    }

    /** False when neither the app nor a browser could open. */
    fun open(context: Context, provider: String, link: String?): Boolean = runCatching {
        val intent = launch(context, provider) ?: Intent(Intent.ACTION_VIEW, Uri.parse(link ?: return false))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
