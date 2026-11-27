package com.gokberkeren.neizlesem

import android.app.Activity
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.AppUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class MediaFormat(val label: String) {
    ANY("Farketmez"),
    MOVIE("🎬 Film"),
    SERIES("📺 Dizi")
}

enum class SourceScope(val label: String) {
    ALL_SITES("Tüm Siteler"),
    CURRENT_SITE("Sadece Açık Site")
}

data class FilterState(
    val format: MediaFormat = MediaFormat.ANY,
    val category: String = CATEGORY_ANY,
    val minImdb: Double = 0.0,
    val scope: SourceScope = SourceScope.ALL_SITES
) {
    companion object {
        const val CATEGORY_ANY = "Farketmez"
        val CATEGORIES = listOf(
            CATEGORY_ANY,
            "Aksiyon",
            "Komedi",
            "Korku",
            "Bilim Kurgu",
            "Gerilim",
            "Dram",
            "Macera",
            "Animasyon",
            "Suç",
            "Romantik",
            "Aile"
        )
        val IMDB_OPTIONS = listOf(
            "Farketmez" to 0.0,
            "6.0+" to 6.0,
            "7.0+" to 7.0,
            "7.5+" to 7.5,
            "8.0+" to 8.0
        )
    }
}

data class PickCandidate(
    val response: SearchResponse,
    val apiName: String,
    val sectionTitle: String,
    val score: Double?,
    val tags: List<String>
)

/**
 * Gathers watchable movie/series candidates from installed CloudStream providers (excluding Live TV & NSFW),
 * filters by format/category/minimum IMDb score, and opens the selected card directly in ResultFragment.
 */
class PoolEngine {

    private data class CachedMeta(val score: Double?, val tags: List<String>)

    private var cachedPool: List<PickCandidate> = emptyList()
    private var cachedPoolAtMs: Long = 0L
    private var cachedScopeKey: String = ""
    private val metaCache = ConcurrentHashMap<String, CachedMeta>()
    private val recentShownUrls = LinkedHashSet<String>()

    fun clear() {
        cachedPool = emptyList()
        cachedPoolAtMs = 0L
        cachedScopeKey = ""
        metaCache.clear()
        recentShownUrls.clear()
    }

    suspend fun drawThree(filter: FilterState, currentApiName: String?): List<PickCandidate> =
        withContext(Dispatchers.IO) {
            val pool = loadPool(filter.scope, currentApiName)
            if (pool.isEmpty()) return@withContext emptyList()

            // 1. Filter by media format (Film / Dizi / Any)
            val byFormat = pool.filter { matchesFormat(it.response.type, filter.format) }
            if (byFormat.isEmpty()) return@withContext emptyList()

            // 2. Prioritize category matches by section title or known tags
            val byCategory = if (filter.category == FilterState.CATEGORY_ANY) {
                byFormat
            } else {
                val direct = byFormat.filter { matchesCategory(it, filter.category) }
                if (direct.size >= 3) direct else byFormat
            }

            // 3. Shuffle & avoid repeating immediately shown items when possible
            val fresh = byCategory.filter { it.response.url !in recentShownUrls }.shuffled()
            val fallback = byCategory.filter { it.response.url in recentShownUrls }.shuffled()
            val ordered = fresh + fallback

            // 4. If IMDb filter or strict category check is active, enrich top candidates via api.load()
            val needsScore = filter.minImdb > 0.0
            val needsCatVerify = filter.category != FilterState.CATEGORY_ANY

            val results = ArrayList<PickCandidate>(3)
            val seenTitles = HashSet<String>()

            if (!needsScore && !needsCatVerify) {
                for (c in ordered) {
                    val norm = c.response.name.trim().lowercase(Locale.ROOT)
                    if (seenTitles.add(norm)) {
                        results.add(c)
                        if (results.size == 3) break
                    }
                }
            } else {
                val batch = ordered.take(24)
                val enriched = coroutineScope {
                    batch.map { cand ->
                        async { enrichCandidate(cand) }
                    }.awaitAll()
                }
                for (c in enriched) {
                    val norm = c.response.name.trim().lowercase(Locale.ROOT)
                    if (norm in seenTitles) continue
                    val scoreOk = !needsScore || (c.score != null && c.score >= filter.minImdb)
                    val catOk = !needsCatVerify || matchesCategory(c, filter.category)
                    if (scoreOk && catOk) {
                        seenTitles.add(norm)
                        results.add(c)
                        if (results.size == 3) break
                    }
                }
                // Relax category slightly if fewer than 3 matched strict score+category, keeping score filter
                if (results.size < 3 && needsScore) {
                    for (c in enriched) {
                        val norm = c.response.name.trim().lowercase(Locale.ROOT)
                        if (norm in seenTitles) continue
                        if (c.score != null && c.score >= filter.minImdb) {
                            seenTitles.add(norm)
                            results.add(c)
                            if (results.size == 3) break
                        }
                    }
                }
            }

            if (results.size == 3) {
                if (recentShownUrls.size > 60) recentShownUrls.clear()
                results.forEach { recentShownUrls.add(it.response.url) }
            }
            results
        }

    fun openCandidate(activity: Activity, candidate: PickCandidate) {
        try {
            // Reflection handles Activity.loadSearchResult(...) across CloudStream versions
            val m = AppUtils::class.java.methods.firstOrNull {
                it.name == "loadSearchResult" && it.parameterTypes.size >= 2 &&
                    SearchResponse::class.java.isAssignableFrom(it.parameterTypes[1])
            }
            if (m != null) {
                val args = Array<Any?>(m.parameterTypes.size) { idx ->
                    when (idx) {
                        0 -> activity
                        1 -> candidate.response
                        2 -> 0
                        else -> null
                    }
                }
                m.invoke(AppUtils, *args)
            }
        } catch (_: Throwable) {
        }
    }

    private suspend fun loadPool(scope: SourceScope, currentApiName: String?): List<PickCandidate> {
        val now = System.currentTimeMillis()
        val scopeKey = "${scope.name}:${currentApiName.orEmpty()}"
        if (cachedPool.isNotEmpty() && cachedScopeKey == scopeKey && (now - cachedPoolAtMs) < CACHE_TTL_MS) {
            return cachedPool
        }

        val allApis = try {
            APIHolder.apis.filter { isEligibleProvider(it) }
        } catch (_: Throwable) {
            emptyList()
        }
        if (allApis.isEmpty()) return emptyList()

        val selectedApis = when (scope) {
            SourceScope.CURRENT_SITE -> {
                val cur = currentApiName?.let { name -> allApis.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                if (cur != null) listOf(cur) else allApis.take(2)
            }
            SourceScope.ALL_SITES -> {
                val trApis = allApis.filter { it.lang.equals("tr", ignoreCase = true) }
                val base = if (trApis.isNotEmpty()) trApis else allApis
                val cur = currentApiName?.let { name -> base.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                val others = base.filter { it !== cur }.shuffled().take(4)
                listOfNotNull(cur) + others
            }
        }

        val collected = coroutineScope {
            selectedApis.map { api ->
                async { fetchFromProvider(api) }
            }.awaitAll().flatten()
        }

        val dedup = LinkedHashMap<String, PickCandidate>()
        for (c in collected) {
            if (c.response.url.isNotBlank() && !dedup.containsKey(c.response.url)) {
                dedup[c.response.url] = c
            }
        }

        val list = dedup.values.toList()
        if (list.isNotEmpty()) {
            cachedPool = list
            cachedPoolAtMs = now
            cachedScopeKey = scopeKey
        }
        return list
    }

    private suspend fun fetchFromProvider(api: MainAPI): List<PickCandidate> {
        val out = ArrayList<PickCandidate>()
        val pages = try { api.mainPage.take(4) } catch (_: Throwable) { emptyList() }
        if (pages.isEmpty()) return out

        for (mp in pages) {
            val resp = withTimeoutOrNull(4_000L) {
                try {
                    val req = MainPageRequest(mp.name, mp.data, mp.horizontalImages)
                    api.getMainPage(1, req)
                } catch (_: Throwable) {
                    null
                }
            } ?: continue

            for (section in resp.items) {
                for (item in section.list) {
                    if (item.type == TvType.Live || item.type == TvType.NSFW) continue
                    val score = extractScore(item)
                    out.add(
                        PickCandidate(
                            response = item,
                            apiName = api.name,
                            sectionTitle = "${mp.name} ${section.name}".trim(),
                            score = score,
                            tags = emptyList()
                        )
                    )
                }
            }
        }
        return out
    }

    private suspend fun enrichCandidate(cand: PickCandidate): PickCandidate {
        val url = cand.response.url
        val cached = metaCache[url]
        if (cached != null) {
            return cand.copy(
                score = cand.score ?: cached.score,
                tags = if (cand.tags.isNotEmpty()) cand.tags else cached.tags
            )
        }

        val api = try {
            APIHolder.getApiFromNameNull(cand.apiName)
        } catch (_: Throwable) {
            null
        } ?: return cand

        val loaded: LoadResponse? = withTimeoutOrNull(2_800L) {
            try {
                api.load(url)
            } catch (_: Throwable) {
                null
            }
        }

        val score = cand.score ?: extractLoadScore(loaded)
        val tags = loaded?.tags?.filter { it.isNotBlank() }.orEmpty()
        metaCache[url] = CachedMeta(score, tags)
        return cand.copy(score = score, tags = tags)
    }

    private fun isEligibleProvider(api: MainAPI): Boolean {
        if (!api.hasMainPage) return false
        val types = try { api.supportedTypes } catch (_: Throwable) { emptySet() }
        if (types.isEmpty()) return false
        val hasVod = types.any {
            it == TvType.Movie || it == TvType.TvSeries || it == TvType.Anime ||
                it == TvType.AnimeMovie || it == TvType.AsianDrama || it == TvType.Cartoon ||
                it == TvType.Documentary
        }
        return hasVod && !types.all { it == TvType.Live || it == TvType.NSFW }
    }

    private fun matchesFormat(type: TvType?, format: MediaFormat): Boolean {
        return when (format) {
            MediaFormat.ANY -> type != TvType.Live && type != TvType.NSFW
            MediaFormat.MOVIE -> type == TvType.Movie || type == TvType.AnimeMovie || type == null
            MediaFormat.SERIES -> type == TvType.TvSeries || type == TvType.Anime ||
                type == TvType.AsianDrama || type == TvType.Cartoon || type == TvType.OVA
        }
    }

    private fun matchesCategory(cand: PickCandidate, category: String): Boolean {
        val keywords = CATEGORY_KEYWORDS[category] ?: listOf(category.lowercase(Locale.ROOT))
        val haystack = (cand.sectionTitle + " " + cand.tags.joinToString(" ")).lowercase(Locale("tr", "TR"))
        return keywords.any { haystack.contains(it) }
    }

    private fun extractScore(resp: SearchResponse): Double? {
        return try {
            val scoreObj = resp.score ?: return null
            val m = scoreObj.javaClass.getMethod("toDouble", Int::class.javaPrimitiveType)
            val v = (m.invoke(scoreObj, 10) as? Double) ?: return null
            if (v in 0.1..10.0) v else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractLoadScore(resp: LoadResponse?): Double? {
        if (resp == null) return null
        return try {
            val scoreObj = resp.score
            if (scoreObj != null) {
                val m = scoreObj.javaClass.getMethod("toDouble", Int::class.javaPrimitiveType)
                val v = (m.invoke(scoreObj, 10) as? Double)
                if (v != null && v in 0.1..10.0) return v
            }
            val ratingMethod = resp.javaClass.methods.firstOrNull {
                it.name == "getRating" && it.parameterTypes.isEmpty()
            }
            val r = ratingMethod?.invoke(resp) as? Int
            if (r != null && r > 0) (r / 1000.0).coerceIn(0.1, 10.0) else null
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val CACHE_TTL_MS = 10L * 60_000L

        private val CATEGORY_KEYWORDS = mapOf(
            "Aksiyon" to listOf("aksiyon", "action", "savaş", "war"),
            "Komedi" to listOf("komedi", "comedy"),
            "Korku" to listOf("korku", "horror"),
            "Bilim Kurgu" to listOf("bilim kurgu", "bilim-kurgu", "sci-fi", "science fiction"),
            "Gerilim" to listOf("gerilim", "thriller", "gizem", "mystery"),
            "Dram" to listOf("dram", "drama"),
            "Macera" to listOf("macera", "adventure", "fantastik", "fantasy"),
            "Animasyon" to listOf("animasyon", "animation", "anime", "çizgi"),
            "Suç" to listOf("suç", "crime", "polisiye"),
            "Romantik" to listOf("romantik", "romance", "aşk"),
            "Aile" to listOf("aile", "family", "çocuk")
        )
    }
}
