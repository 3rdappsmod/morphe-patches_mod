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
import app.morphe.patcher.patch.resourcePatch
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
 * New documents came out in Chinese faces (宋体 for Word and Excel, an empty east-asian face for PowerPoint) that carry
 * no Hangul glyphs, so Korean text was drawn as boxes and fragments ("ㅋㅋ 6 大 66 ㅋ"). The faces are baked into the
 * document templates the app copies for a new file, so each template's default is rewritten to a face the phone has
 * for its language. Korean phones get the face Android registers for Hangul; other phones keep their own default.
 */
private val documentTemplatesPatch = resourcePatch(
    description = "Gives new documents a default font that matches the phone's language.",
) {
    execute {
        val face = defaultFaceFor(java.util.Locale.getDefault())
        val language = java.util.Locale.getDefault().toLanguageTag()

        val word = get("assets/template/word/template.dotx")
        rewriteTemplate(word, "word/styles.xml") { xml ->
            xml.replace("w:eastAsia=\"宋体\"", "w:eastAsia=\"$face\"")
                .replace("w:eastAsia=\"zh-CN\"", "w:eastAsia=\"$language\"")
        }

        val excel = get("assets/template/excel/template.xltx")
        rewriteTemplate(excel, "xl/styles.xml") { xml ->
            xml.replace("<name val=\"宋体\"/>", "<name val=\"$face\"/>")
        }

        val powerpoint = get("assets/template/ppt/template.potx")
        rewriteTemplate(powerpoint, "ppt/theme/theme1.xml") { xml ->
            xml.replace("<a:ea typeface=\"\"", "<a:ea typeface=\"$face\"")
        }

        log.info("document templates default to $face for $language")
    }
}

/**
 * The face to write into a new document for [locale]. The names are resolved by Android at draw time, and it fills in
 * a face that has the script from its own per-language fallback chain, so a family name that only covers Latin still
 * draws Hangul: "sans-serif" is Roboto plus the phone's Korean fallback (Noto Sans CJK on this build). Names a phone
 * does not carry fall back to the default face instead of failing, but a family it does carry is used directly, which
 * keeps the document's font name meaningful when it is opened elsewhere.
 */
private fun defaultFaceFor(locale: java.util.Locale): String = when (locale.language) {
    "ko" -> "sans-serif"
    "zh" -> locale.let { if (it.country == "TW" || it.country == "HK") "sans-serif" else "宋体" }
    "ja" -> "sans-serif"
    else -> "sans-serif"
}

/** Rewrites one part of a zipped template, leaving every other part byte-for-byte as it was. */
private fun rewriteTemplate(
    file: java.io.File,
    part: String,
    rewrite: (String) -> String,
) {
    if (!file.isFile) return
    val rewritten = java.io.ByteArrayOutputStream()
    java.util.zip.ZipFile(file).use { zip ->
        java.util.zip.ZipOutputStream(rewritten).use { out ->
            for (item in zip.entries()) {
                val bytes = zip.getInputStream(item).use { it.readBytes() }
                val replacement = if (item.name == part) {
                    rewrite(String(bytes, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                } else {
                    bytes
                }
                out.putNextEntry(java.util.zip.ZipEntry(item.name))
                out.write(replacement)
                out.closeEntry()
            }
        }
    }
    file.writeBytes(rewritten.toByteArray())
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
    dependsOn(manifestPatch)
    dependsOn(documentTemplatesPatch)
    extendWith(COLOROS_DOCUMENTS_EXTENSION)

    execute {
        val receivers = exportDynamicReceivers(RUNTIME)
        log.info("registerReceiver calls through ColorOS: $receivers")
        // Measured against 17.1.32: 44 calls.
        if (receivers < 30) throw PatchException("only $receivers registerReceiver calls found")

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
    method.replaceInstruction(chinese, "const-string/jumbo v$register, \"개  |  \"")
    log.info("save sheet folder counter changed from 项 to 개")
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
