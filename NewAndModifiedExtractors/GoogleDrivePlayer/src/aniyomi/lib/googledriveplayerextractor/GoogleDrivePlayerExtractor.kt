package aniyomi.lib.googledriveplayerextractor

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.ShindenLog
import keiyoushi.utils.applicationContext
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GoogleDrivePlayerExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val verbose: Boolean = false,
) {
    private val tag by lazy { javaClass.simpleName }
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var proxyServer: GoogleDriveProxyServer? = null

    // Streaming through the proxy can last longer than the shared client's callTimeout,
    // so use a dedicated client without a call timeout for upstream requests.
    private val proxyClient by lazy {
        client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    private fun ensureProxy(): GoogleDriveProxyServer = proxyServer ?: synchronized(this) {
        proxyServer ?: GoogleDriveProxyServer(0, proxyClient).also {
            it.start()
            if (verbose) ShindenLog.d(tag, "Proxy started on port ${it.port}")
            proxyServer = it
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Synchronized
    fun videosFromUrl(origRequestUrl: String): List<Video> {
        if (verbose) ShindenLog.d(tag, "Fetching videos from: $origRequestUrl")
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var playbackUrl: String? = null
        var downloadUrl: String? = null

        handler.post {
            val newView = WebView(applicationContext)
            webView = newView
            with(newView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = USER_AGENT
            }
            newView.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView?,
                        url: String?,
                    ) {
                        if (verbose) ShindenLog.d(tag, "Page loaded")
                    }

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val url = request.url.toString()
                        if (verbose) ShindenLog.d(tag, "Intercepted URL: $url")
                        if (VIDEO_REGEX.containsMatchIn(url) && playbackUrl == null) {
                            playbackUrl = url
                            if (verbose) ShindenLog.d(tag, "Found playback URL: $url")
                            latch.countDown()
                        } else if (DOWNLOAD_REGEX.containsMatchIn(url) && downloadUrl == null) {
                            downloadUrl = url
                            if (verbose) ShindenLog.d(tag, "Found download URL: $url")
                            latch.countDown()
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

            webView?.loadUrl(origRequestUrl)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        val prefix = "Google Drive Player"

        val rawCookieStr =
            CookieManager
                .getInstance()
                ?.getCookie(origRequestUrl)
                ?: ""
        if (verbose) ShindenLog.d(tag, "Raw cookies len=${rawCookieStr.length}")

        val proxy = ensureProxy()

        // No /playback intercepted — file redirects to download (viewer disabled).
        // Resolve the drive.usercontent download URL to a direct video stream.
        if (playbackUrl == null) {
            val downloadUrlFinal = downloadUrl
            if (downloadUrlFinal == null) {
                if (verbose) ShindenLog.d(tag, "No playback or download URL intercepted")
                return emptyList()
            }
            val resolved = resolveDownloadStream(downloadUrlFinal, rawCookieStr)
            if (resolved == null) {
                if (verbose) ShindenLog.d(tag, "Download URL could not be resolved to a video stream")
                return emptyList()
            }
            if (verbose) ShindenLog.d(tag, "Resolved download stream: ${resolved.url.take(120)}")
            val streamId =
                proxy.register(
                    GoogleDriveProxyServer.StreamInfo(
                        videoUrl = resolved.url,
                        cookies = resolved.cookies,
                        referer = "https://drive.google.com/",
                        userAgent = USER_AGENT,
                    ),
                )
            val proxyUrl = "http://127.0.0.1:${proxy.port}/stream/$streamId"
            return listOf(Video(proxyUrl, prefix, proxyUrl, Headers.headersOf()))
        }

        val playbackUrlFinal = playbackUrl ?: return emptyList()
        if (verbose) ShindenLog.d(tag, "Playback URL: $playbackUrlFinal")

        val apiHeaders =
            headers
                .newBuilder()
                .apply {
                    if (rawCookieStr.isNotEmpty()) {
                        set("Cookie", rawCookieStr)
                    }
                    set("Accept", "*/*")
                    set("Referer", "https://drive.google.com/")
                    set("Origin", "https://drive.google.com")
                    set("User-Agent", USER_AGENT)
                }.build()

        return try {
            val responseBody =
                client
                    .newCall(GET(playbackUrlFinal, apiHeaders))
                    .execute()
                    .bodyString()
            if (verbose) ShindenLog.d(tag, "Response body: ${responseBody.take(300)}...")

            if (responseBody.contains("\"error\"") || responseBody.contains("RESOURCE_EXHAUSTED")) {
                if (verbose) ShindenLog.d(tag, "Playback API returned an error, falling back to download")
                val fileId = FILE_ID_REGEX.find(playbackUrlFinal)?.groupValues?.get(1)
                if (fileId != null) {
                    val downloadUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&authuser=0"
                    val resolved = resolveDownloadStream(downloadUrl, rawCookieStr)
                    if (resolved != null) {
                        if (verbose) ShindenLog.d(tag, "Download fallback resolved: ${resolved.url.take(120)}")
                        val streamId =
                            proxy.register(
                                GoogleDriveProxyServer.StreamInfo(
                                    videoUrl = resolved.url,
                                    cookies = resolved.cookies,
                                    referer = "https://drive.google.com/",
                                    userAgent = USER_AGENT,
                                ),
                            )
                        val proxyUrl = "http://127.0.0.1:${proxy.port}/stream/$streamId"
                        return listOf(Video(proxyUrl, prefix, proxyUrl, Headers.headersOf()))
                    }
                }
                if (verbose) ShindenLog.d(tag, "Download fallback failed, returning empty")
                return emptyList()
            }

            if (verbose) ShindenLog.d(tag, "Streaming response parsed, body len=${responseBody.length}")

            val streamingData = responseBody.parseAs<GoogleDriveStreamingResponse>()
            val videos = mutableListOf<Video>()

            val hbResponse = streamingData.mediaStreamingData.serializedHouseBrandPlayerResponse
            if (!hbResponse.isNullOrBlank()) {
                if (verbose) ShindenLog.d(tag, "Parsing serializedHouseBrandPlayerResponse (len=${hbResponse.length})")
                try {
                    val houseBrand = hbResponse.parseAs<HouseBrandPlayerResponse>()
                    val hbStreaming = houseBrand.streamingData
                    if (hbStreaming != null) {
                        val allFormats = hbStreaming.formats + hbStreaming.adaptiveFormats
                        if (verbose) ShindenLog.d(tag, "HouseBrand: ${hbStreaming.formats.size} formats, ${hbStreaming.adaptiveFormats.size} adaptive")

                        val audioTracks =
                            hbStreaming.adaptiveFormats
                                .filter { it.mimeType?.startsWith("audio/") == true }
                                .mapNotNull { fmt ->
                                    fmt.url?.let { Track(it, getAudioQualityFromMime(fmt.mimeType ?: "")) }
                                }

                        for (fmt in allFormats) {
                            val url = fmt.url ?: continue
                            val itag = fmt.itag ?: 0
                            val h = fmt.height ?: 0
                            val mime = fmt.mimeType ?: ""
                            val quality = fmt.qualityLabel ?: getQualityFromItag(itag, h)

                            if (mime.startsWith("audio/")) continue

                            val isAdaptive = hbStreaming.adaptiveFormats.any { it.itag == itag }
                            val label = if (isAdaptive) "$prefix Adaptive: $quality" else "$prefix: $quality"

                            val streamId =
                                proxy.register(
                                    GoogleDriveProxyServer.StreamInfo(
                                        videoUrl = url,
                                        cookies = rawCookieStr,
                                        referer = "https://drive.google.com/",
                                        userAgent = USER_AGENT,
                                    ),
                                )
                            val proxyUrl = "http://127.0.0.1:${proxy.port}/stream/$streamId"
                            if (verbose) ShindenLog.d(tag, "HB: itag=$itag quality=$quality proxy=$proxyUrl")
                            videos.add(Video(proxyUrl, label, proxyUrl, Headers.headersOf(), audioTracks = audioTracks))
                        }
                    }
                } catch (e: Exception) {
                    ShindenLog.e(tag, "Failed to parse HouseBrand response, falling back", e)
                }
            }

            if (videos.isEmpty()) {
                if (verbose) ShindenLog.d(tag, "Falling back to formatStreamingData")
                val fmtData = streamingData.mediaStreamingData.formatStreamingData
                if (fmtData != null) {
                    fmtData.progressiveTranscodes.forEach { tc ->
                        val quality = getQualityFromItag(tc.itag, tc.transcodeMetadata.height)
                        val streamId =
                            proxy.register(
                                GoogleDriveProxyServer.StreamInfo(
                                    videoUrl = tc.url,
                                    cookies = rawCookieStr,
                                    referer = "https://drive.google.com/",
                                    userAgent = USER_AGENT,
                                ),
                            )
                        val proxyUrl = "http://127.0.0.1:${proxy.port}/stream/$streamId"
                        if (verbose) ShindenLog.d(tag, "Progressive: itag=${tc.itag} quality=$quality proxy=$proxyUrl")
                        videos.add(Video(proxyUrl, "$prefix: $quality", proxyUrl, Headers.headersOf()))
                    }

                    val audioTracks =
                        fmtData.adaptiveTranscodes
                            .filter { it.transcodeMetadata.mimeType.startsWith("audio/") }
                            .map { tc -> Track(tc.url, getAudioQualityLabel(tc)) }

                    fmtData.adaptiveTranscodes.forEach { tc ->
                        if (tc.transcodeMetadata.mimeType.startsWith("video/")) {
                            val quality = getQualityFromItag(tc.itag, tc.transcodeMetadata.height)
                            val streamId =
                                proxy.register(
                                    GoogleDriveProxyServer.StreamInfo(
                                        videoUrl = tc.url,
                                        cookies = rawCookieStr,
                                        referer = "https://drive.google.com/",
                                        userAgent = USER_AGENT,
                                    ),
                                )
                            val proxyUrl = "http://127.0.0.1:${proxy.port}/stream/$streamId"
                            if (verbose) ShindenLog.d(tag, "Adaptive: itag=${tc.itag} quality=$quality proxy=$proxyUrl")
                            videos.add(
                                Video(proxyUrl, "$prefix Adaptive: $quality", proxyUrl, Headers.headersOf(), audioTracks = audioTracks),
                            )
                        }
                    }
                }
            }

            if (verbose) ShindenLog.d(tag, "Found ${videos.size} video(s)")
            videos
        } catch (e: Exception) {
            ShindenLog.e(tag, "Error fetching streaming data", e)
            emptyList()
        }
    }

    /**
     * Resolves a drive.usercontent.google.com/download URL to a playable
     * video stream. First tries the URL as-is (HEAD); if Google serves the
     * virus-scan interstitial instead, parses `confirm` + `uuid` from the
     * form and retries with those parameters.
     */
    private data class ResolvedStream(val url: String, val cookies: String)

    private fun resolveDownloadStream(downloadUrl: String, cookies: String): ResolvedStream? {
        val withCookies = resolveDownloadStreamAttempt(downloadUrl, cookies)
        if (withCookies != null) return ResolvedStream(withCookies, cookies)
        if (cookies.isNotEmpty()) {
            if (verbose) ShindenLog.d(tag, "Cookie-based resolution failed, retrying without cookies")
            val anonymous = resolveDownloadStreamAttempt(downloadUrl, "")
            if (anonymous != null) return ResolvedStream(anonymous, "")
        }
        return null
    }

    private fun resolveDownloadStreamAttempt(downloadUrl: String, cookies: String): String? {
        val baseHeaders =
            headers
                .newBuilder()
                .apply {
                    if (cookies.isNotEmpty()) set("Cookie", cookies)
                    set("Accept", "*/*")
                    set("Referer", "https://drive.google.com/")
                    set("Origin", "https://drive.google.com")
                    set("User-Agent", USER_AGENT)
                }.build()

        fun contentTypeOf(url: String): Pair<Int, String> {
            val request =
                okhttp3.Request
                    .Builder()
                    .url(url)
                    .method("HEAD", null)
                    .apply {
                        baseHeaders.forEach { (name, value) -> addHeader(name, value) }
                    }.build()
            client.newCall(request).execute().use { resp ->
                val type = resp.header("Content-Type") ?: ""
                if (verbose) ShindenLog.d(tag, "HEAD $url -> ${resp.code} type=$type")
                return resp.code to type
            }
        }

        val (_, directType) = runCatching { contentTypeOf(downloadUrl) }.getOrDefault(-1 to "")
        if (directType.startsWith("video/")) {
            if (verbose) ShindenLog.d(tag, "Download URL returns video directly ($directType)")
            return downloadUrl
        }

        val page =
            runCatching {
                client.newCall(GET(downloadUrl, baseHeaders)).execute().use { resp ->
                    if (verbose) ShindenLog.d(tag, "GET $downloadUrl -> ${resp.code} final=${resp.request.url}")
                    resp.body?.string() ?: ""
                }
            }.getOrDefault("")
        val title = Regex("<title>([^<]*)</title>").find(page)?.groupValues?.get(1)
        if (verbose) ShindenLog.d(tag, "Page len=${page.length} title=${title ?: "unknown"}")
        val confirm = Regex("""name="confirm" value="([^"]*)"""").find(page)?.groupValues?.get(1)
        val uuid = Regex("""name="uuid" value="([^"]*)"""").find(page)?.groupValues?.get(1)
        if (verbose) ShindenLog.d(tag, "confirm=${confirm ?: "null"} uuid=${uuid ?: "null"}")

        val sep = if (downloadUrl.contains("?")) "&" else "?"
        if (confirm != null && uuid != null) {
            val finalUrl = "$downloadUrl${sep}confirm=$confirm&uuid=$uuid"
            val (code, confirmedType) = runCatching { contentTypeOf(finalUrl) }.getOrDefault(-1 to "")
            if (confirmedType.startsWith("video/")) {
                if (verbose) ShindenLog.d(tag, "Resolved via confirm+uuid ($confirmedType)")
                return finalUrl
            }
            if (verbose) ShindenLog.d(tag, "Confirmed HEAD not video: code=$code type=$confirmedType")
        } else {
            val finalUrl = "$downloadUrl${sep}confirm=${confirm ?: "t"}"
            val (code, type) = runCatching { contentTypeOf(finalUrl) }.getOrDefault(-1 to "")
            if (type.startsWith("video/")) {
                if (verbose) ShindenLog.d(tag, "Resolved via confirm=t only ($type)")
                return finalUrl
            }
            if (verbose) ShindenLog.d(tag, "confirm-only HEAD not video: code=$code type=$type")
        }
        ShindenLog.w(tag, "Could not resolve download URL as video stream")
        return null
    }

    private fun getAudioQualityFromMime(mime: String): String = when {
        mime.contains("opus") -> "Audio Opus"
        mime.contains("mp4a") -> "Audio AAC"
        mime.contains("audio/") -> "Audio"
        else -> "Audio"
    }

    private fun getQualityFromItag(itag: Int, height: Int): String = when (itag) {
        18, 43, 82, 134 -> "360p"

        22, 45, 84, 136 -> "720p"

        37, 46, 85, 137 -> "1080p"

        59, 44, 135 -> "480p"

        83, 133 -> "240p"

        298 -> "720p"

        299 -> "1080p"

        else -> when {
            height >= 1080 -> "1080p"
            height >= 720 -> "720p"
            height >= 480 -> "480p"
            height >= 360 -> "360p"
            height >= 240 -> "240p"
            else -> "Unknown"
        }
    }

    private fun getAudioQualityLabel(transcode: AdaptiveTranscode): String {
        val metadata = transcode.transcodeMetadata
        return when {
            metadata.audioCodecString != null -> {
                val bitrate = metadata.maxContainerBitrate
                when {
                    bitrate >= 192000 -> "High Quality"
                    bitrate >= 128000 -> "Medium Quality"
                    else -> "Standard Quality"
                }
            }

            else -> "Audio"
        }
    }

    companion object {
        const val TIMEOUT_SEC: Long = 10
        private val VIDEO_REGEX by lazy { Regex(".*/playback.*") }
        private val FILE_ID_REGEX by lazy { Regex(".*/drive/media/([^/?]+).*") }
        private val DOWNLOAD_REGEX by lazy { Regex(".*(drive\\.usercontent\\.google\\.com/download|/uc\\?.*export=download).*") }
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
    }
}
