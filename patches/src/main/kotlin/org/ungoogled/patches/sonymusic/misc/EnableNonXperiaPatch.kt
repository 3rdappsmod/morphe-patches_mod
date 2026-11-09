package org.ungoogled.patches.sonymusic.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.sonymusic.SonyMusicConstants.COMPATIBILITY_SONY_MUSIC
import org.ungoogled.patches.shared.supportedVersionCheck
import org.w3c.dom.Element

private val log = java.util.logging.Logger.getLogger("SonyMusic")

private const val DLNA_ROUTE_PROVIDER = "com.sonymobile.somcmediarouter.provider.dlna.DlnaMediaRouteProviderService"

/** Sony's "is this Xperia API available" lookup, which decides whether DSEE HX, ClearAudio+ and low power audio run. */
private object ApiAvailabilityFingerprint : Fingerprint(
    definingClass = "Lcom/sonyericsson/music/common/ApiAvailabilityManager;",
    name = "isApiAvailable",
    returnType = "Z",
    parameters = listOf("Lcom/sonyericsson/music/common/ApiAvailabilityManager\$Api;"),
)

/** Whether DLNA is available: it asks for Sony's own DLNA provider, which no other phone has. */
private object DlnaSupportFingerprint : Fingerprint(
    definingClass = "Lcom/sonyericsson/music/common/MediaRouterUtils;",
    name = "isDlnaSupported",
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
)

/** Players for Sony's DLNA and PlayAnywhere services, built from Sony-only libraries. */
private object PlayerServiceFactoryFingerprint : Fingerprint(
    definingClass = "Lcom/sonymobile/somcmediarouter/provider/playerservice/PlayerServiceCompat;",
    name = "getFactory",
    returnType = "Lcom/sonymobile/somcmediarouter/provider/playerservice/PlayerServiceCompat\$Factory;",
)

/**
 * Xperia firmware's own libraries: com.sony.device is required, so the app does not install without it, and the DLNA
 * and PlayAnywhere libraries only exist on Sony phones. The DLNA route provider is part of the same stack.
 */
private val manifestPatch = resourcePatch(
    description = "Makes Sony's system libraries optional and drops the DLNA route provider.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            var required = 0
            for (tag in listOf("uses-library", "uses-native-library")) {
                val list = manifest.getElementsByTagName(tag)
                for (index in 0 until list.length) {
                    val element = list.item(index) as Element
                    if (element.getAttribute("android:required") != "false") required++
                    element.setAttribute("android:required", "false")
                }
            }
            if (required == 0) throw PatchException("no required Sony library in the manifest")

            var removed = 0
            for (tag in listOf("service", "activity", "provider", "receiver")) {
                val list = manifest.getElementsByTagName(tag)
                val doomed = (0 until list.length)
                    .map { list.item(it) as Element }
                    .filter { it.getAttribute("android:name") == DLNA_ROUTE_PROVIDER }
                for (element in doomed) {
                    element.parentNode.removeChild(element)
                    removed++
                }
            }
            if (removed == 0) throw PatchException("Sony's DLNA route provider not found")
        }
    }
}

/**
 * Xperia Music on a phone from another maker.
 *
 * The app asks the framework whether Sony's audio and casting APIs exist (ApiAvailabilityManager), whether Sony's DLNA
 * provider is installed (MediaRouterUtils) and which of Sony's player implementations to build (PlayerServiceCompat).
 * Every one of those answers comes from a Sony ROM library, and off an Xperia phone the lookups fail, so the four
 * methods are answered "not available" instead. Playback, the library, playlists, the equalizer and Android's own audio
 * effects do not go through any of them, so they are left exactly as they are.
 */
@Suppress("unused")
val enableNonXperiaPatch = bytecodePatch(
    name = "Enable non-Xperia ROM support",
    description = "Makes Xperia Music installable and usable on phones from other makers.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SONY_MUSIC)
    dependsOn(supportedVersionCheck(COMPATIBILITY_SONY_MUSIC))
    dependsOn(manifestPatch)

    execute {
        ApiAvailabilityFingerprint.method.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
        DlnaSupportFingerprint.method.addInstructions(0, "const/4 v0, 0x0\nreturn v0")

        val factories = PlayerServiceFactoryFingerprint.matchAll()
        // Measured against 9.4.17.A.0.11: two overloads, with and without a type.
        if (factories.size != 2) throw PatchException("expected 2 player factories, found ${factories.size}")
        for (match in factories) {
            match.method.addInstructions(0, "const/4 v0, 0x0\nreturn-object v0")
        }

        log.info("Sony-only APIs answered as unavailable")
    }
}
