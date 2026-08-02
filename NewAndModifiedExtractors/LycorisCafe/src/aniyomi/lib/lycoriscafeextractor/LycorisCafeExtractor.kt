package aniyomi.lib.lycoriscafeextractor

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.ExtLog
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject

class LycorisCafeExtractor(private val client: OkHttpClient) {

    private val tag = "LycorisCafeExtractor"

    private companion object {
        const val GETLNKURL = "https://www.lycoris.cafe/api/watch/getVideoLink"
        const val DECRYPTURL = "https://www.lycoris.cafe/api/watch/decryptVideoLink"
        const val DECRYPT_API_KEY = "303a897d-sd12-41a8-84d1-5e4f5e208878"
    }

    fun getVideosFromUrl(url: String, headers: Headers, prefix: String): List<Video> {
        val videos = mutableListOf<Video>()

        try {
            val document = client.newCall(GET(url, headers = headers)).execute().useAsJsoup()

            val script = document.selectFirst("script[type='application/json']")?.data()
                ?: return emptyList()

            val wrapper = script.parseAs<SvelteKitWrapper>()
            val episode = wrapper.body.parseAs<ScriptEpisode>()

            // Try direct links from primarySource first (no API needed)
            val primary = episode.episodeInfo.primarySource
            if (primary != null) {
                ExtLog.d(tag, "Found primarySource, using direct links")
                primary.FHD?.let { videos.add(Video(it, "${prefix}lycoris.cafe - 1080p", it)) }
                primary.HD?.let { videos.add(Video(it, "${prefix}lycoris.cafe - 720p", it)) }
                primary.SD?.let { videos.add(Video(it, "${prefix}lycoris.cafe - 480p", it)) }
                if (videos.isNotEmpty()) return videos
            }

            // Fallback: decrypt API
            ExtLog.d(tag, "No primarySource, using decrypt API for episodeId=${episode.episodeInfo.id}")
            val linkList = fetchAndDecodeVideo(episode.episodeInfo.id.toString())

            linkList.FHD?.let { videos.add(Video(it, "${prefix}lycoris.cafe - 1080p", it)) }
            linkList.HD?.let { videos.add(Video(it, "${prefix}lycoris.cafe - 720p", it)) }
            linkList.SD?.let { videos.add(Video(it, "${prefix}lycoris.cafe - 480p", it)) }
            linkList.Source?.let { videos.add(Video(it, "${prefix}lycoris.cafe - Source", it)) }
            linkList.SourceMKV?.let { videos.add(Video(it, "${prefix}lycoris.cafe - SourceMKV", it)) }
        } catch (e: Exception) {
            ExtLog.e(tag, "Error: ${e.message}", e)
        }

        return videos
    }

    private fun fetchAndDecodeVideo(episodeId: String): VideoLinksApi {
        val url = GETLNKURL.toHttpUrl().newBuilder()
            .addQueryParameter("id", episodeId)
            .build()
        val encryptedText = client.newCall(GET(url)).execute().bodyString()

        val textByte = encryptedText.toByteArray(Charsets.ISO_8859_1)
        val base64Data = Base64.encodeToString(textByte, Base64.DEFAULT)

        val jsonObject = JSONObject()
        jsonObject.put("encoded", base64Data.trim())

        val decryptHeaders = Headers.Builder()
            .add("x-api-key", DECRYPT_API_KEY)
            .add("Content-Type", "application/json")
            .build()

        return client.newCall(
            POST(DECRYPTURL, headers = decryptHeaders, body = jsonObject.toJsonRequestBody()),
        ).execute().parseAs<VideoLinksApi>()
    }

    @Serializable
    data class SvelteKitWrapper(val body: String)

    @Serializable
    data class ScriptEpisode(val episodeInfo: EpisodeInfo)

    @Serializable
    data class EpisodeInfo(
        val id: Int,
        val primarySource: PrimarySource? = null,
    )

    @Serializable
    data class PrimarySource(
        val FHD: String? = null,
        val HD: String? = null,
        val SD: String? = null,
    )

    @Serializable
    data class VideoLinksApi(
        val HD: String? = null,
        val SD: String? = null,
        val FHD: String? = null,
        val Source: String? = null,
        val SourceMKV: String? = null,
    )
}
