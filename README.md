# Onyx Shelf Launcher

**English** · [Русский](README.ru.md)

A minimalist, iOS‑inspired home screen and book library for **ONYX BOOX** e‑readers, designed around the slow refresh of E Ink: no scrolling, no animations, pages you flip with a swipe.

Built and tested on **BOOX Kon‑Tiki 5** (Android 11). It should work on other BOOX devices with Android 11+.

**[⬇ Download APK](https://github.com/ysagisan/onyxboox_custom_launcher-/raw/main/apk/onyx-shelf-launcher.apk)** · v1.15

<p align="center">
  <img src="docs/screenshots/home.png" width="32%" alt="Home screen">
  <img src="docs/screenshots/library.png" width="32%" alt="Library">
  <img src="docs/screenshots/dark.png" width="32%" alt="Dark theme">
</p>

## Features

**Home screen**
- *Now reading* with a large cover, progress, current page and last opened time — taken from the BOOX reading database, so it matches the stock readers.
- Configurable tiles (two rows of two): books finished and total reading time, library size, reading time today / this week, books finished this year, currently reading, *Want to read*, *Continue the series*.
- A shelf at the bottom: recent, continue the series, want to read, new arrivals — or nothing.
- One tap opens the ONYX reading statistics screen.

**Library**
- Finds books anywhere in storage: FB2, FB2.ZIP, EPUB, PDF, DJVU, MOBI, AZW3, CBZ, DOCX, TXT and more.
- Reads titles, authors, nested series with numbers, genres, annotations and covers from the files (and from Calibre's `metadata.calibre`).
- Seven views: large (3×2), normal (5×3), small (6×4), covers only, list, detailed list, compact list. Covers are always 2:3.
- Sorting by title, author, series, date added, last opened, progress, file size — ascending or descending.
- Filters by reading status, format and language.
- Sections: all books, authors, series, genres, recent, shelves. Series can be collapsed into stacks.
- Shelves: automatic (*Continue the series*, *Currently reading*, *New arrivals*, *Abandoned*, *Finished this year*) and your own.
- Search by title, author or series.

**Book card** (long press on any book)
- Annotation, series, genres, format, progress and how long you have been reading it.
- *Read*, *Open with…*, put on a shelf, change the cover, remove from recent.
- Remembers which app you last used for each format — no "Open with" prompt every time.

**Series and covers (optional, needs Wi‑Fi)**
- *Check series online* matches books against [Fantlab](https://fantlab.ru): fixes cycles and numbers, and shows which books of a series are **missing** from the device.
- Custom covers from the device or from the internet (all editions from Fantlab and Open Library).
- The network is used only when you press these buttons.

**Also**
- Apps grid, file browser, settings — all in the same style.
- Light and dark theme (covers are never inverted).
- Duplicate finder: hide extra copies of the same book (files are not deleted).
- Battery friendly: zero redraws and ~0% CPU while idle, no background work, no timers.

<p align="center">
  <img src="docs/screenshots/series.png" width="32%" alt="Series with missing books">
  <img src="docs/screenshots/details.png" width="32%" alt="Book card">
  <img src="docs/screenshots/filters.png" width="32%" alt="View and filters">
</p>
<p align="center">
  <img src="docs/screenshots/shelves.png" width="32%" alt="Shelves">
  <img src="docs/screenshots/apps.png" width="32%" alt="Apps">
  <img src="docs/screenshots/settings.png" width="32%" alt="Settings">
</p>

## Installation

1. **[Download onyx-shelf-launcher.apk](https://github.com/ysagisan/onyxboox_custom_launcher-/raw/main/apk/onyx-shelf-launcher.apk)** (v1.15, 3.3 MB) — a ready-to-install build, no compiling needed. It is also in the [`apk`](apk) folder.
2. Copy it to the device (USB cable, BooxDrop or any cloud) and open it from **Storage** to install. Allow installing from unknown sources if asked.
   Or with a computer: `adb install onyx-shelf-launcher.apk`
3. Open **Моя библиотека** (*My library*) and grant **All files access** when prompted — it is needed to find books in storage.

### Make it the home screen

The ONYX launcher has a fixed list of libraries, so a third‑party library cannot be added there. Instead this app replaces the whole home screen:

- **On the device:** Android settings → Apps → Default apps → **Home app** → *Моя библиотека*.
  (On some firmware you get to it from the app: Settings → *Выбрать главный экран*.)
- **With adb:** `adb shell cmd package set-home-activity ru.efimov.booklib/.MainActivity`

**To go back to ONYX:** Settings → *Выбрать главный экран* → *Оболочка ONYX*. ONYX features (store, cloud, E Ink settings) stay available via Settings → *Открыть лаунчер ONYX*.

> A BOOX firmware update may reset the home app to ONYX — just select it again.

## Usage

- **Swipe** left/up for the next page, right/down for the previous one. Tapping the arrows next to the page number works too.
- **Tap** a book to open it, **long press** for the book card.
- The first page of the library is the home screen; swipe once to get to all books.
- The title in the top bar (*All books ▾*) switches between all books, authors, series, genres, recent and shelves.
- The sliders button opens view, sorting and filters.
- New books appear after a restart or Settings → *Обновить библиотеку* (no background file watching, to save battery).

## Building from source

Requirements: JDK 17+, Android SDK (platform 35).

```bash
git clone https://github.com/ysagisan/onyxboox_custom_launcher-.git
cd onyxboox_custom_launcher-
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # path to your Android SDK
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key for sideloading.

## How it works

- Kotlin + Jetpack Compose, a single activity that also registers as a HOME activity.
- Reading progress and statistics are read from the BOOX content providers (`com.onyx.content.database.ContentProvider`, `com.onyx.kreader.statistics.provider`) with the same queries the ONYX statistics screen uses.
- Books are opened in the selected reader with a `file://` URI so stock readers keep their reading position.
- The interface is paged instead of scrolled: every list is cut into pages that exactly fit the screen.

## Credits

- Font: [Inter](https://rsms.me/inter/) by Rasmus Andersson, SIL Open Font License 1.1 (`licenses/Inter-OFL.txt`). San Francisco is not used: Apple's license forbids it outside Apple platforms.
- Series data and Russian edition covers: [Fantlab](https://fantlab.ru). Other covers: [Open Library](https://openlibrary.org).

Not affiliated with ONYX International. BOOX and ONYX are trademarks of their respective owners.
