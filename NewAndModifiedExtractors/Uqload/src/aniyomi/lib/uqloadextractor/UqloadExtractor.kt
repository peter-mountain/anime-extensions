package aniyomi.lib.uqloadextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.lib.autoUnpacker
import keiyoushi.utils.ExtLog
import keiyoushi.utils.bodyString
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.regex.Pattern

class UqloadExtractor(private val client: OkHttpClient) {

    private val tag = "UqloadExtractor"

    fun videosFromUrl(url: String, prefix: String, headers: Headers? = null): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            val host = try {
                url.toHttpUrl().host
            } catch (_: Exception) {
                "uqload.net"
            }
            val origin = "https://$host"
            val isUqloadIs = host.contains("uqload.is")
            ExtLog.d(tag, "Detected host: $host (isUqloadIs=$isUqloadIs)")

            val requestHeaders = Headers.Builder().apply {
                set("Referer", "$origin/")
                set("Origin", origin)
                headers?.let { orig ->
                    for (name in orig.names()) {
                        if (name != "Referer" && name != "Origin") {
                            set(name, orig[name]!!)
                        }
                    }
                }
            }.build()

            val html = if (isUqloadIs) {
                handleUqloadIs(url, origin, requestHeaders)
            } else {
                val fetched = client.newCall(GET(url, requestHeaders)).execute().bodyString()
                ExtLog.d(tag, "Fetched embed page: ${url.take(80)}... (length=${fetched.length})")
                fetched
            }

            if (html == null) {
                ExtLog.e(tag, "Failed to get HTML content")
                return emptyList()
            }

            val unpacked = if (html.contains("eval(function(p,a,c,k,e")) {
                ExtLog.d(tag, "Found packed JS, unpacking...")
                val result = autoUnpacker(html)
                if (result != null) {
                    ExtLog.d(tag, "Unpacked JS length: ${result.length}")
                    result
                } else {
                    ExtLog.e(tag, "autoUnpacker returned null")
                    return emptyList()
                }
            } else {
                ExtLog.d(tag, "No packed JS found, using raw HTML")
                html
            }

            val m3u8Url = extractSourceUrl(unpacked)
            if (m3u8Url == null) {
                ExtLog.e(tag, "No m3u8 source URL found in unpacked JS")
                val fallbackUrl = Pattern.compile("https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*")
                    .matcher(unpacked)
                    .takeIf { it.find() }
                    ?.group(0)
                if (fallbackUrl != null) {
                    ExtLog.d(tag, "Fallback m3u8 URL: $fallbackUrl")
                    val quality = getResolution(client, fallbackUrl, requestHeaders)
                    videos.add(Video(fallbackUrl, "${prefix}Uqload - $quality", fallbackUrl, requestHeaders))
                }
                return videos
            }

            ExtLog.d(tag, "Extracted m3u8 URL: ${m3u8Url.take(120)}...")

            if (m3u8Url.contains("master")) {
                val subUrls = extractSubPlaylists(client, m3u8Url, requestHeaders)
                if (subUrls.isNotEmpty()) {
                    for ((quality, subUrl) in subUrls) {
                        videos.add(Video(subUrl, "${prefix}Uqload - $quality", subUrl, requestHeaders))
                    }
                    return videos
                }
            }

            val quality = getResolution(client, m3u8Url, requestHeaders)
            videos.add(Video(m3u8Url, "${prefix}Uqload - $quality", m3u8Url, requestHeaders))
        } catch (e: Exception) {
            ExtLog.e(tag, "Error: ${e.message}", e)
        }
        return videos
    }

    /**
     * Handle uqload.is flow:
     * 1. Extract file_code from URL path (JS sets it dynamically, HTML value is empty)
     * 2. POST to /dl with op=embed&file_code=XXX&auto=1&referer=...
     * 3. POST response contains packed JS with m3u8 URL
     */
    private fun handleUqloadIs(url: String, origin: String, requestHeaders: Headers): String? {
        // Extract file_code from URL path: /e/{code} or /e/{code}.html
        val fileCode = extractFileCodeFromUrl(url)
        if (fileCode == null) {
            ExtLog.e(tag, "uqload.is: could not extract file_code from URL: $url")
            return null
        }
        ExtLog.d(tag, "uqload.is file_code=$fileCode (from URL)")

        // POST to /dl
        val postBody = FormBody.Builder()
            .add("op", "embed")
            .add("file_code", fileCode)
            .add("auto", "1")
            .add("referer", url)
            .build()

        val postHeaders = Headers.Builder().apply {
            set("Referer", url)
            set("Origin", origin)
            set("Content-Type", "application/x-www-form-urlencoded")
            set(
                "User-Agent",
                requestHeaders["User-Agent"] ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36",
            )
        }.build()

        val postUrl = "$origin/dl"
        ExtLog.d(tag, "uqload.is POST $postUrl")

        val postResponse = client.newCall(
            okhttp3.Request.Builder()
                .url(postUrl)
                .post(postBody)
                .headers(postHeaders)
                .build(),
        ).execute()

        ExtLog.d(tag, "uqload.is POST response code=${postResponse.code}")

        val postHtml = postResponse.bodyString()
        ExtLog.d(tag, "uqload.is POST body length=${postHtml.length}")

        val setCookies = postResponse.headers("Set-Cookie")
        ExtLog.d(tag, "uqload.is Set-Cookie count=${setCookies.size}")

        return postHtml
    }

    /**
     * Extract file_code from URL path.
     * Format: /e/{code} or /e/{code}.html or /e/{code}-XXXX
     * Mirrors JS: path.split("/").pop().split("-").pop()
     */
    private fun extractFileCodeFromUrl(url: String): String? {
        return try {
            val httpUrl = url.toHttpUrl()
            val lastSegment = httpUrl.pathSegments.lastOrNull() ?: return null
            // Remove .html extension
            val cleaned = lastSegment.removeSuffix(".html")
            // Split by "-" and take last part (mirrors JS .split("-").pop())
            val code = cleaned.split("-").lastOrNull()
            if (code.isNullOrBlank()) null else code
        } catch (_: Exception) {
            // Fallback: regex on raw URL
            val match = Regex("""/e/([^/?&#]+)""").find(url)
            match?.groupValues?.get(1)?.removeSuffix(".html")
        }
    }

    private fun extractSourceUrl(js: String): String? {
        val patterns = listOf(
            Pattern.compile("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Pattern.compile("""["']file["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Pattern.compile("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Pattern.compile("""["'](https?://[^"'\s]+strm\d*\.uqload\.[^"'\s]+\.m3u8[^"'\s]*)["']"""),
            Pattern.compile("""["'](https?://[^"'\s]+\.m3u8[^"'\s]*)["']"""),
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(js)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    private fun extractSubPlaylists(
        client: OkHttpClient,
        masterUrl: String,
        headers: Headers,
    ): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        try {
            val content = client.newCall(GET(masterUrl, headers)).execute().bodyString()
            val baseUrl = masterUrl.substringBeforeLast("/").plus("/")

            val resolutionPattern = Pattern.compile("""RESOLUTION=(\d+)x(\d+)""")
            val urlPattern = Pattern.compile("""^([^#][^\s]*\.m3u8.*)$""", Pattern.MULTILINE)

            val resolutions = mutableListOf<Int>()
            val urls = mutableListOf<String>()

            for (line in content.lines()) {
                val resMatcher = resolutionPattern.matcher(line)
                if (resMatcher.find()) {
                    resolutions.add(resMatcher.group(2)!!.toInt())
                }
                val urlMatcher = urlPattern.matcher(line.trim())
                if (urlMatcher.find()) {
                    val subUrl = urlMatcher.group(1)!!.trim()
                    urls.add(if (subUrl.startsWith("http")) subUrl else baseUrl + subUrl)
                }
            }

            for (i in urls.indices) {
                val quality = if (i < resolutions.size) "${resolutions[i]}p" else "Unknown"
                results.add(quality to urls[i])
            }
        } catch (e: Exception) {
            ExtLog.e(tag, "Error extracting sub-playlists: ${e.message}")
        }
        return results
    }

    private fun getResolution(client: OkHttpClient, m3u8Url: String, headers: Headers): String = try {
        val content = client.newCall(GET(m3u8Url, headers)).execute().bodyString()
        Pattern.compile("RESOLUTION=\\d+x(\\d+)")
            .matcher(content)
            .takeIf { it.find() }
            ?.group(1)
            ?.let { "${it}p" }
            ?: "Unknown"
    } catch (_: Exception) {
        "Unknown"
    }
}
