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

object ShindenListEditor {

    @SuppressLint("SetJavaScriptEnabled")
    fun open(
        context: Context,
        title: String,
        currentItems: String,
        allowReorder: Boolean = false,
        defaultSuggestions: List<String> = emptyList(),
        onSave: (String) -> Unit,
    ) {
        val activity = context as? Activity
            ?: (context as? android.content.ContextWrapper)?.baseContext as? Activity
            ?: return

        val accentColor = getAccentColor(activity)
        val accentDark = darkenColor(accentColor, 0.35f)

        val webView = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportMultipleWindows(false)
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): Boolean = false
            }
            webChromeClient = WebChromeClient()
        }

        var dialogRef: Dialog? = null

        val jsInterface = object {
            @JavascriptInterface
            fun save(itemsJson: String) {
                // itemsJson is a JSON array string like ["item1","item2"]
                val cleaned = itemsJson
                    .removePrefix("[").removeSuffix("]")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.isNotBlank() }
                    .joinToString(",")
                activity.runOnUiThread { onSave(cleaned) }
                activity.runOnUiThread { dialogRef?.dismiss() }
            }

            @JavascriptInterface
            fun dismiss() {
                webView.post { dialogRef?.dismiss() }
            }
        }
        webView.addJavascriptInterface(jsInterface, "EditorBridge")

        val escapedTitle = escapeJs(title)
        val escapedItems = escapeJs(currentItems)
        val escapedSuggestions = escapeJs(defaultSuggestions.joinToString(","))
        val reorderFlag = if (allowReorder) "true" else "false"

        val html = EDITOR_HTML
            .replace("__ACCENT__", accentColor)
            .replace("__ACCENT_DARK__", accentDark)
            .replace("__TITLE__", escapedTitle)
            .replace("__ITEMS__", escapedItems)
            .replace("__SUGGESTIONS__", escapedSuggestions)
            .replace("__REORDER__", reorderFlag)

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
        dialogRef = dialog
        dialog.show()
    }

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

    private val EDITOR_HTML = """
<!DOCTYPE html>
<html lang="pl">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<title>Editor</title>
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
    font-family: 'Segoe UI', system-ui, sans-serif;
    background: var(--bg);
    color: var(--text);
    min-height: 100vh;
    display: flex;
    flex-direction: column;
  }

  /* ══════ HEADER ══════ */
  .header {
    background: linear-gradient(160deg, var(--accent), var(--accent-dark));
    padding: 20px 20px 16px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-shrink: 0;
  }
  .header h1 {
    font-size: 18px;
    font-weight: 600;
    color: #fff;
  }
  .close-btn {
    background: rgba(255,255,255,0.15);
    border: none;
    color: #fff;
    width: 32px; height: 32px;
    border-radius: 50%;
    font-size: 18px;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  /* ══════ CONTENT ══════ */
  .content {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 16px;
    overflow: hidden;
  }

  /* ══════ ADD ROW ══════ */
  .add-row {
    display: flex;
    gap: 8px;
    margin-bottom: 16px;
  }
  .add-row input {
    flex: 1;
    padding: 12px 14px;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 10px;
    color: var(--text);
    font-size: 15px;
    outline: none;
    transition: border-color 0.2s;
  }
  .add-row input:focus {
    border-color: var(--accent);
  }
  .add-row input::placeholder { color: var(--text-dim); opacity: 0.5; }

  .add-btn {
    width: 44px; height: 44px;
    background: var(--accent);
    color: #fff;
    border: none;
    border-radius: 10px;
    font-size: 22px;
    font-weight: 700;
    cursor: pointer;
    flex-shrink: 0;
    transition: background 0.2s;
    display: flex;
    align-items: center;
    justify-content: center;
  }
  .add-btn:hover { background: var(--accent-dark); }

  /* ══════ SUGGESTIONS ══════ */
  .suggestions {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-bottom: 16px;
  }
  .suggestions:empty { display: none; }
  .sug-chip {
    padding: 5px 12px;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 20px;
    color: var(--text-dim);
    font-size: 12px;
    cursor: pointer;
    transition: all 0.2s;
  }
  .sug-chip:hover {
    border-color: var(--accent);
    color: var(--accent);
  }

  /* ══════ LIST ══════ */
  .list {
    flex: 1;
    overflow-y: auto;
    -webkit-overflow-scrolling: touch;
  }
  .item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 12px 14px;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 10px;
    margin-bottom: 8px;
    animation: slideIn 0.2s ease;
  }
  @keyframes slideIn {
    from { opacity: 0; transform: translateY(-8px); }
    to { opacity: 1; transform: translateY(0); }
  }

  .item .handle {
    color: var(--text-dim);
    font-size: 16px;
    cursor: grab;
    user-select: none;
    flex-shrink: 0;
    width: 20px;
    text-align: center;
    transition: transform 0.15s ease, color 0.15s ease;
  }
  .item .handle:hover { color: var(--accent); transform: scale(1.2); }
  .item .handle:active { cursor: grabbing; transform: scale(1.1); color: var(--accent); }
  .item.dragging {
    opacity: 0.5;
    transform: scale(0.97);
    transition: opacity 0.15s ease, transform 0.15s ease;
  }
  .item.drag-over {
    border-color: var(--accent);
    box-shadow: 0 0 8px var(--accent-glow);
  }

  .item .order-num {
    color: var(--text-dim);
    font-size: 12px;
    font-weight: 600;
    width: 20px;
    text-align: center;
    flex-shrink: 0;
  }

  .item .text {
    flex: 1;
    font-size: 14px;
    word-break: break-all;
  }

  .item .up-btn, .item .down-btn {
    background: var(--surface-2);
    border: 1px solid var(--border);
    color: var(--text-dim);
    width: 30px; height: 30px;
    border-radius: 8px;
    font-size: 14px;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    transition: all 0.15s;
  }
  .item .up-btn:hover, .item .down-btn:hover {
    border-color: var(--accent);
    color: var(--accent);
  }

  .item .del-btn {
    background: transparent;
    border: none;
    color: var(--text-dim);
    width: 30px; height: 30px;
    border-radius: 8px;
    font-size: 16px;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    transition: all 0.15s;
  }
  .item .del-btn:hover {
    background: rgba(239,83,80,0.15);
    color: var(--error);
  }

  .empty-msg {
    text-align: center;
    color: var(--text-dim);
    padding: 32px 0;
    font-size: 14px;
  }

  /* ══════ SAVE BAR ══════ */
  .save-bar {
    padding: 12px 16px;
    flex-shrink: 0;
    border-top: 1px solid var(--border);
    background: var(--bg);
  }
  .save-btn {
    width: 100%;
    padding: 14px;
    background: var(--accent);
    color: #fff;
    border: none;
    border-radius: 12px;
    font-size: 15px;
    font-weight: 600;
    cursor: pointer;
    transition: background 0.2s;
  }
  .save-btn:hover { background: var(--accent-dark); }
</style>
</head>
<body>

<div class="header">
  <h1 id="headerTitle"></h1>
  <button class="close-btn" onclick="EditorBridge.dismiss()">✕</button>
</div>

<div class="content">
  <div class="add-row">
    <input type="text" id="itemInput" placeholder="Dodaj element...">
    <button class="add-btn" onclick="addItem()">+</button>
  </div>
  <div class="suggestions" id="suggestions"></div>
  <div class="list" id="list"></div>
</div>

<div class="save-bar">
  <button class="save-btn" onclick="saveItems()">Zapisz</button>
</div>

<script>
  var allowReorder = __REORDER__;
  var items = [];
  var dragIdx = null;

  function init() {
    document.getElementById('headerTitle').textContent = '__TITLE__';
    var raw = '__ITEMS__';
    if (raw && raw.trim() !== '') {
      items = raw.split(',').map(function(s) { return s.trim(); }).filter(function(s) { return s !== ''; });
    }
    renderSuggestions();
    renderList();
  }

  function renderSuggestions() {
    var sugRaw = '__SUGGESTIONS__';
    var sug = sugRaw ? sugRaw.split(',').map(function(s) { return s.trim(); }).filter(function(s) { return s !== ''; }) : [];
    var container = document.getElementById('suggestions');
    container.innerHTML = '';
    sug.forEach(function(s) {
      if (items.indexOf(s) === -1) {
        var chip = document.createElement('span');
        chip.className = 'sug-chip';
        chip.textContent = '+ ' + s;
        chip.onclick = function() {
          items.push(s);
          renderList();
          renderSuggestions();
        };
        container.appendChild(chip);
      }
    });
  }

  function renderList() {
    var list = document.getElementById('list');
    list.innerHTML = '';
    if (items.length === 0) {
      list.innerHTML = '<div class="empty-msg">📭 Brak elementów. Dodaj pierwszy!</div>';
      return;
    }
    items.forEach(function(item, idx) {
      var div = document.createElement('div');
      div.className = 'item';

      var html = '';
      if (allowReorder) {
        html += '<span class="handle">⠿</span>';
        html += '<span class="order-num">' + (idx + 1) + '</span>';
      }
      html += '<span class="text">' + escHtml(item) + '</span>';
      if (allowReorder) {
        html += '<button class="up-btn" onclick="moveUp(' + idx + ')"' + (idx === 0 ? ' disabled' : '') + '>↑</button>';
        html += '<button class="down-btn" onclick="moveDown(' + idx + ')"' + (idx === items.length - 1 ? ' disabled' : '') + '>↓</button>';
      }
      html += '<button class="del-btn" onclick="removeItem(' + idx + ')">✕</button>';
      div.innerHTML = html;

      if (allowReorder) {
        var handle = div.querySelector('.handle');
        handle.addEventListener('touchstart', function(e) {
          dragIdx = idx;
          div.classList.add('dragging');
        }, { passive: true });
        handle.addEventListener('touchend', function(e) {
          div.classList.remove('dragging');
          if (dragIdx === null) return;
          var touch = e.changedTouches[0];
          var target = document.elementFromPoint(touch.clientX, touch.clientY);
          var targetItem = target ? target.closest('.item') : null;
          document.querySelectorAll('.item.drag-over').forEach(function(el) { el.classList.remove('drag-over'); });
          if (targetItem) {
            targetItem.classList.add('drag-over');
            setTimeout(function() { targetItem.classList.remove('drag-over'); }, 200);
            var targetIdx = Array.from(list.children).indexOf(targetItem);
            if (targetIdx !== -1 && targetIdx !== dragIdx) {
              var moved = items.splice(dragIdx, 1)[0];
              items.splice(targetIdx, 0, moved);
              renderList();
            }
          }
          dragIdx = null;
        });
      }

      list.appendChild(div);
    });
  }

  function addItem() {
    var input = document.getElementById('itemInput');
    var val = input.value.trim();
    if (!val) return;
    if (items.indexOf(val) === -1) {
      items.push(val);
      renderList();
      renderSuggestions();
    }
    input.value = '';
    input.focus();
  }

  function removeItem(idx) {
    items.splice(idx, 1);
    renderList();
    renderSuggestions();
  }

  function moveUp(idx) {
    if (idx <= 0) return;
    var temp = items[idx];
    items[idx] = items[idx - 1];
    items[idx - 1] = temp;
    renderList();
  }

  function moveDown(idx) {
    if (idx >= items.length - 1) return;
    var temp = items[idx];
    items[idx] = items[idx + 1];
    items[idx + 1] = temp;
    renderList();
  }

  function saveItems() {
    EditorBridge.save(JSON.stringify(items));
  }

  function escHtml(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  document.getElementById('itemInput').addEventListener('keypress', function(e) {
    if (e.key === 'Enter') addItem();
  });

  init();
</script>

</body>
</html>
    """.trimIndent()
}
