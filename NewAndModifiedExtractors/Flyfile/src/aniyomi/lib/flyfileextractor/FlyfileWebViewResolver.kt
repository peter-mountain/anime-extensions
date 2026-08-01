package aniyomi.lib.flyfileextractor

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.ShindenLog
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FlyfileWebViewResolver(private val client: OkHttpClient) {
    private val context: Application = Injekt.get()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val tag = "FlyfileWV"
    private var resultUrl: String? = null

    class JsInterface(private val latch: CountDownLatch) {
        var hlsUrl: String? = null

        @JavascriptInterface
        fun onHlsUrl(url: String) {
            hlsUrl = url
            latch.countDown()
        }

        @JavascriptInterface
        fun onPlayback(json: String) {
            ShindenLog.d("FlyfileWV", "js:playback:${json.take(200)}")
        }

        @JavascriptInterface
        fun log(msg: String) {
            ShindenLog.d("FlyfileWV", "js:$msg")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(embedUrl: String, ua: String): String? {
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        val jsi = JsInterface(latch)
        val ifName = randomString()

        logDebug("resolve embedUrl=$embedUrl")

        handler.post {
            val wv = WebView(context)
            webView = wv
            with(wv.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                userAgentString = ua
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            wv.addJavascriptInterface(jsi, ifName)
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return null
                    val method = request?.method ?: "GET"

                    if (reqUrl.contains("/streaming/assign/")) {
                        logDebug("intercept_streaming_passthrough: $method $reqUrl")
                    }

                    if (reqUrl.contains(".m3u8") && !reqUrl.contains("/assets/")) {
                        logDebug("intercept_m3u8: $reqUrl")
                        resultUrl = reqUrl
                        latch.countDown()
                        return super.shouldInterceptRequest(view, request)
                    }

                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    logDebug("page_finished: $url")
                    injectMonitor(view, ifName)
                }
            }

            wv.loadUrl(embedUrl)
        }

        return try {
            latch.await(25, TimeUnit.SECONDS)
            logDebug("latch_result url=${jsi.hlsUrl} resultUrl=$resultUrl")
            jsi.hlsUrl ?: resultUrl
        } catch (e: InterruptedException) {
            logDebug("latch_timeout")
            null
        } finally {
            handler.post { webView?.destroy() }
        }
    }

    private fun injectMonitor(view: WebView?, ifName: String) {
        if (view == null) return
        try {
            val js = """
                javascript:(function() {
                    var origFetch = window.fetch;
                    window.fetch = function(input, init) {
                        var url = typeof input === 'string' ? input : input.url;
                        if (url && url.indexOf('/streaming/assign/') !== -1) {
                            return origFetch.apply(this, arguments).then(function(resp) {
                                resp.clone().json().then(function(data) {
                                    try {
                                        var hlsUrl = '';
                                        if (data.url && data.token) {
                                            hlsUrl = data.url + '/hls/' + data.token + '/master.m3u8';
                                        }
                                        window.$ifName.onHlsUrl(hlsUrl);
                                    } catch(e) {}
                                });
                                return resp;
                            });
                        }
                        return origFetch.apply(this, arguments);
                    };
                    console.log('Flyfile fetch interceptor installed');
                })();
            """.trimIndent()
            view.post { view.evaluateJavascript(js, null) }
        } catch (e: Exception) {
            logDebug("inject_err:${e.message}")
        }
    }

    private fun logDebug(msg: String) {
        ShindenLog.d(tag, msg)
    }

    companion object {
        fun randomString(): String {
            val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val sb = StringBuilder(16)
            val rnd = java.security.SecureRandom()
            for (i in 0 until 16) sb.append(chars[rnd.nextInt(chars.length)])
            return sb.toString()
        }
    }
}
