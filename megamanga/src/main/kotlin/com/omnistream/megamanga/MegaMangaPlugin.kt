package com.omnistream.megamanga

import android.util.Log
import com.omnistream.pluginapi.android.AndroidPluginApi
import com.omnistream.pluginapi.plugin.OmniPlugin

class MegaMangaPlugin : OmniPlugin() {

    override val pluginName: String = "MegaManga"
    override val pluginVersion: String = "1.0.0"

    override fun load() {
        val host = AndroidPluginApi.require()
        if (host.isTv) {
            Log.i(TAG, "TV device — no manga sources registered")
            return
        }
        val client = host.httpClient.newBuilder()
            .addInterceptor(ParserHeadersInterceptor())
            .build()
        MangaBridge.initialize(ParserLoaderContext(client, host.appContext))
        MangaBridge.cfRetryHandler = host.cfRetry
        MangaBridge.mechanismLogger = { tag, message -> host.record(tag, message) }
        val parsers = MangaBridge.getAllParsers()
        parsers.forEach { registerSource(ParserMangaSource(it)) }
        Log.i(TAG, "registered ${parsers.size} manga sources")
    }

    override fun unload() {
        MangaBridge.getContext()?.destroy()
        MangaBridge.mechanismLogger = null
    }

    private companion object {
        const val TAG = "MegaMangaPlugin"
    }
}
