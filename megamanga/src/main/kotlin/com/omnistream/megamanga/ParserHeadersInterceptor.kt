package com.omnistream.megamanga

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.net.IDN

/**
 * Replicates what upstream's CommonHeadersInterceptor does:
 * 1. Reads the MangaSource tag from the request (set by OkHttpWebClient)
 * 2. Looks up the parser via MangaBridge
 * 3. Merges parser.getRequestHeaders() into the request
 * 4. Adds Referer header if missing
 * 5. Delegates to parser.intercept() for source-specific request manipulation
 */
class ParserHeadersInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val source = request.tag(MangaSource::class.java)

        val parser = if (source is org.koitharu.kotatsu.parsers.model.MangaParserSource) {
            MangaBridge.createParser(source)
        } else {
            null
        }

        val headersBuilder = request.headers.newBuilder()

        if (parser != null) {
            val parserHeaders = parser.getRequestHeaders()
            for (i in 0 until parserHeaders.size) {
                val name = parserHeaders.name(i)
                if (headersBuilder[name] == null) {
                    headersBuilder.add(name, parserHeaders.value(i))
                }
            }
            if (headersBuilder["Referer"] == null) {
                val idn = IDN.toASCII(parser.domain)
                headersBuilder.set("Referer", "https://$idn/")
            }
        }

        if (headersBuilder["User-Agent"] == null) {
            headersBuilder.set("User-Agent", MangaBridge.getContext()?.getDefaultUserAgent() ?: "Mozilla/5.0")
        }

        val newRequest = request.newBuilder().headers(headersBuilder.build()).build()
        android.util.Log.d("MangaHeaders", "${newRequest.method} ${newRequest.url} headers=${newRequest.headers.names()}")

        val response = if (parser != null) {
            try {
                parser.intercept(ProxyChain(chain, newRequest))
            } catch (e: java.io.IOException) {
                throw e
            } catch (e: Exception) {
                chain.proceed(newRequest)
            }
        } else {
            chain.proceed(newRequest)
        }
        android.util.Log.d("MangaHeaders", "← ${response.code} ${newRequest.url}")
        return response
    }

    private class ProxyChain(
        private val delegate: Interceptor.Chain,
        private val request: Request,
    ) : Interceptor.Chain by delegate {
        override fun request(): Request = request
    }
}
