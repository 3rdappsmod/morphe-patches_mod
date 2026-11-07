package org.ungoogled.patches.asusfilemanager.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.asusfilemanager.AsusFileManagerConstants.COMPATIBILITY_ASUS_FILE_MANAGER
import org.w3c.dom.Element

internal const val ASUS_FILE_MANAGER_EXTENSION = "extensions/asus.mpe"

private const val ALL_FILES_ACCESS = "Lorg/ungoogled/asus/AllFilesAccess;"

private const val PATHS = "Lorg/ungoogled/asus/Paths;"

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
 * The path check every file goes through: it throws "Illegal Path" for a local file whose path does not start with
 * /storage/ (VFile.t() == 0 means a local file). The app assumes ASUS's storage stack, which always hands out a
 * /storage path; other phones report a removable volume's raw vold mount instead, so a USB drive plugged in crashes
 * the home screen as it draws the volume list.
 */
private object PathValidationFingerprint : Fingerprint(
    definingClass = "Lu3/t;",
    name = "u",
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/io/File;"),
    strings = listOf("Illegal Path: "),
)

/**
 * ASUS File Manager on a phone from another maker.
 *
 * Three things tie the app to ASUS: its version check, which reads resources out of the ASUS update service's APK and
 * dies with NoClassDefFoundError where that service is missing; "All files access", which the app holds as a system
 * app on a ZenFone; and a path check that only accepts ASUS's /storage layout, which makes a USB drive crash the home
 * screen on a phone that reports its raw vold mount. The version check is answered "up to date" and the dialog is
 * skipped, the activity asks for storage access on start, and the path check accepts the raw mount too.
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

        // Accept vold's raw mount for a removable volume as a valid local path too. The check is
        //   const-string v1, "/storage/" -> startsWith -> move-result v1 -> if-nez v1, :valid
        // and a phone that reports /mnt/media_rw/<uuid> for a USB drive fails it, so the volume list throws
        // "Illegal Path" while the home screen draws. The string and the call are replaced in place: the existing
        // move-result and branch then read the answer from Paths.isLocalPath, so no label moves.
        PathValidationFingerprint.method.apply {
            val instructions = implementation!!.instructions.toList()
            val storage = instructions.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "/storage/"
            }
            if (storage < 0) throw PatchException("the /storage/ path check was not found")
            if (storage + 3 >= instructions.size) throw PatchException("the /storage/ path check is short")
            val startsWith = (instructions[storage + 1] as? ReferenceInstruction)?.reference as? MethodReference
            if (instructions[storage + 1].opcode != Opcode.INVOKE_VIRTUAL || startsWith?.name != "startsWith") {
                throw PatchException("the /storage/ path check is not a startsWith call")
            }
            if (instructions[storage + 3].opcode !in setOf(Opcode.IF_NEZ, Opcode.IF_EQZ)) {
                throw PatchException("the /storage/ path check does not branch next")
            }
            // The check's three instructions become the call and its result; move-result directly follows the invoke,
            // and the leftover slot is a nop. The if-nez and the label it branches to stay where they are.
            replaceInstruction(storage, "invoke-static {v0}, $PATHS->isLocalPath(Ljava/lang/String;)Z")
            replaceInstruction(storage + 1, "move-result v1")
            replaceInstruction(storage + 2, "nop")
            log.info("path check accepts vold's raw mount")
        }

        FileManagerActivityFingerprint.method.addInstructions(
            0,
            "invoke-static {p0}, $ALL_FILES_ACCESS->request(Landroid/app/Activity;)V",
        )
        log.info("ASUS version check disabled, storage access requested on start")
    }
}
