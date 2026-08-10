package aniyomi.lib.aparatextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

class AparatExtractor(private val client: OkHttpClient) {

    private val apiHeaders = Headers.headersOf(
        "Accept",
        "application/json, text/plain, */*",
        "Referer",
        "https://www.aparat.com/",
    )

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val videoHash = extractVideoHash(url) ?: return emptyList()
        val body = runCatching {
            client.newCall(GET("$API_SHOW$videoHash", apiHeaders)).awaitSuccess().bodyString()
        }.getOrNull() ?: return emptyList()

        val attributes = runCatching {
            JSONObject(body).getJSONObject("data").getJSONObject("attributes")
        }.getOrNull() ?: return emptyList()

        val videos = mutableListOf<Video>()

        // Full quality ladder from the API (e.g. 144p, 240p, 360p, 480p, 720p, 1080p)
        runCatching {
            val fileLinkAll = attributes.optString("file_link_all")
            if (fileLinkAll.isNotBlank()) {
                val items = JSONArray(fileLinkAll)
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val profile = item.optString("profile")
                    val urls = item.optJSONArray("urls") ?: continue
                    val directUrl = urls.optString(0)
                    if (directUrl.isNotBlank()) {
                        videos.add(Video(directUrl, "${prefix}Aparat - $profile", directUrl))
                    }
                }
            }
        }

        // HLS adaptive stream, used when the API exposes no direct mp4 list
        if (videos.isEmpty()) {
            val hlsLink = attributes.optString("hls_link")
            if (hlsLink.isNotBlank()) {
                videos.add(Video(hlsLink, "${prefix}Aparat - HLS", hlsLink))
            }
        }

        // Single default quality fallback
        if (videos.isEmpty()) {
            val fileLink = attributes.optString("file_link")
            if (fileLink.isNotBlank()) {
                videos.add(Video(fileLink, "${prefix}Aparat", fileLink))
            }
        }

        return videos.sortedByDescending { it.quality.substringAfterLast('-').trim().removeSuffix("p").toIntOrNull() ?: 0 }
    }

    private fun extractVideoHash(url: String): String? {
        val patterns = listOf(
            "videohash/([A-Za-z0-9]+)".toRegex(),
            "/v/([A-Za-z0-9]+)".toRegex(),
        )
        for (regex in patterns) {
            regex.find(url)?.let { return it.groupValues[1] }
        }
        return null
    }

    companion object {
        private const val API_SHOW = "https://www.aparat.com/api/fa/v1/video/video/show/videohash/"
    }
}
