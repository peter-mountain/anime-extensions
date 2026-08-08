package eu.kanade.tachiyomi.animeextension.pl.shinden

import android.app.Application
import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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
import aniyomi.lib.okruextractor.OkruExtractor // NewAndModified
import aniyomi.lib.playmateextractor.PlaymateExtractor
import aniyomi.lib.sharevideoextractor.ShareVideoExtractor
import aniyomi.lib.sibnetextractor.SibnetExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamupextractor.StreamupExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidaraextractor.VidaraExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
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
        private const val TAG = "ShindenExt"
    }

    override val name = "Shinden"

    override val baseUrl = "https://shinden.pl"

    override val lang = "pl"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // My anime list - server-side filtering state
    // Track whether myAnime mode is active (for getFilterList)
    @Volatile
    private var isMyAnimeActive = false

    @Volatile
    private var myAnimeNoUserId = false
    private var myAnimeRemainingStatuses: List<String> = emptyList()
    private var myAnimePendingResults: MutableList<SAnime> = mutableListOf()
    private var myAnimeActiveTypeFilter: String? = null
    private var myAnimeActiveSortFilter: Int = 0
    private var myAnimeActiveLetterFilter: String? = null
    private var myAnimeActiveEpisodeFilter: String? = null
    private var myAnimeActiveTitleStatusFilter: String? = null
    private var myAnimeSearchQuery: String? = null

    // Batch loading: fetch multiple pages at once
    private val batchSize = 4
    private var batchOffset = 1
    private var batchHasNext = false
    private var searchBaseUrl = ""
    private var lastSearchUrl = ""
    private var lastSearchResult: AnimesPage? = null
    private var lastSearchQuery = ""
    private var lastSearchFiltersHash = 0
    private var lastSearchPage = 0
    private var currentSearchPage = 1

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

    private val cookiePrefs: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("cookies_$id", 0x0000)
    }

    private val genreCache: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("genre_cache_$id", 0x0000)
    }

    @Volatile
    private var isLoggedIn = preferences.getString("shinden_user_id", null) != null

    // Anime list client-side filter state
    private var animeListTypeFilter: String? = null
    private var animeListSortFilter: Int = 0

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
            return lastSearchResult!!
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
    private fun cleanTitle(raw: String): String {
        // Remove leading "Anime" when it's a single word prefix
        // Shinden prepends "Anime" to distinguish from manga/novels
        // The <h1> on details page has "Anime\n\nTitle" pattern
        val trimmed = raw.trim()
        val cleaned = trimmed.replace(Regex("""^Anime\s+""", RegexOption.IGNORE_CASE), "")
        return cleaned.ifBlank { trimmed }
    }

    private fun parseAnimeList(response: Response): AnimesPage {
        val document = response.asJsoup()
        val items = document.select("li.desc-col")

        val entries = items.mapNotNull { li ->
            val link = li.select("a").firstOrNull { a ->
                val href = a.attr("href")
                href.contains("/series/") || href.contains("/titles/")
            } ?: return@mapNotNull null

            SAnime.create().apply {
                title = cleanTitle(link.text())
                setUrlWithoutDomain(link.attr("abs:href"))
                thumbnail_url = li.parent()?.selectFirst(".cover-col > a")?.attr("abs:href")
                val type = li.selectFirst(".title-kind-col")?.text()?.trim()
                val rating = li.selectFirst(".rate-top")?.text()?.trim()
                val episodes = li.selectFirst(".episodes-col")?.text()?.trim()
                val extra = listOfNotNull(
                    type?.let { "Typ: $it" },
                    rating?.let { "Ocena: $it" },
                    episodes?.let { "Odcinki: $it" },
                ).joinToString(" | ")
                if (extra.isNotBlank()) {
                    genre = if (genre.isNullOrBlank()) extra else "$genre | $extra"
                }
            }
        }.distinctBy { it.url }

        val hasNextPage = document.selectFirst("li.pagination-next") != null

        return AnimesPage(entries, hasNextPage)
    }

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
        }
    }

    // ================================ Episodes ====================================

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url + "/all-episodes", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val rows = document.select("tbody.list-episode-checkboxes tr")

        return rows.mapNotNull { row ->
            val cols = row.select("td")
            if (cols.size < 5) return@mapNotNull null

            val href = row.selectFirst("a[href]")?.attr("abs:href") ?: return@mapNotNull null
            val num = cols[0].text().trim().toFloatOrNull() ?: return@mapNotNull null
            val title = cols[1].text().trim()

            // Detect filler episodes (Shinden uses i.fa-facebook.button-with-tip for filler)
            val isFiller = row.selectFirst("i.fa-facebook.button-with-tip") != null

            SEpisode.create().apply {
                setUrlWithoutDomain(href)
                episode_number = num
                name = if (title.isNotBlank()) {
                    if (isFiller) "(Filler) ${num.toInt()}. $title" else "${num.toInt()}. $title"
                } else {
                    if (isFiller) "(Filler) Odcinek ${num.toInt()}" else "Odcinek ${num.toInt()}"
                }
                date_upload = parseEpisodeDate(cols[4].text().trim())
            }
        }.sortedBy { it.episode_number }
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

    // ================================ Video Links =================================

    private val fallbackAuth = "X2d1ZXN0XzowLDUsMjEwMDAwMDAsMjU1LDQxNzQyOTM2NDQ="

    private val cdaExtractor by lazy { CdaExtractor(client) }
    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val lycorisExtractor by lazy { LycorisCafeExtractor(client) }
    private val streamupExtractor by lazy { StreamupExtractor(client) }
    private val megaNzExtractor by lazy { MegaNzExtractor(client) }
    private val vidaraExtractor by lazy { VidaraExtractor(client) }
    private val bysesukiorExtractor by lazy { BysesukiorExtractor(client, ShindenDns()) }
    private val flyfileExtractor by lazy { FlyfileExtractor(client) }
    private val shareVideoExtractor by lazy { ShareVideoExtractor(client) }
    private val googleDrivePlayerExtractor by lazy { GoogleDrivePlayerExtractor(client, headers, preferences.getBoolean("verbose_logging", false)) }
    private val gdrivePlayerExtractor by lazy { GdrivePlayerExtractor(client) }
    private val playmateExtractor by lazy { PlaymateExtractor(client) }
    private val m3u8Integration by lazy { aniyomi.lib.m3u8server.M3u8Integration(client) }

    override fun videoListParse(response: Response): List<Video> {
        ExtLog.d(TAG, "=== videoListParse http=${response.code} url=${response.request.url} ===")

        val document = response.asJsoup()
        val bodyText = document.outerHtml()

        val authCode = Regex("""_Storage\.basic\s*=\s*'(.*?)'""")
            .find(bodyText)
            ?.groupValues
            ?.get(1)
            ?: fallbackAuth

        val sources = parseSources(document, authCode)
        ExtLog.d(TAG, "parsed ${sources.size} sources")

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
                ExtLog.d(TAG, "batch: created ${loadClients.size} per-call clients")

                // ===== BATCH PHASE 2: fire ALL player_load in parallel =====
                loadClients.map { (loadUrl, client) ->
                    async {
                        runCatching {
                            val r = client.newCall(GET(loadUrl, headers)).execute()
                            val code = r.code
                            r.close()
                            ExtLog.d(TAG, "batch player_load http=$code for $loadUrl")
                        }.onFailure {
                            ExtLog.w(TAG, "batch player_load FAILED: $loadUrl — ${it.message}")
                        }
                    }
                }.awaitAll()
                ExtLog.d(TAG, "batch: all ${loadClients.size} player_load done in ${System.currentTimeMillis() - t0}ms")

                // ===== BATCH PHASE 3: single sleep(2000) =====
                ExtLog.d(TAG, "batch: sleep(2000) after ${loadClients.size} player_loads")
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
                        ExtLog.d(TAG, "batch player_show http=${r.code} for $loadUrl")
                        val doc = r.asJsoup()
                        val raw = doc.selectFirst("iframe[src]")?.attr("src")
                            ?: doc.selectFirst("a[href]")?.attr("href")
                        raw?.let { if (it.startsWith("//")) "https:$it" else it }
                    }.onFailure {
                        ExtLog.w(TAG, "batch player_show FAILED: $showUrl — ${it.message}")
                    }.getOrNull()
                    ExtLog.d(TAG, "batch player_show resolved: $resolved for $loadUrl")
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
                    ExtLog.d(TAG, "retry: ${failedClients.size} failed, mode=${if (seqRetry) "sequential(${seqSleepMs.toLong()}ms)" else "stagger"}")
                    if (seqRetry) {
                        // Sequential retry: player_load → sleep(3500) → player_show for each
                        for ((j, pair) in failedClients.withIndex()) {
                            val (loadUrl, client) = pair
                            val loadUrl2 = loadUrl
                            val showUrl = loadUrl2.replace("player_load", "player_show") + "&width=0&height=-1"
                            val resolved = runCatching {
                                ExtLog.d(TAG, "seq retry: player_load $loadUrl2")
                                val rLoad = client.newCall(GET(loadUrl2, headers)).execute()
                                rLoad.close()
                                ExtLog.d(TAG, "seq retry: sleep(${seqSleepMs.toLong()}ms)")
                                Thread.sleep(seqSleepMs.toLong())
                                val r = client.newCall(GET(showUrl, headers)).execute()
                                ExtLog.d(TAG, "seq retry: player_show http=${r.code} for $loadUrl2")
                                val doc = r.asJsoup()
                                val raw = doc.selectFirst("iframe[src]")?.attr("src")
                                    ?: doc.selectFirst("a[href]")?.attr("href")
                                raw?.let { if (it.startsWith("//")) "https:$it" else it }
                            }.onFailure {
                                ExtLog.w(TAG, "seq retry FAILED: $loadUrl2 — ${it.message}")
                            }.getOrNull()
                            ExtLog.d(TAG, "seq retry resolved: $resolved for $loadUrl2")
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
                                ExtLog.d(TAG, "retry player_show http=${r.code} for $loadUrl")
                                val doc = r.asJsoup()
                                val raw = doc.selectFirst("iframe[src]")?.attr("src")
                                    ?: doc.selectFirst("a[href]")?.attr("href")
                                raw?.let { if (it.startsWith("//")) "https:$it" else it }
                            }.onFailure {
                                ExtLog.w(TAG, "retry player_show FAILED: $showUrl — ${it.message}")
                            }.getOrNull()
                            ExtLog.d(TAG, "retry player_show resolved: $resolved for $loadUrl")
                            if (resolved != null) embedUrlMap[loadUrl] = resolved
                        }
                    }
                    ExtLog.d(TAG, "retry: recovered ${failedClients.count { embedUrlMap[it.first] != null }} of ${failedClients.size}")
                }
                ExtLog.d(TAG, "batch: all ${embedUrlMap.size} player_show done in ${System.currentTimeMillis() - t0}ms total")

                // ===== BATCH PHASE 5: extract videos in parallel =====
                sources.map { (meta, loadUrl) ->
                    async {
                        withTimeoutOrNull(30_000L) {
                            try {
                                val (host, quality, audio, subs, subsAuthor) = meta
                                ExtLog.d(TAG, ">>> source: host=$host quality=$quality audio=$audio subs=$subs")

                                val embedUrl = embedUrlMap[loadUrl]
                                    ?: return@withTimeoutOrNull listOf(debugVideo("$host — resolveEmbedUrl zwrócił null"))
                                ExtLog.d(TAG, "embedUrl RESOLVED: $embedUrl")
                                val prefix = buildPrefix(audio, subs)

                                val embedHost = runCatching { embedUrl.toHttpUrl().host }.getOrDefault("?")

                                // Domain skip filter
                                val skipDomains = preferences.getString("skip_domains_list", "hqq.tv,vk.com,lulu")?.trim() ?: ""
                                if (skipDomains.isNotBlank()) {
                                    val skipList = skipDomains.split(",").map { it.trim().lowercase() }
                                    if (skipList.any { embedHost.lowercase().contains(it) }) {
                                        ExtLog.d(TAG, "SKIP domain: $embedHost matched skip_domain list")
                                        return@withTimeoutOrNull emptyList()
                                    }
                                }

                                // Language filter (hard skip before dispatch)
                                val filterLang = preferences.getBoolean("filter_language", false)
                                if (filterLang) {
                                    val allowedAudio = preferences.getStringSet("allowed_audio_langs", emptySet()) ?: emptySet()
                                    val hideNoSubs = preferences.getBoolean("hide_no_subs", false)
                                    if (hideNoSubs && (subs.isBlank() || subs.contains("--"))) {
                                        ExtLog.d(TAG, "SKIP no subs: host=$host")
                                        return@withTimeoutOrNull emptyList()
                                    }
                                    if (allowedAudio.isNotEmpty() && !allowedAudio.any { audio.contains(it, ignoreCase = true) }) {
                                        ExtLog.d(TAG, "SKIP audio lang: host=$host audio=$audio")
                                        return@withTimeoutOrNull emptyList()
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
                                        ExtLog.d(TAG, ">>> ok.ru dispatch: embedUrl=$embedUrl prefix=$prefix")
                                        val result = okruExtractor.videosFromUrl(embedUrl, prefix = prefix)
                                        ExtLog.d(TAG, "<<< ok.ru returned ${result.size} videos")
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
                                    ExtLog.d(TAG, "host=$host -> ${mapped.size} videos [verbose: embedHost=$embedHost extractor=$extractorName]")
                                } else {
                                    ExtLog.d(TAG, "host=$host -> ${mapped.size} videos")
                                }
                                mapped.ifEmpty {
                                    listOf(debugVideo("$host $quality — embedUrl=$embedUrl ale ekstraktor zwrócił 0 wideo"))
                                }
                            } catch (e: Throwable) {
                                ExtLog.e(TAG, "source error: ${e.message}", e)
                                listOf(debugVideo("Error: ${e.message?.take(200)}"))
                            }
                        } ?: run {
                            ExtLog.w(TAG, "source timed out: $loadUrl")
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
                ExtLog.d(TAG, "=== DONE: ${processed.size} videos total (showEmpty=$showEmpty, filtered=${filtered.size}) ===")
            } else {
                ExtLog.d(TAG, "=== DONE: ${filtered.size} videos total ===")
            }
            filtered
        }
    }

    private data class SourceMeta(
        val host: String,
        val quality: String,
        val audio: String,
        val subs: String,
        val subsAuthor: String = "",
    )

    private fun parseSources(document: org.jsoup.nodes.Document, authCode: String): List<Pair<SourceMeta, String>> {
        val jsonButtons = document.select(".ep-buttons a[data-episode]")
        ExtLog.d(TAG, "jsonButtons=${jsonButtons.size}")
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
        ExtLog.d(TAG, "table fallback: section=${section != null} rows=${rows.size}")
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

    private fun langAbbrev(full: String): String = when {
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

    private fun buildLangLabel(audio: String, subs: String): String {
        val parts = mutableListOf<String>()
        if (audio.isNotBlank()) parts.add("\uD83C\uDF99\uFE0F ${langAbbrev(audio)}")
        if (subs.isNotBlank() && !subs.contains("--")) parts.add("\uD83D\uDCDD ${langAbbrev(subs)}")
        return if (parts.isNotEmpty()) " ${parts.joinToString(" \u00B7 ")}" else ""
    }

    private fun buildPrefix(audio: String, subs: String): String {
        val parts = mutableListOf<String>()
        if (audio.contains("Polski", ignoreCase = true)) parts.add("PL")
        if (subs.isNotBlank() && !subs.contains("--")) parts.add(subs)
        val label = parts.joinToString(" - ")
        return if (label.isNotBlank()) "[$label] " else ""
    }

    private fun debugVideo(message: String): Video = Video("about:blank", "DEBUG: $message".take(300), "about:blank")

    private fun createPerCallClient(): OkHttpClient = network.client.newBuilder()
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

    private fun resolveEmbedUrl(loadUrl: String): String? = runCatching {
        ExtLog.d(TAG, "resolveEmbedUrl START loadUrl=$loadUrl")

        val perCallClient = network.client.newBuilder()
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
        ExtLog.d(TAG, "player_load http=${r1.code}")
        r1.close()
        Thread.sleep(2000)

        val showUrl = loadUrl.replace("player_load", "player_show") + "&width=0&height=-1"
        val r2 = perCallClient.newCall(GET(showUrl, headers)).execute()
        ExtLog.d(TAG, "player_show http=${r2.code}")
        val doc = r2.asJsoup()

        val raw = doc.selectFirst("iframe[src]")?.attr("src")
            ?: doc.selectFirst("a[href]")?.attr("href")
        ExtLog.d(TAG, "raw=$raw")
        if (raw == null) return@runCatching null

        val resolved = if (raw.startsWith("//")) "https:$raw" else raw
        ExtLog.d(TAG, "resolveEmbedUrl RESOLVED: $resolved")
        resolved
    }.getOrElse {
        ExtLog.e(TAG, "resolveEmbedUrl EXCEPTION: ${it.message}", it)
        null
    }

    // ================================ Video display mode filter ======================

    private fun filterVideosByPreference(vids: List<Video>): List<Video> {
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
        val filtered = if (prefInt != null) {
            val exactMatch = vids.filter { matchesPref(it.quality) }
            if (exactMatch.isNotEmpty()) {
                exactMatch
            } else {
                val lower = vids.filter { numericP(it.quality) in 1 until prefInt }
                    .sortedByDescending { numericP(it.quality) }
                if (lower.isNotEmpty()) lower else vids
            }
        } else {
            vids
        }

        if (filtered.size <= 1) return filtered

        val auto = filtered.filter { it.quality.contains("auto", ignoreCase = true) }
        val numeric = filtered.filter { numericP(it.quality) > 0 }
        val maxP = numeric.maxByOrNull { numericP(it.quality) }

        return when (mode) {
            "highest_only" -> {
                if (maxP != null) listOf(maxP) else filtered
            }

            "auto_highest" -> {
                val r = mutableListOf<Video>()
                if (maxP != null) r.add(maxP)
                r.addAll(auto.filter { it.quality != maxP?.quality })
                r
            }

            else -> filtered
        }
    }
    // ================================ Anime List =====================================

    private fun parseAnimeListApiEntries(response: Response): List<SAnime> {
        val body = response.body?.string() ?: return emptyList()
        return try {
            val json = JSONObject(body)
            val items = json.getJSONObject("result").getJSONArray("items")

            // Server-side filtering via API path — iterate items directly
            val entries = (0 until items.length()).mapNotNull { i ->
                val item = items.getJSONObject(i)
                val titleId = item.optInt("titleId", 0)
                val title = item.optString("title", "")
                if (titleId == 0 || title.isBlank()) return@mapNotNull null

                SAnime.create().apply {
                    this.title = cleanTitle(title)
                    setUrlWithoutDomain("/series/$titleId")
                    val coverId = item.optInt("coverId", 0)
                    thumbnail_url = if (coverId > 0) {
                        "https://shinden.pl/res/images/genuine/$coverId.jpg"
                    } else {
                        null
                    }
                    val extraParts = listOfNotNull(
                        item.optString("animeType", null)?.let { "Typ: $it" },
                        item.optString("titleStatus", null)?.let { "Status: $it" },
                        item.optInt("episodes", 0).takeIf { it > 0 }?.let { "Odcinki: $it" },
                        item.optString("watchStatus", null)?.let { "Postęp: $it" },
                    )
                    genre = extraParts.joinToString(" | ").ifBlank { null }
                }
            }

            ExtLog.d(TAG, "my anime: ${entries.size} items (filtered from ${items.length()})")
            entries
        } catch (e: Exception) {
            ExtLog.e(TAG, "my anime parse error: ${e.message}", e)
            emptyList()
        }
    }

    private fun applyMyAnimeStandardFilters(items: List<SAnime>): List<SAnime> {
        var result = items

        myAnimeSearchQuery?.let { query ->
            val lower = query.lowercase()
            result = result.filter { anime ->
                anime.title.lowercase().contains(lower)
            }
        }

        myAnimeActiveTypeFilter?.let { type ->
            result = result.filter { anime ->
                anime.genre?.contains("Typ: $type") == true
            }
        }

        myAnimeActiveTitleStatusFilter?.let { status ->
            result = result.filter { anime ->
                anime.genre?.contains("Status: $status") == true
            }
        }

        myAnimeActiveLetterFilter?.let { letter ->
            result = result.filter { anime ->
                val first = anime.title.firstOrNull()?.uppercase() ?: ""
                if (letter == "0-9") {
                    first.matches(Regex("[0-9]"))
                } else {
                    first == letter
                }
            }
        }

        myAnimeActiveEpisodeFilter?.let { range ->
            result = result.filter { anime ->
                val epText = anime.genre?.let { g ->
                    Regex("Odcinki: (\\d+)").find(g)?.groupValues?.get(1)?.toIntOrNull()
                } ?: 0
                when (range) {
                    "only_1" -> epText == 1
                    "2_to_14" -> epText in 2..14
                    "15_to_28" -> epText in 15..28
                    "29_to_100" -> epText in 29..100
                    "over_100" -> epText > 100
                    else -> true
                }
            }
        }

        when (myAnimeActiveSortFilter) {
            1 -> result = result.sortedBy { it.title.lowercase() }
            2 -> result = result.sortedByDescending { it.title.lowercase() }
        }

        return result
    }

    // ================================ Sort =========================================

    override fun List<Video>.sort(): List<Video> {
        val (empty, nonEmpty) = partition { it.url.startsWith("about:blank") }
        return nonEmpty.sortInternal() + empty
    }

    private fun List<Video>.sortInternal(): List<Video> {
        val serversStr = preferences.getString("preferred_servers_list", "") ?: ""
        val servers = serversStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val prefQuality = preferences.getString("preferred_quality", "1080") ?: "1080"
        val prefInt = prefQuality.toIntOrNull()
        val prefIsAuto = prefQuality.equals("auto", ignoreCase = true)

        val embedRegex = Regex("\\(([^)]+)\\)")
        val groupMap = mutableMapOf<String, MutableList<Video>>()

        for (video in this) {
            val embedHost = embedRegex.find(video.quality)?.groupValues?.get(1) ?: video.quality
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
        for (video in this) {
            val embedHost = embedRegex.find(video.quality)?.groupValues?.get(1) ?: video.quality
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

        fun intraSort(videos: List<Video>): List<Video> = videos.sortedWith(compareBy<Video> { v ->
            val q = v.quality
            when {
                exactPref(q) -> 0
                closestDist(q) < Int.MAX_VALUE -> 10000 + closestDist(q)
                isAuto(q) -> 20000
                else -> 30000
            }
        }.thenByDescending { v ->
            if (isAuto(v.quality)) -1 else numericP(v.quality)
        })

        if (servers.isEmpty()) {
            return groupOrder
                .sortedBy { host ->
                    val vids = groupMap.getValue(host)
                    if (vids.any { exactPref(it.quality) }) 0
                    else if (prefInt != null && !prefIsAuto) 10000 + (vids.minOfOrNull { closestDist(it.quality) } ?: Int.MAX_VALUE)
                    else 20000
                }
                .flatMap { intraSort(groupMap.getValue(it)) }
        }

        return groupOrder
            .sortedBy { getPriority(it) }
            .flatMap { intraSort(groupMap[it].orEmpty()) }
    }

    // ================================ Preferences =================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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
            summary = preferences.getString("skip_domains_list", "hqq.tv,vk.com,lulu")?.replace(",", ", ")
                ?.ifBlank { "Kliknij aby edytować" } ?: "Kliknij aby edytować"
            setOnPreferenceClickListener { pref ->
                ShindenListEditor.open(
                    context = screen.context,
                    title = "Pomiń domeny",
                    currentItems = preferences.getString("skip_domains_list", "hqq.tv,vk.com,lulu") ?: "",
                    allowReorder = false,
                    defaultSuggestions = listOf("hqq.tv", "vk.com", "luluvid.com"),
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

        SwitchPreferenceCompat(screen.context).apply {
            key = "hide_no_subs"
            title = " Ukryj źródła bez napisów"
            summary = "Źródła bez napisów nie będą wyświetlane"
            setDefaultValue(false)
        }.let(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = "allowed_audio_langs"
            title = "Dozwolone języki dźwięku"
            summary = "Źródła z innym językiem dźwięku zostaną pominięte"
            entries = arrayOf("Japoński", "Polski", "Angielski", "Niemiecki", "Hiszpański", "Francuski", "Włoski", "Koreański")
            entryValues = arrayOf("Japoński", "Polski", "Angielski", "Niemiecki", "Hiszpański", "Francuski", "Włoski", "Koreański")
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
}

private class PersistentCookieJar(
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
