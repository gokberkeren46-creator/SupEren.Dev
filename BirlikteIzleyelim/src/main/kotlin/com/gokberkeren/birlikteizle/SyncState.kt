package com.gokberkeren.birlikteizle

import org.json.JSONObject

/**
 * Shared playback state stored under /rooms/{code}/state.
 */
data class PlaybackState(
    val playing: Boolean = false,
    val position: Long = 0L,
    val updatedAt: Long = 0L,
    val by: String = "",
    val seq: Long = 0L
) {
    fun toJson(useServerTimestamp: Boolean = true): JSONObject {
        return JSONObject().apply {
            put("playing", playing)
            put("position", position)
            if (useServerTimestamp) {
                put("updatedAt", JSONObject().put(".sv", "timestamp"))
            } else {
                put("updatedAt", updatedAt)
            }
            put("by", by)
            put("seq", seq)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject?): PlaybackState? {
            if (obj == null) return null
            return PlaybackState(
                playing = obj.optBoolean("playing", false),
                position = obj.optLong("position", 0L),
                updatedAt = obj.optLong("updatedAt", 0L),
                by = obj.optString("by", ""),
                seq = obj.optLong("seq", 0L)
            )
        }
    }
}

/**
 * Currently selected media metadata stored under /rooms/{code}/media.
 */
data class MediaInfo(
    val url: String = "",
    val apiName: String = "",
    val name: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val episodeId: Int? = null,
    val by: String = "",
    val updatedAt: Long = 0L
) {
    fun uniqueKey(): String = "$url|$apiName|${season ?: -1}|${episode ?: -1}|${episodeId ?: -1}"

    /**
     * Normalized match key resilient to minor URL differences between ResultFragment and GeneratorPlayer.
     */
    fun matchKey(): String {
        val normApi = apiName.trim().lowercase()
        val normName = name.trim().lowercase()
        return "$normApi|$normName|${season ?: -1}|${episode ?: -1}"
    }

    fun displayTitle(): String {
        val base = name.ifBlank { "İçerik" }
        return when {
            season != null && episode != null && episode > 0 -> "$base (S${season}B${episode})"
            episode != null && episode > 0 -> "$base (Bölüm $episode)"
            else -> base
        }
    }

    fun toJson(useServerTimestamp: Boolean = true): JSONObject {
        return JSONObject().apply {
            put("url", url)
            put("apiName", apiName)
            put("name", name)
            if (season != null) put("season", season) else put("season", JSONObject.NULL)
            if (episode != null) put("episode", episode) else put("episode", JSONObject.NULL)
            if (episodeId != null) put("episodeId", episodeId) else put("episodeId", JSONObject.NULL)
            put("by", by)
            if (useServerTimestamp) {
                put("updatedAt", JSONObject().put(".sv", "timestamp"))
            } else {
                put("updatedAt", updatedAt)
            }
        }
    }

    companion object {
        fun fromJson(obj: JSONObject?): MediaInfo? {
            if (obj == null) return null
            val url = obj.optString("url", "")
            val apiName = obj.optString("apiName", "")
            if (url.isBlank() || apiName.isBlank()) return null
            val season = if (obj.isNull("season")) null else obj.optInt("season").takeIf { it > 0 }
            val episode = if (obj.isNull("episode")) null else obj.optInt("episode").takeIf { it > 0 }
            val episodeId = if (obj.isNull("episodeId") || !obj.has("episodeId")) null else obj.optInt("episodeId")
            return MediaInfo(
                url = url,
                apiName = apiName,
                name = obj.optString("name", ""),
                season = season,
                episode = episode,
                episodeId = episodeId,
                by = obj.optString("by", ""),
                updatedAt = obj.optLong("updatedAt", 0L)
            )
        }
    }
}

/**
 * Live floating emoji reaction stored under /rooms/{code}/reaction.
 */
data class EmojiReaction(
    val id: String = "",
    val emoji: String = "",
    val by: String = "",
    val senderName: String = "",
    val ts: Long = 0L
) {
    fun toJson(useServerTimestamp: Boolean = true): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("emoji", emoji)
            put("by", by)
            put("senderName", senderName)
            if (useServerTimestamp) {
                put("ts", JSONObject().put(".sv", "timestamp"))
            } else {
                put("ts", ts)
            }
        }
    }

    companion object {
        fun fromJson(obj: JSONObject?): EmojiReaction? {
            if (obj == null) return null
            val id = obj.optString("id", "")
            val emoji = obj.optString("emoji", "")
            if (id.isBlank() || emoji.isBlank()) return null
            return EmojiReaction(
                id = id,
                emoji = emoji,
                by = obj.optString("by", ""),
                senderName = obj.optString("senderName", "").ifBlank { "Katılımcı" },
                ts = obj.optLong("ts", 0L)
            )
        }
    }
}

data class RoomMember(
    val clientId: String,
    val name: String = "Misafir",
    val ts: Long = 0L
) {
    companion object {
        fun fromJson(clientId: String, obj: JSONObject?): RoomMember? {
            if (obj == null) return null
            return RoomMember(
                clientId = clientId,
                name = obj.optString("name", "Misafir").ifBlank { "Misafir" },
                ts = obj.optLong("ts", 0L)
            )
        }
    }
}

data class RoomSnapshot(
    val code: String = "",
    val createdAt: Long = 0L,
    val lastActive: Long = 0L,
    val state: PlaybackState? = null,
    val media: MediaInfo? = null,
    val reaction: EmojiReaction? = null,
    val members: Map<String, RoomMember> = emptyMap()
) {
    companion object {
        fun serverTimestampJson(): JSONObject = JSONObject().put(".sv", "timestamp")

        fun fromJson(code: String, obj: JSONObject?): RoomSnapshot? {
            if (obj == null) return null
            val membersObj = obj.optJSONObject("members")
            val membersMap = mutableMapOf<String, RoomMember>()
            if (membersObj != null) {
                val keys = membersObj.keys()
                while (keys.hasNext()) {
                    val cid = keys.next()
                    RoomMember.fromJson(cid, membersObj.optJSONObject(cid))?.let {
                        membersMap[cid] = it
                    }
                }
            }
            return RoomSnapshot(
                code = code,
                createdAt = obj.optLong("createdAt", 0L),
                lastActive = obj.optLong("lastActive", 0L),
                state = PlaybackState.fromJson(obj.optJSONObject("state")),
                media = MediaInfo.fromJson(obj.optJSONObject("media")),
                reaction = EmojiReaction.fromJson(obj.optJSONObject("reaction")),
                members = membersMap
            )
        }
    }
}
