package com.omnistream.megamanga

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.coroutines.resume

class ParserLoaderContext(
    okHttpClient: OkHttpClient,
    private val androidContext: Context
) : MangaLoaderContext() {

    override val httpClient: OkHttpClient = okHttpClient

    /** Per-source persisted config — carries MirrorSwitcher's domain overrides. */
    val sourceConfigStore = SourceConfigStore(androidContext)

    override val cookieJar: CookieJar = okHttpClient.cookieJar

    @Volatile private var webView: WebView? = null

    override suspend fun evaluateJs(script: String): String? {
        return evaluateJs("about:blank", script)
    }

    override suspend fun evaluateJs(baseUrl: String, script: String): String? {
        return withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine { cont ->
                Handler(Looper.getMainLooper()).post {
                    val wv = webView ?: WebView(androidContext).also {
                        it.settings.javaScriptEnabled = true
                        webView = it
                    }
                    if (baseUrl != "about:blank") {
                        wv.loadUrl(baseUrl)
                    }
                    wv.evaluateJavascript(script) { result ->
                        cont.resume(result?.trim('"'))
                    }
                }
            }
        }
    }

    fun destroy() {
        Handler(Looper.getMainLooper()).post {
            webView?.destroy()
            webView = null
        }
    }

    override fun getDefaultUserAgent(): String =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    override fun getConfig(source: MangaSource): MangaSourceConfig =
        sourceConfigStore.configFor(source)

    override fun encodeBase64(data: ByteArray): String =
        Base64.encodeToString(data, Base64.DEFAULT)

    override fun decodeBase64(data: String): ByteArray =
        Base64.decode(data, Base64.DEFAULT)

    override fun getPreferredLocales(): List<Locale> =
        listOf(Locale.getDefault()) + Locale.getAvailableLocales().toList()

    override fun redrawImageResponse(
        response: Response,
        redraw: (Bitmap) -> Bitmap
    ): Response {
        val body = response.body ?: return response
        val bytes = body.bytes()
        val androidBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return response
        val original = AndroidBitmap(androidBitmap)
        val result = redraw(original)
        val outputBitmap = (result as AndroidBitmap).backing
        val output = ByteArrayOutputStream()
        outputBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)
        if (outputBitmap !== androidBitmap) androidBitmap.recycle()
        outputBitmap.recycle()
        val newBody = output.toByteArray().toResponseBody("image/jpeg".toMediaType())
        return response.newBuilder().body(newBody).build()
    }

    override fun createBitmap(width: Int, height: Int): Bitmap {
        val backing = android.graphics.Bitmap.createBitmap(
            width, height, android.graphics.Bitmap.Config.ARGB_8888
        )
        return AndroidBitmap(backing)
    }
}

private class AndroidBitmap(val backing: android.graphics.Bitmap) : Bitmap {
    private val canvas by lazy { Canvas(backing) }

    override val width: Int get() = backing.width
    override val height: Int get() = backing.height

    override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) {
        val srcAndroid = android.graphics.Rect(src.left, src.top, src.right, src.bottom)
        val dstAndroid = android.graphics.Rect(dst.left, dst.top, dst.right, dst.bottom)
        canvas.drawBitmap((sourceBitmap as AndroidBitmap).backing, srcAndroid, dstAndroid, null)
    }
}
