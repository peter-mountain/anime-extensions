package eu.kanade.tachiyomi.animeextension.pl.shinden

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.util.TypedValue
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.ExtLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

object ShindenLoginWebView {

    private const val TAG = "ShindenLogin"

    @SuppressLint("SetJavaScriptEnabled")
    fun open(
        context: Context,
        client: OkHttpClient,
        headers: okhttp3.Headers,
        isLoggedIn: Boolean = false,
        savedUserId: String? = null,
        savedDisplayName: String? = null,
        onLoginSuccess: (userId: String, displayName: String) -> Unit,
        onLogout: () -> Unit,
    ) {
        val activity = context as? Activity
            ?: (context as? android.content.ContextWrapper)?.baseContext as? Activity
            ?: return

        val accentColor = getAccentColor(activity)
        val accentDark = darkenColor(accentColor, 0.35f)

        val webView = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowContentAccess = true
            settings.setSupportMultipleWindows(false)
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.startsWith("https://shinden.pl")) {
                        view?.loadUrl(url, emptyMap())
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (isLoggedIn && view != null && savedUserId != null && savedDisplayName != null) {
                        view.postDelayed({
                            view.evaluateJavascript(
                                "onLoginSuccess('$savedUserId', '${escapeJs(savedDisplayName)}')",
                                null,
                            )
                            Thread {
                                val data = parseProfile(client, headers, savedUserId, savedDisplayName)
                                if (data.isNotEmpty()) {
                                    view.post {
                                        view.evaluateJavascript("onProfileData($data)", null)
                                    }
                                }
                            }.start()
                        }, 300)
                    }
                }
            }
            webChromeClient = WebChromeClient()
        }

        // JS bridge
        val jsInterface = object {
            @JavascriptInterface
            fun login(username: String, password: String) {
                performLogin(
                    context = activity,
                    webView = webView,
                    client = client,
                    headers = headers,
                    username = username,
                    password = password,
                    onLoginSuccess = onLoginSuccess,
                )
            }

            @JavascriptInterface
            fun logout() {
                onLogout()
                webView.post {
                    // Switch to login view immediately
                    webView.evaluateJavascript("showView('login-view')", null)
                    // Clear form fields
                    webView.evaluateJavascript(
                        "document.getElementById('username').value='';" +
                            "document.getElementById('password').value='';" +
                            "document.getElementById('error-msg').textContent='';",
                        null,
                    )
                }
            }
        }
        webView.addJavascriptInterface(jsInterface, "AndroidBridge")

        // Load HTML with accent color injected
        val html = LOGIN_HTML
            .replace("__ACCENT__", accentColor)
            .replace("__ACCENT_DARK__", accentDark)
        webView.loadDataWithBaseURL("https://shinden.pl", html, "text/html", "UTF-8", null)

        val dialog = Dialog(
            activity,
            android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen,
        ).apply {
            setContentView(webView)
            window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
            )
        }

        dialog.show()

        webView.requestFocus()
        webView.postDelayed({
            webView.requestFocus()
            webView.dispatchWindowFocusChanged(true)
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(webView, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
        }, 500)
    }

    // ================================ Login logic ================================

    private fun performLogin(
        context: Context,
        webView: WebView,
        client: OkHttpClient,
        headers: okhttp3.Headers,
        username: String,
        password: String,
        onLoginSuccess: (userId: String, displayName: String) -> Unit,
    ) {
        Thread {
            try {
                // Step 1: GET login page
                val getLogin = client.newCall(
                    Request.Builder()
                        .url("https://shinden.pl/main/0/login")
                        .headers(headers)
                        .build(),
                ).execute()
                getLogin.close()
                ExtLog.d(TAG, "login step1: http=${getLogin.code}")

                // Step 2: POST login form
                val body = okhttp3.FormBody.Builder()
                    .add("username", username)
                    .add("password", password)
                    .add("remember", "on")
                    .add("login", "")
                    .build()

                val postResponse = client.newCall(
                    Request.Builder()
                        .url("https://shinden.pl/main/0/login")
                        .headers(
                            headers.newBuilder()
                                .add("Referer", "https://shinden.pl/main/0/login")
                                .add("Origin", "https://shinden.pl")
                                .add("Content-Type", "application/x-www-form-urlencoded")
                                .build(),
                        )
                        .post(body)
                        .build(),
                ).execute()
                val postCode = postResponse.code
                postResponse.close()
                ExtLog.d(TAG, "login step2: http=$postCode")

                if (postCode !in 200..399) {
                    webView.post {
                        webView.evaluateJavascript("onLoginFailed('Błąd HTTP: $postCode')", null)
                    }
                    return@Thread
                }

                // Step 3: GET main page to verify session
                val mainResponse = client.newCall(
                    Request.Builder()
                        .url("https://shinden.pl/main")
                        .headers(headers)
                        .build(),
                ).execute()
                val mainBody = mainResponse.body?.string() ?: ""
                mainResponse.close()

                val userIdMatch = Regex("""_Storage\.userId\s*=\s*(\d+)""").find(mainBody)
                if (userIdMatch == null) {
                    val hasUserMenu = mainBody.contains("user-panel") || mainBody.contains("logout")
                    if (!hasUserMenu) {
                        webView.post {
                            webView.evaluateJavascript(
                                "onLoginFailed('Nie udało się zalogować. Sprawdź dane logowania.')",
                                null,
                            )
                        }
                        return@Thread
                    }
                }

                val userId = userIdMatch?.groupValues?.get(1) ?: ""
                val usernameMatch = Regex("""_Storage\.username\s*=\s*['"](.*?)['"]""").find(mainBody)
                    ?: Regex("""<title>([^<]+)\s*\(użytkownik\)""").find(mainBody)
                val displayName = usernameMatch?.groupValues?.get(1)?.trim() ?: username

                ExtLog.d(TAG, "login OK: userId=$userId displayName=$displayName")

                // Parse profile page for stats
                val profileData = parseProfile(client, headers, userId, displayName)

                // Notify WebView
                webView.post {
                    webView.evaluateJavascript("onLoginSuccess('$userId', '${escapeJs(displayName)}')", null)
                    if (profileData.isNotEmpty()) {
                        webView.evaluateJavascript("onProfileData($profileData)", null)
                    }
                }

                // Notify Shinden.kt
                onLoginSuccess(userId, displayName)
            } catch (e: Exception) {
                ExtLog.e(TAG, "login error: ${e.message}", e)
                webView.post {
                    webView.evaluateJavascript("onLoginFailed('${escapeJs(e.message ?: "Nieznany błąd")}')", null)
                }
            }
        }.start()
    }

    // ================================ Profile parsing ================================

    private fun parseProfile(
        client: OkHttpClient,
        headers: okhttp3.Headers,
        userId: String,
        displayName: String,
    ): String {
        return try {
            // Build profile URL from userId
            val slug = displayName.lowercase().replace(" ", "-")
            val profileUrl = "https://shinden.pl/user/$userId-$slug"

            val response = client.newCall(
                Request.Builder().url(profileUrl).headers(headers).build(),
            ).execute()
            val body = response.body?.string() ?: ""
            response.close()

            if (body.isBlank()) return ""

            val doc = Jsoup.parse(body)

            // Avatar
            val avatarImg = doc.selectFirst("img.avatar-image.av-size225x350")
            val avatarUrl = avatarImg?.attr("src") ?: ""

            // Rank
            val rankDd = doc.selectFirst("dl.stats")
            var rank = ""
            if (rankDd != null) {
                val dts = rankDd.select("dt")
                val dds = rankDd.select("dd")
                for (i in dts.indices) {
                    if (dts[i].text().contains("Ranga")) {
                        rank = dds.getOrNull(i)?.text()?.trim() ?: ""
                        break
                    }
                }
            }

            // Anime stats
            val episodesTable = doc.selectFirst("table.episodes")
            var titles = 0
            var episodes = 0
            var rewatch = 0
            if (episodesTable != null) {
                val rows = episodesTable.select("tr")
                for (row in rows) {
                    val cells = row.select("td")
                    if (cells.size >= 2) {
                        when {
                            cells[0].text().contains("Tytułów") -> titles = cells[1].text().trim().toIntOrNull() ?: 0
                            cells[0].text().contains("Epizodów") -> episodes = cells[1].text().trim().toIntOrNull() ?: 0
                            cells[0].text().contains("Rewatch") -> rewatch = cells[1].text().trim().toIntOrNull() ?: 0
                        }
                    }
                }
            }

            // Watch time
            val totalTimeEl = doc.selectFirst(".total-time strong")
            val watchTime = totalTimeEl?.text()?.replace("\\u00a0", " ")?.trim() ?: ""

            // Statuses
            var watching = 0
            var completed = 0
            var planned = 0
            var hold = 0
            var dropped = 0
            var skip = 0
            val statusesTable = doc.selectFirst("table.anime-statuses")
            if (statusesTable != null) {
                val rows = statusesTable.select("tr")
                for (row in rows) {
                    val cells = row.select("td")
                    if (cells.size >= 2) {
                        val label = cells[0].text()
                        val value = cells[1].text().trim().toIntOrNull() ?: 0
                        when {
                            label.contains("Oglądam") -> watching = value
                            label.contains("Obejrzane") -> completed = value
                            label.contains("Planuję") || label.contains("Planuje") -> planned = value
                            label.contains("Wstrzymane") -> hold = value
                            label.contains("Porzucone") -> dropped = value
                            label.contains("Pomijam") -> skip = value
                        }
                    }
                }
            }

            buildString {
                append("{")
                append("\"userId\":\"$userId\",")
                append("\"displayName\":\"${escapeJs(displayName)}\",")
                append("\"avatarUrl\":\"${escapeJs(avatarUrl)}\",")
                append("\"rank\":\"${escapeJs(rank)}\",")
                append("\"titles\":$titles,")
                append("\"episodes\":$episodes,")
                append("\"rewatch\":$rewatch,")
                append("\"watchTime\":\"${escapeJs(watchTime)}\",")
                append("\"watching\":$watching,")
                append("\"completed\":$completed,")
                append("\"planned\":$planned,")
                append("\"hold\":$hold,")
                append("\"dropped\":$dropped,")
                append("\"skip\":$skip")
                append("}")
            }
        } catch (e: Exception) {
            ExtLog.e(TAG, "parseProfile error: ${e.message}", e)
            ""
        }
    }

    // ================================ Theme helpers ================================

    private fun getAccentColor(context: Context): String {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(
            android.R.attr.colorPrimary,
            typedValue,
            true,
        )
        return String.format("#%06X", 0xFFFFFF and typedValue.data)
    }

    private fun darkenColor(hex: String, factor: Float): String {
        val color = android.graphics.Color.parseColor(hex)
        val r = (android.graphics.Color.red(color) * (1 - factor)).toInt()
        val g = (android.graphics.Color.green(color) * (1 - factor)).toInt()
        val b = (android.graphics.Color.blue(color) * (1 - factor)).toInt()
        return String.format("#%02X%02X%02X", r, g, b)
    }

    private fun escapeJs(s: String): String = s.replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    // ================================ HTML Template ================================

    private val LOGIN_HTML = """
<!DOCTYPE html>
<html lang="pl">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<title>Shinden</title>
<style>
  :root {
    --accent: __ACCENT__;
    --accent-dark: __ACCENT_DARK__;
    --accent-glow: rgba(132,15,173,0.25);
    --bg: #121218;
    --surface: #1c1c28;
    --surface-2: #24243a;
    --text: #eae8f2;
    --text-dim: #8a889a;
    --border: #2e2e48;
    --success: #4caf50;
    --error: #ef5350;
  }

  * { box-sizing: border-box; margin: 0; padding: 0; }

  body {
    font-family: 'Segoe UI', system-ui, -apple-system, sans-serif;
    background: var(--bg);
    color: var(--text);
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 16px;
    -webkit-tap-highlight-color: transparent;
  }

  .card {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 20px;
    width: 100%;
    max-width: 400px;
    overflow: hidden;
    box-shadow: 0 12px 40px rgba(0,0,0,0.5);
  }

  /* ══════ LOGIN HEADER ══════ */
  .login-header {
    text-align: center;
    padding: 40px 24px 28px;
    background: linear-gradient(160deg, var(--accent), var(--accent-dark));
    position: relative;
  }
  .login-header::after {
    content: '';
    position: absolute;
    bottom: 0; left: 0; right: 0;
    height: 40px;
    background: linear-gradient(to bottom, transparent, var(--surface));
  }
  .brand {
    font-family: 'Comfortaa', 'Segoe UI', sans-serif;
    font-size: 32px;
    font-weight: 700;
    color: #fff;
    letter-spacing: 3px;
    text-shadow: 0 0 20px rgba(255,255,255,0.15), 0 2px 4px rgba(0,0,0,0.3);
    position: relative;
    z-index: 1;
  }
  .brand-outline {
    position: relative;
    display: inline-block;
  }
  .brand-outline::before {
    content: 'SHINDEN';
    position: absolute;
    top: 0; left: 0;
    font-family: 'Comfortaa', 'Segoe UI', sans-serif;
    font-size: 32px;
    font-weight: 700;
    letter-spacing: 3px;
    color: transparent;
    -webkit-text-stroke: 1.5px rgba(255,255,255,0.2);
    z-index: 0;
  }
  .login-subtitle {
    font-size: 13px;
    color: rgba(255,255,255,0.6);
    margin-top: 8px;
  }

  /* ══════ FORM ══════ */
  .form { padding: 24px 24px 28px; }
  .field { margin-bottom: 16px; }
  .field label {
    display: block;
    font-size: 11px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.8px;
    color: var(--text-dim);
    margin-bottom: 6px;
  }
  .field input {
    width: 100%;
    padding: 13px 14px;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 12px;
    color: var(--text);
    font-size: 15px;
    outline: none;
    transition: border-color 0.2s, box-shadow 0.2s;
  }
  .field input:focus {
    border-color: var(--accent);
    box-shadow: 0 0 0 3px var(--accent-glow);
  }
  .field input::placeholder { color: var(--text-dim); opacity: 0.4; }

  .btn {
    width: 100%;
    padding: 14px;
    border: none;
    border-radius: 12px;
    font-size: 15px;
    font-weight: 600;
    cursor: pointer;
    transition: background 0.2s, transform 0.1s;
    color: #fff;
    background: var(--accent);
    margin-top: 4px;
  }
  .btn:hover { background: var(--accent-dark); }
  .btn:active { transform: scale(0.98); }
  .btn:disabled { opacity: 0.5; cursor: not-allowed; }

  .error-msg {
    text-align: center;
    font-size: 13px;
    color: var(--error);
    margin-top: 14px;
    min-height: 18px;
  }

  .hint {
    text-align: center;
    font-size: 11px;
    color: var(--text-dim);
    margin-top: 18px;
    line-height: 1.6;
  }

  /* ══════ SPINNER ══════ */
  .spinner { display: none; text-align: center; padding: 48px; }
  .spinner::after {
    content: '';
    display: inline-block;
    width: 30px; height: 30px;
    border: 3px solid var(--border);
    border-top-color: var(--accent);
    border-radius: 50%;
    animation: spin 0.7s linear infinite;
  }
  @keyframes spin { to { transform: rotate(360deg); } }

  /* ══════ PROFILE ══════ */
  #profile-view { display: none; }

  .avatar-ring {
    width: 80px; height: 80px;
    border-radius: 50%;
    padding: 3px;
    background: linear-gradient(135deg, rgba(255,255,255,0.3), rgba(255,255,255,0.05));
    margin: 24px auto 12px;
    position: relative;
    z-index: 2;
  }
  .avatar-inner {
    width: 100%; height: 100%;
    border-radius: 50%;
    background: var(--surface-2);
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
    border: 2px solid var(--surface);
  }
  .avatar-inner img { width: 100%; height: 100%; object-fit: cover; }
  .avatar-inner .initials {
    font-size: 28px; font-weight: 700; color: var(--accent);
  }

  .profile-body { padding: 4px 24px 24px; }

  .profile-name {
    text-align: center;
    font-size: 18px;
    font-weight: 600;
    margin-bottom: 2px;
  }
  .profile-rank {
    text-align: center;
    font-size: 12px;
    color: var(--text-dim);
    margin-bottom: 16px;
  }
  .rank-badge {
    display: inline-block;
    padding: 2px 10px;
    border-radius: 20px;
    background: var(--surface-2);
    border: 1px solid var(--border);
    font-size: 11px;
    color: var(--accent);
    font-weight: 600;
    letter-spacing: 0.5px;
  }

  .stats-row {
    display: flex;
    justify-content: center;
    gap: 24px;
    margin: 16px 0;
    padding: 14px 0;
    border-top: 1px solid var(--border);
    border-bottom: 1px solid var(--border);
  }
  .stat { text-align: center; min-width: 56px; }
  .stat .num { font-size: 20px; font-weight: 700; color: var(--text); }
  .stat .lbl {
    font-size: 10px;
    color: var(--text-dim);
    text-transform: uppercase;
    letter-spacing: 0.5px;
    margin-top: 2px;
  }

  .status-bar {
    display: flex;
    height: 6px;
    border-radius: 3px;
    overflow: hidden;
    margin: 12px 0 16px;
    background: var(--bg);
  }
  .status-bar .seg { height: 100%; transition: width 0.5s; }
  .seg-watching { background: var(--success); }
  .seg-completed { background: var(--accent); }
  .seg-planned { background: #42a5f5; }
  .seg-other { background: var(--border); }

  .statuses {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 6px 16px;
    margin-bottom: 20px;
  }
  .status-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 13px;
    padding: 6px 0;
  }
  .status-item .dot {
    width: 8px; height: 8px;
    border-radius: 50%;
    margin-right: 8px;
    flex-shrink: 0;
  }
  .status-item .name { color: var(--text-dim); flex: 1; }
  .status-item .val {
    font-weight: 600;
    color: var(--text);
    min-width: 20px;
    text-align: right;
  }

  .watch-time {
    text-align: center;
    font-size: 13px;
    color: var(--text-dim);
    margin-bottom: 20px;
  }
  .watch-time strong { color: var(--text); font-weight: 600; }

  .online-dot {
    display: inline-block;
    width: 7px; height: 7px;
    border-radius: 50%;
    background: var(--success);
    margin-right: 4px;
    vertical-align: middle;
    box-shadow: 0 0 6px var(--success);
  }

  .logout-btn {
    width: 100%;
    padding: 12px;
    border: 1.5px solid var(--border);
    border-radius: 12px;
    background: transparent;
    color: var(--text-dim);
    font-size: 13px;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.2s;
  }
  .logout-btn:hover { border-color: var(--error); color: var(--error); }
</style>
</head>
<body>

<div class="card">

  <!-- ════════ LOGIN ════════ -->
  <div id="login-view">
    <div class="login-header">
      <div class="brand-outline"><span class="brand">SHINDEN</span></div>
      <div class="login-subtitle">Zaloguj się do swojego konta</div>
    </div>
    <div class="form">
      <div class="field">
        <label>Nazwa użytkownika</label>
        <input type="text" id="username" placeholder="Nazwa użytkownika" autocomplete="username">
      </div>
      <div class="field">
        <label>Hasło</label>
        <input type="password" id="password" placeholder="••••••••" autocomplete="current-password">
      </div>
      <button class="btn" id="login-btn" onclick="doLogin()">Zaloguj się</button>
      <div class="error-msg" id="error-msg"></div>
      <div class="hint">
        Logowanie odblokowuje pełniejszą listę odcinków<br>
        i dostęp do listy anime.
      </div>
    </div>
  </div>

  <!-- ════════ LOADING ════════ -->
  <div class="spinner" id="loading-view"></div>

  <!-- ════════ PROFILE ════════ -->
  <div id="profile-view">
    <div class="profile-body">
      <div class="avatar-ring">
        <div class="avatar-inner" id="avatar-box">
          <span class="initials" id="avatar-letter">?</span>
        </div>
      </div>
      <div class="profile-name" id="profile-name">—</div>
      <div class="profile-rank"><span class="rank-badge" id="profile-rank">—</span></div>

      <div class="stats-row">
        <div class="stat">
          <div class="num" id="stat-titles">—</div>
          <div class="lbl">Tytułów</div>
        </div>
        <div class="stat">
          <div class="num" id="stat-episodes">—</div>
          <div class="lbl">Epizodów</div>
        </div>
        <div class="stat">
          <div class="num" id="stat-rewatch">—</div>
          <div class="lbl">Rewatch</div>
        </div>
      </div>

      <div class="watch-time">
        <span class="online-dot"></span>Czas oglądania: <strong id="stat-time">—</strong>
      </div>

      <div class="status-bar" id="status-bar"></div>

      <div class="statuses">
        <div class="status-item">
          <span class="dot" style="background:var(--success)"></span>
          <span class="name">Oglądam</span>
          <span class="val" id="st-watching">0</span>
        </div>
        <div class="status-item">
          <span class="dot" style="background:var(--accent)"></span>
          <span class="name">Obejrzane</span>
          <span class="val" id="st-completed">0</span>
        </div>
        <div class="status-item">
          <span class="dot" style="background:#42a5f5"></span>
          <span class="name">Planuję</span>
          <span class="val" id="st-planned">0</span>
        </div>
        <div class="status-item">
          <span class="dot" style="background:#ffa726"></span>
          <span class="name">Wstrzymane</span>
          <span class="val" id="st-hold">0</span>
        </div>
        <div class="status-item">
          <span class="dot" style="background:var(--error)"></span>
          <span class="name">Porzucone</span>
          <span class="val" id="st-dropped">0</span>
        </div>
        <div class="status-item">
          <span class="dot" style="background:var(--border)"></span>
          <span class="name">Pomijam</span>
          <span class="val" id="st-skip">0</span>
        </div>
      </div>

      <button class="logout-btn" onclick="doLogout()">Wyloguj się</button>
    </div>
  </div>

</div>

<script>
  function showView(id) {
    ['login-view','loading-view','profile-view'].forEach(function(v) {
      document.getElementById(v).style.display = v === id ? 'block' : 'none';
    });
  }

  function onLoginSuccess(userId, displayName) {
    document.getElementById('loading-view').style.display = 'none';
    document.getElementById('login-view').style.display = 'none';
    document.getElementById('profile-name').textContent = displayName || '—';
    document.getElementById('avatar-letter').textContent = (displayName || '?')[0].toUpperCase();
    document.getElementById('profile-view').style.display = 'block';
  }

  function onProfileData(json) {
    var d = typeof json === 'string' ? JSON.parse(json) : json;
    if (d.displayName) {
      document.getElementById('profile-name').textContent = d.displayName;
      document.getElementById('avatar-letter').textContent = d.displayName[0].toUpperCase();
    }
    if (d.avatarUrl) {
      document.getElementById('avatar-box').innerHTML =
        '<img src="' + d.avatarUrl + '" alt="avatar">';
    }
    if (d.rank) document.getElementById('profile-rank').textContent = d.rank;
    if (d.titles != null) document.getElementById('stat-titles').textContent = d.titles;
    if (d.episodes != null) document.getElementById('stat-episodes').textContent = d.episodes;
    if (d.rewatch != null) document.getElementById('stat-rewatch').textContent = d.rewatch;
    if (d.watchTime) document.getElementById('stat-time').textContent = d.watchTime;
    if (d.watching != null) document.getElementById('st-watching').textContent = d.watching;
    if (d.completed != null) document.getElementById('st-completed').textContent = d.completed;
    if (d.planned != null) document.getElementById('st-planned').textContent = d.planned;
    if (d.hold != null) document.getElementById('st-hold').textContent = d.hold;
    if (d.dropped != null) document.getElementById('st-dropped').textContent = d.dropped;
    if (d.skip != null) document.getElementById('st-skip').textContent = d.skip;

    var total = (d.watching||0) + (d.completed||0) + (d.planned||0) +
                (d.hold||0) + (d.dropped||0) + (d.skip||0);
    if (total > 0) {
      var bar = document.getElementById('status-bar');
      bar.innerHTML = '';
      var segs = [
        ['seg-watching', d.watching||0],
        ['seg-completed', d.completed||0],
        ['seg-planned', d.planned||0],
        ['seg-other', (d.hold||0)+(d.dropped||0)+(d.skip||0)]
      ];
      segs.forEach(function(s) {
        if (s[1] > 0) {
          var el = document.createElement('div');
          el.className = 'seg ' + s[0];
          el.style.width = (s[1]/total*100) + '%';
          bar.appendChild(el);
        }
      });
    }
    document.getElementById('profile-view').style.display = 'block';
  }

  function onLoginFailed(reason) {
    showView('login-view');
    document.getElementById('error-msg').textContent = reason || 'Logowanie nie powiodło się';
    document.getElementById('login-btn').disabled = false;
    document.getElementById('login-btn').textContent = 'Zaloguj się';
  }

  function onLogoutSuccess() {
    showView('login-view');
    document.getElementById('username').value = '';
    document.getElementById('password').value = '';
    document.getElementById('error-msg').textContent = '';
    document.getElementById('stat-titles').textContent = '—';
    document.getElementById('stat-episodes').textContent = '—';
    document.getElementById('stat-rewatch').textContent = '—';
    document.getElementById('stat-time').textContent = '—';
    document.getElementById('st-watching').textContent = '0';
    document.getElementById('st-completed').textContent = '0';
    document.getElementById('st-planned').textContent = '0';
    document.getElementById('st-hold').textContent = '0';
    document.getElementById('st-dropped').textContent = '0';
    document.getElementById('st-skip').textContent = '0';
    document.getElementById('status-bar').innerHTML = '';
    document.getElementById('avatar-box').innerHTML =
      '<span class="initials">?</span>';
  }

  function doLogin() {
    var u = document.getElementById('username').value.trim();
    var p = document.getElementById('password').value;
    if (!u || !p) {
      document.getElementById('error-msg').textContent = 'Wpisz login i hasło';
      return;
    }
    document.getElementById('error-msg').textContent = '';
    document.getElementById('login-btn').disabled = true;
    document.getElementById('login-btn').textContent = 'Logowanie…';
    showView('loading-view');
    window.AndroidBridge.login(u, p);
  }

  function doLogout() {
    window.AndroidBridge.logout();
  }
</script>

</body>
</html>
    """.trimIndent()
}
