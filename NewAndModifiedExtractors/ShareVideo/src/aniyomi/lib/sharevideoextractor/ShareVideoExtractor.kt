package aniyomi.lib.sharevideoextractor

import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.ShindenLog
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject

class ShareVideoExtractor(private val client: OkHttpClient) {
    private val playlistUtils by lazy { PlaylistUtils(client) }
    private val tag = "ShareVideoExtractor"
    private val androidUA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.134 Mobile Safari/537.36"

    fun videosFromUrl(url: String, prefix: String = "", headers: Headers? = null): List<Video> = try {
        videosFromUrlOrThrow(url, prefix, headers)
    } catch (e: Exception) {
        ShindenLog.e(tag, "Failed: ${e.message}", e)
        listOf(Video("about:blank", "${prefix}ShareVideo: failed", "about:blank"))
    }

    private fun videosFromUrlOrThrow(url: String, prefix: String, headers: Headers?): List<Video> {
        val httpUrl = runCatching { url.toHttpUrl() }.getOrNull()
            ?: return listOf(Video("about:blank", "${prefix}ShareVideo: invalid_url", "about:blank"))

        val host = httpUrl.host
        val apiOrigin = "https://$host"

        val videoId = Regex("""/videos/embed/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""/w/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: return listOf(Video("about:blank", "${prefix}ShareVideo: no_id", "about:blank"))

        logDebug("host=$host videoId=$videoId")

        val apiUrl = "$apiOrigin/api/v1/videos/$videoId"
        logDebug("GET $apiUrl")

        val reqHeaders = Headers.Builder()
            .set("User-Agent", androidUA)
            .set("Accept", "application/json")
            .build()

        val resp = runCatching { client.newCall(GET(apiUrl, reqHeaders)).execute() }.getOrNull()
            ?: return listOf(Video("about:blank", "${prefix}ShareVideo: http_err", "about:blank"))

        logDebug("api_http=${resp.code}")
        val body = resp.body?.string() ?: ""
        resp.close()

        if (resp.code != 200 || body.isBlank()) {
            logDebug("api_err: code=${resp.code}")
            return listOf(Video("about:blank", "${prefix}ShareVideo: api_${resp.code}", "about:blank"))
        }

        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return listOf(Video("about:blank", "${prefix}ShareVideo: json_err", "about:blank"))

        val name = json.optString("name", "")
        logDebug("video_name=$name")

        val playlists = json.optJSONArray("streamingPlaylists")
        if (playlists != null && playlists.length() > 0) {
            for (i in 0 until playlists.length()) {
                val pl = playlists.getJSONObject(i)
                val playlistUrl = pl.optString("playlistUrl", "")
                if (playlistUrl.isNotBlank() && playlistUrl.contains(".m3u8")) {
                    logDebug("playlist_url=$playlistUrl")
                    val outHeaders = Headers.Builder()
                        .set("Referer", "$apiOrigin/")
                        .set("Origin", apiOrigin)
                        .set("User-Agent", androidUA)
                        .build()
                    val result = playlistUtils.extractFromHls(
                        playlistUrl,
                        masterHeaders = outHeaders,
                        videoHeaders = outHeaders,
                        videoNameGen = { "${prefix}$it" },
                    )
                    logDebug("hls_tracks=${result.size}")
                    return result.ifEmpty {
                        listOf(Video(playlistUrl, "${prefix}${name.ifBlank { "ShareVideo" }}", playlistUrl, headers = outHeaders))
                    }
                }
            }
        }

        logDebug("no_playlists_found")
        return listOf(Video("about:blank", "${prefix}ShareVideo: no_streams", "about:blank"))
    }

    private fun logDebug(msg: String) {
        ShindenLog.d(tag, msg)
    }
}
