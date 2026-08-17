package eu.kanade.tachiyomi.animeextension.pl.shinden

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import aniyomi.lib.aparatextractor.AparatExtractor
import aniyomi.lib.bysesukiorextractor.BysesukiorExtractor
import aniyomi.lib.cdaextractor.CdaExtractor
import aniyomi.lib.dailymotionextractor.DailymotionExtractor
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.flyfileextractor.FlyfileExtractor
import aniyomi.lib.gdriveplayerextractor.GdrivePlayerExtractor
import aniyomi.lib.googledriveplayerextractor.GoogleDrivePlayerExtractor
import aniyomi.lib.lycoriscafeextractor.LycorisCafeExtractor
import aniyomi.lib.meganzextractor.MegaNzExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.playmateextractor.PlaymateExtractor
import aniyomi.lib.sharevideoextractor.ShareVideoExtractor
import aniyomi.lib.sibnetextractor.SibnetExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamupextractor.StreamupExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidaraextractor.VidaraExtractor
import aniyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.ExtLog
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale

class Shinden :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    companion object {
        internal const val TAG = "ShindenExt"
    }

    override val name = "Shinden"

    override val baseUrl = "https://shinden.pl"

    override val lang = "pl"

    override val supportsLatest = true

    internal val preferences by getPreferencesLazy()

    // My anime list - server-side filtering state
    // Track whether myAnime mode is active (for getFilterList)
    @Volatile
    internal var isMyAnimeActive = false

    @Volatile
    internal var myAnimeNoUserId = false
    internal var myAnimeRemainingStatuses: List<String> = emptyList()
    internal var myAnimePendingResults: MutableList<SAnime> = mutableListOf()
    internal var myAnimeActiveTypeFilter: String? = null
    internal var myAnimeActiveSortFilter: Int = 0
    internal var myAnimeActiveLetterFilter: String? = null
    internal var myAnimeActiveEpisodeFilter: String? = null
    internal var myAnimeActiveTitleStatusFilter: String? = null
    internal var myAnimeSearchQuery: String? = null

    @Volatile
    private var currentAnimeTitle: String = ""

    // Batch loading: fetch multiple pages at once
    internal val batchSize = 4
    internal var batchOffset = 1
    internal var batchHasNext = false
    internal var searchBaseUrl = ""
    internal var lastSearchUrl = ""
    internal var lastSearchResult: AnimesPage? = null
    internal var lastSearchQuery = ""
    internal var lastSearchFiltersHash = 0
    internal var lastSearchPage = 0
    internal var currentSearchPage = 1

    init {
        ExtLog.enabled = preferences.getBoolean("verbose_logging", false)
        // Migrate old String values from preferred_servers/skip_domains
        // to new keys (old keys now used by SwitchPreferenceCompat as Boolean)
        try {
            val editor = preferences.edit()
            var changed = false
            for (oldKey in listOf("preferred_servers", "skip_domains")) {
                val newKey = "${oldKey}_list"
                // Check if old key has a String value (not Boolean)
                val raw = preferences.all[oldKey]
                if (raw is String) {
                    editor.putString(newKey, raw)
                    editor.remove(oldKey)
                    changed = true
                }
            }
            if (changed) editor.apply()
        } catch (_: Exception) {}
    }

    internal val cookiePrefs: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("cookies_$id", 0x0000)
    }

    private val genreCache: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("genre_cache_$id", 0x0000)
    }

    private val tenraiCache: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("jikan_cache_$id", 0x0000)
    }

    @Volatile
    internal var isLoggedIn = preferences.getString("shinden_user_id", null) != null

    // Force refresh on login: Shinden shows incomplete data to non-logged-in users
    // Clear Tenrai cache when user logs in so thumbnails/episodes are re-fetched
    private val loginRefreshListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "shinden_user_id") {
            val nowLoggedIn = preferences.getString("shinden_user_id", null) != null
            if (isLoggedIn != nowLoggedIn) {
                ExtLog.d(TAG, "Login state changed (loggedIn=$nowLoggedIn) - clearing Tenrai cache")
                tenraiCache.edit().clear().apply()
                genreCache.edit().clear().apply()
            }
            isLoggedIn = nowLoggedIn
        }
    }.also { listener ->
        preferences.registerOnSharedPreferenceChangeListener(listener)
        ExtLog.d(TAG, "Login refresh listener registered")
    }

    // Anime list client-side filter state
    internal var animeListTypeFilter: String? = null
    internal var animeListSortFilter: Int = 0

    private val sharedCookieJar = PersistentCookieJar(cookiePrefs)

    override val client: OkHttpClient = network.client.newBuilder()
        .dns(ShindenDns())
        .cookieJar(sharedCookieJar)
        .connectionPool(okhttp3.ConnectionPool(64, 5, java.util.concurrent.TimeUnit.MINUTES))
        .addInterceptor(::browserHeadersInterceptor)
        .addInterceptor(::loginInterceptor)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    internal val videoClient get() = network.client

    private val loginClient: OkHttpClient = network.client.newBuilder()
        .dns(ShindenDns())
        .cookieJar(sharedCookieJar)
        .addInterceptor(::browserHeadersInterceptor)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // ================================ Interceptors ================================

    private fun browserHeadersInterceptor(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val host = original.url.host
        val isCda = host.endsWith("cda.pl")

        val builder = original.newBuilder()
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        if (original.header("Accept-Language") == null) {
            builder.header("Accept-Language", "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7")
        }
        if (original.header("Accept") == null) {
            builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        }
        if (!isCda && original.header("Referer") == null) {
            builder.header("Referer", "$baseUrl/")
        }
        return chain.proceed(builder.build())
    }

    private fun loginInterceptor(chain: Interceptor.Chain): Response {
        // Legacy: auto-login from old shinden_username/shinden_password prefs
        val username = preferences.getString("shinden_username", "").orEmpty()
        val password = preferences.getString("shinden_password", "").orEmpty()

        if (!isLoggedIn && username.isNotBlank() && password.isNotBlank()) {
            synchronized(this) {
                if (!isLoggedIn) {
                    val success = runCatching { login(username, password) }.getOrDefault(false)
                    if (success) {
                        isLoggedIn = true
                    } else {
                        ExtLog.w(TAG, "Login failed — clearing saved cookies")
                        cookiePrefs.edit().remove("cookies").apply()
                        (client.cookieJar as? PersistentCookieJar)?.clear()
                    }
                }
            }
        }
        return chain.proceed(chain.request())
    }

    private fun login(username: String, password: String): Boolean {
        // Step 1: GET login page to collect initial cookies/CSRF
        val getLogin = loginClient.newCall(
            GET("$baseUrl/main/0/login", headers),
        ).execute()
        getLogin.close()
        ExtLog.d(TAG, "login step1 GET: http=${getLogin.code}")

        // Step 2: POST login form
        val body = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .add("remember", "on")
            .add("login", "")
            .build()

        val postResponse = loginClient.newCall(
            POST(
                "$baseUrl/main/0/login",
                headers = headers.newBuilder()
                    .add("Referer", "$baseUrl/main/0/login")
                    .add("Origin", baseUrl)
                    .add("Content-Type", "application/x-www-form-urlencoded")
                    .build(),
                body = body,
            ),
        ).execute()

        val postCode = postResponse.code
        val postBody = postResponse.body?.string() ?: ""
        postResponse.close()
        ExtLog.d(TAG, "login step2 POST: http=$postCode body_len=${postBody.length}")

        if (postCode !in 200..399) {
            ExtLog.w(TAG, "login failed: HTTP $postCode")
            return false
        }

        // Step 3: Follow up GET main page to confirm session
        val mainResponse = loginClient.newCall(
            GET("$baseUrl/main", headers),
        ).execute()
        val mainCode = mainResponse.code
        val mainBody = mainResponse.body?.string() ?: ""
        mainResponse.close()
        ExtLog.d(TAG, "login step3 GET main: http=$mainCode body_len=${mainBody.length}")

        // Verify: check _Storage.userId exists (= logged in)
        val userIdMatch = Regex("""_Storage\.userId\s*=\s*(\d+)""").find(mainBody)
        if (userIdMatch != null) {
            val userId = userIdMatch.groupValues[1]
            ExtLog.d(TAG, "login OK: userId=$userId")
            preferences.edit()
                .putString("shinden_user_id", userId)
                .apply()

            // Extract username from title or _Storage
            val usernameMatch = Regex("""_Storage\.username\s*=\s*['"](.*?)['"]""").find(mainBody)
                ?: Regex("""<title>([^<]+)\s*\(użytkownik\)""").find(mainBody)
            val extractedUsername = usernameMatch?.groupValues?.get(1)?.trim() ?: username
            preferences.edit().putString("shinden_display_name", extractedUsername).apply()
            ExtLog.d(TAG, "login OK: userId=$userId displayName=$extractedUsername")
            return true
        }

        // Fallback: check if page has user menu (indicates logged in)
        val hasUserMenu = mainBody.contains("user-panel") || mainBody.contains("logout")
        ExtLog.d(TAG, "login fallback: userId not found, hasUserMenu=$hasUserMenu")
        return hasUserMenu
    }

    // ================================ Popular =====================================

    override fun popularAnimeRequest(page: Int): Request = GET(
        "$baseUrl/series?view=list&page=$page&sort=rating" +
            "&series_status[0]=Currently+Airing&series_status[1]=Finished+Airing",
        headers,
    )

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimeList(response)

    // ================================ Latest ======================================

    override fun latestUpdatesRequest(page: Int): Request = GET(
        "$baseUrl/series?view=list&page=$page&sort=latest",
        headers,
    )

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimeList(response)

    // ================================ Search ======================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        currentSearchPage = page
        val filtersHash = filters.hashCode()
        if (query != lastSearchQuery || filtersHash != lastSearchFiltersHash || page != lastSearchPage) {
            lastSearchUrl = ""
            lastSearchResult = null
            lastSearchQuery = query
            lastSearchFiltersHash = filtersHash
            lastSearchPage = page
        }
        // Handle my anime filter - server-side per-status
        val myAnimeFilter = filters.filterIsInstance<MyAnimeFilter>().firstOrNull()
        if (myAnimeFilter != null && myAnimeFilter.state) {
            isMyAnimeActive = true
            myAnimeNoUserId = false
            myAnimeSearchQuery = query.trim().ifBlank { null }
            val userId = preferences.getString("shinden_user_id", null)
            if (userId != null) {
                val selectedStatuses = filters.filterIsInstance<MyAnimeWatchStatusFilter>().firstOrNull()
                    ?.state
                    ?.filterIsInstance<MyAnimeWatchStatusFilter.WatchStatus>()
                    ?.filter { it.state }
                    ?.map { it.apiPath }
                    ?: emptyList()

                myAnimeRemainingStatuses = if (selectedStatuses.isNotEmpty()) selectedStatuses else emptyList()
                myAnimePendingResults = mutableListOf()

                // Capture standard filters for client-side application
                val typeMap = mapOf(
                    1 to "TV",
                    2 to "ONA",
                    3 to "OVA",
                    4 to "Movie",
                    5 to "Special",
                    6 to "Music",
                )
                myAnimeActiveTypeFilter = filters.filterIsInstance<AnimeTypeFilter>().firstOrNull()
                    ?.let { typeMap[it.state] }

                myAnimeActiveLetterFilter = filters.filterIsInstance<LetterFilter>().firstOrNull()?.let { f ->
                    if (f.state > 0) {
                        arrayOf(
                            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J",
                            "K", "L", "M", "N", "O", "P", "R", "S", "T", "U",
                            "W", "Y", "Z", "0-9",
                        )[f.state - 1]
                    } else {
                        null
                    }
                }

                myAnimeActiveEpisodeFilter = filters.filterIsInstance<EpisodeCountFilter>().firstOrNull()?.let { f ->
                    when (f.state) {
                        1 -> "only_1"
                        2 -> "2_to_14"
                        3 -> "15_to_28"
                        4 -> "29_to_100"
                        5 -> "over_100"
                        else -> null
                    }
                }

                // Capture sort (A-Z/Z-A work, rating sort not supported)
                myAnimeActiveSortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()?.state ?: 0

                // Capture status filter before resetting (works with list API)
                myAnimeActiveTitleStatusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()?.let { f ->
                    when (f.state) {
                        1 -> "Currently Airing"
                        2 -> "Finished Airing"
                        3 -> "Not yet aired"
                        4 -> "Proposal"
                        else -> null
                    }
                }

                // Reset unsupported filters - they don't work for list API
                filters.filterIsInstance<StatusFilter>().firstOrNull()?.let { it.state = 0 }
                filters.filterIsInstance<GenreFilter>().firstOrNull()?.let { f -> f.state.forEach { it.state = false } }
                filters.filterIsInstance<TargetGroupFilter>().firstOrNull()?.let { f -> f.state.forEach { it.state = false } }
                filters.filterIsInstance<EntityFilter>().firstOrNull()?.let { f -> f.state.forEach { it.state = false } }
                filters.filterIsInstance<PlaceFilter>().firstOrNull()?.let { f -> f.state.forEach { it.state = false } }
                filters.filterIsInstance<MiscTagFilter>().firstOrNull()?.let { f -> f.state.forEach { it.state = false } }
                filters.filterIsInstance<ProductionTypeFilter>().firstOrNull()?.let { f -> f.state.forEach { it.state = false } }
                filters.filterIsInstance<SourceFilter>().firstOrNull()?.let { f -> f.state.forEach { it.state = false } }
                filters.filterIsInstance<SortFilter>().firstOrNull()?.let { it.state = 0 }
                ExtLog.d(TAG, "my anime: reset StatusFilter, GenreFilter, SortFilter (unsupported for list)")

                val firstStatus = myAnimeRemainingStatuses.firstOrNull()
                if (firstStatus != null) {
                    myAnimeRemainingStatuses = myAnimeRemainingStatuses.drop(1)
                    val url = "https://lista.shinden.pl/api/userlist/$userId/anime/$firstStatus"
                    ExtLog.d(TAG, "my anime server-side: fetching status=$firstStatus, remaining=$myAnimeRemainingStatuses")
                    return GET(url, headers)
                } else {
                    // All statuses selected = no filter, fetch everything
                    val url = "https://lista.shinden.pl/api/userlist/$userId/anime"
                    ExtLog.d(TAG, "my anime server-side: fetching all (no status filter)")
                    return GET(url, headers)
                }
            } else {
                ExtLog.w(TAG, "Moje anime: no userId, returning empty list")
                myAnimeNoUserId = true
            }
        }

        if (!myAnimeNoUserId) {
            isMyAnimeActive = false
            myAnimeSearchQuery = null
            // Reset watch status filter when Moje anime is unchecked
            filters.filterIsInstance<MyAnimeWatchStatusFilter>().firstOrNull()?.let { f ->
                f.state.forEach { it.state = false }
            }
        }

        val url = buildString {
            append("$baseUrl/series?search=")
            append(query.trim().replace(" ", "+"))

            filters.filterIsInstance<AnimeTypeFilter>().firstOrNull()?.let { f ->
                val typeMap = mapOf(
                    1 to "TV",
                    2 to "ONA",
                    3 to "OVA",
                    4 to "Movie",
                    5 to "Special",
                    6 to "Music",
                )
                typeMap[f.state]?.let { append("&series_type[]=$it") }
            }

            filters.filterIsInstance<StatusFilter>().firstOrNull()?.let { f ->
                val statusMap = mapOf(
                    1 to "Currently Airing",
                    2 to "Finished Airing",
                    3 to "Not yet aired",
                    4 to "Proposal",
                )
                statusMap[f.state]?.let { append("&series_status[]=$it") }
            }

            // Collect all genre IDs from all genre group filters into single param
            val allGenreIds = mutableListOf<String>()
            filters.filterIsInstance<GenreFilter>().firstOrNull()
                ?.state?.filter { it.state }?.forEach { allGenreIds.add(it.id) }
            filters.filterIsInstance<TargetGroupFilter>().firstOrNull()
                ?.state?.filter { it.state }?.forEach { allGenreIds.add(it.id) }
            filters.filterIsInstance<EntityFilter>().firstOrNull()
                ?.state?.filter { it.state }?.forEach { allGenreIds.add(it.id) }
            filters.filterIsInstance<PlaceFilter>().firstOrNull()
                ?.state?.filter { it.state }?.forEach { allGenreIds.add(it.id) }
            filters.filterIsInstance<MiscTagFilter>().firstOrNull()
                ?.state?.filter { it.state }?.forEach { allGenreIds.add(it.id) }
            filters.filterIsInstance<ProductionTypeFilter>().firstOrNull()
                ?.state?.filter { it.state }?.forEach { allGenreIds.add(it.id) }
            filters.filterIsInstance<SourceFilter>().firstOrNull()
                ?.state?.filter { it.state }?.forEach { allGenreIds.add(it.id) }

            if (allGenreIds.isNotEmpty()) {
                append("&genres-type=all")
                append("&genres=")
                append(allGenreIds.joinToString(";") { "i$it" })
            }

            filters.filterIsInstance<SortFilter>().firstOrNull()?.let { f ->
                when (f.state) {
                    1 -> append("&sort_by=desc&sort_order=asc") // title A-Z
                    2 -> append("&sort_by=desc&sort_order=desc") // title Z-A
                    3 -> append("&sort_by=type&sort_order=desc") // type
                    4 -> append("&sort_by=multimedia&sort_order=desc") // multimedia
                    5 -> append("&sort_by=status&sort_order=desc") // status
                    6 -> append("&sort_by=ranking-rate&sort_order=desc") // rating high-low
                    7 -> append("&sort_by=ranking-rate&sort_order=asc") // rating low-high
                }
            }

            filters.filterIsInstance<LetterFilter>().firstOrNull()?.let { f ->
                if (f.state > 0) {
                    val letter = arrayOf(
                        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J",
                        "K", "L", "M", "N", "O", "P", "R", "S", "T", "U",
                        "W", "Y", "Z", "0-9",
                    )[f.state - 1]
                    append("&letter=$letter")
                }
            }

            filters.filterIsInstance<SeriesLengthFilter>().firstOrNull()?.let { f ->
                val lengthMap = mapOf(
                    1 to "less_7",
                    2 to "7_to_18",
                    3 to "19_to_27",
                    4 to "28_to_48",
                    5 to "over_48",
                )
                lengthMap[f.state]?.let { append("&series_length[]=$it") }
            }

            filters.filterIsInstance<EpisodeCountFilter>().firstOrNull()?.let { f ->
                val epMap = mapOf(
                    1 to "only_1",
                    2 to "2_to_14",
                    3 to "15_to_28",
                    4 to "29_to_100",
                    5 to "over_100",
                )
                epMap[f.state]?.let { append("&series_number[]=$it") }
            }

            // Date validation based on precision
            val precisionFilter = filters.filterIsInstance<YearPrecisionFilter>().firstOrNull()
            val datePattern = when (precisionFilter?.state ?: 0) {
                0 -> Regex("^\\d{4}$") // RRRR
                1 -> Regex("^\\d{4}-\\d{2}$") // RRRR-MM
                2 -> Regex("^\\d{4}-\\d{2}-\\d{2}$") // RRRR-MM-DD
                else -> Regex("^\\d{4}$")
            }
            filters.filterIsInstance<YearFromFilter>().firstOrNull()?.let { f ->
                val sanitized = f.state.replace(".", "-").replace(Regex("[^0-9-]"), "")
                if (sanitized.isNotBlank() && datePattern.matches(sanitized)) append("&year_from=$sanitized")
            }
            filters.filterIsInstance<YearToFilter>().firstOrNull()?.let { f ->
                val sanitized = f.state.replace(".", "-").replace(Regex("[^0-9-]"), "")
                if (sanitized.isNotBlank() && datePattern.matches(sanitized)) append("&year_to=$sanitized")
            }
            precisionFilter?.let { f ->
                val precision = when (f.state) {
                    0 -> "1" // RRRR
                    1 -> "2" // RRRR-MM
                    2 -> "3" // RRRR-MM-DD
                    else -> "1"
                }
                append("&start_date_precision=$precision")
            }
        }
        // Store base URL (without page) for batch loading
        batchOffset = (page - 1) * batchSize + 1
        val pageUrl = if (url.contains("page=")) {
            url.replace(Regex("page=\\d+"), "page=$batchOffset")
        } else {
            "$url&page=$batchOffset"
        }
        searchBaseUrl = pageUrl.replace(Regex("page=\\d+"), "page={PAGE}")
        return GET(pageUrl, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        ExtLog.d(TAG, "searchAnimeParse: called, url=${response.request.url}")
        val currentUrl = response.request.url.toString()
        if (currentUrl == lastSearchUrl && lastSearchResult != null && currentSearchPage == lastSearchPage) {
            ExtLog.d(TAG, "searchAnimeParse: returning cached result for same URL")
            return lastSearchResult ?: return AnimesPage(emptyList(), false)
        }
        // No userId + Moje anime = empty
        if (isMyAnimeActive && myAnimeNoUserId) {
            isMyAnimeActive = false
            myAnimeNoUserId = false
            myAnimeSearchQuery = null
            return AnimesPage(emptyList(), false)
        }
        val body = response.peekBody(2048).string()
        if (body.trimStart().startsWith("{") && body.contains("\"result\"")) {
            val entries = parseAnimeListApiEntries(response)

            if (myAnimeRemainingStatuses.isNotEmpty()) {
                myAnimePendingResults.addAll(entries)
                val userId = preferences.getString("shinden_user_id", null)
                    ?: return AnimesPage(entries, false)
                val nextStatus = myAnimeRemainingStatuses.first()
                myAnimeRemainingStatuses = myAnimeRemainingStatuses.drop(1)
                val url = "https://lista.shinden.pl/api/userlist/$userId/anime/$nextStatus"
                ExtLog.d(TAG, "my anime server-side: merging status=$nextStatus, accumulated=${myAnimePendingResults.size}")
                val nextResponse = client.newCall(GET(url, headers)).execute()
                val nextBody = nextResponse.peekBody(2048).string()
                if (nextBody.trimStart().startsWith("{") && nextBody.contains("\"result\"")) {
                    myAnimePendingResults.addAll(parseAnimeListApiEntries(nextResponse))
                }
                nextResponse.close()
                val filtered = applyMyAnimeStandardFilters(myAnimePendingResults.toList())
                return AnimesPage(filtered, false)
            }

            return if (myAnimePendingResults.isNotEmpty()) {
                myAnimePendingResults.addAll(entries)
                val result = myAnimePendingResults.toList()
                myAnimePendingResults.clear()
                val filtered = applyMyAnimeStandardFilters(result)
                ExtLog.d(TAG, "my anime server-side: final merge total=${result.size} filtered=${filtered.size}")
                AnimesPage(filtered, false)
            } else {
                val filtered = applyMyAnimeStandardFilters(entries)
                AnimesPage(filtered, false)
            }
        }
        // Batch fetch for regular search
        ExtLog.d(TAG, "searchAnimeParse: batch start batchOffset=$batchOffset batchSize=$batchSize searchBaseUrl=$searchBaseUrl")
        val allEntries = mutableListOf<SAnime>()
        var currentResponse = response
        for (i in 0 until batchSize) {
            val pageResult = parseAnimeList(currentResponse)
            ExtLog.d(TAG, "searchAnimeParse: page ${batchOffset + i} returned ${pageResult.animes.size} items, hasNextPage=${pageResult.hasNextPage}")
            allEntries.addAll(pageResult.animes)
            batchHasNext = pageResult.hasNextPage
            if (!batchHasNext || i == batchSize - 1) break
            currentResponse.close()
            val nextPage = batchOffset + i + 1
            val nextUrl = searchBaseUrl.replace("{PAGE}", nextPage.toString())
            ExtLog.d(TAG, "searchAnimeParse: fetching page $nextPage url=$nextUrl")
            currentResponse = client.newCall(GET(nextUrl, headers)).execute()
        }
        ExtLog.d(TAG, "searchAnimeParse: batch done total=${allEntries.size} distinct=${allEntries.distinctBy { it.url }.size} batchHasNext=$batchHasNext")
        val result = AnimesPage(allEntries.distinctBy { it.url }, batchHasNext)
        lastSearchUrl = currentUrl
        lastSearchResult = result
        return result
    }
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("⚠️ Z filtrem 🎬 Moje anime działa jedynie sortowanie alfabetyczne oraz domyślne"),
        MyAnimeFilter(),
        MyAnimeWatchStatusFilter(),
        AnimeTypeFilter(),
        StatusFilter(),
        LetterFilter(),
        EpisodeCountFilter(),
        SortFilter(),
        AnimeFilter.Header("Poniższe filtry nie współpracują z 🎬 Moje anime:"),
        GenreFilter(),
        TargetGroupFilter(),
        EntityFilter(),
        PlaceFilter(),
        MiscTagFilter(),
        ProductionTypeFilter(),
        SourceFilter(),
        SeriesLengthFilter(),
        YearFromFilter(),
        YearToFilter(),
        YearPrecisionFilter(),
    )

    // ============================== Tenrai (MAL) Integration ============================

    // Helper: Jikan HTTP with retry for transient errors (429, 504, 5xx)
    private fun tenraiHttpGet(url: String, maxRetries: Int = 3): String? {
        for (attempt in 1..maxRetries) {
            try {
                if (attempt > 1) {
                    Thread.sleep(1000L * attempt)
                    ExtLog.d(TAG, "Tenrai HTTP: retry $attempt/$maxRetries for $url")
                }
                val request = Request.Builder().url(url).build()
                network.client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (resp.code == 429 || resp.code >= 500) {
                        ExtLog.w(TAG, "Tenrai HTTP: ${resp.code} for $url (attempt $attempt/$maxRetries)")
                        if (attempt < maxRetries) return@use // retry
                        return null
                    }
                    if (!resp.isSuccessful) {
                        ExtLog.w(TAG, "Tenrai HTTP: ${resp.code} for $url")
                        return null
                    }
                    return body
                }
            } catch (e: Exception) {
                ExtLog.d(TAG, "Tenrai HTTP exception (attempt $attempt/$maxRetries): ${e.message}")
                if (attempt >= maxRetries) return null
            }
        }
        return null
    }

    private fun tenraiSearchMalId(title: String): Int? {
        val cached = tenraiCache.getString("mal_$title", null)
        if (cached != null && cached != "null") return cached.toIntOrNull()

        // Remove invalid cache entries
        if (cached == "null") {
            tenraiCache.edit().remove("mal_$title").apply()
        }

        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
        val url = "https://api.tenrai.org/v1/anime?q=$encodedTitle&limit=1&sfw=true"
        ExtLog.d(TAG, "Tenrai search: querying for '$title'")
        val body = tenraiHttpGet(url) ?: return null
        return try {
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return null
            if (data.length() == 0) return null
            val first = data.getJSONObject(0)
            val malId = first.getInt("mal_id")
            val jikanTitle = first.optString("title", "")
            ExtLog.d(TAG, "Tenrai search: found MAL $malId for '$title' (jikan_title='$jikanTitle')")
            tenraiCache.edit().putString("mal_$title", malId.toString()).apply()
            malId
        } catch (e: Exception) {
            ExtLog.e(TAG, "Jikan search exception for '$title': ${e.message}", e)
            null
        }
    }

    // Returns pair: fillerEpisodes, recapEpisodes
    private fun tenraiGetFillerEpisodes(malId: Int): Pair<Set<Int>, Set<Int>> {
        val fillerCached = tenraiCache.getString("filler_$malId", null)
        val recapCached = tenraiCache.getString("recap_$malId", null)
        if (fillerCached != null && recapCached != null) {
            return try {
                val fillerArr = org.json.JSONArray(fillerCached)
                val recapArr = org.json.JSONArray(recapCached)
                val fillers = (0 until fillerArr.length()).map { fillerArr.getInt(it) }.toSet()
                val recaps = (0 until recapArr.length()).map { recapArr.getInt(it) }.toSet()
                Pair(fillers, recaps)
            } catch (_: Exception) {
                Pair(emptySet(), emptySet())
            }
        }

        return try {
            val fillerSet = mutableSetOf<Int>()
            val recapSet = mutableSetOf<Int>()
            var page = 1
            var hasNext = true
            while (hasNext && page <= 20) {
                val url = "https://api.tenrai.org/v1/anime/$malId/episodes?page=$page"
                if (page > 1) Thread.sleep(500)
                val body = tenraiHttpGet(url) ?: break
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: break
                for (i in 0 until data.length()) {
                    val ep = data.getJSONObject(i)
                    val epId = ep.getInt("mal_id")
                    if (ep.optBoolean("filler", false)) fillerSet.add(epId)
                    if (ep.optBoolean("recap", false)) recapSet.add(epId)
                }
                hasNext = json.optJSONObject("pagination")?.optBoolean("has_next_page", false) ?: false
                page++
            }
            val fillerArr = org.json.JSONArray(fillerSet.toList())
            val recapArr = org.json.JSONArray(recapSet.toList())
            tenraiCache.edit()
                .putString("filler_$malId", fillerArr.toString())
                .putString("recap_$malId", recapArr.toString())
                .putLong("filler_${malId}_ts", System.currentTimeMillis())
                .apply()
            Pair(fillerSet, recapSet)
        } catch (e: Exception) {
            ExtLog.d(TAG, "Tenrai filler fetch failed for MAL $malId: ${e.message}")
            Pair(emptySet(), emptySet())
        }
    }

    private fun tenraiGetStaff(malId: Int): List<String> {
        val cached = tenraiCache.getString("staff_$malId", null)
        if (cached != null) {
            return try {
                val arr = org.json.JSONArray(cached)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) {
                emptyList()
            }
        }

        return try {
            val staffNames = mutableListOf<String>()
            // Use full endpoint for staff
            val fullUrl = "https://api.tenrai.org/v1/anime/$malId/full"
            val body = tenraiHttpGet(fullUrl)
            if (body != null) {
                val json = runCatching { JSONObject(body) }.getOrNull()
                val data = json?.optJSONObject("data")
                val staffArr = data?.optJSONArray("staff")
                if (staffArr != null) {
                    val wantedRoles = listOf("Director", "Screenplay", "Music", "Character Design")
                    for (i in 0 until staffArr.length()) {
                        val s = staffArr.getJSONObject(i)
                    val role = s.optString("role", "")
                    if (wantedRoles.any { role.contains(it, ignoreCase = true) }) {
                        val name = s.optJSONObject("person")?.optString("name", "") ?: ""
                        if (name.isNotBlank()) staffNames.add(name)
                    }
                    }
                }
            }
            if (staffNames.isNotEmpty()) {
                val arr = org.json.JSONArray(staffNames)
                tenraiCache.edit().putString("staff_$malId", arr.toString()).apply()
            }
            staffNames
        } catch (e: Exception) {
            ExtLog.d(TAG, "Tenrai staff fetch failed for MAL $malId: ${e.message}")
            emptyList()
        }
    }

    // ============================== AniList Cover Fallback ============================

    private fun fetchAniListCover(title: String): String? {
        return try {
            val body = JSONObject().apply {
                put("query", "query(\$search: String) { Media(search: \$search, type: ANIME) { coverImage { large } } }")
                put("variables", JSONObject().put("search", title))
            }
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url("https://graphql.anilist.co")
                .post(body.toString().toRequestBody(mediaType))
                .build()
            network.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string() ?: return null
                val json = JSONObject(bodyStr)
                json.optJSONObject("data")
                    ?.optJSONObject("Media")
                    ?.optJSONObject("coverImage")
                    ?.optString("large")
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            ExtLog.d(TAG, "AniList cover failed for '$title': ${e.message}")
            null
        }
    }

    private val placeholderCover = Regex("/res/other/placeholders/")

    // ============================== Anime Details ================================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        return SAnime.create().apply {
            title = cleanTitle(document.selectFirst("h1")?.text() ?: "")
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            description = document.selectFirst("meta[property=og:description], meta[name=description]")
                ?.attr("content")?.trim()
            genre = document.select("a[href*=/genre/], a[href*=/gatunek/]")
                .joinToString(", ") { it.text().trim() }
                .ifBlank { null }

            // Parse status from the page
            val statusText = document.selectFirst("section.title-small-info dl.info-aside-list dt:matchesOwn(^Status:$) + dd")
                ?.text()?.trim() ?: ""
            status = when {
                statusText.contains("Zakończone", ignoreCase = true) ||
                    statusText.contains("Finished", ignoreCase = true) -> SAnime.COMPLETED
                statusText.contains("Emitowane", ignoreCase = true) ||
                    statusText.contains("Currently Airing", ignoreCase = true) -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }

            // Parse rating and add to top of description
            val ratingText = document.selectFirst("h3.info-aside-rating-data span.info-aside-rating-user")
                ?.text()?.replace(",", ".")?.trim() ?: ""
            if (ratingText.isNotBlank()) {
                val rating = ratingText.toFloatOrNull()
                if (rating != null) {
                    val stars = when {
                        rating >= 9.0 -> "★★★★★"
                        rating >= 8.0 -> "★★★★½"
                        rating >= 7.0 -> "★★★★"
                        rating >= 6.0 -> "★★★½"
                        rating >= 5.0 -> "★★★"
                        rating >= 4.0 -> "★★½"
                        rating >= 3.0 -> "★★"
                        else -> "★"
                    }
                    val ratingLine = "$stars $ratingText/10"
                    val currentDesc = description ?: ""
                    description = if (currentDesc.isNotBlank()) {
                        "$ratingLine\n\n$currentDesc"
                    } else {
                        ratingLine
                    }
                }
            }

            // Set studio as author (Aniyomi shows author at top)
            val studioText = document.select("section.title-small-info dl.info-aside-list dt:matchesOwn(^Studio:$) + dd a[href^='/studio/']")
                .joinToString(", ") { it.text().trim() }
                .ifBlank { null }
            if (studioText != null) {
                author = studioText
            }

            // Extract artist (reżyser, scenarzysta, etc.) from staff section
            val artistRoles = listOf("Reżyser", "Scenarzysta", "Kompozytor", "Projektant postaci")
            val artistNames = mutableListOf<String>()
            document.select("section.person-list .person-character-item").forEach { item ->
                val role = item.selectFirst(".person-character-text p")?.text()?.trim() ?: ""
                if (artistRoles.any { role.contains(it, ignoreCase = true) }) {
                    val name = item.selectFirst(".person-character-text h3 a")?.text()?.trim() ?: ""
                    if (name.isNotBlank()) artistNames.add(name)
                }
            }
            if (artistNames.isNotEmpty()) {
                artist = artistNames.joinToString(", ")
            }

            // Tenrai staff fallback: if Shinden didn't find artist, try Jikan
            if (artist.isNullOrBlank()) {
                val titleClean = title.substringBefore(" ·").trim()
                val malId = tenraiSearchMalId(titleClean)
                if (malId != null) {
                    val tenraiStaff = tenraiGetStaff(malId)
                    if (tenraiStaff.isNotEmpty()) {
                        ExtLog.d(TAG, "Tenrai staff fallback: MAL $malId -> ${tenraiStaff.joinToString()}")
                        artist = tenraiStaff.joinToString(", ")
                    }
                }
            }

            // Completed anime: no periodic refresh needed
            if (status == SAnime.COMPLETED) {
                update_strategy = AnimeUpdateStrategy.ONLY_FETCH_ONCE
            }

            // AniList cover fallback: if no cover or placeholder, try AniList
            val hasPlaceholder = thumbnail_url == null || "placeholders" in (thumbnail_url ?: "")
            if (hasPlaceholder) {
                val titleClean = title.substringBefore(" \u00b7 ").trim()
                ExtLog.d(TAG, "AniList cover: no cover/placeholder (url=$thumbnail_url), fetching for '$titleClean'")
                val anilistCover = fetchAniListCover(titleClean)
                if (anilistCover != null) {
                    ExtLog.d(TAG, "AniList cover fallback: $titleClean -> ${anilistCover.take(60)}")
                    thumbnail_url = anilistCover
                }
            }
        }
    }

    // ================================ Related =======================================

    override val supportsRelatedAnimes: Boolean = true

    override fun relatedAnimeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        ExtLog.d(TAG, "relatedAnimeListParse: called, url=${response.request.url}")
        val document = response.asJsoup()
        val relationItems = document.select("li.relation_t2t")
        ExtLog.d(TAG, "relatedAnimeListParse: found ${relationItems.size} li.relation_t2t elements")
        return relationItems.mapNotNull { li ->
            try {
                // Get link — prefer /series/ (anime), skip /manga/ etc.
                val link = li.select("a[href*=/series/]").firstOrNull() ?: return@mapNotNull null
                val href = link.attr("href")
                if (href.isBlank()) return@mapNotNull null

                val title = link.text().trim()
                if (title.isBlank()) return@mapNotNull null

                // Get relation type (Sequel, Prequel, Side Story, etc.)
                val relationType = li.select("figcaption.figure-type").lastOrNull()?.text()?.trim() ?: ""

                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    this.title = "$title ($relationType)"
                    thumbnail_url = li.selectFirst("img")?.attr("abs:src")
                }
            } catch (_: Exception) {
                null
            }
        }.distinctBy { it.url }.also { results ->
            ExtLog.d(TAG, "relatedAnimeListParse: returning ${results.size} related anime: ${results.map { "${it.title} (${it.url})" }}")
        }
    }

    // ================================ Episodes ====================================

    override fun episodeListRequest(anime: SAnime): Request {
        currentAnimeTitle = anime.title ?: ""
        return GET(baseUrl + anime.url + "/all-episodes", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val rows = document.select("tbody.list-episode-checkboxes tr")

        return rows.mapNotNull { row ->
            val cols = row.select("td")
            if (cols.size < 5) return@mapNotNull null

            val href = row.selectFirst("a[href]")?.attr("abs:href") ?: return@mapNotNull null
            val num = cols[0].text().trim().toFloatOrNull() ?: return@mapNotNull null
            val title = cols[1].text().trim()

            SEpisode.create().apply {
                setUrlWithoutDomain(href)
                episode_number = num
                name = if (title.isNotBlank()) {
                    "${num.toInt()}. $title"
                } else {
                    "Odcinek ${num.toInt()}"
                }
                date_upload = parseEpisodeDate(cols[4].text().trim())
            }
        }.sortedBy { it.episode_number }.also { episodes ->
            if (episodes.isNotEmpty()) {
                val animeTitle = currentAnimeTitle.substringBefore(" ·").trim()
                ExtLog.d(TAG, "Tenrai filler check: title='$animeTitle', eps=${episodes.size}")
                val malId = tenraiSearchMalId(animeTitle)
                ExtLog.d(TAG, "Tenrai filler: malId=$malId for '$animeTitle'")
                if (malId != null) {
                    val (tenraiFillers, tenraiRecaps) = tenraiGetFillerEpisodes(malId)
                    ExtLog.d(TAG, "Tenrai: MAL $malId -> ${tenraiFillers.size} fillers, ${tenraiRecaps.size} recaps for '$animeTitle'")
                    var added = 0
                    episodes.forEach { ep ->
                        val epNum = ep.episode_number.toInt()
                        when {
                            epNum in tenraiFillers -> { ep.name = "(Filler) ${ep.name}"; added++ }
                            epNum in tenraiRecaps -> { ep.name = "(Recap) ${ep.name}"; added++ }
                        }
                    }
                    if (added > 0) ExtLog.d(TAG, "Tenrai: added $added marks for '$animeTitle'")
                }
            }
        }
    }

    private fun parseEpisodeDate(raw: String): Long {
        val formats = listOf("dd.MM.yyyy", "yyyy-MM-dd")
        for (pattern in formats) {
            runCatching {
                return SimpleDateFormat(pattern, Locale("pl")).parse(raw)?.time ?: 0L
            }
        }
        return 0L
    }

    internal val fallbackAuth = "X2d1ZXN0XzowLDUsMjEwMDAwMDAsMjU1LDQxNzQyOTM2NDQ="

    internal val cdaExtractor by lazy { CdaExtractor(client) }
    internal val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    internal val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    internal val aparatExtractor by lazy { AparatExtractor(client) }
    internal val universalExtractor by lazy { UniversalExtractor(client) }
    internal val filemoonExtractor by lazy { FilemoonExtractor(client) }
    internal val doodExtractor by lazy { DoodExtractor(client) }
    internal val sibnetExtractor by lazy { SibnetExtractor(client) }
    internal val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    internal val okruExtractor by lazy { OkruExtractor(client, headers) }
    internal val uqloadExtractor by lazy { UqloadExtractor(client) }
    internal val vkExtractor by lazy { VkExtractor(client, headers) }
    internal val lycorisExtractor by lazy { LycorisCafeExtractor(client) }
    internal val streamupExtractor by lazy { StreamupExtractor(client) }
    internal val megaNzExtractor by lazy { MegaNzExtractor(client) }
    internal val vidaraExtractor by lazy { VidaraExtractor(client) }
    internal val bysesukiorExtractor by lazy { BysesukiorExtractor(client, ShindenDns()) }
    internal val flyfileExtractor by lazy { FlyfileExtractor(client) }
    internal val shareVideoExtractor by lazy { ShareVideoExtractor(client) }
    internal val googleDrivePlayerExtractor by lazy { GoogleDrivePlayerExtractor(client, headers, preferences.getBoolean("verbose_logging", false)) }
    internal val gdrivePlayerExtractor by lazy { GdrivePlayerExtractor(client) }
    internal val playmateExtractor by lazy { PlaymateExtractor(client) }
    internal val m3u8Integration by lazy { aniyomi.lib.m3u8server.M3u8Integration(client) }

    // ================================ Delegations to extension files ===

    override fun videoListParse(response: Response): List<Video> = videoListParseExt(response)

    override fun List<Video>.sort(): List<Video> = sortVideos(this)

    override fun setupPreferenceScreen(screen: PreferenceScreen) = setupPreferenceScreenExt(screen)
}

internal class PersistentCookieJar(
    private val prefs: SharedPreferences,
) : CookieJar {

    private val cookies = Collections.synchronizedList(mutableListOf<Cookie>())

    init {
        loadPersisted()
    }

    override fun saveFromResponse(url: HttpUrl, newCookies: List<Cookie>) {
        synchronized(cookies) {
            for (c in newCookies) {
                cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                if (c.expiresAt > System.currentTimeMillis()) {
                    cookies.add(c)
                }
            }
            persist()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(cookies) {
            cookies.removeAll { it.expiresAt < System.currentTimeMillis() }
            return cookies.filter { it.matches(url) }.toList()
        }
    }

    fun clear() {
        synchronized(cookies) {
            cookies.clear()
            persist()
        }
    }

    private fun persist() {
        val json = JSONArray()
        cookies.forEach { cookie ->
            json.put(
                JSONObject().apply {
                    put("name", cookie.name)
                    put("value", cookie.value)
                    put("domain", cookie.domain)
                    put("path", cookie.path)
                    put("expiresAt", cookie.expiresAt)
                    put("secure", cookie.secure)
                    put("httpOnly", cookie.httpOnly)
                },
            )
        }
        prefs.edit().putString("cookies", json.toString()).apply()
    }

    private fun loadPersisted() {
        val raw = prefs.getString("cookies", null) ?: return
        runCatching {
            val json = JSONArray(raw)
            val now = System.currentTimeMillis()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                if (obj.optLong("expiresAt", 0) < now) continue
                val cookie = Cookie.Builder()
                    .name(obj.getString("name"))
                    .value(obj.getString("value"))
                    .domain(obj.getString("domain"))
                    .path(obj.getString("path"))
                    .expiresAt(obj.optLong("expiresAt", Long.MAX_VALUE))
                if (obj.optBoolean("secure")) cookie.secure()
                if (obj.optBoolean("httpOnly")) cookie.httpOnly()
                cookies.add(cookie.build())
            }
        }
    }
}
