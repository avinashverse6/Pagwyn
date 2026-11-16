# Changelog

## Reader, theme and interface overhaul

### Fixed — pages that never appeared

A document could open, report the right page count and still show "Rendering page…"
forever. It was two defects stacked on top of each other:

- **One open page per renderer.** `PdfRenderer` allows exactly one page handle to be
  open at a time; a cache of page handles made every `openPage` after the first throw
  `IllegalStateException("Current page not closed")`. Pages are now opened and closed
  inside a single locked call, in `finally`, which is the platform's own contract.
- **A listener that called itself.** The render listener in `ReaderActivity` invoked
  `onPageFailed` unqualified from inside its own object expression, so a failure
  recursed until `StackOverflowError` and the failure never reached the UI.

Also in this area:

- A page that genuinely fails now shows **"Page N could not be rendered"** with a
  **Retry** button. There is no code path that leaves a page on a permanent spinner.
- Rendering uses the measured width, not a zero width taken from a view that has not
  been laid out yet, and results are posted back to the still-bound view holder.
- The empty black strip between the page area and the bottom bar is gone: the bar's own
  bottom inset was being applied twice.

### Fixed — crash on Android 7 when opening any screen

Every `Text.Pagwyn.*` style carried `android:fontFamily`. On API 24 that attribute is
read through `TypedArray.getString` while a `TextView` is being constructed, and the
platform resource pool lookup throws `ArrayIndexOutOfBoundsException` for some layouts,
which surfaces as `InflateException: Error inflating class TextView`. Weight now comes
from `android:textStyle`, and **no style or layout sets a font family**. Typography uses
the system face only.

A second, unrelated Android 7 crash was found while chasing that one: `PdfRenderer`
closes its native document twice on API 24 because `finalize()` re-closes a handle that
`doClose()` never cleared. Pagwyn now keeps closed renderers reachable for the life of
the process on API 24 so the finaliser never runs.

### Theme

- One app-wide theme — **System, Light or Dark** — applies to every screen, sheet and
  dialog, and changes live without a restart.
- It is applied in `Application.onCreate`, before the first activity, so there is no
  flash of the previous theme.
- The old per-document "reading theme" (sepia, page tint) is **removed**, with its
  preferences and strings.

### Menu and bottom bar

- The three-dot menu holds, in order: Bookmarks, Go to page, Theme, Full-screen
  reading, Keep screen on, Copy text, Share document, Document information.
- The floating bottom bar holds navigation only: Contents, previous/next page with a
  tappable "3 / 12" indicator, and fit-to-width.
- Nothing appears in both. Share, zoom controls, the zoom percentage, brightness and
  the thumbnails button are gone from the bar.
- **Page thumbnails are removed entirely** — button, tab and code.
- Zoom is pinch and double-tap only, stays centred on the pinch point, has a sensible
  minimum and maximum, and shows no numeric label.

### Interface

- Dark-first palette: near-black surfaces, soft off-white text, one lime accent, with a
  warm off-white light theme.
- Home: a "Continue reading" hero card with a gradient cover generated from the file
  name, progress bar and "Page X of Y"; a two-column grid of recent documents as
  rounded cards; All / Starred / Recent filter chips; a search field; and a friendly
  empty state with a vector illustration.
- Rounded 16–24dp cards, pill chips and buttons, a floating reader bar, and bottom
  sheets with drag handles.
- Subtle 150–250ms fades, press-scale on cards, a light haptic on bookmark and star, and
  reader chrome that fades out on its own.
- The settings icon is redrawn to match the rest of the set: 24dp, 1.75dp rounded
  stroke, tinted from the theme.

### Kept deliberately light

No Material Components, no image library, no font files, no new dependencies, no
`INTERNET` permission, no analytics. Still Android 7.0 (API 24) and ARMv7 compatible;
the release APK contains no native libraries.

### Verification

- JVM: 51 tests, all passing.
- Device: 98 tests across Android 14 (API 34) and Android 7.0 (API 24). Everything
  passes on API 34. On API 24 one test is limited by a native pdfium fault in the
  emulator image; see below and `docs/LIMITATIONS.md`.
- A new `LayoutInflationTest` inflates every screen layout on the device, which is the
  only place the Android 7 inflation bug can be caught.

### Not verified without physical hardware

These were checked only on emulators (x86_64, software GPU) and need a real device to be
called done:

| Area | Why it needs hardware |
|---|---|
| Haptics on bookmark and star | Emulators do not vibrate. Only the code path is verified. |
| Pinch-zoom feel and "centred on the pinch point" | Needs a real multi-touch digitiser; the emulator injects synthetic pointer events, which cannot judge smoothness. |
| Scrolling and rendering performance on ARMv7 | No physical low-end device was available. The emulator cannot represent a Cortex-A7 with 1 GB RAM. |
| TalkBack narration | Requires TalkBack plus a screen reader user. Content descriptions and 48dp targets are in place and lint-checked, but the spoken experience is unverified. |
| Font scaling at 200% | Checked by inspection for the new layouts; not exercised on a device with the largest accessibility font size. |
| Real storage providers (Downloads, Drive, OneDrive) | The suite uses a test `FileProvider`, whose behaviour differs from providers that stream or re-grant access. |
| AMOLED true black | Only meaningful on an OLED panel. |
| Battery impact of the dimming overlay | Requires a physical screen. |
| The API 24 native pdfium fault | Reproduces in the emulator image when a run opens a dozen documents back to back. Whether it also affects real Android 7 hardware, and at what rate, is unknown. Pagwyn keeps at most one document open per session to minimise the exposure. |
