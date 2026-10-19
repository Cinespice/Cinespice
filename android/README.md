# Cinespice for Android

Native Java Android port of the Swift app, with TDLib and embedded libVLC.
Package: `app.cinespice.android`. Android **8.0 or later** (API 26+).
The iOS project remains at the repository root; the Android project is self-contained here.

## Install

Use `Cinespice-arm64-v8a.apk` for most modern phones/tablets. Use the
`armeabi-v7a` APK for older 32-bit ARM devices, or `x86_64` for an emulator.
These are separate complete APKs; install only the one matching your device.
Allow your browser/file manager to install unknown apps when Android prompts.
Open Cinespice, enter your own API ID/hash from https://my.telegram.org, and sign
in with your Telegram phone/code/password or requested email verification.
Choose channels/groups in **My channels**, return to Library, open a collection,
and tap Play. Credentials are entered on-device; never put them in Git or send
login codes to another person.

The current build uses Android's **debug signing certificate** and is directly
installable; it is not a Play Store release. Subsequent builds must use the same
signing key to update an existing installation. CI creates a new debug key on a
fresh runner; APKs from unrelated CI runs may require uninstalling the earlier
build, which erases local session/history. For permanent releases configure your
own private release key outside Git before distributing updates.

## Build

Requirements: JDK 17, Android SDK platform 35 and build-tools 35.0.0, Bash,
curl, tar, sha256sum, and Internet access. Android Studio can open this directory.
Gradle 8.9, Android Gradle Plugin 8.7.3, TDLib 1.8.65 and libVLC 3.6.5 are pinned.
The Gradle distribution and TDLib archive have pinned SHA-256 checksums.

```bash
export ANDROID_HOME=/path/to/android/sdk
# With Google's SDK command-line tools:
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
./prepare-tdlib.sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Find signed installable APKs in `app/build/outputs/apk/debug/`.
Use `adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` for a
connected ARM64 device. `./gradlew :app:connectedDebugAndroidTest` additionally
runs the storage, JNI, HTTP range transfer, and local VLC playback checks on a
connected Android device/emulator. Test fixtures are synthetic; no account
credentials are required for those tests.

GitHub Actions → **Build Cinespice Android APK** builds all three APKs and uploads
**Cinespice-Android-APKs**. This workflow also runs on Android branch pushes and
pull requests affecting this project. CI configuration needs to be published
before GitHub can run it.

`./gradlew :app:assembleRelease` creates unsigned release APKs. Sign them using
your own keystore and `apksigner`, then verify with `apksigner verify`.
Never commit a keystore or its passwords. See [third party notices](THIRD_PARTY.md)
for dependency sources and licenses before distributing.

## Implemented flows

- Telegram personal account login, code, two-step password, email verification,
  encrypted session restore and sign out. AES-GCM credentials/database key in
  Android Keystore; cloud backup and device transfer excluded.
- Main/archive channel and group discovery in batches of 30, owned channels
  first, persistent source selection, source title filter and Telegram search.
  Private chats and Saved Messages are excluded.
- Account-specific SQLite metadata, per-filter pagination cursors and history.
  Each collection requests up to 30 videos and 30 documents; document filtering
  accepts video MIME types/extensions. Load more and retry retain cursors.
- Cached title/filename/caption search plus Telegram search within selected
  sources, bounded to four requests per batch with source/filter rotation.
- Cancellable opening, refreshed message/file state, loopback HTTP range bridge
  with random URL token, HEAD/suffix/seek ranges, serialized per-file reads,
  1 MB chunks and 8 MB read-ahead; downloaded files play directly.
- Full downloads, cancel, progress, and save completed media through Android's
  system file picker. Cleanup does not delete Telegram cloud messages.
- Embedded VLC with play/pause, timeline, ±10 seconds, double tap seeking,
  speed 0.25×–4×, audio/subtitle tracks/off, external tracks, delays ±30 seconds,
  five saved subtitle sizes, fit/fill/aspect choices, screen edges, manual
  portrait/landscape, control lock and auto hide.
- Periodic progress, Continue Watching, resume/from beginning, mark watched,
  remove/clear history, pause when backgrounded and resume on return.

## Android differences and device checks

This is an Android UI using the same app's library/player model. SwiftUI and
MobileVLCKit cannot run on Android. Android uses libVLC's public controls and
system document pickers. Subtitle size changes reopen the video at its saved
position; bitmap or styled subtitles may ignore text size. Reopening also resets
external tracks, delay/speed and picture choices. External IDX/SUB companion
pairs require device verification; this importer selects one track file at a time.
Artwork starts with embedded minithumbnails, then loads visible thumbnail files.
The local artwork cache has a 40 MB disk and 32 MB decoded-image memory budget.

Downloads/streams are foreground work; Android process termination can interrupt
them. This port does not include a foreground download service or background
playback. Keep the app open to finish downloads. No broad storage permission is
requested. There is no backend, WebView wrapper, subscription, or analytics SDK.

Authenticated Telegram streaming, channel permissions, large libraries, external
tracks, physical touch, codecs and ARM-device playback still need checking on a
real device. Automated synthetic tests do not establish those results. The
loopback server is private to the device and bound to 127.0.0.1; Telegram uses
TDLib's MTProto transport with no application HTTP endpoint override.
