package org.ungoogled.patches.xiaomiremote

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object XiaomiRemoteConstants {
    /**
     * Mi Remote ships inside HyperOS firmware as a single APK (mi_ext/product/data-app/XMRemoteController). Pinned to
     * 7.2.5R from a HyperOS 4.0.1 build: R8 renames its classes every release.
     */
    val COMPATIBILITY_XIAOMI_REMOTE = Compatibility(
        name = "Mi Remote",
        packageName = "com.duokan.phone.remotecontroller",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xF26B21,
        targets = listOf(
            AppTarget(version = "7.2.5R"),
        ),
    )
}
