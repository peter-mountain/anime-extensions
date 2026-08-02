<div align="center">

# 🇯🇵🇵🇱 Shinden Extension for Aniyomi

**Wtyczka do przeglądania i odtwarzania anime z [Shinden.pl](https://shinden.pl) w aplikacji [Aniyomi](https://github.com/aniyomiorg/aniyomi)**

[![Build](https://github.com/peter-mountain/anime-extensions/actions/workflows/build-shinden-release.yml/badge.svg)](https://github.com/peter-mountain/anime-extensions/actions)
[![License](https://img.shields.io/github/license/peter-mountain/anime-extensions)](LICENSE)

</div>

---

## O projekcie

Shinden to wtyczka do Aniyomi, która udostępnia pełny katalog anime z polskiego serwisu [Shinden.pl](https://shinden.pl) — przeglądanie, wyszukiwanie, odtwarzanie z wybranego źródła oraz integrację z Twoją listą anime na Shinden.

---

## ✨ Funkcje

- **Przeglądanie i wyszukiwanie** — katalog Shinden.pl, filtry (typ, status, gatunek, litera, liczba odcinków), sortowanie.
- **Moje anime** — integracja z listą użytkownika, filtrowanie po statusie (oglądam, obejrzane, planuję, itp.)
- **20+ ekstraktorów** — CDA, Google Drive, Mega.nz, Bysesukior, Filemoon, Luluvid, Uqload i inne
- **Logowanie** — do Shinden (lista anime, profil) oraz Google Drive (prywatne materiały)
- **Konfigurowalne ustawienia** — preferowane serwery, pomijane domeny, jakość wideo

---

## 📥 Wymagania

- Android 6.0+
- [Aniyomi](https://github.com/aniyomiorg/aniyomi)
  
> Instrukcja instalacji wtyczki zostanie dodana wraz z publikacją repozytorium dystrybucyjnego.

---

## ⚙️ Ustawienia

| Ustawienie | Opis |
|-----------|------|
| Konto Shinden | Logowanie / wylogowanie, podgląd profilu |
| Zaloguj do Google Drive | Logowanie Google — wymagane dla prywatnych materiałów |
| Preferowane serwery | Kolejność priorytetu serwerów wideo (przeciągnij aby zmienić) |
| Pomiń domeny | Pomijanie nie działających serwerów (domyślnie: hqq.tv, luluvid.com, vk.com, dailymotion) |
| Wyświetlanie źródeł wideo | Wszystkie / Auto + najwyższa / Tylko najwyższa |
| Preferowana jakość | Auto, 1080p, 720p, 480p, 360p 
| Szczegółowe logi | Logowanie diagnostyczne do `logcat` (domyślnie wyłączone) |
| Pokazuj puste źródła | Wyświetlaj serwery które zwróciły 0 wideo (domyślnie ukryte) |

### Raportowanie błędów

Jeśli problem nie zniknie, zbierz logi i otwórz [issue](https://github.com/peter-mountain/anime-extensions/issues):

1. Ustawienia → Szczegółowe logi → włącz
2. Odtwórz problem
3. Zbierz logcat: `adb logcat -s ShindenExt`
4. Dołącz log do issue

---

## 🔧 Budowanie z kodu źródłowego

```bash
git clone https://github.com/peter-mountain/anime-extensions.git
cd anime-extensions
./gradlew :src:pl:shinden:assembleDebug
```

APK pojawi się w `src/pl/shinden/build/outputs/apk/debug/`

---

## 📄 Licencja

Projekt jest na licencji **Apache License 2.0** (patrz [LICENSE](LICENSE)). Stanowi rozwidlenie [yuzono/anime-extensions](https://github.com/yuzono/anime-extensions).

## 🙏 Podziękowania

Struktura danych i przepływ informacji na stronie Shinden.pl został poznany dzięki projektom:

- [kosmateus/shinden4j](https://github.com/kosmateus/shinden4j) — klient API Shinden w Javie
- [Tsugumik/shinden-pl-api-rs](https://github.com/Tsugumik/shinden-pl-api-rs) — klient API Shinden w Rust

---

## ⚠️ Zastrzeżenie

- Ten projekt nie jest powiązany z Shinden.pl ani żadnymi dostawcami treści
- Ten projekt nie jest oficjalną wtyczką Aniyomi/Anikku
- Wszystkie znaki towarowe należą do ich właścicieli
