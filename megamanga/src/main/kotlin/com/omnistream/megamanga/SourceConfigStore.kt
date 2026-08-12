package com.omnistream.megamanga

import android.content.Context
import android.content.SharedPreferences
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * Persistent per-source parser config (minimal port of upstream's SourceSettings).
 *
 * Today it stores exactly one thing: a domain override per source — the persistence layer
 * [MirrorSwitcher] writes a working mirror into, so a discovered domain survives app
 * restarts. Everything else falls through to the parser's compiled-in defaults, same as
 * the old EmptyMangaSourceConfig this replaces.
 *
 * Reads are LIVE (SharedPreferences hit on every get): parsers cache their config OBJECT
 * lazily (AbstractMangaParser.config by lazy) but resolve `domain` through config[key] on
 * every request, so setting an override here applies to already-instantiated parsers
 * immediately — no parser re-creation needed.
 */
class SourceConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("manga_source_config", Context.MODE_PRIVATE)

    fun getDomainOverride(source: MangaSource): String? =
        prefs.getString(domainKey(source), null)

    fun setDomainOverride(source: MangaSource, domain: String?) {
        setDomainOverrideByName(source.name, domain)
    }

    /** Name-keyed access for callers without a MangaSource handle (Diagnostics screen). */
    fun setDomainOverrideByName(sourceName: String, domain: String?) {
        prefs.edit().apply {
            val key = "$sourceName:domain"
            if (domain == null) remove(key) else putString(key, domain)
        }.apply()
    }

    /** All active domain overrides, source name -> domain (Diagnostics screen). */
    fun allDomainOverrides(): Map<String, String> = prefs.all.entries
        .mapNotNull { (key, value) ->
            if (key.endsWith(":domain") && value is String) key.removeSuffix(":domain") to value else null
        }
        .toMap()

    fun configFor(source: MangaSource): MangaSourceConfig = StoreBackedConfig(source)

    private fun domainKey(source: MangaSource) = "${source.name}:domain"

    private inner class StoreBackedConfig(private val source: MangaSource) : MangaSourceConfig {
        @Suppress("UNCHECKED_CAST")
        override fun <T> get(key: ConfigKey<T>): T = when (key) {
            is ConfigKey.Domain -> (getDomainOverride(source) as? T) ?: key.defaultValue
            else -> key.defaultValue
        }
    }
}
