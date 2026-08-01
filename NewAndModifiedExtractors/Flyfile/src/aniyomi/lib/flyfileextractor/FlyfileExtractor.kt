package aniyomi.lib.flyfileextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.ShindenLog
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class FlyfileExtractor(private val client: OkHttpClient) {
    private val playlistUtils by lazy { PlaylistUtils(client) }
    private val tag = "FlyfileExtractor"
    private val androidUA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.134 Mobile Safari/537.36"

    fun videosFromUrl(url: String, prefix: String = "", headers: Headers? = null): List<Video> = try {
        videosFromUrlOrThrow(url, prefix, headers)
    } catch (e: Exception) {
        ShindenLog.e(tag, "Failed: ${e.message}", e)
        listOf(Video("about:blank", "${prefix}Flyfile: failed", "about:blank"))
    }

    private fun videosFromUrlOrThrow(url: String, prefix: String, headers: Headers?): List<Video> {
        val httpUrl = runCatching { url.toHttpUrl() }.getOrNull()
            ?: return listOf(Video("about:blank", "${prefix}Flyfile: invalid_url", "about:blank"))

        val host = httpUrl.host
        val apiHost = resolveApiHost(host)
        val mediaId = Regex("""/embed/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""/([A-Za-z0-9_-]+)$""").find(url)?.groupValues?.get(1)
            ?: return listOf(Video("about:blank", "${prefix}Flyfile: no_id", "about:blank"))

        logDebug("host=$host apiHost=$apiHost mediaId=$mediaId")

        val direct = tryStreamingAssign(apiHost, mediaId, host, headers)
        if (direct != null) return direct

        logDebug("direct_failed, trying_webview")
        val wvResult = FlyfileWebViewResolver(client).resolve(url, androidUA)
        if (wvResult != null) {
            logDebug("wv_result_len=${wvResult.length}")
            val data = runCatching { org.json.JSONObject(wvResult) }.getOrNull()
                ?: return listOf(Video("about:blank", "${prefix}Flyfile: wv_parse_err", "about:blank"))
            val hlsUrl = pickHlsUrl(data)
            if (!hlsUrl.isNullOrBlank()) return extractHls(hlsUrl, "https://$host", prefix)
        }

        return listOf(Video("about:blank", "${prefix}Flyfile: no_stream", "about:blank"))
    }

    /**
     * Resolve API host. flyf.lat aliases to flyfile.app but api.flyf.lat doesn't exist.
     */
    private fun resolveApiHost(embedHost: String): String = when {
        embedHost.contains("flyf.lat") -> "api.flyfile.app"
        embedHost.contains("flyfile.app") -> "api.flyfile.app"
        else -> "api.$embedHost"
    }

    private fun tryStreamingAssign(apiHost: String, mediaId: String, embedHost: String, headers: Headers?): List<Video>? {
        val apiOrigin = "https://$apiHost"
        val assignUrl = "https://$apiHost/api/streaming/assign/$mediaId"

        val reqHeaders = Headers.Builder()
            .set("Referer", "https://$embedHost/embed/$mediaId")
            .set("Origin", apiOrigin)
            .set("User-Agent", androidUA)
            .set("Accept", "application/json, text/plain, */*")
            .set("Accept-Language", "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7")
            .set("Sec-Fetch-Dest", "empty")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Site", "same-origin")
            .build()

        logDebug("GET $assignUrl")
        val resp = runCatching { client.newCall(GET(assignUrl, reqHeaders)).execute() }.getOrNull()
            ?: return null
        logDebug("assign_http=${resp.code}")
        val body = resp.body?.string() ?: ""
        logDebug("assign_body=${body.take(500)}")
        resp.close()

        if (resp.code != 200 || body.isBlank()) return null

        val json = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return null
        val streamUrl = json.optString("url", "")
        val token = json.optString("token", "")
        if (streamUrl.isBlank() || token.isBlank()) {
            logDebug("assign_missing url=$streamUrl token_len=${token.length}")
            return null
        }

        val hlsUrl = "$streamUrl/hls/$token/master.m3u8"
        logDebug("HLS URL=$hlsUrl")
        return extractHls(hlsUrl, apiOrigin, prefix = "")
    }

    private fun pickHlsUrl(data: org.json.JSONObject): String? {
        if (data.has("sources")) {
            val srcs = data.getJSONArray("sources")
            if (srcs.length() > 0) {
                for (i in 0 until srcs.length()) {
                    val s = srcs.getJSONObject(i)
                    val src = s.optString("src", "")
                    if (src.isNotBlank() && src.contains(".m3u8")) return src
                }
            }
        }
        if (data.has("hls")) return data.optString("hls", null)
        if (data.has("url")) {
            val u = data.optString("url", "")
            if (u.contains(".m3u8")) return u
        }
        return null
    }

    private fun extractHls(hlsUrl: String, origin: String, prefix: String): List<Video> {
        val outHeaders = Headers.Builder()
            .set("Referer", "https://flyfile.app/")
            .set("Origin", origin)
            .set("Accept", "*/*")
            .set("User-Agent", androidUA)
            .build()
        val result = playlistUtils.extractFromHls(
            hlsUrl,
            masterHeaders = outHeaders,
            videoHeaders = outHeaders,
            videoNameGen = { "$prefix$it" },
        )
        logDebug("hls_tracks=${result.size}")
        return result.ifEmpty { listOf(Video(hlsUrl, "${prefix}Flyfile HLS", hlsUrl, headers = outHeaders)) }
    }

    private fun logDebug(msg: String) {
        ShindenLog.d(tag, msg)
    }
}
