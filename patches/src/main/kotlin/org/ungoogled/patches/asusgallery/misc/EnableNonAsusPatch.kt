package org.ungoogled.patches.asusgallery.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.asusgallery.AsusGalleryConstants.COMPATIBILITY_ASUS_GALLERY
import org.ungoogled.patches.shared.supportedVersionCheck
import org.w3c.dom.Element

internal const val ASUS_GALLERY_EXTENSION = "extensions/asus.mpe"

private const val ALL_FILES_ACCESS = "Lorg/ungoogled/asus/AllFilesAccess;"

private val log = java.util.logging.Logger.getLogger("AsusGallery")

/** The gallery's own activity, where the app is set up and permissions are asked for. */
private object GalleryActivityFingerprint : Fingerprint(
    definingClass = "Lcom/asus/gallery/app/EPhotoActivity;",
    name = "onResume",
    returnType = "V",
    parameters = listOf(),
)

/**
 * ZenUI is the launcher and settings app ASUS ships; the gallery only names it as an optional feature, but the
 * required flag alone would stop the install. The OpenCL and Qualcomm DSP libraries are chipset specific: most phones
 * have them, some do not, and the gallery falls back to its CPU paths. "All files access" is what the gallery needs to
 * manage the library, and the media permissions are limited to Android 13 and lower by the stock manifest because
 * Android 14 replaces them with the selected-photos access.
 */
private val manifestPatch = resourcePatch(
    description = "Makes ASUS's tools optional and grants storage access.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement

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

            var libraries = 0
            for (element in manifest.getElementsByTagName("uses-native-library").let { list ->
                (0 until list.length).map { list.item(it) as Element }
            }) {
                if (element.getAttribute("android:required") != "false") libraries++
                element.setAttribute("android:required", "false")
            }
            if (libraries == 0) throw PatchException("no required native library in the manifest")

            val permissions = manifest.getElementsByTagName("uses-permission")
            fun grant(name: String) {
                val existing = (0 until permissions.length)
                    .map { permissions.item(it) as Element }
                    .firstOrNull { it.getAttribute("android:name") == name }
                if (existing != null) {
                    // A maxSdkVersion would switch the permission off on the phone we are installing on.
                    if (name.startsWith("android.permission.READ_MEDIA_")) {
                        existing.removeAttribute("android:maxSdkVersion")
                    }
                    return
                }
                root.insertBefore(
                    manifest.createElement("uses-permission").apply { setAttribute("android:name", name) },
                    manifest.getElementsByTagName("application").item(0),
                )
            }
            grant("android.permission.MANAGE_EXTERNAL_STORAGE")
            grant("android.permission.READ_MEDIA_IMAGES")
            grant("android.permission.READ_MEDIA_VIDEO")
            grant("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        }
    }
}

/**
 * ASUS Gallery on a phone from another maker.
 *
 * The app expects the ZenFone's own features, its OpenCL and DSP libraries and, above all, "All files access" - on an
 * ASUS phone it is a system app and holds that already. The manifest makes the features optional and grants the
 * storage permission; the activity asks for "All files access" on first start, which is what Android 11+ requires of an
 * ordinary install.
 */
@Suppress("unused")
val enableNonAsusPatch = bytecodePatch(
    name = "Enable non-ASUS ROM support",
    description = "Makes ASUS Gallery installable and usable on phones from other makers.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ASUS_GALLERY)
    dependsOn(supportedVersionCheck(COMPATIBILITY_ASUS_GALLERY))
    dependsOn(manifestPatch)
    extendWith(ASUS_GALLERY_EXTENSION)

    execute {
        GalleryActivityFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0}, $ALL_FILES_ACCESS->request(Landroid/app/Activity;)V
            """.trimIndent(),
        )
        log.info("All files access requested on start")
    }
}
