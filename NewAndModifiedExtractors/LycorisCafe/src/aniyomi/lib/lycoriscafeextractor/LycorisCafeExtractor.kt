package aniyomi.lib.lycoriscafeextractor

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.ExtLog
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class LycorisCafeExtractor(private val client: OkHttpClient) {

    private val tag = "LycorisCafeExtractor"

    fun getVideosFromUrl(url: String, headers: Headers, prefix: String): List<Video> {
        val videos = mutableListOf<Video>()

        try {
            var episodeInfo = scrapeEpisodeInfo(url, headers)

            if (episodeInfo == null) {
                episodeInfo = fetchEpisodeInfoFromApi(url)
                if (episodeInfo != null) {
                    ExtLog.d(tag, "episodeInfo from api/embed")
                }
            }

            if (episodeInfo == null) {
                ExtLog.w(tag, "no episodeInfo available for $url")
                return emptyList()
            }

            // Complete quality ladder from primarySource
            episodeInfo.primarySource?.let { primary ->
                videos += collectSources(
                    primary.FHD to "1080p",
                    primary.HD to "720p",
                    primary.SD to "480p",
                    primary.preview to "Preview",
                    primary.SourceMKV to "SourceMKV",
                    prefix = prefix,
                )
            }

            // Fallback: top-level keys with local decoding (no decrypt API needed)
            if (videos.isEmpty()) {
                videos += collectSources(
                    episodeInfo.FHD to "1080p",
                    episodeInfo.HD to "720p",
                    episodeInfo.SD to "480p",
                    episodeInfo.PL to "PL",
                    episodeInfo.Source to "Source",
                    episodeInfo.SourceMKV to "SourceMKV",
                    prefix = prefix,
                )
            }

            return videos.distinctBy { it.url }
        } catch (e: Exception) {
            ExtLog.e(tag, "Error: ${e.message}", e)
            return emptyList()
        }
    }

    private fun scrapeEpisodeInfo(url: String, headers: Headers): EpisodeInfo? {
        return runCatching {
            val document = client.newCall(GET(url, headers = headers)).execute().useAsJsoup()
            val script = document.selectFirst("script[type='application/json']")?.data()
                ?: return null
            script.parseAs<SvelteKitWrapper>().body.parseAs<ScriptEpisode>().episodeInfo
        }.getOrNull()
    }

    private fun fetchEpisodeInfoFromApi(url: String): EpisodeInfo? {
        val embedUri = url.toHttpUrlOrNull() ?: return null
        val id = embedUri.queryParameter("id") ?: return null
        val episode = embedUri.queryParameter("episode") ?: return null

        val apiUrl = embedUri.newBuilder()
            .encodedPath("/api/embed")
            .addQueryParameter("id", id)
            .addQueryParameter("episode", episode)
            .build()

        val apiHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36")
            .add("Accept", "application/json, text/plain, */*")
            .add("Referer", url)
            .build()

        return runCatching {
            client.newCall(GET(apiUrl, headers = apiHeaders)).execute().use { response ->
                if (!response.isSuccessful) return null
                response.bodyString().parseAs<ApiEmbedResponse>().episodeInfo
            }
        }.getOrNull()
    }

    private fun collectSources(vararg sources: Pair<String?, String>, prefix: String): List<Video> {
        return sources.mapNotNull { (link, label) ->
            val resolved = resolveLink(link) ?: return@mapNotNull null
            Video(resolved, "$prefix lycoris.cafe - $label", resolved)
        }
    }

    private fun resolveLink(link: String?): String? {
        if (link.isNullOrEmpty()) return null
        if (link.startsWith("http")) return link
        return decodeLycorisUrl(link)?.takeIf { it.startsWith("http") }
    }

    // Port of shinden_mobile decodeLycorisUrl:
    // reverse -> char code -7 -> strip non-base64 chars -> base64 decode
    private fun decodeLycorisUrl(encodedString: String): String? {
        if (encodedString.isEmpty() || !encodedString.endsWith("LC")) return encodedString

        val withoutSuffix = encodedString.dropLast(2)
        val reversed = withoutSuffix.reversed()
        val shifted = reversed.map { (it.code - 7).toChar() }.joinToString("")
        val sanitized = shifted.filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }

        return runCatching { String(Base64.decode(sanitized, Base64.DEFAULT)) }.getOrNull()
    }

    @Serializable
    data class SvelteKitWrapper(val body: String)

    @Serializable
    data class ScriptEpisode(val episodeInfo: EpisodeInfo)

    @Serializable
    data class ApiEmbedResponse(val episodeInfo: EpisodeInfo? = null)

    @Serializable
    data class EpisodeInfo(
        val id: Int? = null,
        val primarySource: PrimarySource? = null,
        val FHD: String? = null,
        val HD: String? = null,
        val SD: String? = null,
        val PL: String? = null,
        val Source: String? = null,
        val SourceMKV: String? = null,
    )

    @Serializable
    data class PrimarySource(
        val FHD: String? = null,
        val HD: String? = null,
        val SD: String? = null,
        val preview: String? = null,
        val SourceMKV: String? = null,
    )
}
