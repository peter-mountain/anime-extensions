package eu.kanade.tachiyomi.animeextension.pl.shinden

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

class AnimeTypeFilter :
    AnimeFilter.Select<String>(
        "Typ",
        arrayOf(
            "— Wszystkie —",
            "TV",
            "ONA",
            "OVA",
            "Film",
            "Specjalny",
            "TV Short",
        ),
    )

class StatusFilter :
    AnimeFilter.Select<String>(
        "Status",
        arrayOf(
            "— Wszystkie —",
            "Emitowane",
            "Zakończone",
            "Zapowiedzi",
            "Nie wyemitowano",
        ),
    )

class GenreFilter :
    AnimeFilter.Group<GenreFilter.Genre>(
        "Gatunek",
        listOf(
            Genre("Akcja", "5"),
            Genre("Przygodowy", "6"),
            Genre("Komedia", "7"),
            Genre("Drama", "8"),
            Genre("Ecchi", "78"),
            Genre("Fantazja", "22"),
            Genre("Horror", "51"),
            Genre("Mecha", "98"),
            Genre("Muzyka", "136"),
            Genre("Romans", "38"),
            Genre("Sci-Fi", "549"),
            Genre("Codzienne życie", "42"),
            Genre("Sport", "31"),
            Genre("Nadprzyrodzone", "19"),
            Genre("Psychologiczne", "52"),
            Genre("Tajemnica", "5"),
            Genre("Sci-Fi (Space Opera)", "384"),
            Genre("Thriller", "52"),
        ),
    ) {
    class Genre(name: String, val id: String) : AnimeFilter.CheckBox(name)
}

class SortFilter :
    AnimeFilter.Select<String>(
        "Sortowanie",
        arrayOf(
            "Domyślne",
            "Tytuł A-Z",
            "Tytuł Z-A",
            "Ocena ↓",
            "Ocena ↑",
        ),
    )

class LetterFilter :
    AnimeFilter.Select<String>(
        "Litera",
        arrayOf(
            "— Wszystkie —",
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J",
            "K", "L", "M", "N", "O", "P", "R", "S", "T", "U",
            "W", "Y", "Z", "0-9",
        ),
    )

class EpisodeCountFilter :
    AnimeFilter.Select<String>(
        "Liczba odcinków",
        arrayOf(
            "— Wszystkie —",
            "Tylko 1",
            "2-14",
            "15-28",
            "29-100",
            "100+",
        ),
    )

class MyAnimeFilter : AnimeFilter.CheckBox("🎬 Moje anime", false)

class MyAnimeWatchStatusFilter :
    AnimeFilter.Group<MyAnimeWatchStatusFilter.WatchStatus>(
        "Postęp oglądania",
        listOf(
            WatchStatus("Oglądam", "in-progress"),
            WatchStatus("Obejrzane", "completed"),
            WatchStatus("Planuję", "plan"),
            WatchStatus("Wstrzymane", "hold"),
            WatchStatus("Porzucone", "dropped"),
            WatchStatus("Pomijam", "skip"),
        ),
    ) {
    class WatchStatus(name: String, val apiPath: String) : AnimeFilter.CheckBox(name, false)
}
