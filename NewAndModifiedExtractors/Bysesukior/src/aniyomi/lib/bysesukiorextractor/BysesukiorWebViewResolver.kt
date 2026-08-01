package aniyomi.lib.bysesukiorextractor

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.ShindenLog
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BysesukiorWebViewResolver(private val client: OkHttpClient) {
    private val context: Application = Injekt.get()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val tag = "BysesukiorWV"
    private var qCookies = ""

    class JsInterface(private val latch: CountDownLatch) {
        var playbackJson: String? = null

        @JavascriptInterface
        fun onPlaybackResponse(json: String) {
            playbackJson = json
            latch.countDown()
        }

        @JavascriptInterface
        fun log(msg: String) {
            ShindenLog.d("BysesukiorWV", "js:$msg")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(embedUrl: String, ua: String, cookies: String = ""): String? {
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        val jsi = JsInterface(latch)
        val ifName = randomString()
        qCookies = cookies

        ShindenLog.d(tag, "resolve embedUrl=$embedUrl cookies=${cookies.take(80)}")

        if (cookies.isNotBlank()) {
            try {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                val pairs = cookies.split(";").map { it.trim() }
                for (pair in pairs) {
                    val name = pair.substringBefore("=").trim()
                    val value = pair.substringAfter("=").trim()
                    if (name.isNotBlank()) {
                        cm.setCookie("https://q8y5z.com", "$name=$value")
                        ShindenLog.d(tag, "set_cookie: $name=$value for q8y5z.com")
                    }
                }
                cm.flush()
            } catch (e: Exception) {
                ShindenLog.d(tag, "cookie_err:${e.message}")
            }
        }

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
            wv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    ShindenLog.d(tag, "[${msg.messageLevel()}] ${msg.message()} : ${msg.sourceId()}:${msg.lineNumber()}")
                    return true
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: ""
                    val method = request?.method ?: "GET"
                    val isMainFrame = request?.isForMainFrame ?: true

                    if (!reqUrl.contains("/assets/") && !reqUrl.contains("logo.svg")) {
                        ShindenLog.d(tag, "req: $method $reqUrl mainFrame=$isMainFrame")
                    }

                    if (!isMainFrame && method == "GET" && reqUrl.startsWith("https://q8y5z.com/")) {
                        val path = reqUrl.substring("https://q8y5z.com/".length)
                        if (!path.startsWith("assets/") && !path.startsWith("api/") && !path.startsWith("js/") && !path.startsWith("player/") && !path.startsWith("images/")) {
                            ShindenLog.d(tag, "intercepting_iframe_html: $reqUrl")
                            return try {
                                val resp = client.newCall(GET(reqUrl)).execute()
                                val bodyBytes = resp.body?.bytes() ?: return super.shouldInterceptRequest(view, request)
                                val html = String(bodyBytes, Charsets.UTF_8)
                                ShindenLog.d(tag, "iframe_html_len=${html.length}")
                                ShindenLog.d(tag, "iframe_html_preview=${html.take(300)}")
                                if (html.startsWith("<!") || html.startsWith("<html") || html.startsWith("<")) {
                                    val hook = buildIframeHook(ifName)
                                    val modified = html.replace("</body>", "$hook\n</body>")
                                        .replace("</BODY>", "$hook\n</BODY>")
                                    ShindenLog.d(tag, "iframe_hook_injected")
                                    WebResourceResponse(
                                        "text/html",
                                        "utf-8",
                                        200,
                                        "ok",
                                        mapOf("content-type" to "text/html; charset=utf-8", "access-control-allow-origin" to "*"),
                                        ByteArrayInputStream(modified.toByteArray(Charsets.UTF_8)),
                                    )
                                } else {
                                    ShindenLog.d(tag, "iframe_not_html, passing through")
                                    super.shouldInterceptRequest(view, request)
                                }
                            } catch (e: Exception) {
                                ShindenLog.d(tag, "iframe_intercept_err:${e.message}")
                                super.shouldInterceptRequest(view, request)
                            }
                        }
                    }

                    if (method == "GET" && reqUrl.startsWith("https://q8y5z.com/api/") && reqUrl.contains("/embed/settings")) {
                        ShindenLog.d(tag, "intercepting_settings: $reqUrl")
                        return try {
                            val hb = okhttp3.Headers.Builder()
                            for ((k, v) in (request?.requestHeaders ?: emptyMap())) {
                                if (k.equals("Cookie", ignoreCase = true)) continue
                                hb.set(k, v)
                            }
                            if (qCookies.isNotBlank()) hb.set("Cookie", qCookies)
                            val resp = client.newCall(GET(reqUrl, hb.build())).execute()
                            val bodyBytes = resp.body?.bytes() ?: return super.shouldInterceptRequest(view, request)
                            val bodyStr = String(bodyBytes, Charsets.UTF_8)
                            ShindenLog.d(tag, "settings_resp_len=${bodyBytes.size} http=${resp.code}")
                            ShindenLog.d(tag, "settings_body=${bodyStr.take(500)}")
                            val modifiedBytes = bodyStr.toByteArray(Charsets.UTF_8)
                            val ct = resp.header("Content-Type", "application/json") ?: "application/json"
                            val reason = resp.message.ifBlank { "OK" }
                            WebResourceResponse(
                                ct,
                                "utf-8",
                                resp.code,
                                reason,
                                mapOf("content-type" to "$ct; charset=utf-8", "access-control-allow-origin" to "*"),
                                ByteArrayInputStream(modifiedBytes),
                            )
                        } catch (e: Exception) {
                            ShindenLog.d(tag, "settings_intercept_err:${e.message}")
                            super.shouldInterceptRequest(view, request)
                        }
                    }

                    if (method == "POST" && reqUrl.startsWith("https://q8y5z.com/api/") && reqUrl.contains("/embed/playback")) {
                        ShindenLog.d(tag, "playback_pass: $reqUrl")
                        return super.shouldInterceptRequest(view, request)
                    }

                    return super.shouldInterceptRequest(view, request)
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    val reqUrl = request?.url?.toString() ?: ""
                    val status = errorResponse?.statusCode ?: -1
                    if (status != 200) {
                        ShindenLog.d(tag, "http_error: ${request?.method} $reqUrl -> $status")
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    ShindenLog.d(tag, "onPageFinished url=$url")
                    injectParentHook(view, ifName)
                    handler.postDelayed({
                        val wv2 = view ?: return@postDelayed
                        val cx = wv2.width / 2.0f
                        val cy = wv2.height / 2.0f
                        val downTime = SystemClock.uptimeMillis()
                        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, cx, cy, 0)
                        val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, cx, cy, 0)
                        wv2.dispatchTouchEvent(down)
                        wv2.dispatchTouchEvent(up)
                        down.recycle()
                        up.recycle()
                        ShindenLog.d(tag, "touch_center_sent")
                        handler.postDelayed({
                            val down2 = MotionEvent.obtain(downTime + 100, downTime + 100, MotionEvent.ACTION_DOWN, cx, cy, 0)
                            val up2 = MotionEvent.obtain(downTime + 100, downTime + 150, MotionEvent.ACTION_UP, cx, cy, 0)
                            wv2.dispatchTouchEvent(down2)
                            wv2.dispatchTouchEvent(up2)
                            down2.recycle()
                            up2.recycle()
                            ShindenLog.d(tag, "touch_center_sent2")
                        }, 2000)
                    }, 1500)
                }
            }
            wv.loadUrl(embedUrl)
        }

        latch.await(90, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        ShindenLog.d(tag, "resolve result=${jsi.playbackJson != null}")
        return jsi.playbackJson
    }

    private fun injectParentHook(view: WebView?, ifName: String) {
        val script = """
            (function() {
                var I = $ifName;
                try { I.log('parent_hook_start'); } catch(e) {}
                try { I.log('parent_cookies=' + document.cookie); } catch(e) {}
                var origPostMessage = window.postMessage;
                if (origPostMessage) {
                    window.postMessage = function(msg, targetOrigin) {
                        try { I.log('parent_postmsg_send: target=' + targetOrigin + ' msg=' + JSON.stringify(msg).substring(0, 200)); } catch(e) {}
                        return origPostMessage.apply(this, arguments);
                    };
                }
                window.addEventListener('message', function(event) {
                    try { I.log('parent_postmsg_recv: from=' + event.origin + ' msg=' + JSON.stringify(event.data).substring(0, 200)); } catch(e) {}
                });
                var origFetch = window.fetch;
                if (typeof origFetch === 'function') {
                    window.fetch = function(url, opts) {
                        var urlStr = typeof url === 'string' ? url : (url && url.url ? url.url : String(url));
                        var method = (opts && opts.method) || 'GET';
                        try { I.log('parent_fetch:' + method + ' ' + urlStr); } catch(e) {}
                        return origFetch.call(window, url, opts).then(function(response) {
                            try { I.log('parent_fetch_resp:' + urlStr + ' -> ' + response.status); } catch(e) {}
                            try {
                                var hdrs = '';
                                response.headers.forEach(function(v, k) { hdrs += k + '=' + v + '; '; });
                                I.log('parent_fetch_hdrs:' + urlStr + ' -> ' + hdrs);
                            } catch(e) {}
                            return response;
                        });
                    };
                }
                try {
                    for (var k in window) {
                        try {
                            var v = window[k];
                            if (typeof v === 'string' && v.length > 100 && v.indexOf('eyJ') !== -1) {
                                I.log('parent_token_var: ' + k + '=' + v.substring(0,60) + '...');
                            }
                        } catch(e) {}
                    }
                } catch(e) { try { I.log('parent_scan_err:' + e.message); } catch(e2) {} }
                try { I.log('parent_hook_done'); } catch(e) {}
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    private fun buildIframeHook(ifName: String): String = """
<script>
(function() {
    var I = $ifName;
    try { I.log('iframe_hook_start'); } catch(e) {}
    window.onerror = function(msg, src, line) {
        try { I.log('IFRAME_ERR:' + msg + ' @ ' + src + ':' + line); } catch(e) {}
    };
    try { I.log('iframe_cookies=' + document.cookie); } catch(e) {}
    var origPostMessage = window.postMessage;
    if (origPostMessage) {
        window.postMessage = function(msg, targetOrigin) {
            try { I.log('iframe_postmsg_send: target=' + targetOrigin + ' msg=' + JSON.stringify(msg).substring(0, 200)); } catch(e) {}
            return origPostMessage.apply(this, arguments);
        };
    }
    window.addEventListener('message', function(event) {
        try { I.log('iframe_postmsg_recv: from=' + event.origin + ' msg=' + JSON.stringify(event.data).substring(0, 200)); } catch(e) {}
    });
    try {
        for (var k in window) {
            try {
                var v = window[k];
                if (typeof v === 'string' && v.length > 100 && (v.indexOf('eyJ') !== -1 || v.indexOf('mAWIJ') !== -1)) {
                    I.log('iframe_global_token: ' + k + '=' + v.substring(0,60) + '...');
                }
            } catch(e) {}
        }
    } catch(e) { try { I.log('iframe_scan_err:' + e.message); } catch(e2) {} }
    try {
        var lsKeysToClear = ['byse_viewer_id', 'byse_device_id', 'byse_attest_token', 'byse_captcha_token', 'byse:captcha_token'];
        for (var ki = 0; ki < lsKeysToClear.length; ki++) {
            var kk = lsKeysToClear[ki];
            if (localStorage.getItem(kk) !== null) {
                I.log('iframe_clearing_ls:' + kk + '=' + localStorage.getItem(kk).substring(0,60));
                localStorage.removeItem(kk);
            }
        }
    } catch(e) { try { I.log('iframe_ls_clear_err:' + e.message); } catch(e2) {} }
    try {
        for (var k in localStorage) { try { I.log('iframe_ls:' + k + '=' + localStorage.getItem(k).substring(0,60)); } catch(e) {} }
    } catch(e) { try { I.log('iframe_ls_err:' + e.message); } catch(e2) {} }
    try {
        for (var k in sessionStorage) { try { I.log('iframe_ss:' + k + '=' + sessionStorage.getItem(k).substring(0,60)); } catch(e) {} }
    } catch(e) { try { I.log('iframe_ss_err:' + e.message); } catch(e2) {} }
    var origFetch = window.fetch;
    if (typeof origFetch === 'function') {
        window.fetch = function(url, opts) {
            var urlStr = typeof url === 'string' ? url : (url && url.url ? url.url : String(url));
            var method = (opts && opts.method) || 'GET';
            try { I.log('iframe_fetch:' + method + ' ' + urlStr); } catch(e) {}
            if (method === 'POST' && urlStr.indexOf('/access/attest') !== -1 && opts && opts.body) {
                try {
                    var parsed = JSON.parse(opts.body);
                    if (parsed.client) {
                        parsed.client.pixel_ratio = 1.25;
                        parsed.client.screen_width = 1536;
                        parsed.client.screen_height = 960;
                        parsed.client.hardware_concurrency = 16;
                        parsed.client.touch_points = 0;
                        parsed.client.pointer_type = 'fine,hover';
                        parsed.client.webgl_vendor = 'Google Inc. (AMD)';
                        parsed.client.webgl_renderer = 'ANGLE (AMD, Radeon HD 3200 Graphics Direct3D11 vs_5_0 ps_5_0), or similar';
                        if (parsed.client.extra) {
                            parsed.client.extra.vendor = '';
                            parsed.client.extra.appVersion = '5.0 (Windows)';
                        }
                        if (parsed.attributes) {
                            parsed.attributes.entropy = 'low';
                        }
                        delete parsed.client.platform;
                        delete parsed.client.platform_version;
                        delete parsed.client.architecture;
                        delete parsed.client.bitness;
                        delete parsed.client.model;
                        delete parsed.client.brand_full_versions;
                        delete parsed.client.device_memory;
                        opts.body = JSON.stringify(parsed);
                        try { I.log('iframe_attest_spoofed'); } catch(e) {}
                    }
                } catch(e) {
                    try { I.log('iframe_attest_spoof_err:' + e.message); } catch(e2) {}
                }
            }
            return origFetch.call(window, url, opts).then(function(response) {
                try { I.log('iframe_fetch_resp:' + urlStr + ' -> ' + response.status); } catch(e) {}
                if (urlStr.indexOf('/api/') !== -1) {
                    try {
                        var hdrStr = '';
                        response.headers.forEach(function(v, k) { hdrStr += k + '=' + v + '; '; });
                        I.log('iframe_resp_hdrs:' + urlStr + ' -> ' + hdrStr);
                    } catch(e) { try { I.log('iframe_hdr_err:' + e.message); } catch(e2) {} }
                }
                if (urlStr.indexOf('/access/attest') !== -1) {
                    response.clone().text().then(function(body) {
                        try { I.log('iframe_attest_body=' + body); } catch(e) { try { I.log('iframe_attest_log_err:' + e.message); } catch(e2) {} }
                        try { I.log('iframe_cookies_after_attest=' + document.cookie); } catch(e) {}
                    });
                }
                if (urlStr.indexOf('/embed/playback') !== -1) {
                    try { I.log('iframe_playback_hit=' + response.status); } catch(e) {}
                    response.clone().text().then(function(body) {
                        try { I.log('iframe_playback_body_len=' + body.length); } catch(e) {}
                        try { I.log('iframe_playback_body=' + body); } catch(e) {}
                        if (response.status === 200) {
                            try { I.onPlaybackResponse(body); } catch(e) { try { I.log('iframe_callback_err:' + e.message); } catch(e2) {} }
                        }
                    });
                }
                return response;
            });
        };
    }
    var origOpen = XMLHttpRequest.prototype.open;
    var origSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(method, url) {
        this._xhrUrl = typeof url === 'string' ? url : String(url);
        this._xhrMethod = method;
        try { I.log('iframe_xhr_open:' + method + ' ' + this._xhrUrl); } catch(e) {}
        return origOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function(body) {
        var self = this;
        var url = self._xhrUrl || '';
        var method = self._xhrMethod || 'GET';
        try { I.log('iframe_xhr_send:' + method + ' ' + url); } catch(e) {}
        self.addEventListener('load', function() {
            try { I.log('iframe_xhr_load:' + url + ' -> ' + self.status); } catch(e) {}
            if (url.indexOf('/api/') !== -1) {
                try {
                    var hdrStr = self.getAllResponseHeaders();
                    I.log('iframe_xhr_hdrs:' + url + ' -> ' + hdrStr.substring(0, 300));
                } catch(e) {}
            }
            if (method === 'POST' && url.indexOf('/embed/playback') !== -1) {
                    try { I.log('iframe_xhr_playback_hit=' + self.status); } catch(e) {}
                    try { I.log('iframe_xhr_playback_body=' + self.responseText.substring(0, 200)); } catch(e) {}
                    if (self.status === 200) {
                        try { I.onPlaybackResponse(self.responseText); } catch(e) { try { I.log('iframe_xhr_callback_err:' + e.message); } catch(e2) {} }
                    }
                }
        });
        return origSend.apply(self, arguments);
    };
    try { I.log('iframe_hook_done'); } catch(e) {}
})();
</script>
    """.trimIndent()

    companion object {
        private fun randomString(length: Int = 10): String {
            val charPool = ('a'..'z') + ('A'..'Z')
            return List(length) { charPool.random() }.joinToString("")
        }
    }
}
