package aniyomi.lib.playmateextractor

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.ShindenLog
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PlaymateExtractor(private val client: OkHttpClient) {

    private val tag = "PlaymateExtractor"
    private val context: Application = Injekt.get()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val playlistUtils by lazy { PlaylistUtils(client) }
    private val androidUA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.134 Mobile Safari/537.36"

    fun videosFromUrl(url: String, prefix: String = "", headers: Headers? = null): List<Video> {
        ShindenLog.d(tag, "Starting WebView resolution for: $url")

        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var m3u8Url: String? = null

        class JsBridge(private val latch: CountDownLatch) {
            @JavascriptInterface
            fun onHlsUrl(url: String) {
                ShindenLog.d(tag, "JS bridge received HLS: ${url.take(120)}")
                m3u8Url = url
                latch.countDown()
            }

            @JavascriptInterface
            fun log(msg: String) {
                ShindenLog.d(tag, "JS: ${msg.take(200)}")
            }
        }

        val bridge = JsBridge(latch)
        val bridgeName = "playmateBridge${System.currentTimeMillis()}"

        @SuppressLint("SetJavaScriptEnabled")
        handler.post {
            val wv = WebView(context)
            webView = wv
            with(wv.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                userAgentString = androidUA
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            wv.addJavascriptInterface(bridge, bridgeName)

            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val reqUrl = request?.url?.toString() ?: return false
                    if (reqUrl.contains("/sandboxed")) {
                        ShindenLog.d(tag, "Blocked sandbox redirect: $reqUrl")
                        return true
                    }
                    return false
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return null
                    if (!reqUrl.contains("/assets/") && !reqUrl.contains("cloudflare") && !reqUrl.contains(".css") && !reqUrl.contains(".png") && !reqUrl.contains(".jpg") && !reqUrl.contains(".svg") && !reqUrl.contains(".woff")) {
                        ShindenLog.d(tag, "INTERCEPT: ${request?.method} ${reqUrl.take(200)}")
                    }
                    if (reqUrl.contains(".m3u8") && !reqUrl.contains("/assets/")) {
                        ShindenLog.d(tag, "Intercepted m3u8: ${reqUrl.take(150)}")
                        m3u8Url = reqUrl
                        latch.countDown()
                    }
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    ShindenLog.d(tag, "Page finished: $url")
                    injectJSHook(view, bridgeName)
                    startJwPolling(view, bridgeName)
                }
            }

            wv.loadUrl(url)
        }

        return try {
            val found = latch.await(20, TimeUnit.SECONDS)
            ShindenLog.d(tag, "Latch result: found=$found m3u8=${m3u8Url?.take(120)}")

            val resolvedUrl = m3u8Url
            if (resolvedUrl.isNullOrBlank()) {
                ShindenLog.e(tag, "No m3u8 URL found after 20s")
                webView?.let { handler.post { it.destroy() } }
                return emptyList()
            }

            val outHeaders = Headers.Builder()
                .set("Referer", "https://playmate.to/")
                .set("Origin", "https://playmate.to")
                .set("User-Agent", androidUA)
                .build()

            val result = playlistUtils.extractFromHls(
                resolvedUrl,
                masterHeaders = outHeaders,
                videoHeaders = outHeaders,
                videoNameGen = { "${prefix}Playmate - $it" },
            )
            ShindenLog.d(tag, "HLS tracks: ${result.size}")

            handler.post { webView?.destroy() }

            result.ifEmpty {
                listOf(Video(resolvedUrl, "${prefix}Playmate HLS", resolvedUrl, outHeaders))
            }
        } catch (e: InterruptedException) {
            ShindenLog.e(tag, "Timeout: ${e.message}")
            handler.post { webView?.destroy() }
            emptyList()
        }
    }

    private fun startJwPolling(view: WebView?, bridgeName: String) {
        view ?: return
        val pollJs = """
            javascript:(function() {
                function checkPlayer() {
                    try {
                        if (window.jwplayer) {
                            var p = jwplayer('jwplayer');
                            if (p && typeof p.getState === 'function') {
                                var state = p.getState();
                                console.log('PM_POLL_STATE:' + state);
                                if (state && typeof p.getPlaylistItem === 'function') {
                                    var item = p.getPlaylistItem();
                                    if (item && item.file) {
                                        console.log('PM_POLL_FILE:' + item.file);
                                        $bridgeName.onHlsUrl(item.file);
                                        return;
                                    }
                                }
                            }
                        }
                    } catch(e) { console.log('PM_POLL_ERR:' + e.message); }
                    setTimeout(checkPlayer, 500);
                }
                checkPlayer();
            })();
        """.trimIndent()
        for (i in 1..20) {
            val delay = i * 500L
            handler.postDelayed({ view.evaluateJavascript(pollJs, null) }, delay)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun injectJSHook(view: WebView?, bridgeName: String) {
        view ?: return
        try {
            val js = """
                javascript:(function() {
                    if (window.jwplayer) {
                        try {
                            var p = jwplayer('jwplayer');
                            if (p && p.setup) {
                                var origSetup = p.setup;
                                p.setup = function(cfg) {
                                    if (cfg && cfg.sources) {
                                        for (var i = 0; i < cfg.sources.length; i++) {
                                            var src = cfg.sources[i];
                                            if (src && src.file && src.file.indexOf('.m3u8') !== -1) {
                                                console.log('PM_M3U8:' + src.file);
                                                $bridgeName.onHlsUrl(src.file);
                                            }
                                        }
                                    }
                                    return origSetup.apply(this, arguments);
                                };
                            }
                        } catch(e) {}
                    }

                    var origFetch = window.fetch;
                    window.fetch = function() {
                        var url = arguments[0];
                        if (typeof url === 'string' && url.indexOf('.m3u8') !== -1) {
                            $bridgeName.onHlsUrl(url);
                        }
                        return origFetch.apply(this, arguments);
                    };

                    var origOpen = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        if (typeof url === 'string' && url.indexOf('.m3u8') !== -1) {
                            $bridgeName.onHlsUrl(url);
                        }
                        return origOpen.apply(this, arguments);
                    };

                    console.log('PM hooks installed');
                })();
            """.trimIndent()
            view.post { view.evaluateJavascript(js, null) }
        } catch (e: Exception) {
            ShindenLog.e(tag, "JS inject error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "PlaymateExtractor"
    }
}
