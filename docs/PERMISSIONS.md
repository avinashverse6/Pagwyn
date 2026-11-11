# Permissions

## Declared permissions

```xml
<!-- nothing -->
```

Pagwyn's manifest declares **no** `uses-permission` entries.

That is the entire story, and it is intentional. Each commonly requested permission
was considered and rejected:

| Permission | Why Pagwyn does not need it |
|---|---|
| `INTERNET` | No network features exist. Omitting it is what makes "offline by design" verifiable rather than a promise. |
| `READ_EXTERNAL_STORAGE` / `READ_MEDIA_*` | Documents are opened with the Storage Access Framework, which grants per-document access chosen by the user. |
| `WRITE_EXTERNAL_STORAGE` | Pagwyn never modifies or creates PDF files. Nothing is downloaded, converted or exported. |
| `MANAGE_EXTERNAL_STORAGE` | Would give access to every file on the device. Directly contrary to the project's privacy stance. |
| `POST_NOTIFICATIONS` | No notifications exist. |
| `WAKE_LOCK` | Reading never needs to wake the device. "Keep screen on" uses the window flag `FLAG_KEEP_SCREEN_ON`, which needs no permission. |
| `FOREGROUND_SERVICE` / background services | Pagwyn has no background work at all. |
| `VIBRATE` | No haptics. |
| `ACCESS_NETWORK_STATE` | No network features to gate. |

## What the app does declare in its manifest (not permissions)

| Entry | Purpose |
|---|---|
| `HomeActivity` (launcher) | Library screen; also hosts the two launcher shortcuts |
| `ReaderActivity` (`VIEW`, `SEND`, `BROWSABLE` filters, `content:`/`file:` schemes, `application/pdf`) | Lets other apps open PDFs with Pagwyn. This is an *exported* component by necessity; it only ever reads the single document it was handed. |
| `MetadataActivity` (`exported="false"`) | Document information screen |
| `androidx.startup.InitializationProvider` removed | Prevents unused AndroidX startup initialisers from running, keeping cold start fast |

## Grant model

```
Picker (ACTION_OPEN_DOCUMENT)
        │  user selects one file
        ▼
ContentResolver grants read access to that URI
        │
        ├── takePersistableUriPermission → survives reboot
        └── ReaderActivity opens a ParcelFileDescriptor from it
```

The PDF file is read through a file descriptor; Pagwyn never copies it into its own
storage and never writes to it.

## Verifying on a device

```bash
adb shell dumpsys package io.pagwyn.reader | grep -A5 "requested permissions"
# expected: no requested permissions
```

Or check the APK directly (this is what CI does on every release):

```bash
aapt2 dump permissions pagwyn-release.apk
```
