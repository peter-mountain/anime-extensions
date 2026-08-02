package aniyomi.lib.vidaraextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.ExtLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class VidaraExtractor(private val client: OkHttpClient) {
    private val playlistUtils by lazy { PlaylistUtils(client) }
    private val vidaraJson = Json { ignoreUnknownKeys = true }
    private val tag = "VidaraExtractor"

    fun videosFromUrl(
        url: String,
        prefix: String = "Vidara - ",
        headers: Headers? = null,
    ): List<Video> {
        logDebug("url=$url prefix=$prefix")
        return try {
            val userAgent = headers?.get("User-Agent")
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

            val filecode = extractFilecode(url) ?: return listOf(debugVideo("no_filecode_in_url=$url"))

            val reqHeaders = (headers?.newBuilder() ?: Headers.Builder()).apply {
                set("User-Agent", userAgent)
                set("Referer", url)
                set("Accept", "application/json, text/plain, */*")
                set("Content-Type", "application/json")
            }.build()

            val embedHost = url.toHttpUrl().let { "${it.scheme}://${it.host}" }
            val apiBases = listOf("https://vidara.to", embedHost)

            for (apiBase in apiBases) {
                logDebug("trying apiBase=$apiBase filecode=$filecode")
                val originHeaders = reqHeaders.newBuilder()
                    .set("Origin", apiBase)
                    .build()

                val streamBody = StreamRequest(filecode = filecode, device = "web")
                val streamJson = vidaraJson.encodeToString(StreamRequest.serializer(), streamBody)
                val streamResp = client.newCall(
                    POST(
                        "$apiBase/api/stream",
                        originHeaders,
                        streamJson.toRequestBody("application/json".toMediaType()),
                    ),
                ).execute()
                val bodyStr = streamResp.body.string()
                logDebug("streamResp code=${streamResp.code} body_len=${bodyStr.length}")

                if (streamResp.code == 200 && bodyStr.isNotBlank()) {
                    try {
                        val streamData = vidaraJson.decodeFromString(StreamResponse.serializer(), bodyStr)
                        val hlsUrl = streamData.streaming_url
                        if (!hlsUrl.isNullOrBlank()) {
                            logDebug("got hlsUrl=$hlsUrl")
                            val result = playlistUtils.extractFromHls(
                                hlsUrl,
                                masterHeaders = originHeaders,
                                videoHeaders = originHeaders,
                                videoNameGen = { "$prefix$it" },
                            )
                            return result.ifEmpty {
                                listOf(Video(hlsUrl, "${prefix}HLS", hlsUrl, headers = originHeaders))
                            }
                        }
                    } catch (e: Exception) {
                        logDebug("decode_failed: ${e.message} body=$bodyStr")
                    }
                }
            }

            logDebug("trying fallback embed page")
            val fallbackResult = extractFromEmbedPage(url, reqHeaders, prefix, embedHost)
            if (fallbackResult.isNotEmpty()) return fallbackResult

            listOf(debugVideo("all_methods_failed url=$url"))
        } catch (e: Exception) {
            ExtLog.e(tag, "Failed to extract video from $url", e)
            listOf(debugVideo("${e::class.simpleName}:${e.message}"))
        }
    }

    private fun logDebug(msg: String) {
        ExtLog.d(tag, msg)
    }

    private fun extractFromEmbedPage(
        url: String,
        headers: Headers,
        prefix: String,
        embedHost: String,
    ): List<Video> {
        return try {
            val pageHeaders = headers.newBuilder()
                .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .set("Origin", embedHost)
                .build()
            logDebug("fallback GET $url")
            val resp = client.newCall(GET(url, pageHeaders)).execute()
            val body = resp.body.string()
            resp.close()
            logDebug("fallback body_len=${body.length}")

            val m3u8Regex = Regex("""https?://[^"'\s]+?\.m3u8[^"'\s]*""")
            val m3u8Match = m3u8Regex.find(body)
            if (m3u8Match != null) {
                val foundUrl = m3u8Match.value
                logDebug("fallback found m3u8=$foundUrl")
                val videoHeaders = pageHeaders.newBuilder()
                    .set("Referer", "$embedHost/")
                    .set("Origin", embedHost)
                    .build()
                val result = playlistUtils.extractFromHls(
                    foundUrl,
                    masterHeaders = videoHeaders,
                    videoHeaders = videoHeaders,
                    videoNameGen = { "$prefix$it" },
                )
                if (result.isNotEmpty()) return result
                return listOf(Video(foundUrl, "${prefix}HLS", foundUrl, headers = videoHeaders))
            }

            val masterRegex = Regex("""https?://[^"'\s]*hls2/[^"'\s]*""")
            val masterMatch = masterRegex.find(body)
            if (masterMatch != null) {
                val masterUrl = masterMatch.value
                logDebug("fallback found master=$masterUrl")
                val videoHeaders = pageHeaders.newBuilder()
                    .set("Origin", embedHost)
                    .build()
                val result = playlistUtils.extractFromHls(
                    masterUrl,
                    masterHeaders = videoHeaders,
                    videoHeaders = videoHeaders,
                    videoNameGen = { "$prefix$it" },
                )
                if (result.isNotEmpty()) return result
                return listOf(Video(masterUrl, "${prefix}HLS", masterUrl, headers = videoHeaders))
            }

            logDebug("fallback no_hls_found")
            emptyList()
        } catch (e: Exception) {
            logDebug("fallback_failed: ${e.message}")
            emptyList()
        }
    }

    private fun debugVideo(msg: String) = Video("about:blank", "Vidara debug: $msg".take(300), "about:blank")

    private fun extractFilecode(url: String): String? = Regex("""(?:/e/|/embed/|/\w+/)([a-zA-Z0-9_-]{8,})""")
        .find(url)
        ?.groupValues
        ?.get(1)

    @Serializable
    data class StreamRequest(
        val filecode: String,
        val device: String,
    )

    @Serializable
    data class StreamResponse(
        val streaming_url: String? = null,
        val subtitles: List<Subtitle>? = null,
        val vast_ads: String? = null,
    )

    @Serializable
    data class Subtitle(
        val label: String? = null,
        val file: String? = null,
        val kind: String? = null,
    )
}
