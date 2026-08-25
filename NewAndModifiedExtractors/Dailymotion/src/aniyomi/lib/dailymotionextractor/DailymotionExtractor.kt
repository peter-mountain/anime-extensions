package aniyomi.lib.dailymotionextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.ExtLog
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class DailymotionExtractor(private val client: OkHttpClient, private val headers: Headers) {

    companion object {
        private const val DAILYMOTION_URL = "https://www.dailymotion.com"
        private const val TAG = "DailymotionExtractor"
    }

    private fun headersBuilder(block: Headers.Builder.() -> Unit = {}) = headers.newBuilder()
        .add("Accept", "*/*")
        .set("Referer", "$DAILYMOTION_URL/")
        .set("Origin", DAILYMOTION_URL)
        .apply { block() }
        .build()

    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, prefix: String = "Dailymotion - "): List<Video> {
        ExtLog.d(TAG, "=== DM START === url=$url")
        val videoId = url.toHttpUrl().run {
            queryParameter("video") ?: pathSegments.lastOrNull()
        } ?: run {
            ExtLog.e(TAG, "Cannot extract video ID from URL")
            return emptyList()
        }
        ExtLog.d(TAG, "videoId=$videoId")

        val jsonUrl = "$DAILYMOTION_URL/player/metadata/video/$videoId?locale=en-US&is_native_app=0"
        ExtLog.d(TAG, "metadata URL: $jsonUrl")
        val parsed = client.newCall(GET(jsonUrl, headersBuilder())).execute().parseAs<DailyQuality>()

        if (parsed.error != null) {
            ExtLog.e(TAG, "API error: ${parsed.error.title ?: parsed.error.message ?: parsed.error.code}")
            return emptyList()
        }

        val masterUrl = parsed.qualities?.auto?.firstOrNull()?.url
        if (masterUrl == null) {
            ExtLog.e(TAG, "No HLS URL in qualities.auto")
            return emptyList()
        }
        ExtLog.d(TAG, "HLS master: ${masterUrl.take(120)}")

        val subtitleList = parsed.subtitles?.data?.map {
            Track(it.urls.first(), it.label)
        } ?: emptyList()
        ExtLog.d(TAG, "subtitles: ${subtitleList.size}")

        val result = playlistUtils.extractFromHls(
            masterUrl,
            masterHeadersGen = { _, _ -> headersBuilder() },
            subtitleList = subtitleList,
            videoNameGen = { "$prefix$it" },
        )
        ExtLog.d(TAG, "=== DM END === returning ${result.size} videos")
        return result
    }
}
