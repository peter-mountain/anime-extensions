package aniyomi.lib.cdaextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.ExtLog
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class CdaExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    fun getVideosFromUrl(url: String, headers: Headers, prefix: String): List<Video> {
        ExtLog.d(TAG, "=== CDA START === url=$url")

        val embedHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Host", url.toHttpUrl().host)
            .build()

        val response = client.newCall(GET(url, headers = embedHeaders)).execute()
        val bodyStr = response.body?.string() ?: ""
        ExtLog.d(TAG, "CDA: embed code=${response.code} length=${bodyStr.length}")

        if (bodyStr.length < 100) {
            ExtLog.e(TAG, "CDA: embed body too short (${bodyStr.length} chars)")
            return emptyList()
        }

        val document = org.jsoup.Jsoup.parse(bodyStr, url)

        if (document.toString().contains("został usunięty")) {
            ExtLog.e(TAG, "CDA: video DELETED by owner")
            return emptyList()
        }

        val playerDataEl = document.selectFirst("div[player_data]")
        if (playerDataEl == null) {
            ExtLog.e(TAG, "CDA: div[player_data] NOT FOUND")
            return emptyList()
        }

        val playerDataStr = playerDataEl.attr("player_data")
        if (playerDataStr.isNullOrBlank()) {
            ExtLog.e(TAG, "CDA: player_data is EMPTY")
            return emptyList()
        }

        val data = try {
            json.decodeFromString<PlayerData>(playerDataStr)
        } catch (e: Exception) {
            ExtLog.e(TAG, "CDA: PARSE FAILED: ${e.message}")
            return emptyList()
        }

        ExtLog.d(TAG, "CDA: id=${data.video.id} file=${data.video.file.take(60)} quality=${data.video.quality}")
        ExtLog.d(TAG, "CDA: manifest=${data.video.manifestApple}")
        ExtLog.d(TAG, "CDA: manifest_dash=${data.video.manifest}")

        val cdaHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
            .add("Accept", "*/*")
            .build()

        val results = mutableListOf<Video>()

        // HLS master manifest — adaptive quality (ExoPlayer picks best stream)
        if (!data.video.manifestApple.isNullOrBlank()) {
            ExtLog.d(TAG, "CDA: HLS auto -> ${data.video.manifestApple}")
            results.add(Video(data.video.manifestApple, "${prefix}cda.pl - auto", data.video.manifestApple, cdaHeaders))
        }

        // DASH manifest — highest quality
        if (!data.video.manifest.isNullOrBlank() && data.video.manifest != data.video.manifestApple) {
            ExtLog.d(TAG, "CDA: DASH -> ${data.video.manifest}")
            results.add(Video(data.video.manifest, "${prefix}cda.pl - 1080p", data.video.manifest, cdaHeaders))
        }

        // Quality options from qualities map (if available)
        if (data.video.qualities.isNotEmpty()) {
            val qualityLabels = mapOf(
                "1080" to "1080p",
                "hd" to "1080p",
                "720" to "720p",
                "sd" to "720p",
                "480" to "480p",
                "mq" to "480p",
                "360" to "360p",
                "lq" to "360p",
            )
            for ((key, value) in data.video.qualities) {
                if (results.any { it.quality.contains(qualityLabels[key] ?: key) }) continue
                try {
                    val jsonBody = """
                        {
                            "jsonrpc": "2.0",
                            "method": "videoGetLink",
                            "id": 1,
                            "params": [
                                "${data.video.id}",
                                "$value",
                                ${data.video.ts},
                                "${data.video.hash2}",
                                {}
                            ]
                        }
                    """.trimIndent().toJsonRequestBody()
                    val postHeaders = Headers.headersOf(
                        "Content-Type",
                        "application/json",
                        "X-Requested-With",
                        "XMLHttpRequest",
                    )
                    val resp = client.newCall(
                        okhttp3.Request.Builder()
                            .url("https://www.cda.pl/")
                            .headers(postHeaders)
                            .post(jsonBody)
                            .build(),
                    ).execute()
                    val body = resp.body?.string() ?: ""
                    if (body.isNotBlank()) {
                        val parsed = json.decodeFromString<PostResponse>(body)
                        val label = qualityLabels[key] ?: key
                        ExtLog.d(TAG, "CDA: videoGetLink $key -> ${parsed.result.resp}")
                        results.add(Video(parsed.result.resp, "${prefix}cda.pl - $label", parsed.result.resp, cdaHeaders))
                    }
                } catch (e: Exception) {
                    ExtLog.d(TAG, "CDA: videoGetLink $key FAILED: ${e.message}")
                }
            }
        }

        // Direct URL (newer CDA: file is already a full URL)
        if (data.video.file.isNotBlank() && results.isEmpty()) {
            if (data.video.file.startsWith("https://")) {
                ExtLog.d(TAG, "CDA: direct URL -> ${data.video.file}")
                val label = if (data.video.file.contains("/hls/")) "auto" else data.video.quality
                results.add(Video(data.video.file, "${prefix}cda.pl - $label", data.video.file, cdaHeaders))
            } else {
                // Legacy encoded filename (older CDA)
                try {
                    val decryptedUrl = decryptFile(data.video.file)
                    ExtLog.d(TAG, "CDA: legacy decrypted -> $decryptedUrl")
                    results.add(Video(decryptedUrl, "${prefix}cda.pl - ${data.video.quality}", decryptedUrl, cdaHeaders))
                } catch (e: Exception) {
                    ExtLog.e(TAG, "CDA: DECRYPT FAILED: ${e.message}")
                }
            }
        }

        ExtLog.d(TAG, "=== CDA END === returning ${results.size} videos")
        return results
    }

    private fun decryptFile(a: String): String {
        var decrypted = a
        listOf("_XDDD", "_CDA", "_ADC", "_CXD", "_QWE", "_Q5", "_IKSDE").forEach { p ->
            decrypted = decrypted.replace(p, "")
        }
        decrypted = URLDecoder.decode(decrypted, StandardCharsets.UTF_8.toString())
        val b = mutableListOf<Char>()
        decrypted.forEach { c ->
            val f = c.code
            b.add(if (f in 33..126) (33 + (f + 14) % 94).toChar() else c)
        }
        decrypted = b.joinToString("")
        decrypted = decrypted.replace(".cda.mp4", "")
        listOf(".2cda.pl", ".3cda.pl").forEach { p ->
            decrypted = decrypted.replace(p, ".cda.pl")
        }
        if ("/upstream" in decrypted) {
            decrypted = decrypted.replace("/upstream", ".mp4/upstream")
            return "https://$decrypted"
        }
        return "https://$decrypted.mp4"
    }

    @Serializable
    data class PlayerData(
        val video: VideoObject,
    ) {
        @Serializable
        data class VideoObject(
            val id: String,
            val file: String = "",
            val quality: String = "auto",
            val qualities: Map<String, String> = emptyMap(),
            val ts: Int = 0,
            val hash2: String = "",
            @kotlinx.serialization.SerialName("manifest_apple")
            val manifestApple: String? = null,
            @kotlinx.serialization.SerialName("manifest")
            val manifest: String? = null,
        )
    }

    @Serializable
    data class PostResponse(
        val result: ResultObject,
    ) {
        @Serializable
        data class ResultObject(
            val resp: String,
        )
    }

    companion object {
        private const val TAG = "CdaExtractor"
    }
}
