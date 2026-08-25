package aniyomi.lib.cdaextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.ExtLog
import keiyoushi.utils.toJsonBody
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

        val hlsAuto = data.video.manifestApple
        val dashManifest = data.video.manifest
        val castManifest = data.video.manifestCast

        // Highest quality the embed advertises: DASH MPD maxHeight first,
        // then the qualities map. Used for truthful auto/DASH labels.
        val embedMaxQuality = runCatching {
            (dashManifest ?: castManifest)?.let { mpdMaxHeightLabel(it, cdaHeaders) }
        }.getOrNull() ?: qualitiesMaxLabel(data.video.qualities)

        // HLS master manifest — adaptive quality (ExoPlayer picks best stream)
        if (!hlsAuto.isNullOrBlank()) {
            val autoLabel = embedMaxQuality ?: "auto"
            ExtLog.d(TAG, "CDA: HLS auto -> $hlsAuto (max $autoLabel)")
            results.add(Video(hlsAuto, "${prefix}cda.pl - auto ($autoLabel)", hlsAuto, cdaHeaders))
        }

        // DASH manifest — highest quality
        if (!dashManifest.isNullOrBlank() && dashManifest != hlsAuto) {
            val dashLabel = embedMaxQuality ?: "720p"
            ExtLog.d(TAG, "CDA: DASH -> $dashManifest (label=$dashLabel)")
            results.add(Video(dashManifest, "${prefix}cda.pl - $dashLabel", dashManifest, cdaHeaders))
        }

        // DASH cast manifest — CDA embeds without a dedicated DASH/HLS manifest
        // still expose an MPD for Chromecast; use it as the DASH source.
        if (!castManifest.isNullOrBlank() && castManifest != dashManifest) {
            val castLabel = if (dashManifest.isNullOrBlank()) {
                embedMaxQuality ?: "720p"
            } else {
                runCatching { mpdMaxHeightLabel(castManifest, cdaHeaders) }.getOrNull() ?: "720p"
            }
            ExtLog.d(TAG, "CDA: DASH cast -> $castManifest (label=$castLabel)")
            results.add(Video(castManifest, "${prefix}cda.pl - $castLabel", castManifest, cdaHeaders))
        }

        // Quality options from qualities map (if available)
        if (data.video.qualities.isNotEmpty()) {
            ExtLog.d(TAG, "CDA: qualities=${data.video.qualities.size} ${data.video.qualities.keys}")
            val qualityLabels = mapOf(
                "1080" to "1080p",
                "hd" to "1080p",
                "720" to "720p",
                "sd" to "720p",
                "480" to "480p",
                "mq" to "480p",
                "lq" to "480p",
                "360" to "360p",
                "vl" to "360p",
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
                    """.trimIndent().toJsonBody()
                    val postHeaders = Headers.headersOf(
                        "Content-Type",
                        "application/json",
                        "X-Requested-With",
                        "XMLHttpRequest",
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
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
                        val newUrl = parsed.result.resp
                        if (results.any { it.url == newUrl }) {
                            ExtLog.d(TAG, "CDA: videoGetLink $key duplicate URL, skipping")
                        } else {
                            ExtLog.d(TAG, "CDA: videoGetLink $key -> $newUrl")
                            results.add(Video(newUrl, "${prefix}cda.pl - $label", newUrl, cdaHeaders))
                        }
                    } else {
                        ExtLog.d(TAG, "CDA: videoGetLink $key EMPTY BODY (code=${resp.code})")
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
                val label = if (data.video.file.contains("/hls/")) "auto (${qualityLabelFor(data.video.quality)})" else data.video.quality
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

    private fun mpdMaxHeightLabel(mpdUrl: String, headers: Headers): String? = runCatching {
        val mpdResp = client.newCall(GET(mpdUrl, headers = headers)).execute()
        val mpdBody = mpdResp.body?.string().orEmpty()
        Regex("""maxHeight="(\d+)"""")
            .find(mpdBody)?.groupValues?.get(1)?.let { "${it}p" }
    }.getOrNull()

    private fun qualitiesMaxLabel(qualities: Map<String, String>): String? = qualities.keys
        .mapNotNull { Regex("""(\d{3,4})p""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        .maxOrNull()
        ?.let { "${it}p" }

    private fun qualityLabelFor(code: String): String = when (code.lowercase()) {
        "1080", "hd" -> "1080p"
        "720", "sd" -> "720p"
        "480", "mq", "lq" -> "480p"
        "360", "vl" -> "360p"
        else -> code
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
            @kotlinx.serialization.SerialName("manifest_cast")
            val manifestCast: String? = null,
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
