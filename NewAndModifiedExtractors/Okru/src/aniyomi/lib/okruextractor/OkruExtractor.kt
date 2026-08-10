package aniyomi.lib.okruextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.ExtLog
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
        ExtLog.d(TAG, "=== videosFromUrl START url=$url prefix=$prefix ===")
        val request = GET(url, headers)
        ExtLog.d(TAG, "request: ${request.method} ${request.url}")
        ExtLog.d(TAG, "request headers: ${request.headers}")

        val response = client.newCall(request).awaitSuccess()
        ExtLog.d(TAG, "response: http=${response.code} content-type=${response.header("Content-Type")} content-length=${response.header("Content-Length")}")
        ExtLog.d(TAG, "response headers: ${response.headers}")

        val document = response.useAsJsoup()
        ExtLog.d(TAG, "page title: ${document.title()}")

        val dataOptionsEl = document.selectFirst("div[data-options]")
        if (dataOptionsEl == null) {
            ExtLog.w(TAG, "NO div[data-options] found on page")
            ExtLog.d(TAG, "page body snippet (first 2000 chars): ${document.body().toString().take(2000)}")
            return emptyList<Video>()
        }

        val videoString = dataOptionsEl.attr("data-options")
        ExtLog.d(TAG, "data-options length=${videoString.length}")
        ExtLog.d(TAG, "data-options snippet (first 500): ${videoString.take(500)}")

        val hasHls = "ondemandHls" in videoString
        val hasDash = "ondemandDash" in videoString
        val hasVideos = videoString.contains("\\\"videos\\\":[{\\\"name")
        ExtLog.d(TAG, "hasOndemandHls=$hasHls hasOndemandDash=$hasDash hasVideos=$hasVideos")

        val result = when {
            hasVideos -> {
                ExtLog.d(TAG, "extracting direct MP4 (videos array)")
                videosFromJson(videoString, prefix, fixQualities)
            }

            hasDash -> {
                ExtLog.d(TAG, "extracting DASH")
                val playlistUrl = videoString.extractLink("ondemandDash")
                ExtLog.d(TAG, "dash playlist url: $playlistUrl")
                playlistUtils.extractFromDash(playlistUrl, videoNameGen = { "Okru:$it".addPrefix(prefix) })
            }

            hasHls -> {
                ExtLog.d(TAG, "extracting HLS")
                val playlistUrl = videoString.extractLink("ondemandHls")
                ExtLog.d(TAG, "hls playlist url: $playlistUrl")
                playlistUtils.extractFromHls(playlistUrl, videoNameGen = { "Okru:$it".addPrefix(prefix) })
            }

            else -> {
                ExtLog.d(TAG, "no videos/HLS/DASH found")
                emptyList()
            }
        }
        ExtLog.d(TAG, "=== videosFromUrl END returning ${result.size} videos ===")
        return result
    }

    private fun String.addPrefix(prefix: String) = prefix.takeIf(String::isNotBlank)
        ?.let { "$prefix $this" }
        ?: this

    private fun String.extractLink(attr: String) = substringAfter("$attr\\\":\\\"")
        .substringBefore("\\\"")
        .replace("\\\\u0026", "&")

    private fun videosFromJson(videoString: String, prefix: String = "", fixQualities: Boolean = true): List<Video> {
        ExtLog.d(TAG, "videosFromJson: parsing fallback")
        val arrayData = videoString.substringAfter("\\\"videos\\\":[{\\\"name\\\":\\\"")
            .substringBefore("]")
        ExtLog.d(TAG, "videosFromJson: arrayData length=${arrayData.length}")

        return arrayData.split("{\\\"name\\\":\\\"").reversed().mapNotNull { data ->
            val videoUrl = data.extractLink("url")
            val quality = data.substringBefore("\\\"").let {
                if (fixQualities) fixQuality(it) else it
            }
            val videoQuality = "Okru:$quality".addPrefix(prefix)
            ExtLog.d(TAG, "videosFromJson: quality=$quality url=$videoUrl")

            if (videoUrl.startsWith("https://")) {
                Video(videoUrl, videoQuality, videoUrl, videoHeaders)
            } else {
                ExtLog.w(TAG, "videosFromJson: skipping non-https url: $videoUrl")
                null
            }
        }
    }
}
