# Performance

This document separates **what was measured in CI from the shipped artifact** from
**engineering targets** and **what still needs a physical device**. Nothing here is
presented as verified unless it was actually run.

---

## 1. Verified on every build (automated)

The CI workflow runs unit tests, builds both APKs and inspects the *release artifact*
itself. The numbers below come from that inspection, not from estimates.

### APK size

| Artifact | Size | Contents |
|---|---|---|
| `pagwyn-release.apk` (minified + resource shrunk) | **~1.02 MB** (1,067,585 bytes) | The shippable build |
| `pagwyn-debug.apk` (unminified, includes LeakCanary) | ~5.0 MB (5,044,545 bytes) | Development only |

This lands at the low end of the 2–5 MB target — smaller, because Pagwyn ships no
native PDF engine, no image-loading library and no Material Components.

### Architecture

```
$ unzip -l pagwyn-release.apk | grep ' lib/'
(none)
```

No native libraries means:

- **ARMv7 (`armeabi-v7a`) support is guaranteed by construction.** There is no
  native binary that could be built for arm64 only.
- The same APK installs on ARM64, x86 and x86_64.
- `abiFilters` in `app/build.gradle.kts` lists `armeabi-v7a`, `arm64-v8a`, `x86`,
  `x86_64`, so adding a native dependency later that drops 32-bit ARM fails the
  build instead of silently shipping an ARMv7-broken APK.

### Manifest

```
package: name='io.pagwyn.reader' versionCode='1' versionName='1.0.0'
targetSdkVersion:'35'
launchable-activity: name='io.pagwyn.reader.ui.home.HomeActivity'

uses-permission: name='io.pagwyn.reader.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

`minSdkVersion` is 24 (Android 7.0) and appears in `aapt2 dump badging` as
`sdkVersion:'24'`.

The single `uses-permission` entry is a *self-defined signature permission* that the
AndroidX Core library adds automatically to protect dynamically registered broadcast
receivers. It is not a system capability, grants no access to user data and does not
appear in the system permission list. There is **no `INTERNET` permission**: the app
cannot open a network socket at all.

### Code size after shrinking

R8 removes unused AndroidX/fragment/coroutines classes. The release APK's classes.dex
is roughly 700 KB, which is why the total APK stays around 1 MB.

---

## 2. Engineering targets (design constraints, met by construction)

These are properties of the architecture that can be checked by reading the code.
They are listed separately because they have not been timed on hardware here.

| Target | How it is achieved | Where |
|---|---|---|
| Instant cold start | No DI graph, no analytics init, no splash gate, three lazy singletons | `PagwynApplication` |
| Fast document open | `PdfRenderer` opens directly from a file descriptor; no copying or pre-scanning of pages | `PdfDocument.open` |
| No full-document load | Only the viewport window is rendered; page dimensions are primed lazily | `PageRenderer.setViewport`, `ContinuousReaderAdapter` |
| Bounded RAM | Bitmap cache sized from `ActivityManager.memoryClass`, byte-budgeted, distance-based eviction | `PageBitmapCache` |
| Smooth zoom | Zoom is a canvas transform in `dispatchDraw`; no re-layout during pinch | `ZoomableLayout` |
| Low rendering latency | Preview pass (≤720 px) painted before the sharp pass | `PageRenderer.renderPreview` |
| No background services | No `Service`, `JobScheduler` or `WorkManager` anywhere | manifest |
| No network activity | No `INTERNET` permission | manifest |
| No leaks | Renderer closes on `onCleared`; bitmaps are recycled; `LeakCanary` runs in debug builds | `ReaderViewModel.onCleared` |

### Memory model in numbers

| Device memory class | Cache budget |
|---|---|
| 64 MB (very low-end) | 10.7 MB (heap / 6) |
| 128 MB (typical entry-level) | 21.3 MB (heap / 6) |
| 256 MB+ (modern) | 32 MB+ (heap / 8) |

Single-bitmap allocation is refused above 22 MB or above half the budget, in which
case the preview render is kept — the reader degrades instead of throwing
`OutOfMemoryError`. Both rules are covered by unit tests
(`PageBitmapCacheTest.budget is respected`,
`canAllocate refuses request larger than half the budget`).

### Rendering work per scroll

For a viewport showing 1–2 pages, the renderer is asked for at most **5 pages**
(current, ±1, ±2) and the bitmap cache keeps **±3 pages**. Scrolling to page 2,000 of
a 3,000-page document costs exactly the same as scrolling in a 30-page document.

---

## 3. Measured in CI (functional, not timing)

| Check | Result |
|---|---|
| Unit tests | **40 tests, 0 failures, 0 skipped** |
| Release build | succeeds (`minifyEnabled` + `shrinkResources`) |
| Debug build | succeeds |
| Lint | runs on every build; findings are reviewed, not suppressed wholesale |
| APK verification | sizes, ABIs, SDK levels, permissions, signature all inspected from the artifact |

---

## 4. What has not been measured here, and how to measure it

The build environment is a headless CI container: there is no emulator with GPU
acceleration and no physical ARM device, so the following are **not** claimed as
verified. Each has a concrete procedure so a maintainer can validate it quickly.

### Cold start (`am start -W`)

```bash
adb shell am force-stop io.pagwyn.reader
adb shell am start -W -n io.pagwyn.reader/.ui.home.HomeActivity
# TotalTime / WaitTime reported by the system
```

Target: under 400 ms on a mid-range device, under 900 ms on a 2016-era ARMv7 phone.

### Memory (`dumpsys meminfo`)

```bash
adb shell dumpsys meminfo io.pagwyn.reader | head -20
```

Open a small PDF, read for 30 seconds, then a 1,000-page PDF and repeat. The PSS
should be dominated by the bitmap cache budget and should *not* grow with page count.

### Scroll and zoom smoothness (`gfxinfo` / `jank`)

```bash
adb shell dumpsys gfxinfo io.pagwyn.reader reset
# scroll and pinch for 20 seconds
adb shell dumpsys gfxinfo io.pagwyn.reader | grep -E "Janky|percentile|50th|90th|99th"
```

Target: 95th percentile frame time under 16 ms on a 60 Hz mid-range device.

### Large document behaviour

Use a 1,000+ page file and confirm:

- opening shows the first page in well under a second,
- dragging the scrollbar to the middle renders that page within a frame or two,
- memory stays flat (`PageBitmapCache.size` does not grow with page count).

### Leak check

`LeakCanary` is included in debug builds only. Open and close ten documents in a row;
no retained activity or `PdfRenderer` should be reported.

---

## 5. Why these numbers are honest

A reader that claims "0.3 s startup on all devices" without measurement is not useful.
What is verifiable here — APK size, absence of native code, absence of network
permission, test results, cache bounds — is stated with the artifact it was read from.
Everything that requires hardware is listed with the exact command to reproduce it.

If you run these measurements and get different results, that is a legitimate bug
report: open an issue with the device model, Android version and the command output.
