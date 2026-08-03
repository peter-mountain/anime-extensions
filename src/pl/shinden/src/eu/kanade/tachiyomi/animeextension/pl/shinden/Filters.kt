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
            "Muzyczne",
        ),
    )

class StatusFilter :
    AnimeFilter.Select<String>(
        "Status",
        arrayOf(
            "— Wszystkie —",
            "Emitowane",
            "Zakończone",
            "Zapowiedź",
            "Deklaracja",
        ),
    )

class GenreFilter :
    AnimeFilter.Group<GenreFilter.Genre>(
        "Gatunki",
        listOf(
            Genre("Akcja", "5"), Genre("Cyberpunk", "106"), Genre("Dramat", "8"),
            Genre("Ecchi", "78"), Genre("Eksperymentalne", "1741"), Genre("Fantasy", "22"),
            Genre("Harem", "130"), Genre("Hentai", "234"), Genre("Historyczne", "92"),
            Genre("Horror", "51"), Genre("Komedia", "7"), Genre("Kryminalne", "20"),
            Genre("Magia", "18"), Genre("Mecha", "98"), Genre("Męski harem", "263"),
            Genre("Muzyczne", "136"), Genre("Nadprzyrodzone", "19"), Genre("Obłęd", "97"),
            Genre("Okruchy życia", "42"), Genre("Parodia", "165"), Genre("Przygodowe", "6"),
            Genre("Psychologiczne", "52"), Genre("Romans", "2672"), Genre("Romans (do rozdzielenia)", "38"),
            Genre("Sci-Fi", "549"), Genre("Shoujo-ai", "167"), Genre("Shounen-ai", "207"),
            Genre("Space opera", "384"), Genre("Sportowe", "31"), Genre("Steampunk", "1734"),
            Genre("Szkolne", "65"), Genre("Sztuki walki", "57"), Genre("Tajemnica", "12"),
            Genre("Thriller", "53"), Genre("Wojskowe", "93"), Genre("Yaoi", "364"),
            Genre("Yuri", "380"),
        ),
    ) {
    class Genre(name: String, val id: String) : AnimeFilter.CheckBox(name)
}

class TargetGroupFilter :
    AnimeFilter.Group<TargetGroupFilter.TargetGroup>(
        "Grupa docelowa",
        listOf(
            TargetGroup("Dla dzieci", "218"),
            TargetGroup("Josei", "39"),
            TargetGroup("Seinen", "48"),
            TargetGroup("Shoujo", "128"),
            TargetGroup("Shounen", "23"),
        ),
    ) {
    class TargetGroup(name: String, val id: String) : AnimeFilter.CheckBox(name)
}

class EntityFilter :
    AnimeFilter.Group<EntityFilter.Entity>(
        "Rodzaje postaci",
        listOf(
            Entity("Aktorzy", "2329"), Entity("Albinos", "2656"), Entity("Androidy", "1758"),
            Entity("Anioły", "1055"), Entity("Artyści", "1779"), Entity("Arystokracja", "1781"),
            Entity("Bifauxnen", "2827"), Entity("Bishoujo", "576"), Entity("Bishounen", "1723"),
            Entity("Bliźniaki", "2957"), Entity("Bohater", "3087"), Entity("Bóstwa", "1805"),
            Entity("CGDCT", "2950"), Entity("Chibi", "569"), Entity("Chuunibyou", "1842"),
            Entity("Cyborgi", "1726"), Entity("Czarodzieje", "1922"), Entity("Dandere/Kuudere", "1783"),
            Entity("Delikwenci", "2347"), Entity("Demony", "104"), Entity("Dere-Dere", "2174"),
            Entity("Detektywi", "256"), Entity("Doktor", "2217"), Entity("Dorośli", "1760"),
            Entity("Duchy", "1731"), Entity("Dzieci", "1737"), Entity("Egzorcyści", "1804"),
            Entity("Elfy", "1762"), Entity("Femboy", "3079"), Entity("Furry", "2860"),
            Entity("Futanari", "2116"), Entity("GAR", "1797"), Entity("Geniusz", "2755"),
            Entity("Genki", "2213"), Entity("Gracze", "2325"), Entity("Gyaru", "1807"),
            Entity("Heterochromia", "2681"), Entity("Hikikomori", "2308"), Entity("Hybryda", "2146"),
            Entity("Idole", "352"), Entity("Imouto", "1787"), Entity("Insekty", "2356"),
            Entity("Kapłani", "1780"), Entity("Kelner / Kelnerka", "2935"), Entity("Kemonomimi", "1742"),
            Entity("Kitsune", "2373"), Entity("Kosmici", "421"), Entity("Koty", "594"),
            Entity("Kowal", "3052"), Entity("Krasnoludy", "2864"), Entity("Lokaje", "1232"),
            Entity("Loli", "296"), Entity("Magiczne", "1800"), Entity("Mahou shoujo", "173"),
            Entity("Mayadere", "2005"), Entity("Meganekko", "2214"), Entity("Mentor", "3099"),
            Entity("Moe", "519"), Entity("Mordercy", "1902"), Entity("Mówiące zwierzęta", "1905"),
            Entity("Młodzi chłopcy", "2990"), Entity("Młodzież", "2226"), Entity("Najemnicy", "1916"),
            Entity("Nauczyciele", "1820"), Entity("NEET", "2190"), Entity("Nekomata", "2650"),
            Entity("Niepełnosprawni", "2831"), Entity("Niewolnicy", "2180"), Entity("Ninja", "59"),
            Entity("Ochroniarz", "2338"), Entity("Okularnik", "2853"), Entity("OP postać", "2264"),
            Entity("Otaku", "260"), Entity("Otouto", "2306"), Entity("Ożywiony przedmiot", "3024"),
            Entity("Piraci", "62"), Entity("Pokojówki", "1747"), Entity("Policjanci", "2222"),
            Entity("Potwory", "1727"), Entity("Pracownicy biurowi", "1782"), Entity("Przestępcy", "1778"),
            Entity("Roboty", "1733"), Entity("Rozdwojenie jaźni", "2874"), Entity("Rycerze", "1923"),
            Entity("Rzemieślnik", "3047"), Entity("Samuraje", "108"), Entity("Shinigami", "269"),
            Entity("Sieroty", "2364"), Entity("Slime", "2751"), Entity("Smoki", "1725"),
            Entity("Strażacy", "3035"), Entity("Studenci", "1875"), Entity("Sukkuby", "2839"),
            Entity("Superbohaterzy", "2369"), Entity("Syreny", "496"), Entity("Szkielet", "2949"),
            Entity("Szpiedzy", "2181"), Entity("Tengu", "2626"), Entity("Transwestyta", "2254"),
            Entity("Tsundere", "1759"), Entity("Uczniowie", "1819"), Entity("Villainess", "2977"),
            Entity("Wampiry", "83"), Entity("Wiedźmy", "1728"), Entity("Wilkołaki", "2044"),
            Entity("Wróżki", "387"), Entity("Władca Demonów", "2383"), Entity("Yandere/Yangire", "1755"),
            Entity("Youkai", "1744"), Entity("Zamiana płci", "956"), Entity("Zombie", "1075"),
            Entity("Żołnierze", "2157"), Entity("Zwierzęta", "2632"), Entity("Łowcy nagród", "1761"),
        ),
    ) {
    class Entity(name: String, val id: String) : AnimeFilter.CheckBox(name)
}

class PlaceFilter :
    AnimeFilter.Group<PlaceFilter.Place>(
        "Miejsca i czas",
        listOf(
            Place("Alternatywna Ziemia", "2328"), Place("Ameryka Północna", "1789"),
            Place("Biuro", "2844"), Place("Budynek mieszkalny", "2336"),
            Place("Chiny", "1949"), Place("Dungeon", "2663"),
            Place("Dystopia", "2348"), Place("Europa", "1745"),
            Place("Feudalna Japonia", "1730"), Place("Jak feudalna wschodnia Azja", "3048"),
            Place("Jak gra", "2322"), Place("Jak średniowiecze", "2362"),
            Place("Japonia", "1740"), Place("kawiarnia/restauracja/bar/sklep", "2341"),
            Place("Korea Południowa", "3051"), Place("Kosmos", "10"),
            Place("Miasto", "1785"), Place("Ocean", "2363"),
            Place("Omegaverse", "2875"), Place("Podróż", "1788"),
            Place("Postapokaliptyczne", "470"), Place("Przyszłość", "2326"),
            Place("Pustynia", "2988"), Place("Świat alternatywny", "2327"),
            Place("Szkoła dla chłopców", "2333"), Place("Szkoła dla dziewcząt", "2332"),
            Place("W grze / VR", "1729"), Place("Wielka Brytania", "2858"),
            Place("Wieś", "1784"), Place("Współczesność", "1739"),
            Place("Wyspy", "2357"),
        ),
    ) {
    class Place(name: String, val id: String) : AnimeFilter.CheckBox(name)
}

class MiscTagFilter :
    AnimeFilter.Group<MiscTagFilter.MiscTag>(
        "Pozostałe tagi",
        listOf(
            MiscTag("Alchemia", "450"), MiscTag("Amnezja", "1901"), MiscTag("Bejsbol", "506"),
            MiscTag("Boks", "67"), MiscTag("Broń biała", "2865"), MiscTag("Broń palna", "2155"),
            MiscTag("Buddyzm", "2361"), MiscTag("Choroba", "2355"), MiscTag("Crossdressing", "2346"),
            MiscTag("Death Game", "1933"), MiscTag("Dzielenie ciała", "2339"), MiscTag("Edukacyjne", "558"),
            MiscTag("Ekonomia", "1763"), MiscTag("Eksperymenty na ludziach", "2354"),
            MiscTag("Fantastyka współczesna", "2345"), MiscTag("Fotografia", "2862"),
            MiscTag("Gildie", "2351"), MiscTag("Gimnastyka", "2714"), MiscTag("Golf", "2934"),
            MiscTag("Gore", "2050"), MiscTag("Gra o wysoką stawkę", "2377"), MiscTag("Gry karciane", "1904"),
            MiscTag("Hazard", "2350"), MiscTag("Isekai", "2376"), MiscTag("Iyashikei", "2358"),
            MiscTag("Kanibalizm", "2739"), MiscTag("Kazirodztwo", "383"), MiscTag("Kendo", "554"),
            MiscTag("Klub szkolny", "2765"), MiscTag("Kolarstwo", "1947"), MiscTag("Kontrakt małżeński", "2978"),
            MiscTag("Koszykówka", "225"), MiscTag("Kulinaria", "1803"), MiscTag("Kultywacja", "3045"),
            MiscTag("Lotnictwo", "1749"), MiscTag("Mafia", "513"), MiscTag("Mahjong", "357"),
            MiscTag("Manipulacja czasem i przestrzenią", "1840"),
            MiscTag("Mitologia chrześcijańska", "2360"), MiscTag("Mitologia japońska", "2359"),
            MiscTag("O grach", "2324"), MiscTag("Opieka nad dzieckiem", "2342"),
            MiscTag("Pan i Sługa", "2770"), MiscTag("Panty shots", "2365"),
            MiscTag("Piłka nożna", "32"), MiscTag("Pociągi", "2370"),
            MiscTag("Podróże w czasie", "2731"), MiscTag("Polityka", "2826"),
            MiscTag("Przemoc", "1736"), MiscTag("Reinkarnacja", "2367"),
            MiscTag("Rolnictwo", "2331"), MiscTag("Samochody", "47"),
            MiscTag("Samorząd uczniowski", "2411"), MiscTag("Seks", "1786"),
            MiscTag("Shogi", "2824"), MiscTag("Siatkówka", "2216"),
            MiscTag("Spisek", "2344"), MiscTag("Strój bojowy", "3026"),
            MiscTag("Strzelaniny", "2352"), MiscTag("Supermoce", "58"),
            MiscTag("Survival", "3100"), MiscTag("Taniec", "2318"),
            MiscTag("Tatuaże", "2750"), MiscTag("Tenis", "66"),
            MiscTag("Trójkąt miłosny", "1743"), MiscTag("Walka wręcz", "2353"),
            MiscTag("Wątek romantyczny", "2674"), MiscTag("Wojna", "1962"),
            MiscTag("Wykorzystywanie seksualne", "2368"), MiscTag("Wyraźny seks", "2349"),
            MiscTag("Wyścigi Samochodowe", "1903"), MiscTag("Yakuza", "1089"),
            MiscTag("Zaaranżowany związek", "2337"), MiscTag("Zamiana ciałami", "1732"),
            MiscTag("Zemsta", "2145"), MiscTag("Znęcanie nad zwierzętami", "2334"),
            MiscTag("Znęcanie się", "2340"), MiscTag("Życie pośmiertne", "2330"),
            MiscTag("Łucznictwo", "2335"), MiscTag("Łyżwiarstwo", "2153"),
        ),
    ) {
    class MiscTag(name: String, val id: String) : AnimeFilter.CheckBox(name)
}

class ProductionTypeFilter :
    AnimeFilter.Group<ProductionTypeFilter.ProductionType>(
        "Typy produkcji",
        listOf(
            ProductionType("Animacja 2,5D", "2658"), ProductionType("Animacja 3D", "2617"),
            ProductionType("Animacja chińska", "2343"), ProductionType("Animacja Koreańska", "2634"),
            ProductionType("Antologia", "2747"), ProductionType("Brak dialogów", "2743"),
            ProductionType("Chińsko-japońska koprodukcja", "2604"),
            ProductionType("Czarno-Białe", "2819"), ProductionType("Doujinshi", "1178"),
            ProductionType("Elementy Live-Action", "2660"), ProductionType("Epizodyczne", "2646"),
            ProductionType("Film niezależny", "3036"), ProductionType("Picture Drama", "2683"),
            ProductionType("Pionowe Anime", "2637"), ProductionType("Reklama", "2753"),
            ProductionType("W kolorze", "2418"), ProductionType("Webnovel", "2878"),
            ProductionType("Webtoon", "2877"), ProductionType("Wydane w papierze", "2879"),
            ProductionType("Wydane w Polsce", "2665"), ProductionType("Yonkoma", "1884"),
            ProductionType("Young Animator Training Project", "2644"),
        ),
    ) {
    class ProductionType(name: String, val id: String) : AnimeFilter.CheckBox(name)
}

class SourceFilter :
    AnimeFilter.Group<SourceFilter.Source>(
        "Pierwowzór",
        listOf(
            Source("Anime", "2314"), Source("Doujin", "3095"),
            Source("Gra komputerowa", "193"), Source("Gry (inne)", "2323"),
            Source("Inne", "2410"), Source("Karcianka", "2016"),
            Source("Książka", "2029"), Source("Light novel", "1976"),
            Source("Manga", "1956"), Source("Manga 4-koma", "1996"),
            Source("Novel", "2127"), Source("Seria oryginalna", "1966"),
            Source("Visual novel", "1990"), Source("Web manga", "2025"),
            Source("Web novel", "2872"),
        ),
    ) {
    class Source(name: String, val id: String) : AnimeFilter.CheckBox(name)
}

class SortFilter :
    AnimeFilter.Select<String>(
        "Sortowanie",
        arrayOf(
            "Domyślne",
            "Tytuł A-Z",
            "Tytuł Z-A",
            "Typ ↓",
            "Multimedia ↓",
            "Status ↓",
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

class SeriesLengthFilter :
    AnimeFilter.Select<String>(
        "Długość odcinka",
        arrayOf(
            "— Wszystkie —",
            "< 7 min",
            "7-18 min",
            "19-27 min",
            "28-48 min",
            "> 48 min",
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

class YearFromFilter : AnimeFilter.Text("Od roku / daty", "")
class YearToFilter : AnimeFilter.Text("Do roku / daty", "")
class YearPrecisionFilter :
    AnimeFilter.Select<String>(
        "Precyzja daty",
        arrayOf(
            "RRRR",
            "RRRR-MM",
            "RRRR-MM-DD",
        ),
    )

class MyAnimeFilter : AnimeFilter.CheckBox("🎬 Moje anime", false)

class MyAnimeWatchStatusFilter :
    AnimeFilter.Group<MyAnimeWatchStatusFilter.WatchStatus>(
        "Postęp oglądania 🎬 Moje anime",
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
