# Where to get each APK

Danpoong patches keeps six apps from [bearinmind patches](https://github.com/bearinmindcat/morphe-patches).
Use the exact APK versions below: vendor builds rename classes, and another version can fail to patch or run.
This repository does not distribute vendor APKs.

| App | Package | Supported version | Minimum Android for this patch | Source |
| --- | --- | --- | --- | --- |
| Samsung Music | `com.sec.android.app.music` | 16.2.45.1 | 9 (API 28) | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/music-samsung-electronics-co-ltd/samsung-music-16-2-45-1-release/), single APK |
| OnePlus Documents | `andes.oplus.documentsreader` | 17.1.32 | 15 (API 35) | OnePlus / ColorOS firmware, single APK |
| Sony Music | `com.sonyericsson.music` | 9.4.17.A.0.11 | 11 (API 30) | Xperia XQ-GE44 firmware, single APK |
| ASUS File Manager | `com.asus.filemanager` | 2.12.0.60_250822 | 14 (API 34) | ASUS AI2501 firmware, single APK |
| ASUS Gallery | `com.asus.gallery` | 3.8.0.98_250909 | 14 (API 34) | ASUS AI2501 firmware, single APK |
| Mi Remote | `com.duokan.phone.remotecontroller` | 7.2.5R | 10 (API 29) | HyperOS firmware, `mi_ext/product/data-app/XMRemoteController` |

The minimum Android values above were checked against the APK manifests and the bundled extensions. Mi Remote's
original APK accepts Android 5.1 (API 22); the current extension requires Android 10 (API 29).

For firmware extraction, see [tools/firmware](../tools/firmware/README.md). On a phone that already has the exact
version, `adb shell pm path <package>` lists its APK paths; copy the single base APK with `adb pull <path>`.
In Morphe Manager choose that file from storage. Keep the original APK: the patched copy uses a different signature.

Upstream currently tests Sony Music 9.4.16.A.1.17 and OnePlus Documents 17.1.26. Those versions are not substitutes for
this bundle's pinned versions; this fork retains its existing ports and Documents editor improvements.
