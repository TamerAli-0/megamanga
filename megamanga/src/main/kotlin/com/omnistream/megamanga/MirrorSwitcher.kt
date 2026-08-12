package com.omnistream.megamanga

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import org.koitharu.kotatsu.parsers.util.await
import java.util.Collections

/**
 * Silent source-domain healing (port of upstream's core/parser/MirrorSwitcher).
 *
 * When a parser call fails (or returns an empty list) for what looks like a domain problem,
 * this: (1) HEADs the current domain and follows the site's own redirect to DISCOVER where
 * it moved, (2) falls back to walking the parser's compiled-in alternate domains, retrying
 * the original call on each; whichever domain works is persisted via [SourceConfigStore]
 * so the fix survives restarts. Total failure rolls the domain back and blacklists the
 * source for this process so a dead site is only probed once.
 *
 * NOT treated as domain problems (no switching, error propagates for its own handler):
 * CloudFlare/Turnstile challenges, auth-required, and 429 rate limits.
 *
 * Improvement over upstream: redirect discovery also runs for single-domain parsers
 * (upstream bails when a parser has <=1 preset mirrors — which is exactly the AquaManga
 * "site moved TLD, one preset domain" case that had to be fixed by hand in June).
 */
object MirrorSwitcher {

    private const val TAG = "MirrorSwitcher"

    private val blacklist = Collections.synchronizedSet(HashSet<String>())
    private val mutex = Mutex()

    private fun report(message: String) {
        Log.i(TAG, message)
        MangaBridge.mechanismLogger?.invoke(TAG, message)
    }

    /** Diagnostics: sources given up on this session (their one probe cycle failed). */
    fun blacklistedSources(): Set<String> = blacklist.toSet()

    /** Diagnostics: re-arm switching for every blacklisted source without an app restart. */
    fun clearBlacklist() {
        if (blacklist.isNotEmpty()) {
            report("blacklist cleared manually (${blacklist.size} source(s) re-armed)")
            blacklist.clear()
        }
    }

    suspend fun <T : Any> withMirrors(
        parser: MangaParser,
        emptyIsFailure: Boolean = true,
        block: suspend () -> T
    ): T {
        val store = MangaBridge.getContext()?.sourceConfigStore ?: return block()
        val initial = try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
        if (initial.isValidResult(emptyIsFailure)) {
            return initial.getOrThrow()
        }
        val switched = trySwitchMirror(parser, store, emptyIsFailure, block)
        return switched ?: initial.getOrThrow()
    }

    private suspend fun <T : Any> trySwitchMirror(
        parser: MangaParser,
        store: SourceConfigStore,
        emptyIsFailure: Boolean,
        block: suspend () -> T
    ): T? {
        val sourceName = parser.source.name
        if (sourceName in blacklist) return null
        // Global mutex (upstream-identical): mirror probing is rare, serializing it keeps a
        // burst of failures on one dead source from hammering every mirror in parallel.
        mutex.withLock {
            if (sourceName in blacklist) return null
            val originalOverride = store.getDomainOverride(parser.source)
            val currentDomain = parser.domain

            suspend fun probe(domain: String): T? {
                store.setDomainOverride(parser.source, domain)
                return try {
                    block().takeIfValid(emptyIsFailure).also { result ->
                        if (result == null) report("$sourceName: probe $domain -> empty result")
                    }
                } catch (e: CancellationException) {
                    store.setDomainOverride(parser.source, originalOverride)
                    throw e
                } catch (e: Exception) {
                    // A CF challenge / auth wall / 429 from the probed domain is PROOF OF
                    // LIFE, not death — the site answered. Keep this domain persisted and
                    // rethrow so the caller's own CF/auth pipeline resolves it there.
                    // (upstream swallows these and wrongly declares the mirror dead —
                    // found on device 2026-07-21 with MangaDex/AquaManga probes.)
                    if (e.isNonDomainFailure()) {
                        report("$sourceName: probe $domain answered with ${e.javaClass.simpleName} — domain is alive, keeping it")
                        throw e
                    }
                    report("$sourceName: probe $domain failed: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
                    null
                }
            }

            // 1. Redirect discovery — catches moves to domains no preset knows about.
            //    BRAND GUARD: a dead domain bought by a stranger redirects wherever the
            //    stranger wants (parked pages, casinos, same-engine clone sites). A
            //    discovered domain is only auto-accepted when it shares a brand token
            //    with the source's known domains (aquareader.net -> aquareader.org
            //    passes; deadsite.com -> luckyspin.casino never). Rejected discoveries
            //    are reported, not silently applied — upstream has no such guard.
            val discovered = findRedirect(currentDomain)
            Log.e("DIAG-Mirror", "$sourceName: redirect discovery on $currentDomain → ${discovered ?: "none"}")
            discovered?.let { redirect ->
                if (isTrustedRedirect(redirect, currentDomain, parser.configKeyDomain.presetValues)) {
                    probe(redirect)?.let {
                        report("$sourceName: domain moved, following redirect $currentDomain -> $redirect (persisted)")
                        return it
                    }
                } else {
                    report("$sourceName: redirect $currentDomain -> $redirect REJECTED (brand mismatch, not applied)")
                }
            }
            // 2. Compiled-in alternate domains.
            for (mirror in parser.configKeyDomain.presetValues) {
                if (mirror == currentDomain) continue
                Log.e("DIAG-Mirror", "$sourceName: probing preset mirror $mirror")
                probe(mirror)?.let {
                    report("$sourceName: switched to mirror $mirror (persisted)")
                    return it
                }
            }
            // 3. Nothing worked — roll back, blacklist for this process.
            store.setDomainOverride(parser.source, originalOverride)
            blacklist.add(sourceName)
            report("$sourceName: no working mirror found, blacklisted for this session")
            Log.e(
                "DIAG-Mirror",
                "$sourceName: BLACKLISTED. domain=$currentDomain rolledBackTo=$originalOverride " +
                    "presetsTried=${parser.configKeyDomain.presetValues.filterNot { it == currentDomain }} " +
                    "blacklistSize=${blacklist.size} all=${blacklist.toSortedSet()}"
            )
            return null
        }
    }

    /**
     * Brand guard for redirect-discovered domains: accepted only when a significant
     * label of the target overlaps a significant label of the source's known domains
     * (current + compiled-in presets). "Significant" = dot-separated label of 4+ chars,
     * which drops www/TLDs; containment handles minor renames (aquareader vs
     * aquareader-backup). Preset mirrors bypass this entirely — maintainers vouch for
     * those by hand.
     */
    private fun isTrustedRedirect(
        target: String,
        currentDomain: String,
        presets: Array<out String>
    ): Boolean {
        val trusted = (presets.toList() + currentDomain).flatMap { it.brandLabels() }.toSet()
        return target.brandLabels().any { candidate ->
            trusted.any { known ->
                candidate == known || candidate.contains(known) || known.contains(candidate)
            }
        }
    }

    private fun String.brandLabels(): List<String> = lowercase()
        .removePrefix("www.")
        .split('.')
        .filter { it.length >= 4 }

    /** Follows the current domain's own redirect (HEAD /) to find where the site moved. */
    private suspend fun findRedirect(currentHost: String): String? {
        val client = MangaBridge.getContext()?.httpClient ?: return null
        return try {
            client.newCall(
                Request.Builder().url("https://$currentHost/").head().build()
            ).await().use { response ->
                if (response.isSuccessful) {
                    response.request.url.host.takeIf { it != currentHost }
                } else {
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun <T> Result<T>.isValidResult(emptyIsFailure: Boolean): Boolean = fold(
        onSuccess = { it.isValidValue(emptyIsFailure) },
        onFailure = { e -> e.isNonDomainFailure() }
    )

    private fun <T : Any> T.takeIfValid(emptyIsFailure: Boolean): T? =
        if (isValidValue(emptyIsFailure)) this else null

    private fun Any?.isValidValue(emptyIsFailure: Boolean): Boolean = when (this) {
        null -> false
        is Collection<*> -> !emptyIsFailure || isNotEmpty()
        else -> true
    }

    private fun Throwable.isNonDomainFailure(): Boolean {
        if (this is AuthRequiredException ||
            this is TooManyRequestExceptions ||
            this is com.omnistream.pluginapi.net.RateLimitException
        ) return true
        val name = javaClass.simpleName
        if (name.contains("CloudFlare", ignoreCase = true) ||
            name.contains("Turnstile", ignoreCase = true)
        ) {
            return true
        }
        val cause = cause
        return cause != null && cause !== this && cause.isNonDomainFailure()
    }
}
