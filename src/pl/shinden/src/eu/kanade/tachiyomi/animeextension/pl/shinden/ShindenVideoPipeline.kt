package eu.kanade.tachiyomi.animeextension.pl.shinden

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.ExtLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject
import java.util.Collections

internal fun Shinden.videoListParseExt(response: Response): List<Video> {
    ExtLog.d(Shinden.TAG, "=== videoListParse http=${response.code} url=${response.request.url} ===")

    val document = response.asJsoup()
    val bodyText = document.outerHtml()

    val authCode = Regex("""_Storage\.basic\s*=\s*'(.*?)'""")
        .find(bodyText)
        ?.groupValues
        ?.get(1)
        ?: fallbackAuth

    val sources = parseSourcesExt(document, authCode)
    ExtLog.d(Shinden.TAG, "parsed ${sources.size} sources")

    if (sources.isEmpty()) {
        val snippet = bodyText.take(300).replace("\n", " ")
        return listOf(debugVideo("Brak źródeł. snippet=\"$snippet\""))
    }

    return runBlocking {
        with(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()

            // ===== BATCH PHASE 1: create per-call clients =====
            val loadClients = sources.map { (_, loadUrl) ->
                loadUrl to createPerCallClient()
            }
            ExtLog.d(Shinden.TAG, "batch: created ${loadClients.size} per-call clients")

            // ===== BATCH PHASE 2: fire ALL player_load in parallel =====
            loadClients.map { (loadUrl, client) ->
                async {
                    runCatching {
                        val r = client.newCall(GET(loadUrl, headers)).execute()
                        val code = r.code
                        r.close()
                        ExtLog.d(Shinden.TAG, "batch player_load http=$code for $loadUrl")
                    }.onFailure {
                        ExtLog.w(Shinden.TAG, "batch player_load FAILED: $loadUrl — ${it.message}")
                    }
                }
            }.awaitAll()
            ExtLog.d(Shinden.TAG, "batch: all ${loadClients.size} player_load done in ${System.currentTimeMillis() - t0}ms")

            // ===== BATCH PHASE 3: single sleep(2000) =====
            ExtLog.d(Shinden.TAG, "batch: sleep(2000) after ${loadClients.size} player_loads")
            Thread.sleep(2000)

            // ===== BATCH PHASE 4: player_show sequential with stagger =====
            val embedUrlMap = mutableMapOf<String, String?>()
            val failedClients = mutableListOf<Pair<String, OkHttpClient>>()
            for ((i, pair) in loadClients.withIndex()) {
                val (loadUrl, client) = pair
                val showUrl = loadUrl.replace("player_load", "player_show") + "&width=0&height=-1"
                if (i > 0) Thread.sleep(800)
                val resolved = runCatching {
                    val r = client.newCall(GET(showUrl, headers)).execute()
                    ExtLog.d(Shinden.TAG, "batch player_show http=${r.code} for $loadUrl")
                    val doc = r.asJsoup()
                    val raw = doc.selectFirst("iframe[src]")?.attr("src")
                        ?: doc.selectFirst("a[href]")?.attr("href")
                    raw?.let { if (it.startsWith("//")) "https:$it" else it }
                }.onFailure {
                    ExtLog.w(Shinden.TAG, "batch player_show FAILED: $showUrl — ${it.message}")
                }.getOrNull()
                ExtLog.d(Shinden.TAG, "batch player_show resolved: $resolved for $loadUrl")
                embedUrlMap[loadUrl] = resolved
                if (resolved == null) {
                    failedClients.add(loadUrl to client)
                }
            }
            // ===== PHASE 4b: retry failed player_shows =====
            if (failedClients.isNotEmpty()) {
                val sleepStr = (preferences.getString("sequential_retry_sleep", "")?.trim() ?: "").replace(",", ".")
                val seqSleepMs = (sleepStr.toDoubleOrNull() ?: 0.0) * 1000.0
                val seqRetry = seqSleepMs > 0
                ExtLog.d(Shinden.TAG, "retry: ${failedClients.size} failed, mode=${if (seqRetry) "sequential(${seqSleepMs.toLong()}ms)" else "stagger"}")
                if (seqRetry) {
                    // Sequential retry: player_load → sleep(3500) → player_show for each
                    for ((j, pair) in failedClients.withIndex()) {
                        val (loadUrl, client) = pair
                        val loadUrl2 = loadUrl
                        val showUrl = loadUrl2.replace("player_load", "player_show") + "&width=0&height=-1"
                        val resolved = runCatching {
                            ExtLog.d(Shinden.TAG, "seq retry: player_load $loadUrl2")
                            val rLoad = client.newCall(GET(loadUrl2, headers)).execute()
                            rLoad.close()
                            ExtLog.d(Shinden.TAG, "seq retry: sleep(${seqSleepMs.toLong()}ms)")
                            Thread.sleep(seqSleepMs.toLong())
                            val r = client.newCall(GET(showUrl, headers)).execute()
                            ExtLog.d(Shinden.TAG, "seq retry: player_show http=${r.code} for $loadUrl2")
                            val doc = r.asJsoup()
                            val raw = doc.selectFirst("iframe[src]")?.attr("src")
                                ?: doc.selectFirst("a[href]")?.attr("href")
                            raw?.let { if (it.startsWith("//")) "https:$it" else it }
                        }.onFailure {
                            ExtLog.w(Shinden.TAG, "seq retry FAILED: $loadUrl2 — ${it.message}")
                        }.getOrNull()
                        ExtLog.d(Shinden.TAG, "seq retry resolved: $resolved for $loadUrl2")
                        if (resolved != null) embedUrlMap[loadUrl2] = resolved
                    }
                } else {
                    // Stagger retry: player_show only with 3s cooldown + 800ms stagger
                    Thread.sleep(3000)
                    for ((j, pair) in failedClients.withIndex()) {
                        val (loadUrl, client) = pair
                        if (j > 0) Thread.sleep(800)
                        val showUrl = loadUrl.replace("player_load", "player_show") + "&width=0&height=-1"
                        val resolved = runCatching {
                            val r = client.newCall(GET(showUrl, headers)).execute()
                            ExtLog.d(Shinden.TAG, "retry player_show http=${r.code} for $loadUrl")
                            val doc = r.asJsoup()
                            val raw = doc.selectFirst("iframe[src]")?.attr("src")
                                ?: doc.selectFirst("a[href]")?.attr("href")
                            raw?.let { if (it.startsWith("//")) "https:$it" else it }
                        }.onFailure {
                            ExtLog.w(Shinden.TAG, "retry player_show FAILED: $showUrl — ${it.message}")
                        }.getOrNull()
                        ExtLog.d(Shinden.TAG, "retry player_show resolved: $resolved for $loadUrl")
                        if (resolved != null) embedUrlMap[loadUrl] = resolved
                    }
                }
                ExtLog.d(Shinden.TAG, "retry: recovered ${failedClients.count { embedUrlMap[it.first] != null }} of ${failedClients.size}")
            }
            ExtLog.d(Shinden.TAG, "batch: all ${embedUrlMap.size} player_show done in ${System.currentTimeMillis() - t0}ms total")

            // ===== BATCH PHASE 5: extract videos in parallel =====
            sources.map { (meta, loadUrl) ->
                async {
                    withTimeoutOrNull(30_000L) {
                        try {
                            val (host, quality, audio, subs, subsAuthor) = meta
                            ExtLog.d(Shinden.TAG, ">>> source: host=$host quality=$quality audio=$audio subs=$subs")

                            val embedUrl = embedUrlMap[loadUrl]
                                ?: return@withTimeoutOrNull listOf(debugVideo("$host — resolveEmbedUrl zwrócił null"))
                            ExtLog.d(Shinden.TAG, "embedUrl RESOLVED: $embedUrl")
                            val prefix = buildPrefix(audio, subs)

                            val embedHost = runCatching { embedUrl.toHttpUrl().host }.getOrDefault("?")

                            // Domain skip filter
                            val skipDomains = preferences.getString("skip_domains_list", "hqq.tv,lulu,facebook.com")?.trim() ?: ""
                            if (skipDomains.isNotBlank()) {
                                val skipList = skipDomains.split(",").map { it.trim().lowercase() }
                                if (skipList.any { embedHost.lowercase().contains(it) }) {
                                    ExtLog.d(Shinden.TAG, "SKIP domain: $embedHost matched skip_domain list")
                                    return@withTimeoutOrNull emptyList()
                                }
                            }

                            // Language filter (hard skip before dispatch)
                            val filterLang = preferences.getBoolean("filter_language", false)
                            if (filterLang) {
                                val allowedAudio = preferences.getStringSet("allowed_audio_langs", emptySet()) ?: emptySet()
                                val allowedSubs = preferences.getStringSet("allowed_subs_langs", emptySet()) ?: emptySet()
                                if (allowedAudio.isNotEmpty()) {
                                    val audioCat = audioLangCategory(audio)
                                    if (audioCat !in allowedAudio) {
                                        ExtLog.d(Shinden.TAG, "SKIP audio lang: host=$host audio=$audio cat=$audioCat")
                                        return@withTimeoutOrNull emptyList()
                                    }
                                }
                                if (allowedSubs.isNotEmpty()) {
                                    val subsCat = subsLangCategory(subs)
                                    if (subsCat !in allowedSubs) {
                                        ExtLog.d(Shinden.TAG, "SKIP subs lang: host=$host subs=$subs cat=$subsCat")
                                        return@withTimeoutOrNull emptyList()
                                    }
                                }
                            }

                            val (videos, extractorName) = when {
                                embedUrl.contains("cda.pl") ->
                                    cdaExtractor.getVideosFromUrl(embedUrl, headers, prefix) to "Cda"

                                embedUrl.contains("mp4upload") ->
                                    mp4uploadExtractor.videosFromUrl(embedUrl, headers, prefix) to "Mp4upload"

                                embedUrl.contains("dood") ->
                                    listOfNotNull(doodExtractor.videoFromUrl(embedUrl, prefix = prefix)) to "Dood"

                                embedUrl.contains("sibnet") ->
                                    sibnetExtractor.videosFromUrl(embedUrl, prefix) to "Sibnet"

                                embedUrl.contains("streamtape") ->
                                    listOfNotNull(streamTapeExtractor.videoFromUrl(embedUrl)) to "Streamtape"

                                embedUrl.contains("ok.ru") || embedUrl.contains("odnoklassniki") || embedUrl.contains("okru") -> {
                                    ExtLog.d(Shinden.TAG, ">>> ok.ru dispatch: embedUrl=$embedUrl prefix=$prefix")
                                    val result = okruExtractor.videosFromUrl(embedUrl, prefix = prefix)
                                    ExtLog.d(Shinden.TAG, "<<< ok.ru returned ${result.size} videos")
                                    result to "Okru"
                                }

                                embedUrl.contains("uqload") ->
                                    uqloadExtractor.videosFromUrl(embedUrl, prefix) to "Uqload"

                                embedUrl.contains("lycoris") || embedUrl.contains("lycoris.cafe") ->
                                    lycorisExtractor.getVideosFromUrl(embedUrl, headers, prefix) to "Lycoris"

                                embedUrl.contains("streamup") || embedUrl.contains("strmup") ->
                                    streamupExtractor.getVideosFromUrl(embedUrl, headers, prefix) to "Streamup"

                                embedUrl.contains("filemoon") ->
                                    filemoonExtractor.videosFromUrl(embedUrl, prefix = prefix, headers = headers) to "Filemoon"

                                embedUrl.contains("mega.nz") || embedUrl.contains("mega.co.nz") ->
                                    megaNzExtractor.videosFromUrl(embedUrl, prefix) to "MegaNz"

                                embedUrl.contains("vidara.to") || embedUrl.contains("vidara.") ->
                                    vidaraExtractor.videosFromUrl(embedUrl, prefix = prefix, headers = headers) to "Vidara"

                                embedUrl.contains("bysesukior") || embedUrl.contains("byssesukior") || embedUrl.contains("q8y5z.com") ->
                                    bysesukiorExtractor.videosFromUrl(embedUrl, prefix, headers) to "Bysesukior"

                                embedUrl.contains("flyfile.app") || embedUrl.contains("flyf.lat") || embedUrl.contains("flyfile") ->
                                    flyfileExtractor.videosFromUrl(embedUrl, prefix, headers) to "Flyfile"

                                embedUrl.contains("sharevideo.pl") || embedUrl.contains("sharevideo.") ->
                                    shareVideoExtractor.videosFromUrl(embedUrl, prefix, headers) to "ShareVideo"

                                embedUrl.contains("drive.google.com") || embedUrl.contains("googleusercontent.com") ->
                                    googleDrivePlayerExtractor.videosFromUrl(embedUrl) to "GoogleDrivePlayer"

                                embedUrl.contains("gdriveplayer") || embedUrl.contains("gdrive-player") ->
                                    gdrivePlayerExtractor.videosFromUrl(embedUrl, "$prefix GdrivePlayer", headers) to "GdrivePlayer"

                                embedUrl.contains("playmate.to") ->
                                    playmateExtractor.videosFromUrl(embedUrl, prefix) to "Playmate"

                                embedUrl.contains("dailymotion") ->
                                    dailymotionExtractor.videosFromUrl(embedUrl, prefix) to "Dailymotion"

                                embedUrl.contains("vk.com") || embedUrl.contains("vkvideo.ru") ->
                                    vkExtractor.videosFromUrl(embedUrl, prefix) to "Vk"
                                embedUrl.contains("aparat.com") || embedUrl.contains("aparat.") ->
                                    aparatExtractor.videosFromUrl(embedUrl, prefix) to "Aparat"

                                else ->
                                    universalExtractor.videosFromUrl(embedUrl, headers, customQuality = "$host $quality", prefix = prefix) to "Universal"
                            }

                            val mapped = videos.map { video ->
                                val extractorQuality = video.quality.substringAfterLast(" ").trim()
                                val qMatch = Regex("""\b(\d+p|auto)\b""", RegexOption.IGNORE_CASE)
                                    .find(extractorQuality)?.value
                                    ?: Regex("""\b(\d+p)\b""", RegexOption.IGNORE_CASE).find(video.quality)?.value
                                    ?: quality
                                val authorPart = if (subsAuthor.isNotBlank()) " · $subsAuthor" else ""
                                val finalQuality = "$embedHost $qMatch$authorPart${buildLangLabel(audio, subs)}"
                                Video(video.url, finalQuality, video.videoUrl, video.headers)
                            }.let { vids ->
                                filterVideosByPreference(vids)
                            }

                            if (preferences.getBoolean("verbose_logging", false)) {
                                ExtLog.d(Shinden.TAG, "host=$host -> ${mapped.size} videos [verbose: embedHost=$embedHost extractor=$extractorName]")
                            } else {
                                ExtLog.d(Shinden.TAG, "host=$host -> ${mapped.size} videos")
                            }
                            mapped.ifEmpty {
                                listOf(debugVideo("$host $quality — embedUrl=$embedUrl ale ekstraktor zwrócił 0 wideo"))
                            }
                        } catch (e: Throwable) {
                            ExtLog.e(Shinden.TAG, "source error: ${e.message}", e)
                            listOf(debugVideo("Error: ${e.message?.take(200)}"))
                        }
                    } ?: run {
                        ExtLog.w(Shinden.TAG, "source timed out: $loadUrl")
                        emptyList<Video>()
                    }
                }
            }.awaitAll().flatten()
        }
    }.let { result ->
        val processed = m3u8Integration.processVideoList(result)
        val showEmpty = preferences.getBoolean("show_empty_sources", false)
        val filtered = if (!showEmpty) {
            processed.filter { !it.url.startsWith("about:blank") }
        } else {
            processed.sortedBy { it.url.startsWith("about:blank") }
        }
        if (preferences.getBoolean("verbose_logging", false)) {
            ExtLog.d(Shinden.TAG, "=== DONE: ${processed.size} videos total (showEmpty=$showEmpty, filtered=${filtered.size}) ===")
        } else {
            ExtLog.d(Shinden.TAG, "=== DONE: ${filtered.size} videos total ===")
        }
        filtered
    }
}

internal data class SourceMeta(
    val host: String,
    val quality: String,
    val audio: String,
    val subs: String,
    val subsAuthor: String = "",
)

internal fun Shinden.parseSourcesExt(document: org.jsoup.nodes.Document, authCode: String): List<Pair<SourceMeta, String>> {
    val jsonButtons = document.select(".ep-buttons a[data-episode]")
    ExtLog.d(Shinden.TAG, "jsonButtons=${jsonButtons.size}")
    if (jsonButtons.isNotEmpty()) {
        return jsonButtons.mapNotNull { a ->
            runCatching {
                val json = JSONObject(a.attr("data-episode"))
                val host = json.optString("player", "").trim()
                val quality = json.optString("max_res", "SD").trim().ifBlank { "SD" }
                val audio = json.optString("lang_audio", "").trim()
                val subs = json.optString("lang_subs", "").trim()
                val onlineId = json.optString("online_id", "").trim()
                val subsAuthorRaw = json.optString("subs_author", "").trim()
                val subsAuthor = if (subsAuthorRaw.isNotBlank() && !subsAuthorRaw.equals("null", ignoreCase = true)) {
                    subsAuthorRaw.replace(Regex("""<[^>]*>"""), "").trim()
                        .split("|").first().trim()
                } else {
                    ""
                }
                if (onlineId.isBlank()) return@mapNotNull null

                val loadUrl = "https://api4.shinden.pl/xhr/$onlineId/player_load"
                    .toHttpUrl().newBuilder()
                    .addQueryParameter("auth", authCode)
                    .build().toString()

                SourceMeta(host, quality, audio, subs, subsAuthor) to loadUrl
            }.getOrNull()
        }
    }

    val section = document.selectFirst("section.box.episode-player-list")
    val rows = section?.select("tr") ?: emptyList()
    ExtLog.d(Shinden.TAG, "table fallback: section=${section != null} rows=${rows.size}")
    if (rows.size < 2) return emptyList()

    val vidIdRegex = Regex("""data_(.*?)""")
    return rows.mapNotNull { row ->
        val cols = row.select("td")
        if (cols.size < 6) return@mapNotNull null

        val hostRaw = cols[0].text().trim()
        val host = if (hostRaw.contains("vidoza", ignoreCase = true)) "Vidoza" else hostRaw
        val quality = cols[1].text().trim().ifBlank { "SD" }
        val audio = cols[2].selectFirst("span.mobile-hidden")?.text()?.trim().orEmpty()
        val subs = cols[3].selectFirst("span.mobile-hidden")?.text()?.trim().orEmpty()
        val vidId = vidIdRegex.find(cols[5].outerHtml())?.groupValues?.get(1) ?: return@mapNotNull null

        val loadUrl = "https://api4.shinden.pl/xhr/$vidId/player_load"
            .toHttpUrl().newBuilder()
            .addQueryParameter("auth", authCode)
            .build().toString()

        SourceMeta(host, quality, audio, subs) to loadUrl
    }
}

internal fun Shinden.langAbbrev(full: String): String = when {
    full.contains("Japoński", ignoreCase = true) -> "JP"
    full.contains("Polski", ignoreCase = true) -> "PL"
    full.contains("Angielski", ignoreCase = true) -> "EN"
    full.contains("Niemiecki", ignoreCase = true) -> "DE"
    full.contains("Hiszpański", ignoreCase = true) -> "ES"
    full.contains("Francuski", ignoreCase = true) -> "FR"
    full.contains("Włoski", ignoreCase = true) -> "IT"
    full.contains("Portugalski", ignoreCase = true) -> "PT"
    full.contains("Koreański", ignoreCase = true) -> "KO"
    full.contains("Chiński", ignoreCase = true) -> "ZH"
    full.isBlank() -> ""
    else -> full.take(2).uppercase()
}

internal fun Shinden.buildLangLabel(audio: String, subs: String): String {
    val parts = mutableListOf<String>()
    if (audio.isNotBlank()) parts.add("\uD83C\uDF99\uFE0F ${langAbbrev(audio)}")
    if (subs.isNotBlank() && !subs.contains("--")) parts.add("\uD83D\uDCDD ${langAbbrev(subs)}")
    return if (parts.isNotEmpty()) " ${parts.joinToString(" \u00B7 ")}" else ""
}

internal fun Shinden.buildPrefix(audio: String, subs: String): String {
    val parts = mutableListOf<String>()
    if (audio.contains("Polski", ignoreCase = true)) parts.add("PL")
    if (subs.isNotBlank() && !subs.contains("--")) parts.add(subs)
    val label = parts.joinToString(" - ")
    return if (label.isNotBlank()) "[$label] " else ""
}

internal fun audioLangCategory(raw: String): String {
    val lower = raw.lowercase().trim()
    return when (lower) {
        "jp" -> "jp"
        "en" -> "en"
        "pl" -> "pl"
        else -> "other"
    }
}

internal fun subsLangCategory(raw: String): String {
    val lower = raw.lowercase().trim()
    return when {
        lower.isBlank() || lower == "--" || lower == "brak" -> "none"
        lower == "ipl" || lower == "polski ai" -> "ipl"
        lower == "pl" -> "pl"
        lower == "en" -> "en"
        else -> "other"
    }
}

internal fun Shinden.debugVideo(message: String): Video = Video("about:blank", "DEBUG: $message".take(300), "about:blank")

internal fun Shinden.createPerCallClient(): OkHttpClient = videoClient.newBuilder()
    .connectionPool(okhttp3.ConnectionPool())
    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
    .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
    .cookieJar(object : CookieJar {
        private val cookies = Collections.synchronizedList(mutableListOf<Cookie>())
        override fun saveFromResponse(url: HttpUrl, newCookies: List<Cookie>) {
            synchronized(cookies) {
                for (c in newCookies) {
                    cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                    if (c.expiresAt > System.currentTimeMillis()) cookies.add(c)
                }
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
            cookies.filter { it.matches(url) }
        }
    })
    .build()

internal fun Shinden.resolveEmbedUrl(loadUrl: String): String? = runCatching {
    ExtLog.d(Shinden.TAG, "resolveEmbedUrl START loadUrl=$loadUrl")

    val perCallClient = videoClient.newBuilder()
        .connectionPool(okhttp3.ConnectionPool())
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            private val cookies = Collections.synchronizedList(mutableListOf<Cookie>())
            override fun saveFromResponse(url: HttpUrl, newCookies: List<Cookie>) {
                synchronized(cookies) {
                    for (c in newCookies) {
                        cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                        if (c.expiresAt > System.currentTimeMillis()) cookies.add(c)
                    }
                }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
                cookies.filter { it.matches(url) }
            }
        })
        .build()

    val r1 = perCallClient.newCall(GET(loadUrl, headers)).execute()
    ExtLog.d(Shinden.TAG, "player_load http=${r1.code}")
    r1.close()
    Thread.sleep(2000)

    val showUrl = loadUrl.replace("player_load", "player_show") + "&width=0&height=-1"
    val r2 = perCallClient.newCall(GET(showUrl, headers)).execute()
    ExtLog.d(Shinden.TAG, "player_show http=${r2.code}")
    val doc = r2.asJsoup()

    val raw = doc.selectFirst("iframe[src]")?.attr("src")
        ?: doc.selectFirst("a[href]")?.attr("href")
    ExtLog.d(Shinden.TAG, "raw=$raw")
    if (raw == null) return@runCatching null

    val resolved = if (raw.startsWith("//")) "https:$raw" else raw
    ExtLog.d(Shinden.TAG, "resolveEmbedUrl RESOLVED: $resolved")
    resolved
}.getOrElse {
    ExtLog.e(Shinden.TAG, "resolveEmbedUrl EXCEPTION: ${it.message}", it)
    null
}

// ================================ Video display mode filter ======================

internal fun Shinden.filterVideosByPreference(vids: List<Video>): List<Video> {
    if (vids.size <= 1) return vids

    val prefQuality = preferences.getString("preferred_quality", "1080") ?: "1080"
    val mode = preferences.getString("video_display_mode", "auto_highest") ?: "auto_highest"
    val prefInt = prefQuality.toIntOrNull()

    val qualityRegex = Regex("""\b(\d+)p\b""", RegexOption.IGNORE_CASE)

    fun numericP(quality: String): Int = qualityRegex.find(quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    fun matchesPref(quality: String): Boolean = prefInt != null && Regex("""\b${prefInt}p\b""", RegexOption.IGNORE_CASE).containsMatchIn(quality)

    // "show all": everything visible, preferred quality sorted to top
    if (mode == "all") {
        if (prefInt == null) return vids
        val preferred = vids.filter { matchesPref(it.quality) }
        val rest = vids.filter { !matchesPref(it.quality) }
        return preferred + rest
    }

    // auto_highest / highest_only: quality filter first, then display mode
    // Find best numeric match from original vids
    val exactMatch = vids.filter { matchesPref(it.quality) }
    val bestNumeric = if (exactMatch.isNotEmpty()) {
        exactMatch
    } else if (prefInt != null) {
        val lower = vids.filter { numericP(it.quality) in 1 until prefInt }
            .sortedByDescending { numericP(it.quality) }
        if (lower.isNotEmpty()) lower else vids
    } else {
        vids
    }

    // Always search auto in ORIGINAL vids, not in narrowed list
    val auto = vids.filter { it.quality.contains("auto", ignoreCase = true) }
    val maxP = bestNumeric.maxByOrNull { numericP(it.quality) }

    return when (mode) {
        "highest_only" -> {
            if (maxP != null) listOf(maxP) else bestNumeric
        }

        "auto_highest" -> {
            val r = mutableListOf<Video>()
            if (maxP != null) r.add(maxP)
            r.addAll(auto.filter { it.quality != maxP?.quality })
            r
        }

        else -> bestNumeric
    }
}

// ================================ Sort =========================================

internal fun Shinden.sortVideos(videos: List<Video>): List<Video> {
    val (empty, nonEmpty) = videos.partition { it.url.startsWith("about:blank") }
    return sortInternal(nonEmpty) + empty
}

internal fun Shinden.sortInternal(videos: List<Video>): List<Video> {
    val serversStr = preferences.getString("preferred_servers_list", "") ?: ""
    val servers = serversStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
    val prefQuality = preferences.getString("preferred_quality", "1080") ?: "1080"
    val prefInt = prefQuality.toIntOrNull()
    val prefIsAuto = prefQuality.equals("auto", ignoreCase = true)

    val hostExtractRegex = Regex("^(.+?)\\s+\\b(?:\\d+p|auto)\\b", RegexOption.IGNORE_CASE)
    fun extractHost(quality: String): String = hostExtractRegex.find(quality)?.groupValues?.get(1)?.trim() ?: quality

    val groupMap = mutableMapOf<String, MutableList<Video>>()

    for (video in videos) {
        val embedHost = extractHost(video.quality)
        groupMap.getOrPut(embedHost) { mutableListOf() }.add(video)
    }

    fun getPriority(host: String): Int {
        for ((idx, server) in servers.withIndex()) {
            if (host.contains(server, ignoreCase = true)) return idx
        }
        return Int.MAX_VALUE
    }

    val seen = mutableSetOf<String>()
    val groupOrder = mutableListOf<String>()
    for (video in videos) {
        val embedHost = extractHost(video.quality)
        if (embedHost !in seen) {
            seen.add(embedHost)
            groupOrder.add(embedHost)
        }
    }

    val qualityNumRegex = Regex("""\b(\d+)p\b""", RegexOption.IGNORE_CASE)
    fun numericP(q: String): Int = qualityNumRegex.find(q)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    fun isAuto(q: String): Boolean = q.contains("auto", ignoreCase = true)
    fun exactPref(q: String): Boolean = when {
        prefIsAuto -> isAuto(q)
        prefInt != null -> Regex("""\b${prefInt}p\b""", RegexOption.IGNORE_CASE).containsMatchIn(q)
        else -> false
    }

    fun closestDist(q: String): Int {
        if (prefInt == null || prefIsAuto || isAuto(q)) return Int.MAX_VALUE
        return kotlin.math.abs(numericP(q) - prefInt)
    }

    fun intraSort(videos: List<Video>): List<Video> = videos.sortedWith(
        compareBy<Video> { v ->
            val q = v.quality
            when {
                exactPref(q) -> 0
                closestDist(q) < Int.MAX_VALUE -> 10000 + closestDist(q)
                isAuto(q) -> 20000
                else -> 30000
            }
        }.thenByDescending { v ->
            if (isAuto(v.quality)) -1 else numericP(v.quality)
        },
    )

    if (servers.isEmpty()) {
        return groupOrder
            .sortedBy { host ->
                val vids = groupMap.getValue(host)
                if (vids.any { exactPref(it.quality) }) {
                    0
                } else if (prefInt != null && !prefIsAuto) {
                    10000 + (vids.minOfOrNull { closestDist(it.quality) } ?: Int.MAX_VALUE)
                } else {
                    20000
                }
            }
            .flatMap { intraSort(groupMap.getValue(it)) }
    }

    return groupOrder
        .sortedBy { getPriority(it) }
        .flatMap { intraSort(groupMap[it].orEmpty()) }
}
