package eu.kanade.tachiyomi.animeextension.pl.shinden

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.ExtLog
import okhttp3.Response
import org.json.JSONObject

internal fun Shinden.cleanTitle(raw: String): String {
    // Remove leading "Anime" when it's a single word prefix
    // Shinden prepends "Anime" to distinguish from manga/novels
    // The <h1> on details page has "Anime\n\nTitle" pattern
    val trimmed = raw.trim()
    val cleaned = trimmed.replace(Regex("""^Anime\s+""", RegexOption.IGNORE_CASE), "")
    return cleaned.ifBlank { trimmed }
}

internal fun Shinden.parseAnimeList(response: Response): AnimesPage {
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

internal fun Shinden.parseAnimeListApiEntries(response: Response): List<SAnime> {
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

        ExtLog.d(Shinden.TAG, "my anime: ${entries.size} items (filtered from ${items.length()})")
        entries
    } catch (e: Exception) {
        ExtLog.e(Shinden.TAG, "my anime parse error: ${e.message}", e)
        emptyList()
    }
}

internal fun Shinden.applyMyAnimeStandardFilters(items: List<SAnime>): List<SAnime> {
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
