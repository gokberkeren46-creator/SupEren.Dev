package com.gokberkeren.introatla

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class IntroSegment(
    val startMs: Long,
    val endMs: Long,
    val source: String
)

data class EpisodeQuery(
    val imdbId: String? = null,
    val malId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null
) {
    fun cacheKey(): String = "${imdbId.orEmpty()}:${malId ?: 0}:${season ?: 0}:${episode ?: 0}"
    fun isValid(): Boolean = (episode != null && episode > 0) && (!imdbId.isNullOrBlank() || (malId != null && malId > 0))
}

class IntroApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Negative cache using EMPTY_SEGMENT so we never re-query the same episode
    private val cache = ConcurrentHashMap<String, IntroSegment>()

    suspend fun fetchIntro(query: EpisodeQuery): IntroSegment? {
        if (!query.isValid()) return null
        val key = query.cacheKey()
        cache[key]?.let { return if (it === EMPTY_SEGMENT) null else it }

        val found = withContext(Dispatchers.IO) {
            fetchFromIntroDb(query) ?: fetchFromAniSkip(query)
        }
        cache[key] = found ?: EMPTY_SEGMENT
        return found
    }

    private fun fetchFromIntroDb(query: EpisodeQuery): IntroSegment? {
        val imdb = query.imdbId?.trim()?.takeIf { it.startsWith("tt") } ?: return null
        val ep = query.episode ?: return null
        val season = query.season ?: 1
        val url = "https://api.introdb.app/intro?imdb_id=$imdb&season=$season&episode=$ep"
        return try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string()?.takeIf { it.isNotBlank() } ?: return null
                val json = JSONObject(body)
                val startSec = json.optDouble("start_sec", -1.0)
                val endSec = json.optDouble("end_sec", -1.0)
                if (startSec >= 0.0 && endSec > startSec + 5.0) {
                    IntroSegment((startSec * 1000.0).toLong(), (endSec * 1000.0).toLong(), "IntroDB")
                } else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun fetchFromAniSkip(query: EpisodeQuery): IntroSegment? {
        val malId = query.malId?.takeIf { it > 0 } ?: return null
        val ep = query.episode ?: return null
        val url = "https://api.aniskip.com/v2/skip-times/$malId/$ep?types=op&episodeLength=0"
        return try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string()?.takeIf { it.isNotBlank() } ?: return null
                val json = JSONObject(body)
                if (!json.optBoolean("found", false)) return null
                val results = json.optJSONArray("results") ?: return null
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val interval = item.optJSONObject("interval") ?: continue
                    val startSec = interval.optDouble("startTime", -1.0)
                    val endSec = interval.optDouble("endTime", -1.0)
                    if (startSec >= 0.0 && endSec > startSec + 5.0) {
                        return IntroSegment((startSec * 1000.0).toLong(), (endSec * 1000.0).toLong(), "AniSkip")
                    }
                }
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun clear() {
        cache.clear()
    }

    companion object {
        private val EMPTY_SEGMENT = IntroSegment(-1L, -1L, "")
    }
}
