package org.ungoogled.patches.asusfilemanager

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object AsusFileManagerConstants {
    /**
     * ASUS File Manager ships inside ZenFone firmware as a single APK. Pinned to 2.12.0.60 from an ASUS AI2501 on
     * Android 16: R8 renames its classes every build.
     */
    val COMPATIBILITY_ASUS_FILE_MANAGER = Compatibility(
        name = "ASUS File Manager",
        packageName = "com.asus.filemanager",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x00A0E9,
        targets = listOf(
            AppTarget(version = "2.12.0.60_250822", minSdk = 34, description = "needs Android 14 or newer"),
        ),
    )
}
