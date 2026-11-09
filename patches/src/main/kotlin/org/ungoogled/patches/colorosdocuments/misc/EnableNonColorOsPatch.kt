package org.ungoogled.patches.colorosdocuments.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.ungoogled.patches.coloros.exportDynamicReceivers
import org.ungoogled.patches.colorosdocuments.ColorOsDocumentsConstants.COMPATIBILITY_COLOROS_DOCUMENTS
import org.ungoogled.patches.shared.supportedVersionCheck
import org.w3c.dom.Element

internal const val COLOROS_DOCUMENTS_EXTENSION = "extensions/colorosdocuments.mpe"

private const val RUNTIME = "Lorg/ungoogled/colorosdocuments/ColorOS;"

private val log = java.util.logging.Logger.getLogger("OnePlusDocuments")

/** The app requests a few OnePlus-only permissions; an ordinary install is refused them. */
private val manifestPatch = resourcePatch(
    description = "Drops the OnePlus-only permissions the app requests.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            val usesPermission = manifest.getElementsByTagName("uses-permission")
            val onePlusOnly = (0 until usesPermission.length)
                .map { usesPermission.item(it) as Element }
                .filter { it.getAttribute("android:name").startsWith("com.oplus.permission") }
            if (onePlusOnly.isEmpty()) throw PatchException("no OnePlus permission in the manifest")
            for (element in onePlusOnly) root.removeChild(element)
        }
    }
}

/**
 * Rewrites a new document's template for the phone's own language.
 *
 * The templates ship with Chinese defaults: Word and Excel name 宋体, PowerPoint leaves its east-asian slot empty and
 * repeats 宋体 in every slide master and layout, and all three tag their runs "zh-CN". On a phone in another language
 * the app then either draws text in a face it does not have, or shapes it with the wrong east-asian face and gets the
 * widths wrong (overlapping, cramped letters).
 *
 * This has to happen on the phone, not here: the language is the phone's, and the machine that builds the patch is
 * often in another one. An earlier revision rewrote the templates at build time from `Locale.getDefault()`, so a bundle
 * built on an English machine tagged every Korean document "en" and shaped Hangul with the Latin face.
 *
 * Every template the app copies for a new file goes through `FileHelper.getTempFilePath`, which extracts it from the
 * assets to a temp file and returns that path. The extension's `Templates.localize` rewrites the extracted file for
 * `Locale.getDefault()` before the editor reads it, so one APK serves every language, and the patch itself stays out of
 * the templates. PDF and TXT templates are not Office packages and are left alone.
 */
private val documentTemplatesPatch = resourcePatch(
    description = "Gives new documents a default font and language that match the phone's language.",
) {
    execute {
        // The rewrite runs on the phone; there is nothing to change in the packaged templates. The patch stays so the
        // dependency list keeps documenting what it does, and so an APK built by an older patch still gets rewritten.
        log.info("document templates are localised on the phone, by Locale.getDefault()")
    }
}

/** `FileHelper.getTempFilePath(Context, String, String)` — where every new-document template is extracted. */
private object TemplateCopyFingerprint : Fingerprint(
    definingClass = "Lemo/main/FileHelper;",
    name = "getTempFilePath",
    returnType = "Ljava/lang/String;",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "Ljava/lang/String;"),
)

/**
 * Sends every template path `getTempFilePath` returns through the extension, which rewrites the extracted Office package
 * for the phone's language. The return value is replaced in place at each of the method's exits: the two early returns
 * (a path already outside the app's temp directory, and the extracted file) and the two fallbacks.
 */
private fun app.morphe.patcher.patch.BytecodePatchContext.localizeTemplatesAtRuntime() {
    val method = TemplateCopyFingerprint.method
    val exits = method.instructions.withIndex()
        .filter { it.value.opcode == com.android.tools.smali.dexlib2.Opcode.RETURN_OBJECT }
        .sortedByDescending { it.index }
        .toList()
    if (exits.size < 2) throw PatchException("template copy has ${exits.size} returns, expected at least 2")

    for ((index, instruction) in exits) {
        val register = (instruction as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA
        // One instruction in, one out, so labels and switch payloads keep pointing at the same place.
        method.replaceInstruction(
            index,
            "invoke-static {v$register}, $RUNTIME->localizeTemplate(Ljava/lang/String;)Ljava/lang/String;",
        )
        method.addInstructions(index + 1, "move-result-object v$register\nreturn-object v$register")
    }
    log.info("new-document templates localised on the phone at ${exits.size} exits")
}

/** A float's IEEE-754 bits as a smali `const/high16` literal. */
private fun Int.toHexFloat(): String = "0x%08x".format(this)

private object ViewBufferSizeFingerprint : Fingerprint(
    definingClass = "Lemo/wp/control/EWord;",
    name = "setViewBufferSize",
    returnType = "Z",
    parameters = listOf("Z"),
)

private object ViewBufferIntervalFingerprint : Fingerprint(
    definingClass = "Lemo/main/ViewBufferManager;",
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
)

/**
 * The editor's page buffer: its size and its refresh interval. Both are constants in the app, chosen for a 60 Hz
 * 1080p phone; a denser, faster display wants a smaller buffer drawn more often.
 */
private fun app.morphe.patcher.patch.BytecodePatchContext.tuneViewBuffer(
    scale: String,
    interval: Int,
) {
    // Nothing to do at the app's own values: leaving both alone keeps the build byte-identical there.
    if (scale == "stock" && interval == 17) {
        log.info("page buffer left at the app's own size and interval")
        return
    }

    // Width is a float multiply (const/high16 1.5f); height is an integer multiply (mul-int/lit8 3).
    val widthFactor = when (scale) {
        "small" -> 1.25f
        "half" -> 1.0f
        else -> 1.5f
    }
    val heightMultiply = when (scale) {
        "small" -> "mul-int/lit8 v0, v0, 0x2"
        "half" -> "mul-int/lit8 v0, v0, 0x3\ndiv-int/lit8 v0, v0, 0x2"
        else -> null
    }

    val setSize = ViewBufferSizeFingerprint.method
    val instructions = setSize.instructions

    val width = instructions.indexOfFirst {
        it.opcode == com.android.tools.smali.dexlib2.Opcode.CONST_HIGH16 && isFloatConst(it, 1.5f)
    }
    val height = instructions.indexOfFirst {
        it.opcode == com.android.tools.smali.dexlib2.Opcode.MUL_INT_LIT8 &&
            (it as com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction).narrowLiteral == 3
    }
    if (widthFactor != 1.5f && width < 0) {
        throw PatchException("page buffer width no longer multiplies by 1.5 (index=$width)")
    }
    if (heightMultiply != null && height < 0) {
        throw PatchException("page buffer height no longer multiplies by 3 (index=$height)")
    }

    if (widthFactor != 1.5f) {
        val register = (instructions[width] as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction)
            .registerA
        setSize.replaceInstruction(width, "const/high16 v$register, ${widthFactor.toRawBits().toHexFloat()}")
    }
    if (heightMultiply != null) {
        setSize.replaceInstruction(height, heightMultiply.replace("\n", "\n                "))
    }
    log.info("page buffer set to ${widthFactor}x wide (${heightMultiply ?: "x3 tall"})")

    if (interval != 17) {
        // ViewBufferManager.<clinit>: PAINT_TIME_DURATION = round(16.6667f).
        val clinit = ViewBufferIntervalFingerprint.method
        val index = clinit.instructions.indexOfFirst {
            it.opcode == com.android.tools.smali.dexlib2.Opcode.CONST && isFloatConst(it, 16.6667f)
        }
        if (index < 0) throw PatchException("buffer refresh interval is no longer 16.6667f")
        val register = (clinit.instructions[index] as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction)
            .registerA
        clinit.replaceInstruction(index, "const v$register, ${interval.toFloat().toRawBits().toHexFloat()}")
        log.info("page buffer refresh interval set to ${interval}ms")
    }
}

/**
 * Whether [instruction] loads [value] as a float constant. `const/high16` keeps only the top 16 bits of the literal
 * and dexlib2 widens it back, while `const` carries the whole bit pattern; both end up comparable as floats.
 */
private fun isFloatConst(
    instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction,
    value: Float,
): Boolean {
    val narrow = (instruction as? com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction)
        ?.narrowLiteral ?: return false
    // The app carries 16.6667f, which neither the decimal literal nor a bit-for-bit compare reproduces exactly.
    return kotlin.math.abs(Float.fromBits(narrow) - value) < 0.01f
}

/**
 * The split-screen helper every document activity starts: it builds ColorOS's MultiWindowTrigger in onCreate, and the
 * class only exists on ColorOS. Off ColorOS that throws NoClassDefFoundError in every document process (PDF, TXT,
 * spreadsheet, presentation), so the document itself never opens. Answering "not supported" is what the app already
 * does for a phone without split screen: the editor opens, only the split-screen button is gone.
 */
private object SplitScreenFingerprint : Fingerprint(
    definingClass = "Landes/oplus/documentsreader/core/common/splitscreen/SlitsScreenHelper;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroidx/lifecycle/LifecycleOwner;"),
)

/**
 * Whether the app offers a document conversion (Office to PDF and the eight directions it asks ColorOS's conversion
 * service for). Off ColorOS the service is missing, so the app lists the entry and the conversion then fails; the
 * gateway answers "not supported" and the entry is not offered.
 */
private object FormatConvertFingerprint : Fingerprint(
    definingClass = "Landes/oplus/documentsreader/exposetoyozo/FormatConvertApi;",
    name = "isSupportConvert",
    returnType = "Z",
    parameters = listOf("I"),
)

/**
 * The keyboard connection the Office editors (Word, spreadsheet, presentation) type through. It rebuilds its whole text
 * buffer on every edit, which is fine when the IME commits a syllable at a time but destroys a Hangul syllable that is
 * still being composed: the first jamo lands, the rest is cleared, and "사람" arrives as "ㅅㅏ람". The platform base
 * class keeps the composing region intact, so the buffer reset has to be skipped while a composing call is in flight.
 */
private val stInputConnection = "Lemo/simpletext/control/t;"
private val imeDepthField = "paganiImeDepth"

private object StInputConnectionResetFingerprint : Fingerprint(
    definingClass = stInputConnection,
    name = "a",
    returnType = "V",
    parameters = emptyList(),
)

/**
 * The AOSP save sheet the app falls back to when the ColorOS file manager is absent. On a phone the only way into it is
 * the branches of lambda$initSubModule$3; the ColorOS provider branch is taken whenever the app thinks the provider is
 * there, and off ColorOS that provider answers "not available" (or is missing), so saving ends with "no file manager".
 * Sending every request straight to the AOSP sheet keeps the file name, type and callbacks.
 */
private object SaveEntryFingerprint : Fingerprint(
    definingClass = "Lcom/yozo/main/MainAppHelper;",
    name = "lambda\$initSubModule\$3",
    returnType = "V",
    parameters = listOf(
        "Lcom/yozo/io/model/FileSaveRequestInfo;",
        "Lcom/yozo/io/callback/FileSaveInfoPickCallback;",
        "Lcom/yozo/io/callback/PickFolderCase;",
    ),
)

private const val LEGACY_EXPORT =
    "Lcom/yozo/main/MainAppHelper;->legacyExport(" +
        "Lcom/yozo/io/model/FileSaveRequestInfo;" +
        "Lcom/yozo/io/callback/FileSaveInfoPickCallback;" +
        "Lcom/yozo/io/callback/PickFolderCase;)V"

/**
 * The Manage tab list builder. The private vault ("개인 금고") row is added first, guarded by ColorOS's encryption
 * feature check; on this ROM the vault cannot open, so the row is a dead end.
 */
private object ManageListFingerprint : Fingerprint(
    definingClass = "Landes/oplus/documentsreader/launcher/fragment/FolderTabFragment\$f;",
    name = "a",
    returnType = "V",
    parameters = listOf("Landroid/widget/LinearLayout;"),
)

/**
 * The Tools tab and the New Document sheet share one list builder: ToolDataHelper.d(Context, EnumWay, boolean). Its
 * conversion entries (the "File conversion" group and its PDF / Word / Excel / PowerPoint directions) come from
 * ColorOS's conversion service, which does not exist here, so they are filtered out of the finished list. Filtering
 * the result rather than the inputs keeps the list the app builds intact and only hides what cannot work.
 */
private object ToolListFingerprint : Fingerprint(
    definingClass = "Landes/oplus/documentsreader/impl/helper/ToolDataHelper;",
    name = "d",
    returnType = "Ljava/util/ArrayList;",
    parameters = listOf(
        "Landroid/content/Context;",
        "Landes/oplus/documentsreader/doctools/enumer/EnumWay;",
        "Z",
    ),
)

/**
 * The stock tab bar inflates its items from a menu XML and then walks them. Removing the Tools entry there is what
 * actually takes the button out: the menu the app hands the bar elsewhere is not what draws it.
 */
private object TabBarInflateFingerprint : Fingerprint(
    definingClass = "Lcom/coui/appcompat/bottomfloatingnavigationbar/COUIBottomFloatingNavigationBar;",
    name = "<init>",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;", "I"),
)

/**
 * The save sheet's folder row shows "N items | date". The item suffix is a hard-coded Chinese "项" in the adapter, so
 * it stays Chinese on every locale. The Korean counter word is "개".
 */
private object SaveFolderCountFingerprint : Fingerprint(
    definingClass = "Lcom/yozo/ui/dialog/save/LocalFileAdapter;",
    name = "onBindViewHolder",
    returnType = "V",
    parameters = listOf("Lcom/yozo/ui/dialog/save/LocalFileAdapter\$ViewHolder;", "I"),
)

/**
 * The Word editor's selection routine: builds the word range under the finger and hands it to the highlighter.
 */
private object SelectTextFingerprint : Fingerprint(
    definingClass = "Lemo/simpletext/control/d0;",
    name = "selectText",
    returnType = "V",
    parameters = listOf("F", "F"),
)

/**
 * The app's own font-name resolver: `l.P(String)` maps the Windows/GDI names a document carries ("Droid Sans",
 * "SansSerif", "Helvetica") to the Android family of the same shape, and answers null for everything else. `l.Q(style,
 * name)` then resolves that family with `Typeface.create`, falls back to a font file the phone's font scanner found,
 * and finally to `Typeface.DEFAULT`.
 *
 * "sans-serif", the name the templates are rewritten to, is not in P's list. So a document that asks for sans-serif
 * takes the last branch: the face the phone happens to register as DEFAULT. On a phone whose default face is a
 * user-installed font, that face is used for the glyphs while the widths the app lays text out with come from the
 * document's family name elsewhere, and the two disagree: the letters are drawn at one advance and spaced at another,
 * which reads as cramped, overlapping text.
 *
 * A document written on a Chinese phone names 宋体 in every run instead, and P answers null for that too -- but the
 * width table (`s.c`) *does* know it, so the app lays the text out with the Chinese face's advances while drawing it
 * with the phone's default. That is the overlap seen on the OnePlus phone and not on the Xiaomi one.
 *
 * Both halves are answered by `FontCompat`: P gets the family to draw with, and `s.c` gets the family to look a width
 * table up for. They answer the same thing, so the drawn glyphs and the layout widths are always the same font.
 */
private object FontFamilyResolveFingerprint : Fingerprint(
    definingClass = "Lemo/commonkit/font/l;",
    name = "P",
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;"),
)

/** `s.c(String)` — the hand-built width tables, keyed by family. */
private object FontWidthTableFingerprint : Fingerprint(
    definingClass = "Lemo/commonkit/font/s;",
    name = "c",
    returnType = "Lemo/commonkit/font/s;",
    parameters = listOf("Ljava/lang/String;"),
)

private fun app.morphe.patcher.patch.BytecodePatchContext.keepSansSerifFamily() {
    val method = FontFamilyResolveFingerprint.method
    if (method.instructions.isEmpty()) throw PatchException("font family resolver is empty")
    // The label sits inside the added block and jumps over it, so execution continues with the method's own first
    // instruction. The trailing nop is the label's landing spot; it is reached only when the guard does not return.
    method.addInstructions(
        0,
        """
            if-eqz p0, :pagani_font_continue
            invoke-static {p0}, $RUNTIME->fontFamily(Ljava/lang/String;)Ljava/lang/String;
            move-result-object v0
            if-eq v0, p0, :pagani_font_continue
            return-object v0
            :pagani_font_continue
            nop
        """,
    )
    log.info("missing document faces answered with the phone's sans-serif")
}

/**
 * `getMonospacedCharWidth(char, d, int)` — the fixed-width path a compatibility jamo reaches.
 *
 * `o.v()` answers `0x60000000` for U+3100..DFFF and U+2E80..2FFF alike, so CJK ideographs and Hangul share one class
 * and one cached advance in the `monospacedWidth` field.
 */
private object MonospacedWidthFingerprint : Fingerprint(
    definingClass = "Lemo/commonkit/font/EIOFontMetrics;",
    name = "getMonospacedCharWidth",
    returnType = "F",
    parameters = listOf("C", "Lemo/simpletext/view/d;", "I"),
)

/**
 * Gives the Word font metrics individual jamo advances and prevents jamo from seeding the CJK cache.
 *
 * `o.v()` classifies U+3100..DFFF as one class, which covers both CJK ideographs and Hangul, and
 * `getMonospacedCharWidth` keeps a single advance for that class in `monospacedWidth`. The first character to arrive
 * seeds it and every later character is laid out with that value. Measured on the device: ㅂ seeds 25.0, then ㄴ
 * (26.0) and ㄹ (28.0) are still given 25.0, so a composing jamo run is drawn narrower than its glyphs and the letters
 * pile up.
 *
 * Two hooks, both using registers that are already live where they are inserted:
 *
 *  - At `:goto_0` the shared advance is read into a float register and then corrected by `b(width, d)` and the
 *    letter-spacing cases below. Replacing that read keeps every existing correction for jamo, so this changes the
 *    advance and nothing else. `p0` is still `this` and `p1` still the char there; both are overwritten later in the
 *    body, which is why a hook at a `return` broke the verifier in an earlier build.
 *  - Right after the seed write, a jamo seed is undone. Without this the first jamo of a document would define the
 *    advance that ideographs and precomposed syllables inherit.
 */
private fun app.morphe.patcher.patch.BytecodePatchContext.fixHangulJamoWidths() {
    val method = MonospacedWidthFingerprint.method
    val instructions = method.instructions

    fun fieldName(instruction: com.android.tools.smali.dexlib2.builder.BuilderInstruction): String? =
        ((instruction as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
            ?.reference as? com.android.tools.smali.dexlib2.iface.reference.FieldReference)?.name

    // The first read is the `monospacedWidth == -2.0f` test; the second is `:goto_0`, which supplies the width.
    val reads = instructions.withIndex()
        .filter {
            it.value.opcode == com.android.tools.smali.dexlib2.Opcode.IGET &&
                fieldName(it.value) == "monospacedWidth"
        }
        .map { it.index }
    if (reads.size < 2) {
        throw PatchException("getMonospacedCharWidth no longer reads monospacedWidth twice (found ${reads.size})")
    }
    val read = reads[1]
    val width = (instructions[read] as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerA
    method.addInstructions(
        read + 1,
        """
            invoke-static {p0, p1, v$width}, $RUNTIME->jamoWidth(Ljava/lang/Object;CF)F
            move-result v$width
        """.trimIndent(),
    )
    log.info("compatibility jamo measured individually at index $read (width=v$width)")

    // The seed write is `iput monospacedWidth` followed by `iput-char monospacedWidthC`. Every such pair has to be
    // covered: the body has three (the initial seed and two rewrites for the 0xA4B3 case), and hooking only the first
    // left the cache seeded by a jamo at another site -- measured as cached=33.0 where the jamo itself is 28.0.
    val seeds = instructions.withIndex()
        .filter {
            it.value.opcode == com.android.tools.smali.dexlib2.Opcode.IPUT_CHAR &&
                fieldName(it.value) == "monospacedWidthC"
        }
        .map { it.index }
        .toList()
    if (seeds.isEmpty()) {
        throw PatchException("getMonospacedCharWidth no longer records which character seeded the advance")
    }
    // Descending order so each insertion keeps the indexes of the ones still to be placed valid.
    for (seed in seeds.sortedDescending()) {
        method.addInstructions(
            seed + 1,
            "invoke-static {p0, p1}, $RUNTIME->clearJamoSeed(Ljava/lang/Object;C)V",
        )
    }
    log.info("jamo seeds of the shared advance undone at ${seeds.size} sites: $seeds")
}

/** Spreadsheet metrics override the base method, so the Word hook cannot cover this body. */
private object SsMonospacedWidthFingerprint : Fingerprint(
    definingClass = "Lemo/ss/plugins/editor/font/SSFontMetrics;",
    name = "getMonospacedCharWidth",
    returnType = "F",
    parameters = listOf("C", "Lemo/simpletext/view/d;", "I"),
)

/**
 * The spreadsheet keeps no monospacedWidthC: clear its inherited shared field after a jamo seed.
 * The read hook also measures UNSET, so clearing cannot leak a negative advance to getRealWidth.
 * Registers are the original locals (v0 metrics, v3 char, v4 float), before parameters are reused.
 */
private fun app.morphe.patcher.patch.BytecodePatchContext.fixSpreadsheetJamoWidths() {
    val method = SsMonospacedWidthFingerprint.method
    val instructions = method.instructions
    // Pinned 17.1.32: p0=v20, p1=v21, p2=v22, p3=v23. Never encode them in a 35c invoke.
    if (method.implementation!!.registerCount != 24 ||
        instructions[0].opcode != com.android.tools.smali.dexlib2.Opcode.MOVE_OBJECT_FROM16 ||
        (instructions[0] as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerA != 0) {
        throw PatchException("spreadsheet metrics register layout changed")
    }
    fun widthField(instruction: com.android.tools.smali.dexlib2.builder.BuilderInstruction): Boolean {
        val field = (instruction as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
            ?.reference as? com.android.tools.smali.dexlib2.iface.reference.FieldReference
        return field?.definingClass == "Lemo/commonkit/font/EIOFontMetrics;" &&
            field.name == "monospacedWidth" && field.type == "F"
    }
    val reads = instructions.indices.filter {
        instructions[it].opcode == com.android.tools.smali.dexlib2.Opcode.IGET && widthField(instructions[it])
    }
    val seeds = instructions.indices.filter {
        instructions[it].opcode == com.android.tools.smali.dexlib2.Opcode.IPUT && widthField(instructions[it])
    }
    if (reads.size != 2 || seeds.size != 1) throw PatchException("spreadsheet advance cache shape changed")
    val read = reads[1]
    val load = instructions[read] as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
    val seed = instructions[seeds.single()] as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
    val conversion = instructions[read + 1] as? BuilderInstruction35c
    if (load.registerA != 4 || load.registerB != 0 || seed.registerA != 4 || seed.registerB != 0 ||
        conversion?.reference?.toString() !=
        "Lemo/ss/plugins/editor/font/SSFontMetrics;->getRealWidth(FLemo/simpletext/view/d;)F" ||
        conversion.registerC != 0 || conversion.registerD != 4 || conversion.registerE != 1) {
        throw PatchException("spreadsheet advance registers changed")
    }
    // Insert from the higher index first. No register allocation and no writes to live object/char locals.
    method.addInstructions(read + 1, """
        invoke-static {v0, v3, v4}, $RUNTIME->jamoWidth(Ljava/lang/Object;CF)F
        move-result v4
    """)
    method.addInstructions(seeds.single() + 1,
        "invoke-static {v0, v3}, $RUNTIME->clearJamoSeed(Ljava/lang/Object;C)V")
    log.info("spreadsheet jamo advances and inherited CJK seed protected")
}

/**
 * Sends a Chinese face's width table lookup to "sans-serif", which `s.c` does not know, so it answers null and
 * `EIOFontMetrics.getReferenceCharWidth` measures the face the app actually draws. Without this the drawn font and the
 * measured font disagree, which is what makes the letters overlap.
 */
private fun app.morphe.patcher.patch.BytecodePatchContext.dropMissingFontWidthTables() {
    val method = FontWidthTableFingerprint.method
    if (method.instructions.isEmpty()) throw PatchException("width table lookup is empty")
    // p0 is the family; the method's own first instruction null-checks it, so the replacement happens before that.
    method.addInstructions(
        0,
        """
            invoke-static {p0}, $RUNTIME->widthTableFamily(Ljava/lang/String;)Ljava/lang/String;
            move-result-object p0
        """,
    )
    log.info("missing document faces measured with the drawn font, not a hand-built table")
}

/**
 * d0.selectText builds the word range and hands it to addHighLight, but the branch the Word editor takes for a plain
 * double-tap neither repaints nor invalidates: the range exists in the highlighter and is never drawn, so no highlight
 * and no handles appear. The other branch (a selection that grows past a line end) ends with postInvalidate, which is
 * what this adds to the first one. Nothing else about the selection changes, and the call is a plain View method, so a
 * run that already repaints simply repaints once more.
 */
private fun app.morphe.patcher.patch.BytecodePatchContext.repaintAfterSelection() {
    val selectText = SelectTextFingerprint.method

    // Every addHighLight call inside selectText. The last one is the growing-selection branch, which already
    // invalidates on its own; the earlier ones are the paths that return without repainting.
    val highlightCalls = selectText.instructions.withIndex()
        .filter {
            it.value.opcode == com.android.tools.smali.dexlib2.Opcode.INVOKE_VIRTUAL &&
                ((it.value as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                    ?.reference as? com.android.tools.smali.dexlib2.iface.reference.MethodReference)
                    ?.name == "addHighLight"
        }
        .map { it.index }
        .sorted()
        .toList()
    if (highlightCalls.size < 2) throw PatchException("expected two addHighLight calls, found ${highlightCalls.size}")

    // Add the repaint after every call except the last, which already ends with postInvalidate.
    for (index in highlightCalls.dropLast(1).sortedDescending()) {
        selectText.addInstructions(index + 1, "invoke-virtual {v0}, Landroid/view/View;->postInvalidate()V")
    }
    log.info("text selection repaints on ${highlightCalls.size - 1} branches")
}

/**
 * ColorOS Documents on a phone from another maker.
 *
 * The app reads OnePlus framework classes for its window, blur and graphics effects, its storage wrappers and its
 * telemetry, and registers broadcast receivers at run time. The extension carries stand-ins for the framework classes
 * it uses (generated by .github/scripts/coloros-standins), so off ColorOS they answer "absent" instead of throwing.
 *
 * Known limits, from the port this patch is based on: the app's CJK fonts are not on other phones (Hangul spacing is
 * affected), and the AI and cloud features stay off.
 */
@Suppress("unused")
val enableNonColorOsPatch = bytecodePatch(
    name = "Enable non-ColorOS ROM support",
    description = "Makes ColorOS Documents installable and usable on non-ColorOS ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_COLOROS_DOCUMENTS)
    dependsOn(supportedVersionCheck(COMPATIBILITY_COLOROS_DOCUMENTS))
    dependsOn(manifestPatch)
    dependsOn(documentTemplatesPatch)
    extendWith(COLOROS_DOCUMENTS_EXTENSION)

    val bufferScale by stringOption(
        key = "bufferScale",
        default = "half",
        values = mapOf(
            "Stock (1.5x wide, 3x tall)" to "stock",
            "Smaller (1.25x wide, 2x tall)" to "small",
            "Half (1.0x wide, 1.5x tall)" to "half",
        ),
        title = "Page buffer size",
        description = "The Word editor draws the page into an off-screen bitmap and blits it on every scroll, so a " +
            "large buffer costs memory and background drawing time. Smaller can scroll more smoothly on " +
            "high-resolution screens, at the cost of redrawing more during a fast fling. Stock is the app's own value.",
        required = true,
    )

    val bufferInterval by intOption(
        key = "bufferInterval",
        default = 8,
        values = mapOf("60 Hz (17 ms)" to 17, "90 Hz (11 ms)" to 11, "120 Hz (8 ms)" to 8),
        title = "Page buffer refresh interval",
        description = "How often the off-screen page is redrawn while scrolling. Match the phone's display refresh " +
            "rate: 17 ms is enough for 60 Hz, 8 ms for 120 Hz.",
        required = true,
    )

    execute {
        val receivers = exportDynamicReceivers(RUNTIME)
        log.info("registerReceiver calls through ColorOS: $receivers")
        // Measured against 17.1.32: 44 calls.
        if (receivers < 30) throw PatchException("only $receivers registerReceiver calls found")

        // The Word editor's page buffer: sized and refreshed for a 60 Hz 1080p phone out of the box.
        tuneViewBuffer(bufferScale!!, bufferInterval!!)

        // New-document templates: rewritten for the phone's language as the app extracts them.
        localizeTemplatesAtRuntime()

        // Split screen is ColorOS's; the app treats a phone without it as normal (the editor still opens).
        SplitScreenFingerprint.method.addInstructions(0, "return-void")
        log.info("split-screen helper disabled")

        // The conversion service is ColorOS's too; without it the entry is not offered.
        FormatConvertFingerprint.method.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
        log.info("document conversion answered as unavailable")

        // Hangul mid-composition: keep the buffer while the IME is composing.
        holdComposingText()
        log.info("keyboard composing text protected")

        // Saving: use the built-in sheet instead of the ColorOS file manager.
        useBuiltInSavePicker()
        log.info("document saving uses the built-in picker")

        // Manage tab: drop the ColorOS private vault row.
        dropVaultRow()
        log.info("private vault row removed")
        // Tools tab / New Document: drop the conversion entries the ColorOS service would have served.
        dropConversionTools()
        log.info("document conversion entries removed from the tool list")

        // The Tools tab itself: off ColorOS every row in it is either a dead conversion or a ColorOS-only scanner,
        // so the tab leads nowhere. Remove it from the tab bar and from the page list together.
        dropToolTab()
        log.info("Tools tab removed")

        // The save sheet: the folder row's counter word is hard-coded Chinese.
        localizeFolderCount()
        log.info("save sheet folder counter localised")

        // Word: a selection built by a double tap was never painted.
        repaintAfterSelection()

        // Font engine: keep "sans-serif" as its own family, and measure Chinese document faces with the drawn font.
        keepSansSerifFamily()
        dropMissingFontWidthTables()
        fixHangulJamoWidths()
        fixSpreadsheetJamoWidths()

        log.info("ColorOS framework classes answered by the extension")
    }
}

/**
 * The IME overrides below mirror the port this patch follows. The platform base class accepts the composing text and
 * keeps it in its own composing region, which is all the editors need; the reset is only the app clearing its mirror
 * buffer. Raising a counter around the platform call and skipping the reset while it is raised keeps that mirror
 * update from wiping the syllable the IME has not committed yet.
 */
private fun app.morphe.patcher.patch.BytecodePatchContext.holdComposingText() {
    val depth = "$stInputConnection->$imeDepthField:I"
    val classDef = mutableClassDefBy(stInputConnection)

    classDef.fields.add(
        ImmutableField(
            stInputConnection,
            imeDepthField,
            "I",
            AccessFlags.PRIVATE.value,
            null,
            null,
            null,
        ).toMutable(),
    )

    // While a composing call is on the stack, skip the buffer reset. The method keeps p0 above v15, so iget cannot
    // name it directly: the app's own first instruction copies p0 into a low register, and the guard goes after it.
    val reset = StInputConnectionResetFingerprint.method
    val head = reset.instructions.take(3).joinToString(" | ") { "${it.opcode} ${it}" }
    log.info("input connection reset starts with: $head")
    val first = reset.instructions.firstOrNull()
    if (first == null || first.opcode != com.android.tools.smali.dexlib2.Opcode.MOVE_OBJECT_FROM16) {
        throw PatchException("input connection reset no longer starts by copying p0: $head")
    }
    val local = (first as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerA

    // Skip the reset while composing. The continue label is an instruction that already exists, so it survives the
    // insertion without being an inline label the compiler cannot place.
    val continueAt = reset.instructions[1]
    reset.addInstructionsWithLabels(
        1,
        """
            iget v0, v$local, $depth
            if-eqz v0, :pagani_reset_allowed
            return-void
        """,
        app.morphe.patcher.util.smali.ExternalLabel("pagani_reset_allowed", continueAt),
    )

    fun override(
        name: String,
        parameters: List<String>,
        returnType: String,
        call: String,
    ) {
        val methodDef = ImmutableMethod(
            stInputConnection,
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            AccessFlags.PUBLIC.value,
            null,
            null,
            MutableMethodImplementation(6),
        ).toMutable()
        val impl = methodDef.implementation!!

        // depth++
        methodDef.addInstructions(
            0,
            """
                iget v0, p0, $depth
                add-int/lit8 v0, v0, 0x1
                iput v0, p0, $depth
            """,
        )
        val tryStart = impl.instructions.size

        // The platform call and its result are the only instructions inside the try region.
        methodDef.addInstructions(impl.instructions.size, "$call\nmove-result v0")
        val tryEnd = impl.instructions.size

        // depth--
        methodDef.addInstructions(
            impl.instructions.size,
            """
                iget v1, p0, $depth
                add-int/lit8 v1, v1, -0x1
                iput v1, p0, $depth
                return v0
            """,
        )

        // If the platform call throws, the counter must still come down, or the editor's buffer would stay frozen.
        val handler = impl.instructions.size
        methodDef.addInstructions(
            impl.instructions.size,
            """
                move-exception v0
                iget v1, p0, $depth
                add-int/lit8 v1, v1, -0x1
                iput v1, p0, $depth
                throw v0
            """,
        )

        impl.addCatch(
            impl.newLabelForIndex(tryStart),
            impl.newLabelForIndex(tryEnd),
            impl.newLabelForIndex(handler),
        )

        classDef.methods.add(methodDef)
    }

    // Methods a soft keyboard calls while a syllable is being composed.
    override(
        "setComposingText",
        listOf("Ljava/lang/CharSequence;", "I"),
        "Z",
        "invoke-super {p0, p1, p2}, Landroid/view/inputmethod/BaseInputConnection;->setComposingText(Ljava/lang/CharSequence;I)Z",
    )
    override(
        "commitText",
        listOf("Ljava/lang/CharSequence;", "I"),
        "Z",
        "invoke-super {p0, p1, p2}, Landroid/view/inputmethod/BaseInputConnection;->commitText(Ljava/lang/CharSequence;I)Z",
    )
    override(
        "setComposingRegion",
        listOf("I", "I"),
        "Z",
        "invoke-super {p0, p1, p2}, Landroid/view/inputmethod/BaseInputConnection;->setComposingRegion(II)Z",
    )
    override(
        "finishComposingText",
        emptyList(),
        "Z",
        "invoke-super {p0}, Landroid/view/inputmethod/BaseInputConnection;->finishComposingText()Z",
    )
}

private fun app.morphe.patcher.patch.BytecodePatchContext.useBuiltInSavePicker() {
    val method = SaveEntryFingerprint.method
    val count = method.implementation!!.instructions.size
    if (count < 8) throw PatchException("save entry point looks unexpected: only $count instructions")

    // Drop the ColorOS provider path entirely and hand the request to the built-in sheet. The signature stays, so
    // callers and the registered callback are untouched.
    method.removeInstructions(0, count)
    method.addInstructions(
        0,
        """
            invoke-static {p0, p1, p2}, $LEGACY_EXPORT
            return-void
        """,
    )
    log.info("save entry point redirected to the built-in picker")
}

private fun app.morphe.patcher.patch.BytecodePatchContext.dropConversionTools() {
    val method = ToolListFingerprint.method
    val instructions = method.instructions

    // The builder ends with two returns: the non-panel path returns the list util.c built, the panel path returns the
    // list it assembled. A branch label is attached to the return instruction, so the filter must BE that instruction,
    // not sit before it: replacing it keeps every jump aimed at the filter. Work from the last exit backwards so the
    // earlier indices stay valid.
    val exits = instructions.withIndex()
        .filter { it.value.opcode == com.android.tools.smali.dexlib2.Opcode.RETURN_OBJECT }
        .sortedByDescending { it.index }
        .take(2)
        .toList()
    if (exits.size != 2) throw PatchException("expected two tool list exits, found ${exits.size}")

    for ((index, instruction) in exits) {
        val register = (instruction as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction)
            .registerA
        // One instruction in, one out, so the packed-switch payload after this method stays aligned.
        method.replaceInstruction(
            index,
            "invoke-static {v$register}, $RUNTIME->withoutConversion(Ljava/util/ArrayList;)Ljava/util/ArrayList;",
        )
        method.addInstructions(
            index + 1,
            """
                move-result-object v$register
                return-object v$register
            """,
        )
    }
    log.info("tool list conversion entries filtered at both exits")
}

private fun app.morphe.patcher.patch.BytecodePatchContext.dropToolTab() {
    // The tab bar draws whatever the menu XML gives it: COUIBottomFloatingNavigationBar inflates the menu in its
    // constructor, then walks the items. Removing the Tools id right after the inflate is what takes the button out.
    // The pager keeps its three pages, so every j[…] lookup and the page count stay valid.
    val bar = TabBarInflateFingerprint.method
    val instructions = bar.instructions

    val inflate = instructions.indexOfFirst {
        it is com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction &&
            (it.reference as? com.android.tools.smali.dexlib2.iface.reference.MethodReference)?.name == "inflate" &&
            (it.reference as? com.android.tools.smali.dexlib2.iface.reference.MethodReference)
                ?.definingClass == "Landroid/view/MenuInflater;"
    }
    if (inflate < 0) throw PatchException("the tab bar no longer inflates a menu")

    // inflate(int, Menu) is invoke-virtual {menuInflater, xmlId, menu}: the menu is the last argument, registerE.
    // The inflater's own register is free right after the call, and using it avoids touching a register that still
    // holds a reference (reusing p0's v0 would make the verifier reject the constructor).
    val inflateCall = instructions[inflate] as BuilderInstruction35c
    val menuRegister = inflateCall.registerE
    val scratch = inflateCall.registerC
    bar.addInstructions(
        inflate + 1,
        """
            sget v$scratch, Lr1/h;->tab_tool:I
            invoke-virtual {v$menuRegister, v$scratch}, Landroidx/appcompat/view/menu/MenuBuilder;->removeItem(I)V
        """,
    )
    log.info("Tools entry removed from the inflated tab bar menu")
}

private fun app.morphe.patcher.patch.BytecodePatchContext.localizeFolderCount() {
    val method = SaveFolderCountFingerprint.method
    val instructions = method.instructions
    // const-string/jumbo uses format 31c (one register, one string), not 21c.
    val chinese = instructions.indexOfFirst {
        it.opcode == com.android.tools.smali.dexlib2.Opcode.CONST_STRING_JUMBO &&
            ((it as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                ?.reference as? com.android.tools.smali.dexlib2.iface.reference.StringReference)?.string == "项  |  "
    }
    if (chinese < 0) throw PatchException("the Chinese folder counter was not found in the save sheet adapter")

    val register = (instructions[chinese] as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction)
        .registerA
    method.replaceInstruction(chinese, "invoke-static {}, $RUNTIME->folderCountSuffix()Ljava/lang/String;")
    method.addInstructions(chinese + 1, "move-result-object v$register")
    log.info("save sheet folder counter follows the device language")
}

private fun app.morphe.patcher.patch.BytecodePatchContext.dropVaultRow() {
    val method = ManageListFingerprint.method
    val instructions = method.instructions

    // The Manage tab builds its rows in order: the private vault row first, then the recycle bin. The vault row's label
    // comes from the app's string_encrypt_menu resource ("Private Safe"), so start at that load.
    val labelIndex = instructions.indexOfFirst {
        it is BuilderInstruction21c && it.opcode == com.android.tools.smali.dexlib2.Opcode.SGET &&
            it.reference.toString().endsWith("->string_encrypt_menu:I")
    }
    if (labelIndex < 0) throw PatchException("private vault label not found in the Manage tab builder")

    // The row reaches the list a few instructions later, through the only add() before the recycle-bin row. The call
    // returns a boolean nobody reads, so replacing it with a nop leaves the branches and the object intact.
    val addOffset = instructions.drop(labelIndex).indexOfFirst {
        it is BuilderInstruction35c &&
            it.reference.toString() == "Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z"
    }
    if (addOffset < 0) throw PatchException("private vault row is not added to the list")

    method.replaceInstruction(labelIndex + addOffset, "nop")
    log.info("private vault row is no longer added to the Manage tab list")
}
