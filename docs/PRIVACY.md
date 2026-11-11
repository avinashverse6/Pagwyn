# Privacy

**Short version:** Pagwyn cannot send your data anywhere. It does not have the
`INTERNET` permission.

## What Pagwyn stores

All storage is local and app-private.

| Data | Where | Why | Included in cloud backup |
|---|---|---|---|
| Document index: URI, display name, page count, size, last page, progress, zoom, rotation, star flag, timestamps | SQLite `pagwyn.db` | Rebuild the library and restore your reading position | Yes |
| Bookmarks | SQLite `bookmarks` table | Restore page bookmarks | Yes |
| Preferences: theme, reading mode, default zoom, page spacing, keep-screen-on, history limit, last document URI | `SharedPreferences` `pagwyn_prefs` | Remember your settings | Yes |
| Rendered page bitmaps | RAM only | Fast scrolling | No — never written to disk |
| The PDF files themselves | Wherever they already live on your device | — | No — Pagwyn never copies them |

Cloud backup can be disabled entirely with:
`Settings → Apps → Pagwyn → Backup` (Android 12+), or by removing the `include`
entries in `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` in your
own build.

## What Pagwyn does not do

- No analytics, telemetry, crash reporting or usage statistics.
- No advertising or ad SDKs.
- No user accounts, sign-in or cloud sync.
- No network requests of any kind — the `INTERNET` permission is not declared, so the
  operating system will reject any attempt to open a socket.
- No font, image or library downloads at runtime.
- No document upload, ever. Sharing is an explicit user action through the Android
  share sheet, and it hands the receiving app a one-time read grant for that single
  file.

## Storage Access Framework

Pagwyn opens documents through `ACTION_OPEN_DOCUMENT`. That means:

- The user picks the exact files Pagwyn may read.
- No `READ_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE` permission is requested.
- Access is per-document. Pagwyn cannot enumerate or read your other files.
- `takePersistableUriPermission` is used only for documents you opened, so they can be
  reopened later without asking again.

## Removing your data

- **Recent documents and reading positions:**
  `Settings → Clear recent documents`. This deletes the document index and bookmarks.
  The PDF files are untouched.
- **Everything:** uninstall Pagwyn, or clear app storage in Android settings.
  Because nothing was ever sent anywhere, deleting the app data is genuinely complete.

## Verifying these claims

Every claim above corresponds to something you can check in this repository:

| Claim | Where to check |
|---|---|
| No internet permission | `app/src/main/AndroidManifest.xml` (there is no `<uses-permission>` at all) |
| No storage permission | same file |
| No tracking SDKs | `gradle/libs.versions.toml` and `docs/DEPENDENCIES.md` |
| No network code | `grep -ri "http\|socket\|okhttp\|retrofit" app/src/main/java` returns nothing |
| What is backed up | `app/src/main/res/xml/backup_rules.xml` |
| What is stored | `app/src/main/java/io/pagwyn/reader/data/` |

The release workflow prints the declared permission list of the actual APK on every
build, so you can verify the shipped artifact rather than the source.
