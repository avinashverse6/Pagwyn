# Dependencies

Pagwyn keeps its dependency graph deliberately small. Every entry below exists for a
concrete reason; anything that could be replaced by a few lines of code was.

## Runtime dependencies

| Dependency | Version | Why it is here | Approx. size impact |
|---|---|---|---|
| `androidx.core:core-ktx` | 1.15.0 | `WindowInsetsCompat`, `NotificationCompat`-style helpers, `ContextCompat` | ~120 KB |
| `androidx.appcompat:appcompat` | 1.7.0 | `AppCompatActivity`, `DayNight` themes, `AlertDialog`, `PopupMenu` support | ~450 KB |
| `androidx.activity:activity-ktx` | 1.9.3 | `ActivityResultContracts.OpenDocument` (SAF), `OnBackPressedDispatcher` | ~60 KB |
| `androidx.fragment:fragment-ktx` | 1.8.5 | `DialogFragment` used by the bottom sheets | ~180 KB |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.8.7 | `AndroidViewModel`, `viewModelScope` | ~40 KB |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.8.7 | `repeatOnLifecycle` for state collection | ~30 KB |
| `androidx.recyclerview:recyclerview` | 1.3.2 | The page list and document lists | ~300 KB |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.9.0 | Structured concurrency for rendering, IO and search | ~120 KB |

Total third-party runtime code is roughly 1.3 MB before R8 shrinking; after
minification the released APK is in the low single-digit MB range (see the
verification report attached to each release).

### Deliberately not used

| Library | Would have provided | Why it was rejected |
|---|---|---|
| Material Components | `BottomSheetDialogFragment`, chips, sliders, theming | ~600 KB of resources for interactions expressible with AppCompat + a small `DialogFragment` (`PagwynSheet`) |
| Hilt / Dagger | Dependency injection | Code generation, ~200 KB, ~100 ms cold-start cost for three singletons |
| Navigation | Fragment navigation graph | Two activities; `Intent`s are clearer and cheaper |
| Glide / Coil | Image loading | Pages are `Bitmap`s produced in-process; there is nothing to load from disk or network |
| Room | ORM over SQLite | One table and a handful of queries; `SQLiteOpenHelper` avoids an annotation processor |
| PdfBox-Android / MuPDF / pdfium | Full PDF parsing, text extraction, annotations | 6–20 MB and additional native ABIs, which would break the ARMv7 and APK-size goals. Text extraction is implemented instead (`PdfParser`) |
| Any analytics / crash SDK | Telemetry | Explicitly against the project's privacy policy |

## Test dependencies

| Dependency | Version | Scope |
|---|---|---|
| `junit:junit` | 4.13.2 | JVM unit tests |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.9.0 | Coroutine testing |
| `org.robolectric:robolectric` | 4.14.1 | Real `Bitmap`/`Uri` behaviour in JVM tests |
| `androidx.test.ext:junit` | 1.2.1 | Instrumented test runner extensions |
| `androidx.test.espresso:espresso-core` | 3.6.1 | Instrumented UI assertions |
| `com.squareup.leakcanary:leakcanary-android` | 2.14 | `debugImplementation` only — never in release builds |

## Build tooling

| Tool | Version | Notes |
|---|---|---|
| Android Gradle Plugin | 8.7.3 | `gradle/libs.versions.toml` |
| Kotlin Gradle Plugin | 2.1.10 | JVM target 17 |
| Gradle | 8.11.1 | `gradle/wrapper/gradle-wrapper.properties` |

## Asset generation

`tools/generate_icons.py` regenerates every launcher PNG and documentation icon from
a single drawing routine. It requires **Pillow** and is a *developer* tool: Pillow is
not a dependency of the app, and the generated PNGs are committed so nobody needs to
run the script to build Pagwyn.

## Licence compatibility

All runtime dependencies are Apache-2.0 or MIT licensed, which is compatible with
Pagwyn's GPL-3.0 licence (they are permissive licences that can be combined with a
copyleft work). No dependency is GPL-incompatible.

The Android platform classes used by the app (`android.graphics.pdf.PdfRenderer`,
`android.os.ParcelFileDescriptor`, and the rest of the framework) are part of the
operating system, not bundled code, and are used under the Android SDK terms.
