package com.gokberkeren.fragmanizle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class TrailerResult(
    val videoId: String,
    val matchedQuery: String
)

class TrailerSearchEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val cache = ConcurrentHashMap<String, TrailerResult>()

    fun cleanTitle(rawTitle: String): String {
        var t = rawTitle.trim()
        // Strip common Turkish site noise suffixes
        val noisePatterns = listOf(
            Regex("""(?i)\b(türkçe\s*dublaj|turkce\s*dublaj|tr\s*dublaj|altyazılı|altyazili|full\s*hd|1080p|720p|4k|hd\s*izle|film\s*izle|dizi\s*izle|izle)\b"""),
            Regex("""\(\s*(19|20)\d{2}\s*\)"""),
            Regex("""\[\s*.*?\s*]""")
        )
        for (p in noisePatterns) {
            t = p.replace(t, " ")
        }
        return t.replace(Regex("""\s+"""), " ").trim().trim('-', ':', '|')
    }

    suspend fun findTrailer(rawTitle: String, year: String?, turkishFirst: Boolean): TrailerResult? {
        val cleaned = cleanTitle(rawTitle)
        if (cleaned.length < 2) return null

        val cleanYear = year?.trim()?.takeIf { it.matches(Regex("""(19|20)\d{2}""")) }
        val baseTitle = if (cleanYear != null && !cleaned.contains(cleanYear)) {
            "$cleaned $cleanYear"
        } else {
            cleaned
        }

        val cacheKey = "${baseTitle.lowercase(Locale.ROOT)}|tr=$turkishFirst"
        cache[cacheKey]?.let { return it }

        val queries = if (turkishFirst) {
            listOf(
                "$baseTitle Türkçe fragman",
                "$baseTitle official trailer",
                "$cleaned fragman"
            )
        } else {
            listOf(
                "$baseTitle official trailer",
                "$baseTitle Türkçe fragman"
            )
        }

        val found = withContext(Dispatchers.IO) {
            var res: TrailerResult? = null
            for (q in queries) {
                val id = searchYouTubeVideoId(q)
                if (id != null) {
                    res = TrailerResult(id, q)
                    break
                }
            }
            res
        }

        if (found != null) {
            cache[cacheKey] = found
        }
        return found
    }

    private fun searchYouTubeVideoId(query: String): String? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.youtube.com/results?search_query=$encoded&hl=tr"
            val req = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string() ?: return null
                // Match videoRenderer videoIds so we don't pick channel/playlist IDs
                val rendererMatch = VIDEO_RENDERER_REGEX.find(html)
                if (rendererMatch != null) {
                    return rendererMatch.groupValues[1]
                }
                val fallbackMatch = VIDEO_ID_REGEX.find(html)
                fallbackMatch?.groupValues?.get(1)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun clear() {
        cache.clear()
    }

    companion object {
        private val VIDEO_RENDERER_REGEX = Regex(""""videoRenderer"\s*:\s*\{\s*"videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""")
        private val VIDEO_ID_REGEX = Regex(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""")
    }
}
