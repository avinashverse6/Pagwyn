# Pagwyn Architecture

This document explains how Pagwyn is put together and, more importantly, *why*
each decision was made. Every choice is judged against one rule:

> Performance first. Rendering quality second. Reading experience third.
> Feature count last.

---

## 1. Layers

```
┌─────────────────────────────────────────────────────────────────────┐
│ UI (Activities, custom views, bottom sheets)                        │
│   HomeActivity · ReaderActivity · MetadataActivity                  │
│   PageSlotView · ZoomableLayout · PageImageView · PagwynSheet       │
└───────────────┬─────────────────────────────────────────────────────┘
                │ one-way: user intent up, immutable state down
┌───────────────▼─────────────────────────────────────────────────────┐
│ ViewModels (AndroidViewModel, StateFlow)                            │
│   HomeViewModel · ReaderViewModel                                   │
└───────────────┬─────────────────────────────────────────────────────┘
                │
┌───────────────▼─────────────────────────────────────────────────────┐
│ Engine                                                              │
│   PageRenderer ── PdfDocument (android PdfRenderer)                 │
│        │            └── PdfTextService (Kotlin text extraction)      │
│        └── PageBitmapCache (byte-budgeted LRU)                      │
└───────────────┬─────────────────────────────────────────────────────┘
                │
┌───────────────▼─────────────────────────────────────────────────────┐
│ Storage                                                             │
│   DocumentStore (SQLite) · Settings (SharedPreferences)             │
└─────────────────────────────────────────────────────────────────────┘
```

There are no repositories, use-case classes, mappers or event buses. With roughly
6,000 lines of Kotlin, those layers would be indirection without benefit; the
ViewModel *is* the boundary between UI and engine.

## 2. Process-wide singletons

`PagwynApplication` exposes three lazily initialised objects:

| Object | Purpose | Why a singleton |
|---|---|---|
| `Settings` | SharedPreferences wrapper | Read on nearly every screen; cheap after first open |
| `DocumentStore` | SQLite document index | One helper instance, one connection |
| `PageBitmapCache` | Rendered page bitmaps | Survives reader restarts so reopening is instant |

No DI framework. Hilt would add ~200 KB and a KSP code-generation step plus roughly
100 ms of cold-start initialisation for three objects. Kotlin `by lazy` is enough.

## 3. The reader: one list, two modes

The reader uses a single `RecyclerView` for **both** continuous scrolling and
single-page mode.

- Continuous mode: ordinary `LinearLayoutManager` scrolling.
- Single-page mode: a `PagerSnapHelper` is attached to the same list.

Why this matters:

- Switching modes keeps the current page's zoom, scroll offset and cached bitmap.
- There is no second render pipeline to keep in sync (the obvious alternative —
  a `ViewPager2` for single page — doubles the code that talks to the renderer).
- Memory does not spike when toggling, which is exactly when low-RAM devices would
  otherwise be at risk.

Per-page zoom lives in `ZoomableLayout`, one instance per slot. Zoom is applied as a
canvas transform in `dispatchDraw`, so pinching never triggers `measure`/`layout`.

## 4. Rendering pipeline

```
RecyclerView scroll
      │
      ▼
ReaderActivity.updateVisibleWindow()
      │  visible pages in priority order (current, +1, -1, +2, -2 …)
      ▼
ReaderViewModel.requestRender(order, widthPx)
      │
      ▼
PageRenderer.setViewport()   (returns immediately if nothing changed)
      │  coroutine on the single render thread
      ├── phase 1: preview render  (≤720 px wide)  → page is legible at once
      ├── phase 2: sharp render    (display width) → final quality
      └── callback on main thread → PageSlotView.setBitmap()
```

Properties that matter:

1. **Only the viewport is rendered.** Page count does not affect cost.
2. **Generation counter.** Any viewport change increments a counter; in-flight work
   checks it between pages and abandons itself. A fling across 500 pages does not
   queue 500 renders.
3. **Single thread.** `PdfRenderer.openPage` is not thread safe. Confining the whole
   engine to one thread is faster than locking and makes native memory predictable.
4. **`try/finally` page handles.** Every `PdfRenderer.Page` is closed immediately
   after use; running out of page handles is a classic PDF-reader crash.
5. **Progressive quality.** The preview pass is roughly 10× cheaper than the sharp
   pass, so a huge document feels responsive immediately after opening.

## 5. Memory model

`PageBitmapCache`:

- Budget = `memoryClass * 1024 * 1024 / 6` (or `/8` on 256 MB+ classes).
- Stored in bytes, not entries — an A3 landscape page costs much more than a
  paperback page.
- `evictRemote(min, max)` after every viewport change keeps only nearby pages.
- Keys include the render width, so zooming produces a new entry rather than
  stretching an old bitmap.

`PageRenderer.reserveBitmap()` refuses allocations that would exceed half the budget
or 22 MB for a single bitmap, in which case the user keeps the preview image. The
reader degrades gracefully instead of crashing with `OutOfMemoryError`.

## 6. Text extraction and search

Android's `PdfRenderer` exposes no text API. Two options existed:

| Option | Cost | Verdict |
|---|---|---|
| Bundle a third-party engine (PdfBox-Android, MuPDF, pdfium) | +6–20 MB, extra native ABIs, extra startup cost | Rejected: contradicts the size and ARMv7 goals |
| Write a focused extractor | ~700 lines of Kotlin, no APK growth | **Chosen** |

`PdfParser` implements a deliberately narrow subset of the PDF format:

- linear object scan that does not trust the xref table (damaged files still work),
- object streams (`/ObjStm`),
- `FlateDecode`, `ASCIIHexDecode`, `ASCII85Decode` and PNG predictors,
- page tree traversal for correct page order,
- content-stream decoding for `Tj`, `TJ`, `'`, `"` with kerning-aware word breaks and
  Latin-1/WinAnsi text decoding.

Limits are explicit: at most 4,000 pages scanned, 24 MB decoded per stream, 200,000
characters per page. Anything the parser does not understand yields "no text" — never
a crash and never garbage pretending to be text.

**Known consequence:** documents using custom `/Differences` encodings can produce
approximate text. That is documented in [LIMITATIONS.md](LIMITATIONS.md) rather than
hidden.

The outline/table of contents is derived from the same text layer by detecting
short, numbered or capitalised lines near the top of each page. The platform does not
expose `/Outlines` to applications, so this is the best available approximation, and
it degrades to "no contents" for scanned documents instead of lying.

## 7. Threading contract

| Work | Thread |
|---|---|
| All `PdfRenderer` and parser calls | `pagwyn-render` (single, low priority) |
| SQLite | `Dispatchers.IO` |
| State updates, view updates, bitmap hand-off | main thread (`Handler`) |
| Bitmap recycling | render thread or main, guarded by `isRecycled` |

`ReaderViewModel.onCleared()` saves the reading position, detaches the renderer
listener and closes the renderer, which closes the `PdfRenderer` and the file
descriptor. `ReaderActivity.onPause()` also persists the position so a process kill
cannot lose it.

## 8. Storage Access Framework

Pagwyn never requests `READ_EXTERNAL_STORAGE`.

- Documents are chosen with `ACTION_OPEN_DOCUMENT`.
- `takePersistableUriPermission` is called so the entry in the library keeps working
  after a reboot.
- Sharing uses `FLAG_GRANT_READ_URI_PERMISSION`.
- If a provider revokes access, the document is reported as inaccessible instead of
  being silently removed from the library.

## 9. Persistence

SQLite (`pagwyn.db`) with two tables:

- `documents` — one row per opened document: display name, title, size, page count,
  last page, fractional progress, zoom, rotation, star flag, location, timestamps.
- `bookmarks` — `(uri, page)` primary key, cascade-free because it is tiny.

Progress is written on page change (not on every scroll frame) and on pause. History
is trimmed to the user's configured limit so the database cannot grow without bound.

`SharedPreferences` (`pagwyn_prefs`) holds appearance and reading defaults, and is the
only thing included in cloud backup — never the PDF files.

## 10. Why not Material Components

Material Components (buttons, `BottomSheetDialogFragment`, chips, sliders) would add
roughly 600 KB of resources and a dependency tree to the APK for interactions that
are simple to express with AppCompat themes, `PopupMenu` and a small dialog-based
sheet. Pagwyn's visual identity is custom anyway (paper/ink/amber), so the Material
theme was not doing the design work either.

The result is a consistent, deliberately quiet UI that starts fast and installs small.

## 11. Optional features

The brief lists highlights, annotations, TTS and OCR as optional. Their status:

| Feature | Status | Reason |
|---|---|---|
| Highlights / annotations | Not implemented | Requires writing PDFs (a full writer library, several MB, with real risk of corrupting documents) and a text-selection layer that the platform renderer does not provide |
| Text-to-speech | Not implemented | Playback needs a reliable text-position mapping; without it TTS reads a page from the start, which is a worse experience than the system's "select to speak" |
| OCR | Not implemented | Adds tens of megabytes of native model/engine and cannot run well on ARMv7 entry devices |
| Advanced metadata | Partial | File-level facts are shown; `/Outlines` and document info dictionaries are not exposed by the platform |

These are stated plainly rather than approximated with broken placeholders. Every
button that *is* present performs its advertised function.

## 12. Error handling philosophy

- `PdfOpenException` is a sealed class, so the UI must handle every case: password
  required, wrong password, unsupported encryption, corrupt file, missing file,
  too large.
- No generic "Something went wrong" when something specific is known.
- No fake retry buttons: a document that failed to parse will not parse on a second
  identical attempt, so the error panel offers **Open in another app** instead.
- Passwords: the platform renderer cannot accept a password programmatically, so
  Pagwyn asks the user, passes the attempt through, and states honestly when the
  platform refuses the document.
