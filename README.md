# Pagwyn

**Read Without Distraction.**

Pagwyn is a lightweight, offline-first PDF reader for Android. It opens instantly,
renders with the platform's own engine, remembers where you stopped reading, and
does nothing else. No ads. No accounts. No analytics. No internet permission.

<p align="center">
  <img src="docs/assets/icon-192.png" width="120" alt="Pagwyn icon">
</p>

---

## Contents

- [Why Pagwyn](#why-pagwyn)
- [Features](#features)
- [Screens](#screens)
- [Install](#install)
- [Build from source](#build-from-source)
- [Architecture](#architecture)
- [Performance design](#performance-design)
- [Privacy](#privacy)
- [Compatibility](#compatibility)
- [Testing](#testing)
- [Project layout](#project-layout)
- [Documentation](#documentation)
- [License](#license)

---

## Why Pagwyn

Most Android PDF readers are either heavy (tens of megabytes, tracking SDKs, cloud
accounts) or limited (no zoom, no search, no reading position). Pagwyn targets the
gap:

| Goal | How Pagwyn gets there |
|---|---|
| Instant startup | No DI framework, no analytics, no splash gate, one Activity graph |
| Fast opening | `PdfRenderer` opens from a file descriptor; pages render on first need |
| Low RAM | Viewport-driven rendering with a byte-budgeted LRU cache |
| Small APK | No native libraries, no Material Components, no image loading library |
| ARMv7 support | Pure Kotlin/Java: the platform renderer already ships every ABI |
| Offline | No `INTERNET` permission is declared, so it cannot phone home |

Pagwyn deliberately does **not** include: highlights/annotations, OCR, text-to-speech,
cloud sync, or a document editor. Optional advanced features are described in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#optional-features) along with the
reason each was left out of the core build.

## Features

### Reading

- Continuous vertical scrolling and single-page mode
- Fit-to-width, fit-to-page and custom zoom (pinch, double-tap, buttons)
- Smooth panning while zoomed, with per-page zoom state
- Automatic control hiding; tap to bring the chrome back
- Full-screen reading mode
- Light, sepia and dark reading themes that never tint the page itself
- Adjustable page spacing and a dimming overlay for night reading
- Portrait and landscape, with page rotation
- Optional keep-screen-on
- Automatic restoration of the last reading position, zoom and rotation

### Library

- Recently opened documents with real progress bars
- "Continue reading" card for the last document
- Starred documents
- Reading history with positions
- Document search by title
- Document information: name, size, page count, page size, encryption state,
  location and reading progress

### Document tools

- Full-text search with snippets and jump-to-result
- Table of contents derived from the document's own text layer
- Page bookmarks
- Page thumbnails
- Copy page text to the clipboard
- Native Android sharing
- Opens PDFs from other apps (`VIEW` and `SEND` intents)
- Password prompt for encrypted documents the platform can open

## Screens

| Home | Reader | Contents | Search |
|---|---|---|---|
| Library with continue card | Full-bleed page, minimal chrome | Text-derived outline | Live full-text results |

The interface is intentionally quiet: warm paper surfaces in light mode, ink navy in
dark mode, and a single amber accent that marks progress, bookmarks and the active
action.

## Install

1. Download `pagwyn-release.apk` from the [latest release](../../releases/latest).
2. Allow installation from unknown sources for your file manager or browser.
3. Open the APK and install.
4. Open any PDF — or use **Browse files** inside Pagwyn.

The APK is a universal build: 32-bit ARMv7 (`armeabi-v7a`), 64-bit ARM
(`arm64-v8a`) and x86/x86_64 emulators all install the same file.

Minimum Android version: **7.0 (API 24)**.

## Build from source

Requirements: JDK 17, Android SDK with platform 35 and build-tools 35.0.0.

```bash
git clone https://github.com/avinashverse6/Pagwyn.git
cd Pagwyn
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew assembleRelease      # app/build/outputs/apk/release/
./gradlew testDebugUnitTest    # unit tests
```

Command-line installation:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Full instructions, including signing your own release build and the exact tool
versions used by CI, are in [docs/BUILDING.md](docs/BUILDING.md).

## Architecture

Pagwyn uses a deliberately small MVVM stack:

```
HomeActivity ── HomeViewModel ── DocumentStore (SQLite)
     │
     └─ ReaderActivity ── ReaderViewModel ── PageRenderer ── PdfDocument (PdfRenderer)
                                              │                 └─ PdfTextService
                                              └─ PageBitmapCache
```

Key decisions:

- **No dependency injection framework.** `PagwynApplication` holds three lazily
  created singletons. Hilt/Dagger would add code size and startup cost for no
  benefit at this scale.
- **No Material Components.** Buttons, sheets and controls are built from
  AppCompat themes and small custom views, saving roughly 600 KB of resources.
- **One RecyclerView for both reading modes.** Single-page mode adds a snap helper;
  it does not swap view hierarchies.
- **The platform PDF renderer.** `android.graphics.pdf.PdfRenderer` is used for
  rendering because it is fast, memory-efficient and already present on every
  device. Text extraction, which the platform does not expose, is implemented in
  Kotlin.

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Performance design

The rendering pipeline is the core of the app.

1. **Viewport-driven.** The reader reports the visible page window to
   `PageRenderer`. Pages outside it are never rendered, so a 3000-page document
   costs the same as a 30-page one.
2. **Prioritised.** The current page is rendered first, then its neighbours, then
   the rest of the visible window.
3. **Progressive.** A cheap preview (~720 px wide) is painted immediately, then a
   sharp pass at the real display width follows.
4. **Byte-budgeted cache.** `PageBitmapCache` is sized from the device memory class
   (heap/6 for entry-level devices, heap/8 for 256 MB+ classes) and evicts pages by
   distance from the viewport.
5. **Zoom-aware.** Cache keys include the render width, so pinching triggers a
   re-render at the new resolution instead of upscaling a blurry bitmap.
6. **Single render thread.** `PdfRenderer` is not thread safe; one dedicated
   low-priority thread keeps native allocations predictable.
7. **Cancellable.** Stale viewport requests are discarded before doing native work.

Measured results from CI are published as a commit comment on every build and as
release notes. See [docs/PERFORMANCE.md](docs/PERFORMANCE.md) for the methodology
and the honesty notes about what has and has not been measured.

## Privacy

- No `INTERNET` permission in the manifest, so Pagwyn physically cannot send data.
- No analytics, crash reporting, advertising or telemetry code.
- No accounts, no cloud, no subscriptions.
- Documents are opened through the Storage Access Framework; no storage permission
  is requested and Pagwyn only sees files you explicitly pick.
- Reading history and bookmarks live in a local SQLite database and a private
  preferences file. PDF files are never copied.

See [docs/PRIVACY.md](docs/PRIVACY.md) and [docs/PERMISSIONS.md](docs/PERMISSIONS.md).

## Compatibility

- **Android 7.0 (API 24)** through **Android 15 (API 35)**
- **ARMv7 (`armeabi-v7a`)** and **ARM64 (`arm64-v8a`)**, plus x86/x86_64 emulators
- Verified by CI: the release workflow prints the ABI table, `minSdk`, permission
  list and signature of the produced APK.

Because Pagwyn ships no native code, there is no per-ABI APK to choose: the same
universal APK runs everywhere. This is also why ARMv7 support cannot silently
regress through a native dependency update.

## Testing

```bash
./gradlew testDebugUnitTest   # 40+ JVM tests
./gradlew connectedDebugAndroidTest   # instrumented tests (device/emulator)
```

- The PDF text engine is tested against **real PDF byte streams generated inside
  the tests** (`TestPdfs`), covering Flate compression, uncompressed streams,
  multi-page ordering, `TJ` kerning arrays, escaped parentheses and scanned pages
  with no text layer.
- Cache and model tests run under Robolectric.
- Instrumented tests cover storage access, reader launch and settings persistence.

See [docs/TESTING.md](docs/TESTING.md) for the full matrix and what is verified
versus what requires a physical device.

## Project layout

```
app/src/main/java/io/pagwyn/reader/
├── PagwynApplication.kt      process-wide singletons
├── core/                     formatters, intents, URI resolution, document model
├── data/                     SQLite document store, preferences
├── pdf/                      PdfRenderer wrapper + PDF text extraction
├── render/                   render thread, bitmap cache
└── ui/
    ├── home/                 library screen, adapters, settings sheet
    ├── reader/               reading surface, controller, sheets, widgets
    ├── metadata/             document information
    └── widget/               zoom container, page view, sheet base
```

## Documentation

| Document | Contents |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layers, data flow, threading, rendering pipeline, trade-offs |
| [docs/BUILDING.md](docs/BUILDING.md) | Toolchain, builds, signing, reproducible builds |
| [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md) | Every dependency and why it is there |
| [docs/PRIVACY.md](docs/PRIVACY.md) | What data exists and where it lives |
| [docs/PERMISSIONS.md](docs/PERMISSIONS.md) | Declared permissions (and the ones deliberately absent) |
| [docs/PERFORMANCE.md](docs/PERFORMANCE.md) | Targets, measurements and methodology |
| [docs/TESTING.md](docs/TESTING.md) | Test strategy, matrix and results |
| [docs/LIMITATIONS.md](docs/LIMITATIONS.md) | What Pagwyn intentionally does not do |

## License

Pagwyn is free software, licensed under the **GNU General Public License v3.0**.
See [LICENSE](LICENSE).

Bundled and referenced open-source work is listed in
[docs/DEPENDENCIES.md](docs/DEPENDENCIES.md).
