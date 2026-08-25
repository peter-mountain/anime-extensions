package aniyomi.lib.mp4uploadextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.jsunpacker.JsUnpacker
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException

class Mp4uploadExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, headers: Headers, prefix: String = "", suffix: String = ""): List<Video> {
        val videoId = extractVideoId(url) ?: return emptyList()
        val pageUrl = url.substringBefore(".html") + ".html"
        val filePage = pageUrl.replace("embed-", "")

        val requestHeaders = headers.newBuilder()
            .set("Referer", pageUrl)
            .build()

        // Official free-download flow (download2 POST -> 302 Location), same as shinden_mobile
        val directUrl = directLinkViaDownload2(filePage, videoId, requestHeaders)
            ?: directLinkFromEmbedPage(pageUrl, requestHeaders)
            ?: return emptyList()

        val resolution = QUALITY_REGEX.find(directUrl)?.groupValues?.get(1) ?: "Unknown resolution"
        val quality = "${prefix}Mp4Upload - $resolution$suffix"

        return listOf(Video(directUrl, quality, directUrl, requestHeaders))
    }

    private fun directLinkViaDownload2(filePage: String, videoId: String, headers: Headers): String? {
        val body = FormBody.Builder()
            .add("op", "download2")
            .add("id", videoId)
            .add("rand", "")
            .add("referer", "https://www.mp4upload.com/")
            .add("method_free", "Free Download")
            .add("method_premium", "")
            .build()

        return try {
            val response = client.newCall(POST(filePage, headers, body)).execute()
            val location = response.header("Location")
            val responseBody = response.body?.string().orEmpty()
            response.close()
            when {
                !response.isSuccessful && location == null -> null
                location != null -> encodePath(location)
                responseBody.contains("File was deleted") -> null
                else -> Regex("""(?:btn_download|download_link)\s*[:=]\s*["']?([^"']+)""")
                    .find(responseBody)?.groupValues?.get(1)
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun directLinkFromEmbedPage(embedUrl: String, headers: Headers): String? {
        return try {
            val document = client.newCall(GET(embedUrl, headers)).execute().asJsoup()

            val script = document.selectFirst("script:containsData(eval):containsData(p,a,c,k,e,d)")?.data()
                ?.let(JsUnpacker::unpackAndCombine)
                ?: document.selectFirst("script:containsData(player.src)")?.data()
                ?: return null

            script.substringAfter(".src(").substringBefore(")")
                .substringAfter("src:").substringAfter('"').substringBefore('"')
                .takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            "embed-([A-Za-z0-9]+)\\.html".toRegex(),
            "/([A-Za-z0-9]+)\\.html".toRegex(),
        )
        for (regex in patterns) {
            regex.find(url)?.let { return it.groupValues[1] }
        }
        return null
    }

    /**
     * Percent-encode the path of the Location URL (spaces, brackets, non-ASCII filenames),
     * otherwise the CDN rejects the request.
     */
    private fun encodePath(url: String): String = runCatching {
        url.toHttpUrl().toString()
    }.getOrDefault(url)

    companion object {
        private val QUALITY_REGEX by lazy { """\WHEIGHT=(\d+)""".toRegex() }
    }
}
