package org.ungoogled.patches.asusfilemanager.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.asusfilemanager.AsusFileManagerConstants.COMPATIBILITY_ASUS_FILE_MANAGER
import org.w3c.dom.Element

internal const val ASUS_FILE_MANAGER_EXTENSION = "extensions/asus.mpe"

private const val ALL_FILES_ACCESS = "Lorg/ungoogled/asus/AllFilesAccess;"

private val log = java.util.logging.Logger.getLogger("AsusFileManager")

/** The app's own version check, which asks ASUS's update service (com.asus.server.azs). */
private object VersionCheckFingerprint : Fingerprint(
    definingClass = "Li2/b;",
    name = "a",
    returnType = "I",
    parameters = listOf("Landroid/content/Context;"),
)

/** The dialog the version check shows when the ASUS service reports an update. */
private object VersionCheckDialogFingerprint : Fingerprint(
    definingClass = "Li2/b;",
    name = "m",
    returnType = "I",
    parameters = listOf("Landroid/content/Context;", "I", "Li2/b\$b;"),
)

/** The file manager's main activity, where "All files access" is asked for. */
private object FileManagerActivityFingerprint : Fingerprint(
    definingClass = "Lcom/asus/filemanager/activity/FileManagerActivity;",
    name = "onResume",
    returnType = "V",
    parameters = listOf(),
)

/**
 * ASUS File Manager on a phone from another maker.
 *
 * Two things tie the app to ASUS: its version check, which reads resources out of the ASUS update service's APK and
 * dies with NoClassDefFoundError where that service is missing, and "All files access", which the app holds as a
 * system app on a ZenFone. The version check is answered "up to date" and the dialog is skipped; the activity asks for
 * storage access on start.
 */
/**
 * ZenUI is ASUS's own launcher and settings app; the file manager only names it as an optional feature, but the
 * required flag alone would stop the install. "All files access" is what the file manager needs to browse the phone.
 */
private val manifestPatch = resourcePatch(
    description = "Makes the ZenUI features optional and grants storage access.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            var features = 0
            for (element in manifest.getElementsByTagName("uses-feature").let { list ->
                (0 until list.length).map { list.item(it) as Element }
            }) {
                if (element.getAttribute("android:name").startsWith("asus.software.zenui")) {
                    element.setAttribute("android:required", "false")
                    features++
                }
            }
            if (features == 0) throw PatchException("no ZenUI feature in the manifest")

            val permissions = manifest.getElementsByTagName("uses-permission")
            val hasStorage = (0 until permissions.length).any {
                (permissions.item(it) as Element).getAttribute("android:name") ==
                    "android.permission.MANAGE_EXTERNAL_STORAGE"
            }
            if (!hasStorage) {
                manifest.documentElement.insertBefore(
                    manifest.createElement("uses-permission").apply {
                        setAttribute("android:name", "android.permission.MANAGE_EXTERNAL_STORAGE")
                    },
                    manifest.getElementsByTagName("application").item(0),
                )
            }
        }
    }
}

@Suppress("unused")
val enableNonAsusPatch = bytecodePatch(
    name = "Enable non-ASUS ROM support",
    description = "Makes ASUS File Manager installable and usable on phones from other makers.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ASUS_FILE_MANAGER)
    dependsOn(manifestPatch)
    extendWith(ASUS_FILE_MANAGER_EXTENSION)

    execute {
        // 0: no update, no prompt. The app's own "up to date" result.
        VersionCheckFingerprint.method.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
        VersionCheckDialogFingerprint.method.addInstructions(0, "const/4 v0, 0x0\nreturn v0")

        FileManagerActivityFingerprint.method.addInstructions(
            0,
            "invoke-static {p0}, $ALL_FILES_ACCESS->request(Landroid/app/Activity;)V",
        )
        log.info("ASUS version check disabled, storage access requested on start")
    }
}
