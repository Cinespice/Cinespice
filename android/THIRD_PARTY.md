# Android third party components

- **TDLib 1.8.65**, https://github.com/tdlib/td, Boost Software License 1.0.
  Android binaries and JNI entry points are from
  https://github.com/up9cloud/android-libtdjson/releases/tag/v1.8.65.
  JNI wrapper/build scripts: MIT, copyright Chia-Hsueh Huang (see bundled license).
  `prepare-tdlib.sh` pins the archive SHA-256. The native libraries are unmodified.
  Upstream Android build recipe: https://github.com/up9cloud/android-libtdjson/tree/v1.8.65.
- **libVLC Android 3.6.5**, `org.videolan.android:libvlc-all:3.6.5`, LGPL 2.1 or later.
  https://code.videolan.org/videolan/libvlc-android and https://www.videolan.org/vlc/libvlc.html.
  Upstream VLC Android source/build instructions:
  https://code.videolan.org/videolan/vlc-android/-/tree/3.6.5.
  VLC core: https://code.videolan.org/videolan/vlc/-/tree/3.0.x.
  VLC dependencies retain their upstream licenses. Native shared libraries are
  dynamically loaded; no player library source has been modified. This project
  includes the Android application source and its Gradle build so users can rebuild
  and replace the linked player libraries. Reverse engineering for debugging
  modifications to LGPL components is permitted by their license.
- AndroidX test tools and JDK desugaring support are Apache 2.0.
  https://github.com/android/android-test and https://github.com/google/desugar_jdk_libs.
- Gradle wrapper: Apache 2.0, https://github.com/gradle/gradle/tree/v8.9.0.

The TDLib, JNI wrapper, and LGPL texts are bundled in the app's
`assets/licenses/` directory and readable from Settings → Open source notices.
The Cinespice icon is reused from this repository's committed iOS assets.
