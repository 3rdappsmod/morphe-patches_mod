package org.ungoogled.patches.sonymusic

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SonyMusicConstants {
    /**
     * Sony's Music app ships inside Xperia firmware as a single APK. Pinned to 9.4.17.A.0.11 from an Xperia XQ-GE44
     * on Android 17: R8 renames its classes every build.
     */
    val COMPATIBILITY_SONY_MUSIC = Compatibility(
        name = "Sony Music",
        packageName = "com.sonyericsson.music",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x000000,
        targets = listOf(
            AppTarget(version = "9.4.17.A.0.11", minSdk = 30, description = "needs Android 11 or newer"),
        ),
    )
}
