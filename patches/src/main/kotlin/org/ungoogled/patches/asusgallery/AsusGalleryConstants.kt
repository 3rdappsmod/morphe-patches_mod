package org.ungoogled.patches.asusgallery

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object AsusGalleryConstants {
    /**
     * ASUS Gallery ships inside ZenFone firmware as a single APK. Pinned to 3.8.0.98 from an ASUS AI2501 on
     * Android 16: R8 renames its classes every build.
     */
    val COMPATIBILITY_ASUS_GALLERY = Compatibility(
        name = "ASUS Gallery",
        packageName = "com.asus.gallery",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x1B4B8F,
        targets = listOf(
            AppTarget(version = "3.8.0.98_250909"),
        ),
    )
}
