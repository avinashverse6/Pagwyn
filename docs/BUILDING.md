# Building Pagwyn

## Requirements

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 (Temurin recommended) | `sourceCompatibility`/`targetCompatibility` = 17 |
| Android SDK Platform | 35 | `compileSdk = 35`, `targetSdk = 35` |
| Android Build Tools | 35.0.0 | Needed for `aapt2`/`apksigner` in the verification step |
| Gradle | 8.11.1 | Provided by the wrapper — do not install separately |
| Android Gradle Plugin | 8.7.3 | Declared in `gradle/libs.versions.toml` |
| Kotlin | 2.1.10 | Declared in `gradle/libs.versions.toml` |

`ANDROID_HOME` (or `ANDROID_SDK_ROOT`) must point at your SDK installation, or
`local.properties` must contain `sdk.dir=/path/to/sdk`. `local.properties` is
git-ignored.

## Common tasks

```bash
# Debug APK  (installs alongside a release build, package id ...debug)
./gradlew assembleDebug

# Release APK (R8 minified, resources shrunk)
./gradlew assembleRelease

# Unit tests
./gradlew testDebugUnitTest

# Instrumented tests (device or emulator required)
./gradlew connectedDebugAndroidTest

# Lint
./gradlew lintDebug
```

Outputs:

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
app/build/reports/tests/testDebugUnitTest/index.html
```

## Installing

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Architectures

Pagwyn ships **no native libraries**. Every architecture runs the same universal
APK because the PDF renderer is part of the Android platform.

`abiFilters` is declared as `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` so that any
future native dependency cannot silently drop 32-bit ARM support: the build fails
instead.

To confirm the APKs yourself:

```bash
unzip -l app/build/outputs/apk/release/app-release.apk | grep lib/
# expected: no output — there are no lib/ entries at all
```

To verify the manifest and signature:

```bash
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump badging   app/build/outputs/apk/release/app-release.apk
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump permissions app/build/outputs/apk/release/app-release.apk
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## Release signing

`release` currently uses the debug signing config so that CI can produce an
installable, shareable APK without repository secrets. **Sign your own build before
distributing it:**

```bash
keytool -genkeypair -v -keystore pagwyn-release.jks \
        -keyalg RSA -keysize 4096 -validity 10000 -alias pagwyn

export PAGWYN_KEYSTORE=$PWD/pagwyn-release.jks
export PAGWYN_KEYSTORE_PASSWORD='…'
export PAGWYN_KEY_ALIAS=pagwyn
export PAGWYN_KEY_PASSWORD='…'
```

Then replace the signing block in `app/build.gradle.kts`:

```kotlin
signingConfigs {
    create("release") {
        storeFile = file(System.getenv("PAGWYN_KEYSTORE") ?: "pagwyn-release.jks")
        storePassword = System.getenv("PAGWYN_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("PAGWYN_KEY_ALIAS")
        keyPassword = System.getenv("PAGWYN_KEY_PASSWORD")
    }
}
buildTypes {
    release { signingConfig = signingConfigs.getByName("release") }
}
```

Keystores and passwords must never be committed — `.gitignore` already excludes
`*.jks` and `*.keystore`.

## Reproducible builds

The build is reproducible in the practical sense:

- Gradle wrapper pinned to 8.11.1 with `validateDistributionUrl=true`.
- Every dependency version is pinned in `gradle/libs.versions.toml` (no dynamic
  versions, no `+` ranges).
- No build-time network calls, no code generation from remote sources, no timestamp
  in the APK (`-Dorg.gradle.jvmargs` only; version comes from `build.gradle.kts`).
- Lint is run with a fixed rule set; warnings are not fatal so a tool update cannot
  break a release unexpectedly.

Building the same commit twice with the same toolchain produces an APK with matching
`classes.dex` and resource contents. Byte-for-byte identity of the whole APK also
depends on the signing step, which is intentionally left to the distributor.

## CI

Two workflows:

| Workflow | Trigger | What it does |
|---|---|---|
| `.github/workflows/android-ci.yml` | push/PR (manual) | unit tests, lint, debug + release APKs, artifacts |
| `.github/workflows/release.yml` | tag `v*` or manual | tests, lint, APKs, APK verification report, GitHub Release |

The release workflow posts a verification comment containing the APK sizes, the ABI
table, `minSdk`/`targetSdk`, the declared permission list and the signature
fingerprint. That report is generated from the actual artifact — it is not a claim
written by hand.

To cut a release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| `SDK location not found` | Create `local.properties` with `sdk.dir=…` |
| `Unsupported class file major version` | Use JDK 17, not 21+ |
| `Failed to install … INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Debug builds use `.debug` suffix; uninstall the other build first |
| Robolectric download failure on first run | Needs network once to fetch its Android jars; subsequent runs are offline |
