package eu.kanade.tachiyomi.animeextension.pl.shinden

import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat

internal fun Shinden.setupPreferenceScreenExt(screen: PreferenceScreen) {
    SwitchPreferenceCompat(screen.context).apply {
        key = "shinden_login"
        title = "Konto Shinden"
        val dn = preferences.getString("shinden_display_name", null)
        val uid = preferences.getString("shinden_user_id", null)
        summary = if (isLoggedIn && uid != null && dn != null) "Zalogowano: $dn" else "Kliknij aby się zalogować"
        setOnPreferenceClickListener { pref ->
            ShindenLoginWebView.open(
                context = screen.context,
                client = client,
                headers = headers,
                isLoggedIn = isLoggedIn,
                savedUserId = preferences.getString("shinden_user_id", null),
                savedDisplayName = preferences.getString("shinden_display_name", null),
                onLoginSuccess = { userId, name ->
                    isLoggedIn = true
                    preferences.edit().putString("shinden_user_id", userId)
                        .putString("shinden_display_name", name).apply()
                    pref.summary = "Zalogowano: $name"
                },
                onLogout = {
                    isLoggedIn = false
                    cookiePrefs.edit().remove("cookies").apply()
                    preferences.edit()
                        .remove("shinden_user_id")
                        .remove("shinden_display_name")
                        .remove("shinden_username")
                        .remove("shinden_password")
                        .apply()
                    (client.cookieJar as? PersistentCookieJar)?.clear()
                    pref.summary = "Kliknij aby się zalogować"
                },
            )
            true
        }
    }.let(screen::addPreference)

    ListPreference(screen.context).apply {
        key = "preferred_quality"
        title = "Preferowana jakość"
        entries = arrayOf("Auto", "1080p", "720p", "480p", "360p")
        entryValues = arrayOf("auto", "1080", "720", "480", "360")
        setDefaultValue("1080")
        summary = "%s"
    }.let(screen::addPreference)

    SwitchPreferenceCompat(screen.context).apply {
        key = "preferred_servers"
        title = "Preferowane serwery"
        summary = preferences.getString("preferred_servers_list", "")
            ?.replace(",", ", ") ?: "Kliknij aby edytować"
        setOnPreferenceClickListener { pref ->
            ShindenListEditor.open(
                context = screen.context,
                title = "Preferowane serwery",
                currentItems = preferences.getString("preferred_servers_list", "") ?: "",
                allowReorder = true,
                defaultSuggestions = listOf("cda.pl", "google", "mega.nz"),
                onSave = { newOrder ->
                    preferences.edit().putString("preferred_servers_list", newOrder).apply()
                    pref.summary = newOrder.replace(",", ", ")
                },
            )
            true
        }
    }.let(screen::addPreference)

    ListPreference(screen.context).apply {
        key = "video_display_mode"
        title = "Wyświetlanie źródeł wideo"
        summary = "%s"
        entries = arrayOf("Wszystkie", "Auto + najwyższa", "Tylko najwyższa")
        entryValues = arrayOf("all", "auto_highest", "highest_only")
        setDefaultValue("auto_highest")
    }.let(screen::addPreference)

    SwitchPreferenceCompat(screen.context).apply {
        key = "google_login_v2"
        title = "Zaloguj do Google Drive"
        summary = "Kliknij aby otworzyć logowanie Google. Wymagane dla prywatnych materiałów na GDrive."
        setOnPreferenceClickListener {
            GoogleLoginWebView.open(screen.context)
            true
        }
    }.let(screen::addPreference)

    SwitchPreferenceCompat(screen.context).apply {
        key = "google_logout"
        title = "Wyloguj z Google Drive"
        summary = "Wyczyść cookies Google"
        setOnPreferenceClickListener {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
            true
        }
    }.let(screen::addPreference)

    SwitchPreferenceCompat(screen.context).apply {
        key = "show_empty_sources"
        title = "Wyświetlaj puste źródła"
        summary = "Źródła które nie zwróciły wideo będą widoczne na liście zamiast ukryte"
        setDefaultValue(false)
    }.let(screen::addPreference)

    SwitchPreferenceCompat(screen.context).apply {
        key = "skip_domains"
        title = "Pomiń domeny"
        summary = preferences.getString("skip_domains_list", "hqq.tv,lulu,facebook.com")?.replace(",", ", ")
            ?.ifBlank { "Kliknij aby edytować" } ?: "Kliknij aby edytować"
        setOnPreferenceClickListener { pref ->
            ShindenListEditor.open(
                context = screen.context,
                title = "Pomiń domeny",
                currentItems = preferences.getString("skip_domains_list", "hqq.tv,lulu,facebook.com") ?: "",
                allowReorder = false,
                defaultSuggestions = listOf("hqq.tv", "luluvid.com", "facebook.com"),
                onSave = { newDomains ->
                    preferences.edit().putString("skip_domains_list", newDomains).apply()
                    pref.summary = newDomains.replace(",", ", ").ifBlank { "Kliknij aby edytować" }
                },
            )
            true
        }
    }.let(screen::addPreference)

    SwitchPreferenceCompat(screen.context).apply {
        key = "filter_language"
        title = "🎧 Filtr języka"
        summary = "Pomijaj źródła według języka dźwięku i napisów"
        setDefaultValue(false)
    }.let(screen::addPreference)

    MultiSelectListPreference(screen.context).apply {
        key = "allowed_subs_langs"
        title = "📝 Dozwolone języki napisów"
        summary = "Źródła z innym językiem napisów (lub bez) zostaną pominięte"
        entries = arrayOf("Polski", "Polski AI", "Angielski", "Pozostałe", "Brak")
        entryValues = arrayOf("pl", "ipl", "en", "other", "none")
        setDefaultValue(emptySet<String>())
    }.let(screen::addPreference)

    MultiSelectListPreference(screen.context).apply {
        key = "allowed_audio_langs"
        title = "🎧 Dozwolone języki dźwięku"
        summary = "Źródła z innym językiem dźwięku zostaną pominięte"
        entries = arrayOf("Japoński", "Angielski", "Polski", "Pozostałe")
        entryValues = arrayOf("jp", "en", "pl", "other")
        setDefaultValue(emptySet<String>())
    }.let(screen::addPreference)

    SwitchPreferenceCompat(screen.context).apply {
        key = "verbose_logging"
        title = "Szczegółowe logi"
        summary = "Włącz szczegółowe logowanie ekstrakcji źródeł"
        setDefaultValue(false)
    }.let(screen::addPreference)

    EditTextPreference(screen.context).apply {
        key = "sequential_retry_sleep"
        title = "Sekwencyjne ponawianie (sekundy)"
        summary = "Sleep między próbami dla zatrzymanych źródeł. Puste/0 = stagger (domyślnie). Np. 3.5 lub 5"
        dialogTitle = "Sekundy między próbami"
        setDefaultValue("")
        setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            editText.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val normalized = s?.toString()?.replace(",", ".") ?: ""
                    if (normalized != s?.toString()) {
                        val pos = editText.selectionStart
                        editText.setText(normalized)
                        editText.setSelection(minOf(pos, normalized.length))
                    }
                }
            })
        }
    }.let(screen::addPreference)
}
