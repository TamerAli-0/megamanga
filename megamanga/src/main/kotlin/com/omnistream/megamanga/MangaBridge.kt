package com.omnistream.megamanga

import android.util.Log
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.lang.ref.WeakReference

object MangaBridge {
    private const val TAG = "MangaBridge"

    @Volatile private var loaderContext: ParserLoaderContext? = null

    /**
     * CF retry handler injected by PluginManager from the app module.
     * Wraps a suspend block with CloudFlare challenge resolution.
     */
    @Volatile var cfRetryHandler: (suspend (url: String, block: suspend () -> Any?) -> Any?)? = null

    /**
     * Optional sink for mechanism events (mirror switches etc.), injected by the app
     * module so parser-api stays free of app dependencies. Mirrors to MechanismLog for
     * the Diagnostics screen; plain logcat when unset.
     */
    @Volatile var mechanismLogger: ((tag: String, message: String) -> Unit)? = null

    private val parserCache = java.util.Collections.synchronizedMap(
        HashMap<MangaParserSource, WeakReference<MangaParser>>()
    )

    fun initialize(context: ParserLoaderContext) {
        loaderContext = context
        Log.d(TAG, "MangaBridge initialized")
    }

    fun getContext(): ParserLoaderContext? = loaderContext

    fun getAllSourceEntries(): List<MangaParserSource> = MangaParserSource.entries

    fun createParser(source: MangaParserSource): MangaParser? {
        parserCache[source]?.get()?.let { return it }
        val ctx = loaderContext ?: run {
            Log.e(TAG, "MangaBridge not initialized")
            return null
        }
        return try {
            ctx.newParserInstance(source).also {
                parserCache[source] = WeakReference(it)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create parser for ${source.name}", e)
            null
        }
    }

    fun getAllParsers(): List<MangaParser> {
        // Filter out sources upstream marked @Broken — those are confirmed-dead sites
        // (account suspended, domain expired, site closed). KSP processor sets isBroken
        // on each enum entry at build time. Without this filter we register ~374 dead
        // parsers that fail on every tap and pollute the source list.
        val active = MangaParserSource.entries.filterNot { it.isBroken }
        val broken = MangaParserSource.entries.size - active.size
        return active.mapNotNull { createParser(it) }.also {
            Log.d(TAG, "${it.size} active parsers (${broken} broken sources filtered out)")
        }
    }

    fun getParser(sourceName: String): MangaParser? {
        val source = MangaParserSource.entries.find {
            it.name.equals(sourceName, ignoreCase = true)
        } ?: run {
            Log.e(TAG, "Unknown source: '$sourceName'")
            return null
        }
        return createParser(source)
    }
}
