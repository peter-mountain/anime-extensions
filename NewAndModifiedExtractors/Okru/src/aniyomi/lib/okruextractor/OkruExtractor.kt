package aniyomi.lib.okruextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.ShindenLog
import keiyoushi.utils.commonEmptyHeaders
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class OkruExtractor(private val client: OkHttpClient, private val headers: Headers = commonEmptyHeaders) {

    companion object {
        private const val TAG = "OkruExtractor"
    }

    private val playlistUtils by lazy { PlaylistUtils(client) }

    private val videoHeaders = Headers.headersOf(
        "Referer",
        "https://ok.ru/",
        "Origin",
        "https://ok.ru",
        "User-Agent",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
    )

    private fun fixQuality(quality: String): String {
        val qualities = listOf(
            Pair("ultra", "2160p"),
            Pair("quad", "1440p"),
            Pair("full", "1080p"),
            Pair("hd", "720p"),
            Pair("sd", "480p"),
            Pair("low", "360p"),
            Pair("lowest", "240p"),
            Pair("mobile", "144p"),
        )
        return qualities.find { it.first == quality }?.second ?: quality
    }

    suspend fun videosFromUrl(url: String, prefix: String = "", fixQualities: Boolean = true): List<Video> {
        ShindenLog.d(TAG, "fetching: $url")
        val document = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()
        val videoString = document.selectFirst("div[data-options]")
            ?.attr("data-options")
        if (videoString == null) {
            ShindenLog.w(TAG, "no data-options found for $url")
            return emptyList<Video>()
        }
        ShindenLog.d(TAG, "data-options length=${videoString.length}")

        val result = when {
            "ondemandHls" in videoString -> {
                ShindenLog.d(TAG, "found ondemandHls")
                val playlistUrl = videoString.extractLink("ondemandHls")
                ShindenLog.d(TAG, "hls playlist: $playlistUrl")
                playlistUtils.extractFromHls(playlistUrl, videoNameGen = { "Okru:$it".addPrefix(prefix) })
            }

            "ondemandDash" in videoString -> {
                ShindenLog.d(TAG, "found ondemandDash")
                val playlistUrl = videoString.extractLink("ondemandDash")
                ShindenLog.d(TAG, "dash playlist: $playlistUrl")
                playlistUtils.extractFromDash(playlistUrl, videoNameGen = { "Okru:$it".addPrefix(prefix) })
            }

            else -> {
                ShindenLog.d(TAG, "falling back to JSON parsing")
                videosFromJson(videoString, prefix, fixQualities)
            }
        }
        ShindenLog.d(TAG, "returning ${result.size} videos")
        return result
    }

    private fun String.addPrefix(prefix: String) = prefix.takeIf(String::isNotBlank)
        ?.let { "$prefix $this" }
        ?: this

    private fun String.extractLink(attr: String) = substringAfter("$attr\\\":\\\"")
        .substringBefore("\\\"")
        .replace("\\\\u0026", "&")

    private fun videosFromJson(videoString: String, prefix: String = "", fixQualities: Boolean = true): List<Video> {
        val arrayData = videoString.substringAfter("\\\"videos\\\":[{\\\"name\\\":\\\"")
            .substringBefore("]")

        return arrayData.split("{\\\"name\\\":\\\"").reversed().mapNotNull { data ->
            val videoUrl = data.extractLink("url")
            val quality = data.substringBefore("\\\"").let {
                if (fixQualities) fixQuality(it) else it
            }
            val videoQuality = "Okru:$quality".addPrefix(prefix)
            ShindenLog.d(TAG, "quality=$quality url=$videoUrl")

            if (videoUrl.startsWith("https://")) {
                Video(videoUrl, videoQuality, videoUrl, videoHeaders)
            } else {
                ShindenLog.w(TAG, "skipping non-https url: $videoUrl")
                null
            }
        }
    }
}
