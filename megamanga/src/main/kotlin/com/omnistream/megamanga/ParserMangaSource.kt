package com.omnistream.megamanga

import android.util.Log
import com.omnistream.pluginapi.model.Chapter
import com.omnistream.pluginapi.model.Manga as OmniManga
import com.omnistream.pluginapi.model.MangaStatus
import com.omnistream.pluginapi.model.Page
import com.omnistream.pluginapi.model.PagedResult
import com.omnistream.pluginapi.model.MainPageSection
import com.omnistream.pluginapi.source.MangaSource
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga as ParserManga
import org.koitharu.kotatsu.parsers.model.MangaChapter as ParserChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

class ParserMangaSource private constructor(
    private val parserSource: MangaParserSource,
    existingParser: MangaParser?
) : MangaSource {

    constructor(parser: MangaParser) : this(parser.source, parser)
    constructor(source: MangaParserSource) : this(source, null)

    private val TAG = "MangaBridge[${parserSource.name}]"

    private val parser: MangaParser by lazy {
        existingParser ?: MangaBridge.createParser(parserSource)
            ?: throw IllegalStateException("Failed to create parser for ${parserSource.name}")
    }

    // FIX: Cache miss — replaced SoftReference with strong references + LRU eviction.
    // SoftReference allowed GC to collect entries at any time, causing cache misses that
    // led to lossy reconstruction where id = hashCode (wrong for parsers using numeric IDs).
    // Strong cache with size cap ensures we keep entries alive for active browsing sessions.
    private val mangaCacheLru = ConcurrentHashMap<String, ParserManga>()
    private val chapterCacheLru = ConcurrentHashMap<String, ParserChapter>()

    // Track insertion order for eviction
    private val mangaCacheOrder = ConcurrentLinkedDeque<String>()
    private val chapterCacheOrder = ConcurrentLinkedDeque<String>()
    private val MAX_MANGA_CACHE = 100
    private val MAX_CHAPTER_CACHE = 500

    override val id: String = "megamanga_${parserSource.name.lowercase()}"
    override val name: String = parserSource.title
    override val baseUrl: String get() = "https://${parser.domain}"
    override val lang: String = parserSource.locale ?: "en"
    override val isNsfw: Boolean = try {
        // HENTAI/DOUJINSHI plus the adult-gallery family (IMAGE_SET/ARTIST_CG/
        // GAME_CG). NOTE: some porn sites are mis-typed upstream (Multporn has
        // no type → MANGA; MissKon/Kiutaku are OTHER) — those need re-typing in
        // the upstream-parsers fork; they cannot be detected from the enum here.
        when (parserSource.contentType.name.uppercase()) {
            "HENTAI", "DOUJINSHI", "IMAGE_SET", "ARTIST_CG", "GAME_CG" -> true
            else -> false
        }
    } catch (e: Exception) {
        false
    }

    override val hasSearch: Boolean = true
    override val hasLatest: Boolean = true
    override val opaqueIds: Boolean = true

    override val mainPageSections: List<MainPageSection> = listOf(
        MainPageSection("Popular", "popular"),
        MainPageSection("Latest", "latest")
    )

    /**
     * FIX: Expose parser request headers so the app can use them for cover/image loading.
     * upstream parsers implement Interceptor and have getRequestHeaders() which returns
     * headers like Referer, User-Agent, cookies needed for ALL requests including images.
     */
    fun getRequestHeaders(): Map<String, String> {
        return try {
            val headers = parser.getRequestHeaders()
            val map = mutableMapOf<String, String>()
            for (i in 0 until headers.size) {
                map[headers.name(i)] = headers.value(i)
            }
            // Ensure Referer is always present — many sites reject requests without it
            if (!map.containsKey("Referer")) {
                map["Referer"] = "https://${parser.domain}/"
            }
            map
        } catch (e: Exception) {
            Log.w(TAG, "getRequestHeaders failed: ${e.message}")
            mapOf("Referer" to "https://${parser.domain}/")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> withCfRetry(block: suspend () -> T): T {
        val handler = MangaBridge.cfRetryHandler
        return if (handler != null) {
            handler(baseUrl, block as suspend () -> Any?) as T
        } else {
            block()
        }
    }

    private fun cachePutManga(key: String, value: ParserManga) {
        mangaCacheLru[key] = value
        mangaCacheOrder.addLast(key)
        // Evict oldest if over capacity
        while (mangaCacheLru.size > MAX_MANGA_CACHE) {
            val oldest = mangaCacheOrder.pollFirst() ?: break
            mangaCacheLru.remove(oldest)
        }
    }

    private fun cachePutChapter(key: String, value: ParserChapter) {
        chapterCacheLru[key] = value
        chapterCacheOrder.addLast(key)
        while (chapterCacheLru.size > MAX_CHAPTER_CACHE) {
            val oldest = chapterCacheOrder.pollFirst() ?: break
            chapterCacheLru.remove(oldest)
        }
    }

    private fun cacheParserManga(parser: ParserManga): OmniManga {
        val omni = parser.toOmni(id, baseUrl)
        // Once per manga, not on every scan pass (READONEPIECE logged it 18x per scan)
        if (omni.coverUrl.isNullOrBlank() && noCoverWarned.add(omni.id)) {
            Log.w(TAG, "cacheParserManga NO COVER for '${omni.title}' rawCover='${parser.coverUrl}' baseUrl='$baseUrl'")
        }
        cachePutManga(omni.id, parser)
        return omni
    }

    private val noCoverWarned = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun cacheParserChapter(ch: ParserChapter, mangaId: String): Chapter {
        val omni = Chapter(
            id = ch.id.toString(),
            mangaId = mangaId,
            sourceId = id,
            // FIX: Chapter URLs are relative in upstream. Resolve to absolute for the app.
            url = resolveUrl(ch.url),
            title = ch.title,
            number = ch.number,
            volume = ch.volume.takeIf { it > 0 },
            scanlator = ch.scanlator,
            uploadDate = ch.uploadDate.takeIf { it > 0L }
        )
        cachePutChapter(omni.id, ch)
        return omni
    }

    /**
     * FIX: Cover/page/chapter URL resolution — handles all cases parsers may return:
     * - Already absolute (http:// or https://) -> pass through
     * - Protocol-relative (//cdn.example.com/...) -> prepend https:
     * - Root-relative (/path/to/image) -> prepend baseUrl
     * - Relative (path/to/image) -> prepend baseUrl/
     * - Empty/blank -> return as-is (caller handles null)
     */
    private fun resolveUrl(url: String): String {
        if (url.isBlank()) return url
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$baseUrl$url"
            // Some parsers return relative paths without leading slash
            !url.contains("://") -> "$baseUrl/$url"
            else -> url
        }
    }

    private fun resolveParserManga(omni: OmniManga): ParserManga {
        // FIX: Cache miss handling — try cache first, then reconstruct preserving the
        // original upstream id. The old code used omni.id.hashCode().toLong() which produced
        // a different id than the parser originally assigned. Since we store omni.id = parser.url,
        // we use generateUid(url) which matches upstream's own id generation for URL-based manga.
        mangaCacheLru[omni.id]?.let { return it }
        Log.w(TAG, "resolveParserManga CACHE MISS id='${omni.id}' url='${omni.url}' — reconstructing from OmniManga fields")

        // upstream's Manga.url is relative (no domain). Our omni.id IS the parser url (relative).
        // omni.url is also set to parser.url. Recover the relative url for the parser.
        val relativeUrl = omni.id // We set omni.id = parser.url in toOmni()

        // FIX: Use generateUid matching upstream's MangaSource.generateUid() for consistent IDs.
        // upstream uses url.longHashCode() from its internal extensions. We replicate:
        val reconstructedId = relativeUrl.fold(0L) { acc, c -> acc * 31 + c.code.toLong() }

        return ParserManga(
            id = reconstructedId,
            title = omni.title,
            altTitles = omni.alternativeTitles.toSet(),
            url = relativeUrl,
            // upstream parsers must handle a blank publicUrl (they rebuild it from `url`).
            // Some parsers (Webtoons) use a bare token as `url` (e.g. "5816" = titleNo);
            // gluing that onto baseUrl produced garbage hosts like "webtoons.com5816".
            publicUrl = when {
                relativeUrl.startsWith("http") -> relativeUrl
                relativeUrl.startsWith("/") -> "$baseUrl$relativeUrl"
                else -> ""
            },
            rating = omni.rating ?: -1f,
            contentRating = if (omni.isNsfw) ContentRating.ADULT else ContentRating.SAFE,
            coverUrl = omni.coverUrl ?: "",
            tags = emptySet(),
            state = omni.status.toParserState(),
            authors = setOfNotNull(omni.author),
            largeCoverUrl = null,
            description = omni.description,
            chapters = null,
            source = parserSource
        )
    }

    private fun resolveParserChapter(chapter: Chapter): ParserChapter {
        chapterCacheLru[chapter.id]?.let { return it }
        // FIX: Chapter URL must be relative for the parser. Strip baseUrl if we resolved it earlier.
        val relativeUrl = chapter.url.let { url ->
            if (url.startsWith(baseUrl)) url.removePrefix(baseUrl) else url
        }
        return ParserChapter(
            id = chapter.id.toLongOrNull() ?: chapter.id.fold(0L) { acc, c -> acc * 31 + c.code.toLong() },
            title = chapter.title ?: "",
            number = chapter.number,
            volume = chapter.volume ?: 0,
            url = relativeUrl,
            scanlator = chapter.scanlator ?: "",
            uploadDate = chapter.uploadDate ?: 0L,
            branch = null,
            source = parserSource
        )
    }

    override suspend fun getSection(section: MainPageSection, page: Int): PagedResult<OmniManga> {
        return try {
            withCfRetry {
                val pageSize = 30
                val offset = (page - 1) * pageSize
                val sortOrder = when (section.data) {
                    "latest" -> SortOrder.UPDATED
                    else -> SortOrder.POPULARITY
                }
                val list = MirrorSwitcher.withMirrors(parser) {
                    parser.getList(offset, sortOrder, org.koitharu.kotatsu.parsers.model.MangaListFilter.EMPTY)
                }
                Log.d(TAG, "getSection ${section.data} page=$page → ${list.size} items")
                PagedResult(list.map { cacheParserManga(it) }, hasMore = list.isNotEmpty())
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSection ${section.data} failed: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    override suspend fun search(query: String, page: Int): PagedResult<OmniManga> {
        return try {
            withCfRetry {
                val pageSize = 30
                val offset = (page - 1) * pageSize
                val filter = org.koitharu.kotatsu.parsers.model.MangaListFilter(query = query)
                // availableSortOrders is an EnumSet — iteration order is enum DECLARATION
                // order and UPDATED is declared first, so ".first()" handed UPDATED to
                // parsers that reject UPDATED+query ("Sorting by update with filters is
                // not supported"): bananascan/readcomicsonline could never be searched.
                // Prefer query-safe orders; upstream app itself passes RELEVANCE
                // unconditionally in its seed-search path, so parsers tolerate it.
                val order = when {
                    SortOrder.RELEVANCE in parser.availableSortOrders -> SortOrder.RELEVANCE
                    SortOrder.POPULARITY in parser.availableSortOrders -> SortOrder.POPULARITY
                    else -> parser.availableSortOrders.firstOrNull {
                        it != SortOrder.UPDATED && it != SortOrder.UPDATED_ASC
                    } ?: SortOrder.RELEVANCE
                }
                val list = MirrorSwitcher.withMirrors(parser, emptyIsFailure = false) {
                    parser.getList(offset, order, filter)
                }
                Log.i(
                    "DIAG-Bridge",
                    "search src=$id domain=${parser.domain} query='$query' → ${list.size} item(s)"
                )
                PagedResult(list.map { cacheParserManga(it) }, hasMore = list.isNotEmpty())
            }
        } catch (e: Exception) {
            Log.e(TAG, "search failed: ${e.message}")
            throw e
        }
    }

    // FIX: getDetails was returning the input manga on failure, silently hiding parser errors.
    // Now propagates the exception so the UI can show an error state instead of stale data.
    override suspend fun getDetails(manga: OmniManga): OmniManga {
        Log.d(TAG, "getDetails called with manga.id='${manga.id}' manga.url='${manga.url}'")
        return withCfRetry {
            val parserManga = resolveParserManga(manga)
            Log.d(TAG, "getDetails parser.url='${parserManga.url}'")
            val details = MirrorSwitcher.withMirrors(parser) { parser.getDetails(parserManga) }
            Log.d(TAG, "getDetails returned url='${details.url}' chapters=${details.chapters?.size}")
            val result = cacheParserManga(details)
            Log.d(TAG, "getDetails result.id='${result.id}' result.url='${result.url}'")
            result
        }
    }

    override suspend fun getChapters(manga: OmniManga): List<Chapter> {
        Log.d(TAG, "getChapters called with manga.id='${manga.id}' manga.url='${manga.url}' manga.title='${manga.title}'")
        if (manga.url.isBlank() && manga.id.isBlank()) {
            throw IllegalArgumentException("getChapters called with empty manga id and url")
        }
        val effectiveManga = if (manga.url.isBlank() && manga.id.isNotBlank()) manga.copy(url = manga.id) else manga

        // upstream pattern: parser.getDetails() returns manga WITH chapters.
        // If getDetails() was already called, the result is in mangaCacheLru — reuse it.
        val cached = mangaCacheLru[effectiveManga.id]
        if (cached?.chapters != null) {
            Log.d(TAG, "getChapters BRIDGE CACHE HIT: ${cached.chapters!!.size} chapters")
            return cached.chapters!!.map { ch -> cacheParserChapter(ch, effectiveManga.id) }
        }

        return withCfRetry {
            val parserManga = resolveParserManga(effectiveManga)
            val details = MirrorSwitcher.withMirrors(parser) { parser.getDetails(parserManga) }
            cacheParserManga(details)
            details.chapters?.map { ch -> cacheParserChapter(ch, effectiveManga.id) }
                ?: throw IllegalStateException("Parser returned null chapters for '${manga.title}'")
        }
    }

    // FIX: getPages was returning emptyList() on failure AND using baseUrl as referer
    // instead of the parser's actual request headers. Now propagates errors and uses
    // the proper referer from parser.getRequestHeaders().
    override suspend fun getPages(chapter: Chapter): List<Page> {
        return withCfRetry {
            val parserChapter = resolveParserChapter(chapter)
            val pages = MirrorSwitcher.withMirrors(parser) { parser.getPages(parserChapter) }

            // Get the proper referer from the parser's headers
            val referer = try {
                val headers = parser.getRequestHeaders()
                headers["Referer"] ?: "https://${parser.domain}/"
            } catch (e: Exception) {
                "https://${parser.domain}/"
            }

            pages.mapIndexed { index, page ->
                // FIX: Page URL resolution — getPageUrl returns the direct image URL (absolute).
                // If getPageUrl fails, page.url is a relative URL that needs resolution.
                // Old code fell back to page.url raw, which could be relative and break image loading.
                val imageUrl = try {
                    parser.getPageUrl(page)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // A cancelled reader job must stop the loop, not log a warning per
                    // page (77 lines in one burst) while iterating a dead coroutine
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "getPageUrl failed for page $index, resolving page.url='${page.url}': ${e.message}")
                    resolveUrl(page.url)
                }
                // Resolve the final URL in case getPageUrl returned something unexpected
                Page(index = index, imageUrl = resolveUrl(imageUrl), referer = referer)
            }
        }
    }
}

// ─── Model translation helpers ────────────────────────────────────────────────

/**
 * FIX: Cover URL resolution — now handles ALL URL forms parsers may return.
 * Old code missed: relative paths without leading slash, encoded/malformed URLs.
 * Also now maps altTitles and uses publicUrl for the browser-ready URL.
 */
private fun ParserManga.toOmni(sourceId: String, sourceBaseUrl: String = ""): OmniManga {
    val resolvedCover = coverUrl?.takeIf { it.isNotEmpty() }?.let { cover ->
        when {
            cover.startsWith("http://") || cover.startsWith("https://") -> cover
            cover.startsWith("//") -> "https:$cover"
            cover.startsWith("/") && sourceBaseUrl.isNotEmpty() -> "$sourceBaseUrl$cover"
            // FIX: Handle relative URLs without leading slash (e.g. "images/cover.jpg")
            !cover.contains("://") && sourceBaseUrl.isNotEmpty() -> "$sourceBaseUrl/$cover"
            else -> cover
        }
    }
    return OmniManga(
        // FIX: Use parser.url as id — this is the relative URL which is stable and unique per source.
        // This means resolveParserManga can recover the relative URL from omni.id on cache miss.
        id = url,
        sourceId = sourceId,
        title = title,
        url = url,
        coverUrl = resolvedCover,
        description = description?.replace(Regex("<br\\s*/?>"), "\n")?.replace(Regex("<[^>]*>"), "")?.trim(),
        author = authors.firstOrNull(),
        status = state.toOmniStatus(),
        genres = tags.map { it.title },
        rating = rating.takeIf { it > 0f },
        isNsfw = contentRating == ContentRating.ADULT,
        // FIX: Map alternative titles — previously dropped, now preserved for search/display
        alternativeTitles = altTitles.toList()
    )
}

private fun MangaState?.toOmniStatus(): MangaStatus = when (this) {
    MangaState.ONGOING   -> MangaStatus.ONGOING
    MangaState.FINISHED  -> MangaStatus.COMPLETED
    MangaState.ABANDONED -> MangaStatus.CANCELLED
    MangaState.PAUSED    -> MangaStatus.HIATUS
    else                 -> MangaStatus.UNKNOWN
}

/** Reverse mapping for reconstructing ParserManga from OmniManga on cache miss */
private fun MangaStatus.toParserState(): MangaState? = when (this) {
    MangaStatus.ONGOING   -> MangaState.ONGOING
    MangaStatus.COMPLETED -> MangaState.FINISHED
    MangaStatus.CANCELLED -> MangaState.ABANDONED
    MangaStatus.HIATUS    -> MangaState.PAUSED
    MangaStatus.UNKNOWN   -> null
}
