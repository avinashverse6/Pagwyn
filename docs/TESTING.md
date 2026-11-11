# Testing

## How to run

```bash
./gradlew testDebugUnitTest          # 40 JVM tests, no device needed
./gradlew connectedDebugAndroidTest  # instrumented tests, needs a device/emulator
./gradlew lintDebug                  # static analysis
```

Reports:

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/lint-results-debug.html`

## Current status

| Suite | Tests | Result |
|---|---|---|
| JVM unit tests | 40 | **All passing** (verified on every CI build) |
| Robolectric tests | included above | Passing |
| Instrumented tests | 3 classes | Compile-verified; require a device to execute |

The exact count and result of each run is posted as a commit comment by CI, together
with the APK sizes and manifest inspection. It is generated from the real test XML
output, not written by hand.

## JVM unit tests

### `PdfTextServiceTest` — text engine (13 tests)

The riskiest custom code in Pagwyn is the hand-written PDF parser, so it is tested
against **real PDF byte streams generated inside the test** (`TestPdfs`), not against
mock objects.

| Test | What it proves |
|---|---|
| `extracts text from a flate compressed page` | Object scanning + `Inflater` + content decoding |
| `extracts text from an uncompressed page` | The no-filter path |
| `reads every page of a multi page document in order` | Page tree traversal and ordering |
| `joins separate text runs` | Multiple `Tj` operators on one page |
| `decodes text split across a TJ array` | Kerning arrays split into glyph runs |
| `handles escaped parentheses in strings` | PDF string escapes `\(` `\)` |
| `reports no text layer for graphics only documents` | Scanned PDFs degrade cleanly instead of producing garbage |
| `returns null for bytes that are not a pdf` | Arbitrary input cannot crash the parser |
| `returns null for an empty file` | Zero-length files |
| `search finds matching pages and reports snippets` | Page-level search results |
| `search returns nothing for an empty query` | Blank query short-circuit |
| `search honours cancellation` | A cancelled search does no work |
| `search respects the result limit` | Result capping on huge documents |

`TestPdfs` builds valid PDFs with a correct xref table, so the tests also exercise the
"well-formed document" path rather than only the recovery path.

### `PageBitmapCacheTest` — memory (8 tests, Robolectric)

Real `Bitmap` objects are required, hence Robolectric.

| Test | What it proves |
|---|---|
| `stores and returns a page` | Basic caching |
| `different zoom levels are separate entries` | Zooming never shows a stale, blurry bitmap |
| `evicts pages outside the requested window` | Distance-based eviction keeps RAM flat |
| `evictPage drops every zoom level of one page` | Complete cleanup on rotation/mode change |
| `budget is respected` | 20 × 1 MB bitmaps into an 8 MB budget never exceeds the budget |
| `clear releases everything` | Teardown |
| `canAllocate refuses request larger than half the budget` | OOM protection |
| `memory class sizing scales with the device` | 64 MB device gets a smaller cache than 512 MB |

### `FormattersTest` — user-visible strings (9 tests)

File sizes (units and rounding), relative times ("Just now" → "2 weeks ago" →
absolute date), page labels, percentage clamping, A4/Letter detection in both
orientations, title extraction from file names, and middle ellipsis behaviour.

These matter because they are what users read on every screen.

### `DocumentModelTest` — persistence contract (6 tests)

Title/identity derivation, safe page summaries, URI round-tripping, and — importantly —
**stability of every stored enum value**. Those integers are persisted in
SharedPreferences; if they ever changed, every existing user's settings would silently
reset. The test makes that a build failure.

### `ZoomMathTest` — geometry (4 tests)

Aspect ratios (portrait and landscape), division-by-zero guards, and scaled heights.
Getting these wrong shows up immediately as stretched or clipped pages.

## Instrumented tests

These require a device or emulator:

| Test class | Covers |
|---|---|
| `ReaderLaunchTest` | A PDF opens through an intent, the correct page count appears, and the reader does not crash on a malformed file |
| `HomeLibraryTest` | Opening a document records it in the library; starring persists across recreation |
| `SettingsPersistenceTest` | Theme, reading mode and zoom defaults survive a process restart |

They are compiled in CI (`assembleDebugAndroidTest`) so they cannot rot, but they are
only *executed* when a device is attached. Running them is one command:

```bash
./gradlew connectedDebugAndroidTest
```

## Manual test matrix

Automated tests cannot cover rendering quality or real-device performance. The
following matrix is the checklist for a release. Items marked ✅ are covered by
automation; the rest need a human with a device.

### Documents

| Case | Automated | Manual |
|---|---|---|
| Small PDF (1–5 pages) | ✅ parser tests | rendering, zoom |
| Large PDF (1,000+ pages) | — | open time, scrollbar jump, flat memory |
| Scanned PDF (no text layer) | ✅ parser test | search shows "no selectable text" |
| Password-protected PDF | — | prompt appears, honest message on refusal |
| Complex layout / embedded fonts | ✅ flate + TJ tests | visual fidelity |
| Corrupt / truncated PDF | ✅ parser test | error panel + "Open in another app" |
| PDF from Google Drive / Downloads | — | SAF grant, reopen after reboot |
| PDF opened from another app | — | `VIEW` and `SEND` intents |

### Devices

| Case | Status |
|---|---|
| Android 7.0 (API 24) | Supported (`minSdk 24`) |
| Android 10 / 12 / 13 / 15 | Supported |
| 32-bit ARMv7 device | Supported by construction (no native code) |
| ARM64 device | Supported |
| Low-memory device | Cache sizing covered by tests |
| Phone, tablet, landscape, split screen | Layouts are resize-friendly; visual check needed |

### Behaviours

| Case | Verification |
|---|---|
| Reading position restored after kill | Manual (logic covered by `saveProgress`/`open`) |
| Bookmarks survive restart | Manual + `DocumentStore` code path |
| Rotation with the reader open | `configChanges` handled; state kept in the ViewModel |
| Repeated open/close of ten documents | LeakCanary in debug builds |
| Airplane mode | Everything works; there is no network code at all |
| No unexpected network activity | Guaranteed: no `INTERNET` permission |

## What is deliberately not claimed

- **Rendering accuracy** is the platform's `PdfRenderer`; Pagwyn does not modify page
  pixels, so accuracy is inherited from Android itself.
- **Timing numbers** (startup, frame times, memory) are not asserted in CI because the
  runners are headless. [PERFORMANCE.md](PERFORMANCE.md) lists the exact commands to
  measure them on real hardware, and says plainly which numbers are measured and
  which are targets.
- **ARMv7 runtime behaviour** has not been executed on a physical 32-bit device in
  this environment. What *is* verified is that the APK contains no native code, which
  is the usual reason ARMv7 support breaks.
