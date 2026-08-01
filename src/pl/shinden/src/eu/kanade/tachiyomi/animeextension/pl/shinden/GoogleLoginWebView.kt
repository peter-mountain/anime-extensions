package eu.kanade.tachiyomi.animeextension.pl.shinden

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

object GoogleLoginWebView {

    @SuppressLint("SetJavaScriptEnabled")
    fun open(context: Context) {
        val activity = context as? Activity
            ?: (context as? android.content.ContextWrapper)?.baseContext as? Activity
            ?: return

        val webView = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowContentAccess = true
            settings.setSupportMultipleWindows(false)
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean = false

                override fun onPageFinished(view: WebView?, url: String?) {
                    val cookies = CookieManager.getInstance().getCookie("https://accounts.google.com")
                    if (cookies?.contains("SID") == true || cookies?.contains("SSID") == true) {
                        CookieManager.getInstance().flush()
                    }
                }
            }
            webChromeClient = WebChromeClient()
        }

        webView.loadUrl("https://accounts.google.com/signin")

        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen).apply {
            setContentView(webView)
            window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
            )
            setOnDismissListener {
                CookieManager.getInstance().flush()
            }
        }

        dialog.show()

        webView.requestFocus()
        webView.postDelayed({
            webView.requestFocus()
            webView.dispatchWindowFocusChanged(true)
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(webView, InputMethodManager.SHOW_FORCED)
        }, 500)
    }
}
