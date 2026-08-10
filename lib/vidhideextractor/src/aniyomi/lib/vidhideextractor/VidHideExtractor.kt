package aniyomi.lib.vidhideextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.autoUnpacker
import keiyoushi.utils.ExtLog
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidHideExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    suspend fun videosFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "VidHide - $quality" }): List<Video> {
        ExtLog.d(TAG, "=== VidHide START === url=$url")
        val script = fetchAndExtractScript(url)
        if (script == null) {
            ExtLog.e(TAG, "No packed JS found on page")
            return emptyList()
        }
        ExtLog.d(TAG, "Script unpacked, len=${script.length}")
        val playlists = extractVideoUrl(script, url)
        ExtLog.d(TAG, "Found ${playlists.size} m3u8 URLs: ${playlists.map { it.take(80) }}")
        val subtitleList = extractSubtitles(script, url)

        return playlists.parallelCatchingFlatMap { videoUrl ->
            playlistUtils.extractFromHls(
                videoUrl,
                referer = url,
                videoNameGen = videoNameGen,
                subtitleList = subtitleList,
            )
        }
    }

    private suspend fun fetchAndExtractScript(url: String): String? {
        ExtLog.d(TAG, "Fetching embed page...")
        val doc = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()
        val scripts = doc.select("script")
        ExtLog.d(TAG, "Found ${scripts.size} script tags")
        for (s in scripts) {
            val html = s.html()
            if (html.length > 100) {
                ExtLog.d(TAG, "Script tag len=${html.length}, hasPacked=${html.contains("eval(function(p,a,c,k,e,d)")}, preview=${html.take(120)}")
            }
        }
        val packed = scripts.find { it.html().contains("eval(function(p,a,c,k,e,d)") }
        if (packed == null) {
            ExtLog.e(TAG, "No eval(function(p,a,c,k,e,d) found in any script tag")
            // Log all script previews for debugging
            scripts.filter { it.html().length > 50 }.forEachIndexed { i, s ->
                ExtLog.d(TAG, "script[$i] len=${s.html().length}: ${s.html().take(200)}")
            }
            return null
        }
        ExtLog.d(TAG, "Found packed JS, unpacking...")
        val unpacked = autoUnpacker(packed.html())
        if (unpacked == null) {
            ExtLog.e(TAG, "autoUnpacker returned null")
            return null
        }
        ExtLog.d(TAG, "Unpacked len=${unpacked.length}, hasM3u8=${unpacked.contains("m3u8")}, preview=${unpacked.take(200)}")
        return unpacked
    }

    private fun extractVideoUrl(script: String, baseUrl: String): List<String> = sourceRegex
        .findAll(script).mapNotNull {
            UrlUtils.fixUrl(it.groupValues[1], baseUrl)
        }.toList()

    private fun extractSubtitles(script: String, baseUrl: String): List<Track> = try {
        val subtitleStr = script
            .substringAfter("tracks")
            .substringAfter("[")
            .substringBefore("]")
        json.decodeFromString<List<TrackDto>>("[$subtitleStr]")
            .filter { it.kind.equals("captions", true) }
            .mapNotNull {
                UrlUtils.fixUrl(it.file, baseUrl)?.let { url ->
                    Track(url, it.label ?: "")
                }
            }
    } catch (_: SerializationException) {
        emptyList()
    }

    @Serializable
    private data class TrackDto(
        val file: String,
        val kind: String,
        val label: String? = null,
    )

    companion object {
        private const val TAG = "VidHide"
        // Capture both `https://domain/master.m3u8?query` and `/domain/master.m3u8?query`
        private val sourceRegex = Regex(""""((?:https?:/)?/[^"]*m3u8[^"]*)"""")
    }
}
