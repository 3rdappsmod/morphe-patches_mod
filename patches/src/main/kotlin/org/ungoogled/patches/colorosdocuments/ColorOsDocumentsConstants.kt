package org.ungoogled.patches.colorosdocuments

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object ColorOsDocumentsConstants {
    /** ColorOS Documents is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_COLOROS_DOCUMENTS = Compatibility(
        name = "OnePlus Documents",
        packageName = "andes.oplus.documentsreader",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF7A00,
        targets = listOf(
            AppTarget(version = "17.1.32"),
        ),
    )
}
