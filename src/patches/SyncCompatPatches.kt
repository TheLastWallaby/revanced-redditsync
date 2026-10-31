@file:Suppress("unused")
package app.revanced.local.synccompat

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableClassDef
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableField
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.MethodParameter
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableField

private const val EXT = "Lapp/revanced/extension/synccompat/RequestCompat;"
private const val BATCH = "Lapp/revanced/extension/syncforreddit/FixLoadMoreCommentsPatch;"
private const val NETWORK = "Lcom/android/volley/NetworkResponse;"
private const val VOLLEY_ERROR = "Lcom/android/volley/VolleyError;"
private const val SLINK = "Lapp/revanced/extension/synccompat/SafeSLinks;"
private const val SLINK_BASE = "Lapp/revanced/extension/shared/fixes/slink/BaseFixSLinksPatch;"
private const val SLINK_RESULT = "Lapp/revanced/extension/shared/fixes/slink/ResolveResult;"
private const val MEDIA = "Lapp/revanced/extension/synccompat/CommentMedia;"
private const val AWARDS = "Lapp/revanced/extension/synccompat/AwardCompat;"
private const val AWARDS_RESULT = "Lapp/revanced/extension/synccompat/AwardCompat\$Result;"

private val extensionPatch = bytecodePatch {
    extendWith("extensions/synccompat.rve")
}

private fun MutableMethod.resetBody(registers: Int, code: String) {
    implementation = MutableMethodImplementation(registers)
    addInstructions(code)
}

private fun MutableClassDef.addOverride(name: String, returns: String, registers: Int, code: String) {
    if (methods.any { it.name == name && it.parameterTypes.isEmpty() }) {
        throw PatchException("$type already overrides $name; select the supplied base APK, not a previously compatibility-patched copy.")
    }
    val method = MutableMethod(ImmutableMethod(type, name, emptyList<MethodParameter>(), returns,
        AccessFlags.PUBLIC.value, emptySet(), emptySet(), null))
    method.resetBody(registers, code)
    methods.add(method)
}

val secureSyncShareLinks = bytecodePatch(
    name = "Sync: secure Reddit share links",
    description = "Validate HTTPS Reddit origins and share paths before authentication; validate redirects and drop credentials across origins.",
) {
    compatibleWith("com.laurencedawson.reddit_sync"("v23.06.30-13:39"))
    dependsOn(extensionPatch)
    apply {
        val target = classes.singleOrNull { it.type == SLINK_BASE }
            ?: throw PatchException("Expected inherited ReVanced share-link resolver was not found. Use the supported ReVanced-patched Sync 23033 base.")
        val clazz = proxy(target).mutableClass
        fun method(name: String, parameters: List<String>, returns: String): MutableMethod =
            clazz.methods.singleOrNull { it.name == name && it.parameterTypes == parameters && it.returnType == returns }
                ?: throw PatchException("Unsupported inherited share-link resolver method: $name")
        val resolve = method("resolveLink", listOf("Ljava/lang/String;"), SLINK_RESULT)
        if (resolve.implementation?.instructions?.any {
            it is ReferenceInstruction && it.reference.toString().startsWith(SLINK)
        } == true) throw PatchException("Share-link security patch is already installed.")
        val connection = method("getHttpURLConnection", listOf("Ljava/lang/String;", "Ljava/lang/String;"), "Ljava/net/HttpURLConnection;")
        val worker = method("lambda\$resolveLink\$4", listOf("Ljava/lang/String;", "Landroid/content/Context;"), "V")
        // Reuse the inherited Runnable and token/pending-URL lifecycle. Fail on another base layout.
        val runnable = classes.singleOrNull { c ->
            c.superclass == "Ljava/lang/Object;" && c.interfaces.contains("Ljava/lang/Runnable;") &&
                c.methods.any { it.name == "<init>" && it.parameterTypes == listOf(SLINK_BASE, "Ljava/lang/String;", "Landroid/content/Context;") }
        } ?: throw PatchException("Expected inherited share-link background task was not found.")
        for ((name, type) in listOf("accessToken" to "Ljava/lang/String;", "webViewActivityClass" to "Ljava/lang/Class;")) {
            if (clazz.fields.none { it.name == name && it.type == type }) {
                throw PatchException("Unsupported inherited share-link resolver field: $name")
            }
        }
        resolve.resetBody(5, """
            invoke-static {p1}, $SLINK->isShareLink(Ljava/lang/String;)Z
            move-result v0
            if-eqz v0, :continue
            invoke-static {}, Lapp/revanced/extension/shared/Utils;->getContext()Landroid/content/Context;
            move-result-object v0
            if-eqz v0, :continue
            invoke-static {p1}, $SLINK->isBypass(Ljava/lang/String;)Z
            move-result v1
            if-eqz v1, :normal
            invoke-direct {p0, v0, p1}, $SLINK_BASE->openInAppBrowser(Landroid/content/Context;Ljava/lang/String;)V
            goto :handled
            :normal
            iget-object v1, p0, $SLINK_BASE->accessToken:Ljava/lang/String;
            if-nez v1, :background
            invoke-virtual {v0}, Landroid/content/Context;->getPackageManager()Landroid/content/pm/PackageManager;
            move-result-object v1
            invoke-virtual {v0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
            move-result-object v2
            invoke-virtual {v1, v2}, Landroid/content/pm/PackageManager;->getLaunchIntentForPackage(Ljava/lang/String;)Landroid/content/Intent;
            move-result-object v1
            if-eqz v1, :continue
            invoke-virtual {v0, v1}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
            sget-object v0, $SLINK_RESULT->ACCESS_TOKEN_START:$SLINK_RESULT
            return-object v0
            :background
            new-instance v1, ${runnable.type}
            invoke-direct {v1, p0, p1, v0}, ${runnable.type}-><init>(${SLINK_BASE}Ljava/lang/String;Landroid/content/Context;)V
            invoke-static {v1}, Lapp/revanced/extension/shared/Utils;->runOnBackgroundThread(Ljava/lang/Runnable;)V
            :handled
            sget-object v0, $SLINK_RESULT->DO_NOTHING:$SLINK_RESULT
            return-object v0
            :continue
            sget-object v0, $SLINK_RESULT->CONTINUE:$SLINK_RESULT
            return-object v0
        """.trimIndent())
        // Guard the original transport entry point, including any inherited synthetic bridge.
        connection.resetBody(3, """
            invoke-static {p1, p2}, $SLINK->openConnection(Ljava/lang/String;Ljava/lang/String;)Ljava/net/HttpURLConnection;
            move-result-object p1
            return-object p1
        """.trimIndent())
        // Replace the URL-logging worker with validated resolution and the original package-scoped intent.
        worker.resetBody(6, """
            iget-object v0, p0, $SLINK_BASE->accessToken:Ljava/lang/String;
            invoke-static {p1, v0}, $SLINK->resolveOrBypass(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
            move-result-object v0
            if-eqz v0, :done
            invoke-static {v0}, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;
            move-result-object v0
            new-instance v1, Landroid/content/Intent;
            const-string v2, "android.intent.action.VIEW"
            invoke-direct {v1, v2, v0}, Landroid/content/Intent;-><init>(Ljava/lang/String;Landroid/net/Uri;)V
            invoke-virtual {p2}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
            move-result-object v0
            invoke-virtual {v1, v0}, Landroid/content/Intent;->setPackage(Ljava/lang/String;)Landroid/content/Intent;
            const v0, 0x10000000
            invoke-virtual {v1, v0}, Landroid/content/Intent;->setFlags(I)Landroid/content/Intent;
            invoke-virtual {p2, v1}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
            :done
            return-void
        """.trimIndent())
    }
}

val fixSyncGalleries = bytecodePatch(
    name = "Sync: fix Reddit galleries",
    description = "Experimental: load native Reddit galleries through the comment-page API and adapt its listing response.",
) {
    compatibleWith("com.laurencedawson.reddit_sync"("v23.06.30-13:39"))
    dependsOn(extensionPatch)
    apply {
        val target = classes.singleOrNull { it.type == "Ly8/q;" && it.sourceFile == "OAuthGalleryRequest.java" }
            ?: throw PatchException("Expected Sync OAuthGalleryRequest was not found. This patch targets build 23033 only.")
        val clazz = proxy(target).mutableClass
        val url = clazz.methods.single { it.name == "b" && it.parameterTypes == listOf("Ljava/lang/String;") }
        if (url.implementation!!.instructions.any {
            it is ReferenceInstruction && it.reference.toString().contains("synccompat/RequestCompat")
        }) throw PatchException("Gallery compatibility patch is already installed.")
        url.resetBody(1, """
            invoke-static {p0}, $EXT->galleryUrl(Ljava/lang/String;)Ljava/lang/String;
            move-result-object p0
            return-object p0
        """.trimIndent())
        val parse = clazz.methods.single { it.name == "parseNetworkResponse" && it.parameterTypes == listOf(NETWORK) }
        parse.addInstructions(0, """
            invoke-static/range {p1 .. p1}, $EXT->normalizeGallery($NETWORK)$NETWORK
            move-result-object p1
        """.trimIndent())
    }
}

val fixSyncMoreComments = bytecodePatch(
    name = "Sync: load more comments in batches",
    description = "Experimental: batch-load hidden comments through the normal thread endpoint, retaining failed requests for retry.",
) {
    compatibleWith("com.laurencedawson.reddit_sync"("v23.06.30-13:39"))
    dependsOn(extensionPatch)
    apply {
        val target = classes.singleOrNull { it.type == "Ly8/f;" && it.sourceFile == "OAuthCommentsMoreRequest.java" }
            ?: throw PatchException("Expected Sync OAuthCommentsMoreRequest was not found. This patch targets build 23033 only.")
        val clazz = proxy(target).mutableClass
        val parentField = "${clazz.type}->patch_continuationParent:Ljava/lang/String;"
        clazz.fields.add(MutableField(ImmutableField(clazz.type, "patch_continuationParent", "Ljava/lang/String;",
            AccessFlags.PRIVATE.value, null, emptySet(), emptySet())))
        val setter = MutableMethod(ImmutableMethod(clazz.type, "patch_setContinuationParent",
            listOf(com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter("Ljava/lang/String;", null, null)),
            "V", AccessFlags.PUBLIC.value, emptySet(), emptySet(), null))
        setter.resetBody(2, """
            iput-object p1, p0, $parentField
            return-void
        """.trimIndent())
        clazz.methods.add(setter)
        clazz.addOverride("getMethod", "I", 2, """
            const/4 v0, 0x0
            return v0
        """.trimIndent())
        clazz.addOverride("getUrl", "Ljava/lang/String;", 3, """
            invoke-super {p0}, Lcom/android/volley/Request;->getUrl()Ljava/lang/String;
            move-result-object v0
            invoke-super {p0}, Lx8/a;->getParams()Ljava/util/Map;
            move-result-object v1
            invoke-static {v0, v1}, $BATCH->getUrl(Ljava/lang/String;Ljava/util/Map;)Ljava/lang/String;
            move-result-object v0
            return-object v0
        """.trimIndent())
        val adapter = MutableMethod(ImmutableMethod(clazz.type, "patch_getMoreChildrenResponse",
            listOf(com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter(NETWORK, null, null)),
            NETWORK, AccessFlags.PUBLIC.value, emptySet(), emptySet(), null))
        adapter.resetBody(6, """
            invoke-super {p0}, Lcom/android/volley/Request;->getUrl()Ljava/lang/String;
            move-result-object v0
            invoke-super {p0}, Lx8/a;->getParams()Ljava/util/Map;
            move-result-object v1
            invoke-super {p0}, Lx8/a;->getHeaders()Ljava/util/Map;
            move-result-object v2
            iget-object v3, p0, $parentField
            invoke-static {p1, v0, v1, v2, v3}, $BATCH->getMoreChildrenResponse(${NETWORK}Ljava/lang/String;Ljava/util/Map;Ljava/util/Map;Ljava/lang/String;)$NETWORK
            move-result-object v0
            return-object v0
        """.trimIndent())
        clazz.methods.add(adapter)
        val parse = clazz.methods.single { it.name == "parseNetworkResponse" && it.parameterTypes == listOf(NETWORK) }
        parse.addInstructions(0, """
            invoke-virtual/range {p0 .. p1}, ${clazz.type}->patch_getMoreChildrenResponse($NETWORK)$NETWORK
            move-result-object p1
        """.trimIndent())

        val presenter = classes.singleOrNull { it.type == "Lya/d;" && it.sourceFile == "CommentsPresenterImpl.java" }
            ?: throw PatchException("Expected Sync comments presenter was not found.")
        val load = proxy(presenter).mutableClass.methods.single {
            it.name == "V" && it.parameterTypes == listOf("Lxa/d;") && it.returnType == "V"
        }
        // Preserve the original enqueue flow, capturing the clicked row's parent
        // before the compiler reuses its parameter register for the main post.
        val successCallback = "Lya/d\$e;"
        val failureCallback = "Lya/d\$f;"
        val requestConstructor = "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IILjava/lang/String;Lcom/android/volley/Response\$Listener;Lcom/android/volley/Response\$ErrorListener;)V"
        load.resetBody(15, """
            new-instance v0, Ljava/lang/StringBuilder;
            invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
            const-string v1, "Requesting more: "
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {p1}, Lxa/d;->U()Ljava/lang/String;
            move-result-object v1
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v0
            const-string v1, "CommentsPresenterImpl"
            invoke-static {v1, v0}, Lwc/i;->f(Ljava/lang/String;Ljava/lang/String;)V
            invoke-virtual {p0}, Lya/d;->K0()V
            invoke-virtual {p0}, Lya/d;->J0()V
            iget-object v0, p0, Lya/d;->b:Lza/b;
            invoke-interface {v0}, Lza/b;->I()V
            new-instance v0, Ly8/f;
            iget-object v2, p0, Lya/d;->a:Landroid/content/Context;
            iget-object v1, p0, Lya/d;->c:Ljava/lang/String;
            iget-object v3, p0, Lya/d;->d:Ljava/lang/String;
            invoke-static {v1, v3}, Lt7/l;->a(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
            move-result-object v3
            iget-object v4, p0, Lya/d;->c:Ljava/lang/String;
            invoke-virtual {p1}, Lxa/d;->U()Ljava/lang/String;
            move-result-object v5
            invoke-virtual {p1}, Lxa/d;->u()Ljava/lang/String;
            move-result-object v6
            invoke-virtual {p1}, Lxa/d;->l0()I
            move-result v7
            invoke-virtual {p1}, Lxa/d;->d0()I
            move-result v8
            invoke-virtual {p1}, Lxa/d;->t0()Ljava/lang/String;
            move-result-object v12
            invoke-virtual {p0}, Lya/d;->e0()Lxa/d;
            move-result-object p1
            invoke-virtual {p1}, Lxa/d;->X0()Ljava/lang/String;
            move-result-object v9
            new-instance v10, $successCallback
            invoke-direct {v10, p0}, $successCallback-><init>(Lya/d;)V
            new-instance v11, $failureCallback
            invoke-direct {v11, p0}, $failureCallback-><init>(Lya/d;)V
            move-object v1, v0
            invoke-direct/range {v1 .. v11}, Ly8/f;-><init>$requestConstructor
            invoke-virtual {v0, v12}, Ly8/f;->patch_setContinuationParent(Ljava/lang/String;)V
            iput-object v0, p0, Lya/d;->u:Ly8/f;
            iget-object p1, p0, Lya/d;->a:Landroid/content/Context;
            invoke-static {p1, v0}, Lm8/a;->c(Landroid/content/Context;Lx8/a;)V
            return-void
        """.trimIndent())
        val candidates = classes.flatMap { c -> c.methods.map { c to it } }.filter { (_, m) ->
            m.name == "onErrorResponse" && m.parameterTypes == listOf(VOLLEY_ERROR) &&
                m.implementation?.instructions?.any { instruction ->
                    ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string == "Error loading more comments"
                } == true
        }
        if (candidates.size != 1) throw PatchException("Expected one more-comments error handler, found ${candidates.size}.")
        val (errorClass, errorMethod) = candidates.single()
        val mutableError = proxy(errorClass).mutableClass.methods.single {
            it.name == errorMethod.name && it.parameterTypes == errorMethod.parameterTypes
        }
        // The original callback reuses its error argument register immediately.
        // Rebuild the small callback so the error is read before that register is reused.
        val references = mutableError.implementation!!.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference
        }
        val fields = references.filterIsInstance<FieldReference>().distinctBy { it.toString() }
        val outer = fields.single { it.definingClass == errorClass.type }
        val loading = fields.single { it.type == "Ly8/f;" }
        val context = fields.single { it.type == "Landroid/content/Context;" }
        val view = fields.single { it.type == "Lza/b;" }
        val calls = references.filterIsInstance<MethodReference>()
        val toast = calls.single { it.parameterTypes == listOf("Landroid/content/Context;", "Ljava/lang/String;") }
        val complete = calls.single { it.definingClass == "Lza/b;" }
        mutableError.resetBody(4, """
            iget-object v0, p0, $outer
            const/4 v1, 0x0
            iput-object v1, v0, $loading
            iget-object v1, v0, $context
            invoke-static {v1, p1}, $EXT->showCommentsError(Landroid/content/Context;$VOLLEY_ERROR)V
            iget-object v0, v0, $view
            invoke-interface {v0}, $complete
            return-void
        """.trimIndent())
    }
}

private const val SETTINGS = "Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton;"
private const val SETTINGS_VALUES = "Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton\$Settings;"

private fun Instruction.stringValue(): String? =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Instruction.methodRef(): MethodReference? =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.registerA(): Int? = (this as? OneRegisterInstruction)?.registerA

private fun Instruction.branchTarget(): Instruction =
    (this as BuilderOffsetInstruction).target.location.instruction
        ?: throw PatchException("Unsupported comment media branch layout.")

private fun List<BuilderInstruction>.singleIndex(what: String, predicate: (Int) -> Boolean): Int {
    val matches = indices.filter(predicate)
    if (matches.size != 1) throw PatchException("Expected one $what in Sync 23033, found ${matches.size}.")
    return matches.single()
}

private fun layout(valid: Boolean, what: String) {
    if (!valid) throw PatchException("Unsupported $what layout. This patch targets Sync build 23033 only.")
}

val improveSyncCommentMedia = bytecodePatch(
    name = "Sync: improve inline comment media",
    description = "Size Reddit comment pictures from URL parameters in any order or from media_metadata, preserving aspect ratio, reply width and signed links; draw Giphy links inline from media_metadata; inline WebP and other parsed picture formats; keep malformed Giphy markers usable.",
) {
    compatibleWith("com.laurencedawson.reddit_sync"("v23.06.30-13:39"))
    dependsOn(extensionPatch)
    apply {
        val target = classes.singleOrNull { it.type == "Lnc/d;" && it.sourceFile == "SyncHtmlToSpannedConverter.java" }
            ?: throw PatchException("Expected Sync comment HTML converter was not found. This patch targets build 23033 only.")
        val converter = proxy(target).mutableClass
        fun method(name: String, parameters: List<String>): MutableMethod =
            converter.methods.singleOrNull { it.name == name && it.parameterTypes == parameters && it.returnType == "V" }
                ?: throw PatchException("Unsupported comment HTML converter method: $name")
        val link = method("e", listOf("Loc/c;", "Lnc/a;", "Lnc/b;"))
        val image = method("y", listOf("Loc/c;", "Lorg/xml/sax/Attributes;"))
        if (link.implementation!!.instructions.any { it.methodRef()?.definingClass == MEDIA }) {
            throw PatchException("Comment media patch is already installed.")
        }

        // Register roles below are fixed by build 23033 and checked before any change:
        // v0 = link span, v2 = href, v6 = object replacement character, v7/v8/v9 = 2/0/1.
        run {
            val code = link.implementation!!.instructions
            layout(code.any {
                it.opcode == Opcode.IGET_OBJECT && it.registerA() == 2 &&
                    ((it as ReferenceInstruction).reference as FieldReference).let { f -> f.definingClass == "Lnc/d\$h;" && f.name == "a" }
            }, "comment link URL")
            val tenor = code.singleIndex("Tenor inline check") { code[it].stringValue() == "tenor.com" }
            layout(code[tenor].registerA() == 5 && code[tenor + 1].methodRef()?.name == "contains" &&
                code[tenor + 2].opcode == Opcode.MOVE_RESULT && code[tenor + 2].registerA() == 5 &&
                code[tenor + 3].opcode == Opcode.IF_EQZ && code[tenor + 3].registerA() == 5, "inline image gate")
            // Add parsed picture formats (WebP, format=, media_metadata MIME) to Sync's own decision,
            // which still requires its inline-preview preference and comment context.
            link.addInstructions(tenor + 3, """
                invoke-static {v2, v5}, $MEDIA->inlineImageOr(Ljava/lang/String;Z)Z
                move-result v5
            """.trimIndent())
        }
        run {
            val code = link.implementation!!.instructions
            val preview = code.singleIndex("preview image branch") { code[it].stringValue() == "preview.redd.it" }
            layout(code[preview].registerA() == 4 && code[preview + 1].methodRef()?.name == "contains" &&
                code[preview + 2].opcode == Opcode.MOVE_RESULT && code[preview + 3].opcode == Opcode.CONST_HIGH16 &&
                code[preview + 3].registerA() == 5 && code[preview + 4].opcode == Opcode.IF_EQZ &&
                code[preview + 4].registerA() == 4, "preview image branch")
            fun constant(register: Int, value: Int) = code.subList(0, preview).any {
                it.opcode == Opcode.CONST_4 && it.registerA() == register && (it as NarrowLiteralInstruction).narrowLiteral == value
            }
            layout(constant(7, 2) && constant(8, 0) && constant(9, 1) &&
                code.subList(0, preview).any { it.registerA() == 6 && it.stringValue() == "￼" }, "preview image registers")
            val fallback = code[preview + 4].branchTarget()
            layout(fallback.methodRef()?.let { it.definingClass == SETTINGS && it.name == "x" } == true, "comment link fallback")
            // Replace the old preview branch (integer parsing and "&height" slicing) with a parsed,
            // validated size. Giphy links sized by media_metadata draw inline when animated images
            // are enabled. Unknown sizes take Sync's normal link, preview card and inline-preview path.
            // The span draws displayUrl (Reddit's Giphy rendition) while the tap target stays the link.
            link.addInstructionsWithLabels(preview + 4, """
                invoke-static {}, $SETTINGS->x()$SETTINGS_VALUES
                move-result-object v4
                iget-boolean v10, v4, $SETTINGS_VALUES->commentsAnimatedImages:Z
                iget-boolean v4, v4, $SETTINGS_VALUES->inlineImagePreviews:Z
                if-eqz v4, :fallback
                iget v4, p1, Lnc/a;->e:I
                invoke-static {v2, v4, v10}, $MEDIA->inlineImageSize(Ljava/lang/String;IZ)[I
                move-result-object v4
                if-eqz v4, :fallback
                invoke-static {v2}, $MEDIA->displayUrl(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v3
                invoke-virtual {p0, v0}, Loc/c;->r(Loc/c${'$'}a;)Ljava/lang/String;
                invoke-virtual {p0, v0}, Loc/c;->q(Loc/c${'$'}a;)V
                aget v10, v4, v8
                aget v11, v4, v9
                new-array v4, v7, [Ljava/lang/Object;
                new-instance v1, Lnb/c;
                invoke-direct {v1, v3, v10, v11}, Lnb/c;-><init>(Ljava/lang/String;II)V
                aput-object v1, v4, v8
                new-instance v1, Lmb/d;
                invoke-direct {v1, v2}, Lmb/d;-><init>(Ljava/lang/String;)V
                aput-object v1, v4, v9
                invoke-virtual {p0, v6, v4}, Loc/c;->c(Ljava/lang/String;[Ljava/lang/Object;)V
                return-void
            """.trimIndent(), ExternalLabel("fallback", fallback))
        }
        run {
            val code = image.implementation!!.instructions
            val giphy = code.indexOfFirst { it.methodRef()?.let { m -> m.definingClass == "Lnc/d;" && m.name == "a" } == true }
            layout(giphy >= 0 && code[giphy + 1].opcode in setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32), "Giphy image")
            val end = code[giphy + 1].branchTarget()
            layout(end.opcode == Opcode.RETURN_VOID, "Giphy image end")
            val sized = code.singleIndex("sized Giphy check") {
                it + 2 < code.size && code[it].opcode == Opcode.ARRAY_LENGTH &&
                    (code[it] as TwoRegisterInstruction).registerB == 0 &&
                    code[it + 1].opcode == Opcode.CONST_4 && (code[it + 1] as NarrowLiteralInstruction).narrowLiteral == 3 &&
                    code[it + 1].registerA() == 1 && code[it + 2].opcode == Opcode.IF_NE
            }
            // id|width|height markers with missing or non-numeric sizes used to throw from
            // Integer.parseInt; show them through Sync's unsized Giphy path instead.
            image.addInstructionsWithLabels(sized + 1, """
                invoke-static {v0}, $MEDIA->hasGiphySize([Ljava/lang/String;)Z
                move-result v1
                if-nez v1, :sized
                const/4 v1, 0x0
                aget-object v1, v0, v1
                invoke-static {p0, v1}, Lnc/d;->a(Loc/c;Ljava/lang/String;)V
                goto/16 :end
                :sized
                nop
            """.trimIndent(), ExternalLabel("end", end))
        }

        // Reddit comment GIFs arrive as ![gif](giphy|ID). Sync renders that as an img tag and draws
        // a 100px-tall 100.gif preview in nc.d.a, whose static handler has no reply width. Record
        // the converter's width just before the img handler, then draw full size once the size is known.
        val start = method("q", listOf("Ljava/lang/String;", "Lorg/xml/sax/Attributes;"))
        run {
            val code = start.implementation!!.instructions
            val img = code.singleIndex("img tag handler call") { i ->
                code[i].methodRef()?.let { it.definingClass == "Lnc/d;" && it.name == "y" } == true
            }
            layout(img > 0 && code[img - 1].opcode == Opcode.IGET_OBJECT &&
                ((code[img - 1] as ReferenceInstruction).reference as FieldReference).name == "c", "img tag handler call")
            start.addInstructions(img, """
                iget-object v0, p0, Lnc/d;->d:Lnc/a;
                iget v0, v0, Lnc/a;->e:I
                invoke-static {v0}, $MEDIA->setImageWidth(I)V
            """.trimIndent())
        }
        val giphyMarker = converter.methods.singleOrNull {
            it.name == "a" && it.parameterTypes == listOf("Loc/c;", "Ljava/lang/String;") && it.returnType == "V"
        } ?: throw PatchException("Unsupported comment HTML converter method: a")
        run {
            val code = giphyMarker.implementation!!.instructions
            layout(code.firstOrNull()?.opcode == Opcode.NEW_INSTANCE &&
                code.any { it.stringValue() == "https://media.giphy.com/media/" } &&
                code.any { it.stringValue() == "/100.gif" }, "unsized Giphy marker")
            giphyMarker.addInstructionsWithLabels(0, """
                invoke-static {p1}, $MEDIA->giphyInlineSize(Ljava/lang/String;)[I
                move-result-object v0
                if-eqz v0, :preview
                invoke-static {p1}, $MEDIA->giphyDisplayUrl(Ljava/lang/String;)Ljava/lang/String;
                move-result-object p1
                const/4 v1, 0x0
                aget v1, v0, v1
                const/4 v2, 0x1
                aget v2, v0, v2
                new-instance v0, Lnb/c;
                invoke-direct {v0, p1, v1, v2}, Lnb/c;-><init>(Ljava/lang/String;II)V
                const/4 v1, 0x2
                new-array v1, v1, [Ljava/lang/Object;
                const/4 v2, 0x0
                aput-object v0, v1, v2
                new-instance v0, Lmb/d;
                invoke-direct {v0, p1}, Lmb/d;-><init>(Ljava/lang/String;)V
                const/4 v2, 0x1
                aput-object v0, v1, v2
                const-string v0, "￼"
                invoke-virtual {p0, v0, v1}, Loc/c;->c(Ljava/lang/String;[Ljava/lang/Object;)V
                const-string v0, "\n"
                invoke-virtual {p0, v0}, Loc/c;->b(Ljava/lang/CharSequence;)V
                return-void
                :preview
                nop
            """.trimIndent())
        }

        val parserTarget = classes.singleOrNull { it.type == "Lc9/c;" && it.sourceFile == "PostParserHelper.java" }
            ?: throw PatchException("Expected Sync PostParserHelper was not found.")
        val parser = proxy(parserTarget).mutableClass
        val sites = parser.methods.flatMap { m ->
            val code = m.implementation?.instructions ?: return@flatMap emptyList()
            code.indices.filter { i ->
                code[i].stringValue() == "media_metadata" && i + 2 < code.size &&
                    code[i + 1].methodRef()?.name == "optJSONObject" && code[i + 2].opcode == Opcode.MOVE_RESULT_OBJECT &&
                    code.subList(maxOf(0, i - 8), i).any { it.stringValue() == "body" }
            }.map { m to it }
        }
        if (sites.size != 1) throw PatchException("Expected one comment media_metadata read, found ${sites.size}.")
        val (parse, index) = sites.single()
        val metadata = parse.implementation!!.instructions[index + 2].registerA()!!
        // Capture comment image sizes/types while Sync parses the comment; the body is unchanged.
        parse.addInstructions(index + 3,
            "invoke-static/range {v$metadata .. v$metadata}, $MEDIA->rememberMetadata(Lorg/json/JSONObject;)V")
    }
}

private const val VIDEO = "Lapp/revanced/extension/synccompat/CommentVideo;"

val showSyncCommentVideoPreviews = bytecodePatch(
    name = "Sync: show comment video previews",
    description = "Draw Reddit-hosted comment videos as a still frame with a play button at their aspect ratio within the reply width, and open reddit.com/link/.../video/... links in Sync's video viewer instead of a browser redirect to a missing post.",
) {
    compatibleWith("com.laurencedawson.reddit_sync"("v23.06.30-13:39"))
    dependsOn(extensionPatch)
    apply {
        // Link router (y7.b.c). Reddit redirects reddit.com/link/POST/video/MEDIA/player to a
        // placeholder post, so the link is mapped to v.redd.it/MEDIA, which reaches Sync's own
        // "reddit video" route. Every other URL passes through unchanged.
        val helper = classes.singleOrNull { it.type == "Ly7/b;" && it.sourceFile == "LinkHelper.java" }
            ?: throw PatchException("Expected Sync LinkHelper was not found. This patch targets build 23033 only.")
        val router = proxy(helper).mutableClass.methods.singleOrNull { m ->
            m.name == "c" && m.returnType == "Z" &&
                m.implementation?.instructions?.any { it.stringValue() == "Opening strange reddit video link" } == true
        } ?: throw PatchException("Unsupported Sync link router.")
        val routerCode = router.implementation!!.instructions
        if (routerCode.any { it.methodRef()?.definingClass == VIDEO }) throw PatchException("Comment video patch is already installed.")
        // Static method: p3 is the URL only when parameters 0-2 are single-register types.
        layout(AccessFlags.STATIC.isSet(router.accessFlags) && router.parameterTypes.size == 10 &&
            router.parameterTypes.take(4).map { it.toString() } ==
                listOf("Landroid/content/Context;", "[Lr0/d;", "Ljava/lang/String;", "Ljava/lang/String;") &&
            routerCode.any { it.stringValue() == "reddit.com/link/" } &&
            routerCode.any { it.stringValue() == "Was reddit video" }, "link router")
        router.addInstructions(0, """
            invoke-static {p3}, $VIDEO->tapUrl(Ljava/lang/String;)Ljava/lang/String;
            move-result-object p3
        """.trimIndent())

        // Comment link handler (nc.d.e). Register roles are fixed by build 23033 and checked:
        // p0 = builder, p1 = layout (reply width in e), v0 = link span, v2 = href,
        // v4 = "href starts with sync-settings", v6 = object replacement character, v7/v8/v9 = 2/0/1.
        val target = classes.singleOrNull { it.type == "Lnc/d;" && it.sourceFile == "SyncHtmlToSpannedConverter.java" }
            ?: throw PatchException("Expected Sync comment HTML converter was not found. This patch targets build 23033 only.")
        val link = proxy(target).mutableClass.methods.singleOrNull {
            it.name == "e" && it.parameterTypes == listOf("Loc/c;", "Lnc/a;", "Lnc/b;") && it.returnType == "V"
        } ?: throw PatchException("Unsupported comment HTML converter method: e")
        val code = link.implementation!!.instructions
        if (code.any { it.methodRef()?.definingClass == VIDEO }) throw PatchException("Comment video patch is already installed.")
        val settings = code.singleIndex("settings link check") { code[it].stringValue() == "sync-settings" }
        fun literal(i: Int, register: Int, value: Int) = code[i].opcode == Opcode.CONST_4 &&
            code[i].registerA() == register && (code[i] as NarrowLiteralInstruction).narrowLiteral == value
        layout(link.implementation!!.registerCount == 15 && settings + 8 < code.size &&
            code.subList(0, settings).any {
                it.opcode == Opcode.IGET_OBJECT && it.registerA() == 2 &&
                    ((it as ReferenceInstruction).reference as FieldReference).let { f -> f.definingClass == "Lnc/d\$h;" && f.name == "a" }
            } &&
            code[settings].registerA() == 4 &&
            code[settings + 1].methodRef()?.let { it.name == "startsWith" } == true &&
            (code[settings + 1] as FiveRegisterInstruction).let { it.registerCount == 2 && it.registerC == 2 && it.registerD == 4 } &&
            code[settings + 2].opcode == Opcode.MOVE_RESULT && code[settings + 2].registerA() == 4 &&
            code[settings + 3].registerA() == 5 && code[settings + 3].stringValue() == "\n\n" &&
            code[settings + 4].registerA() == 6 && code[settings + 4].stringValue() == "￼" &&
            literal(settings + 5, 7, 2) && literal(settings + 6, 8, 0) && literal(settings + 7, 9, 1) &&
            code[settings + 8].opcode == Opcode.IF_EQZ && code[settings + 8].registerA() == 4, "comment link handler")
        layout(code.subList(0, settings).let { start ->
            start.indices.any { i ->
                i + 1 < start.size && start[i].methodRef()?.let { it.definingClass == "Lnc/d;" && it.name == "i" } == true &&
                    start[i + 1].opcode == Opcode.MOVE_RESULT_OBJECT && start[i + 1].registerA() == 0
            }
        }, "comment link span")
        // Only v4 changes on the fall-through path. It is false there already: a video link
        // never starts with sync-settings, and a settings link skips the new code.
        link.addInstructionsWithLabels(settings + 8, """
            if-nez v4, :continue
            invoke-static {v2}, $VIDEO->isVideoLink(Ljava/lang/String;)Z
            move-result v4
            if-eqz v4, :continue
            iget v4, p1, Lnc/a;->e:I
            invoke-static {v2, v4}, $VIDEO->posterSize(Ljava/lang/String;I)[I
            move-result-object v4
            if-nez v4, :poster
            const/4 v4, 0x0
            goto :continue
            :poster
            aget v10, v4, v8
            aget v11, v4, v9
            invoke-static {v2, v10, v11}, $VIDEO->posterUrl(Ljava/lang/String;II)Ljava/lang/String;
            move-result-object v3
            invoke-virtual {p0, v0}, Loc/c;->r(Loc/c${'$'}a;)Ljava/lang/String;
            invoke-virtual {p0, v0}, Loc/c;->q(Loc/c${'$'}a;)V
            new-array v5, v7, [Ljava/lang/Object;
            new-instance v1, Lnb/c;
            invoke-direct {v1, v3, v10, v11}, Lnb/c;-><init>(Ljava/lang/String;II)V
            invoke-static {v1, v2, v10, v11}, $VIDEO->track(Ljava/lang/Object;Ljava/lang/String;II)V
            aput-object v1, v5, v8
            new-instance v1, Lmb/d;
            invoke-direct {v1, v2}, Lmb/d;-><init>(Ljava/lang/String;)V
            aput-object v1, v5, v9
            invoke-virtual {p0, v6, v5}, Loc/c;->c(Ljava/lang/String;[Ljava/lang/Object;)V
            return-void
            :continue
            nop
        """.trimIndent())

        // Inline image span (nb.c): add syncCompatLoad(path), which loads another file into an
        // existing span exactly as Sync's builder (oc.c.o) loads it, so a placeholder or poster
        // replaces what the span shows without waiting for Sync to redraw the comment.
        val builderTarget = classes.singleOrNull { it.type == "Loc/c;" && it.sourceFile == "SpannableTextViewStringBuilder.java" }
            ?: throw PatchException("Expected Sync span builder was not found. This patch targets build 23033 only.")
        val loader = proxy(builderTarget).mutableClass.methods.singleOrNull {
            it.name == "o" && it.parameterTypes.size == 1 && it.returnType == "V" &&
                it.implementation?.instructions?.any { i -> i.opcode == Opcode.INSTANCE_OF &&
                    (i as ReferenceInstruction).reference.toString() == "Lnb/c;" } == true
        } ?: throw PatchException("Unsupported Sync span loader.")
        val load = loader.implementation!!.instructions
        val k = load.singleIndex("inline image span load") {
            load[it].opcode == Opcode.INSTANCE_OF && (load[it] as ReferenceInstruction).reference.toString() == "Lnb/c;"
        }
        val expected = listOf(Opcode.INSTANCE_OF, Opcode.IF_EQZ, Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL,
            Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT,
            Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT,
            Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT,
            Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.SGET_OBJECT,
            Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.NEW_INSTANCE,
            Opcode.CONST_16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.INVOKE_DIRECT,
            Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL)
        layout(k + expected.size <= load.size && expected.indices.all { load[k + it].opcode == expected[it] }, "inline image span load")
        fun ref(offset: Int) = (load[k + offset] as ReferenceInstruction).reference.toString()
        val span = "Lnb/c;"
        layout(load[k + 3].methodRef()?.let { it.definingClass == span && it.name == "p" } == true &&
            load[k + 8].methodRef()?.let { it.definingClass == span && it.name == "h" && it.returnType == "Ljava/lang/String;" } == true &&
            load[k + 12].methodRef()?.let { it.definingClass == span && it.name == "e" } == true &&
            load[k + 14].methodRef()?.let { it.definingClass == span && it.name == "e" } == true &&
            load[k + 10].methodRef()?.parameterTypes?.map { it.toString() } == listOf("Ljava/lang/String;") &&
            load[k + 31].methodRef()?.parameterTypes?.size == 1, "inline image span load calls")
        val radius = (load[k + 24] as NarrowLiteralInstruction).narrowLiteral
        val spanClass = proxy(classes.singleOrNull { it.type == span && it.sourceFile == "EmoteImageSpan.java" }
            ?: throw PatchException("Expected Sync inline image span was not found.")).mutableClass
        if (spanClass.methods.any { it.name == "syncCompatLoad" }) throw PatchException("Comment video patch is already installed.")
        val loadMethod = MutableMethod(ImmutableMethod(span, "syncCompatLoad",
            listOf(com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter("Ljava/lang/String;", null, null)),
            "V", AccessFlags.PUBLIC.value, emptySet(), emptySet(), null))
        loadMethod.resetBody(6, """
            invoke-static {}, ${ref(4)}
            move-result-object v0
            invoke-static {v0}, ${ref(6)}
            move-result-object v0
            invoke-virtual {v0, p1}, ${ref(10)}
            move-result-object v0
            invoke-virtual {p0}, ${ref(12)}
            move-result v1
            invoke-virtual {v0, v1, v1}, ${ref(16)}
            move-result-object v0
            check-cast v0, ${ref(18)}
            sget-object v1, ${ref(19)}
            invoke-virtual {v0, v1}, ${ref(20)}
            move-result-object v0
            check-cast v0, ${ref(22)}
            new-instance v1, ${ref(23)}
            const/16 v2, $radius
            invoke-static {v2}, ${ref(25)}
            move-result v2
            invoke-direct {v1, v2}, ${ref(27)}
            invoke-virtual {v0, v1}, ${ref(28)}
            move-result-object v0
            check-cast v0, ${ref(30)}
            invoke-virtual {v0, p0}, ${ref(31)}
            return-void
        """.trimIndent())
        spanClass.methods.add(loadMethod)

        // Comment parser (c9.c): record video sizes and queue posters from media_metadata, so
        // the box is reserved and the poster is usually ready before the comment is drawn.
        val parserTarget = classes.singleOrNull { it.type == "Lc9/c;" && it.sourceFile == "PostParserHelper.java" }
            ?: throw PatchException("Expected Sync PostParserHelper was not found.")
        val parser = proxy(parserTarget).mutableClass
        val sites = parser.methods.flatMap { m ->
            val body = m.implementation?.instructions ?: return@flatMap emptyList()
            body.indices.filter { i ->
                body[i].stringValue() == "media_metadata" && i + 2 < body.size &&
                    body[i + 1].methodRef()?.name == "optJSONObject" && body[i + 2].opcode == Opcode.MOVE_RESULT_OBJECT &&
                    body.subList(maxOf(0, i - 8), i).any { it.stringValue() == "body" }
            }.map { m to it }
        }
        if (sites.size != 1) throw PatchException("Expected one comment media_metadata read, found ${sites.size}.")
        val (parse, index) = sites.single()
        val metadata = parse.implementation!!.instructions[index + 2].registerA()!!
        parse.addInstructions(index + 3,
            "invoke-static/range {v$metadata .. v$metadata}, $VIDEO->rememberMetadata(Lorg/json/JSONObject;)V")
    }
}

private const val AWARD = "Lo8/a;"
private const val SELECTION_ITEM = "Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet\$h;"
private const val SHEET_DISMISS = "Lcom/google/android/material/bottomsheet/BottomSheetDialogFragment;->x3()V"
private const val JSON_OBJECT = "Lorg/json/JSONObject;"

val handleSyncAwardData = bytecodePatch(
    name = "Sync: handle unavailable award data",
    description = "Treat missing or changed award metadata as optional: listings stay usable, the awards sheet shows an accurate message for failed, empty or unavailable award data, and offers the item on reddit.com when Reddit doesn't list its awards.",
) {
    compatibleWith("com.laurencedawson.reddit_sync"("v23.06.30-13:39"))
    dependsOn(extensionPatch)
    apply {
        val model = classes.singleOrNull { it.type == AWARD && it.sourceFile == "Award.java" }
            ?: throw PatchException("Expected Sync Award model was not found. This patch targets build 23033 only.")
        layout(listOf("a" to "I", "b" to "Ljava/lang/String;", "c" to "Ljava/lang/String;",
            "q" to "Ljava/lang/String;", "r" to "Ljava/lang/String;").all { (name, type) ->
            model.fields.any { it.name == name && it.type == type && AccessFlags.PUBLIC.isSet(it.accessFlags) }
        } && model.methods.any { it.name == "<init>" && it.parameterTypes.isEmpty() }, "Award model")

        // Awards sheet lookup (y8.c, GET /api/info/?id=). The original threw on any missing field
        // and reported "Could not load gallery".
        val request = classes.singleOrNull { it.type == "Ly8/c;" && it.sourceFile == "OAuthAwardsRequest.java" }
            ?: throw PatchException("Expected Sync OAuthAwardsRequest was not found. This patch targets build 23033 only.")
        val parse = proxy(request).mutableClass.methods.singleOrNull {
            it.name == "parseNetworkResponse" && it.parameterTypes == listOf(NETWORK)
        } ?: throw PatchException("Unsupported awards request parser.")
        val original = parse.implementation!!.instructions
        if (original.any { it.methodRef()?.definingClass == AWARDS }) throw PatchException("Award data patch is already installed.")
        val cache = original.mapNotNull { it.methodRef() }.singleOrNull {
            it.definingClass == "Ld9/a;" && it.parameterTypes == listOf(NETWORK, "J") && it.returnType == "Lcom/android/volley/Cache\$Entry;"
        }
        layout(cache != null && original.any { it.stringValue() == "all_awardings" } &&
            original.any { it.stringValue() == "Could not load gallery" } &&
            original.any { it.opcode == Opcode.CONST_WIDE_32 }, "awards request parser")
        parse.resetBody(5, """
            invoke-virtual {p0}, Lcom/android/volley/Request;->getUrl()Ljava/lang/String;
            move-result-object v0
            invoke-static {p1, v0}, $AWARDS->parseAwards(${NETWORK}Ljava/lang/String;)$AWARDS_RESULT
            move-result-object v0
            iget-object v1, v0, $AWARDS_RESULT->error:$VOLLEY_ERROR
            if-eqz v1, :success
            invoke-static {v1}, Lcom/android/volley/Response;->error($VOLLEY_ERROR)Lcom/android/volley/Response;
            move-result-object p1
            return-object p1
            :success
            iget-object v0, v0, $AWARDS_RESULT->awards:[$AWARD
            const-wide/32 v1, 0x36ee80
            invoke-static {p1, v1, v2}, $cache
            move-result-object p1
            invoke-static {v0, p1}, Lcom/android/volley/Response;->success(Ljava/lang/Object;Lcom/android/volley/Cache${'$'}Entry;)Lcom/android/volley/Response;
            move-result-object p1
            return-object p1
        """.trimIndent())

        // Post and comment awards sheets: accurate error text, and "Open on Reddit" rows.
        for ((type, source) in listOf("Lt9/g;" to "AwardsCommentAsyncSelectionDialogBottomSheet.java",
                                      "Lt9/i;" to "AwardsPostAsyncSelectionDialogBottomSheet.java")) {
            val target = classes.singleOrNull { it.type == type && it.sourceFile == source }
                ?: throw PatchException("Expected Sync $source was not found.")
            val sheet = proxy(target).mutableClass
            val error = sheet.methods.singleOrNull { m ->
                m.parameterTypes == listOf(VOLLEY_ERROR) && m.returnType == "V" &&
                    m.implementation?.instructions?.any { it.stringValue() == "Error loading awards" } == true
            } ?: throw PatchException("Unsupported awards sheet error handler in $source.")
            val calls = error.implementation!!.instructions.mapNotNull { it.methodRef() }
            val toast = calls.singleOrNull { it.definingClass == "Lgb/p;" }
            val ready = calls.singleOrNull { it.definingClass == "Ls9/f;" && it.name == "e4" && it.parameterTypes.isEmpty() }
            layout(toast != null && ready != null && calls.any { it.toString() == SHEET_DISMISS }, "awards sheet error handler")
            val show = when (toast!!.parameterTypes) {
                listOf("Landroid/content/Context;", "Ljava/lang/String;") -> """
                    invoke-virtual {p0}, Landroidx/fragment/app/Fragment;->B0()Landroidx/fragment/app/FragmentActivity;
                    move-result-object p1
                    invoke-static {p1, v0}, $toast
                """.trimIndent()
                listOf("Ljava/lang/String;") -> "invoke-static {v0}, $toast"
                else -> throw PatchException("Unsupported awards sheet toast in $source.")
            }
            // The original reuses its error register before reading it; read the message first.
            error.resetBody(3, """
                invoke-static {p1}, $AWARDS->errorMessage($VOLLEY_ERROR)Ljava/lang/String;
                move-result-object v0
                invoke-virtual {p0}, $ready
                move-result p1
                if-eqz p1, :done
                $show
                invoke-virtual {p0}, $SHEET_DISMISS
                :done
                return-void
            """.trimIndent())
            val select = sheet.methods.singleOrNull {
                it.name == "r0" && it.parameterTypes == listOf(SELECTION_ITEM) && it.returnType == "V"
            } ?: throw PatchException("Unsupported awards sheet selection handler in $source.")
            val code = select.implementation!!.instructions
            layout(select.implementation!!.registerCount >= 4 &&
                code.any { it.opcode == Opcode.CHECK_CAST && (it as ReferenceInstruction).reference.toString() == AWARD },
                "awards sheet selection handler")
            // Rows made by AwardCompat for unlisted awards open the item with Sync's own
            // "Open in browser" helper; real awards keep the award detail sheet.
            select.addInstructionsWithLabels(0, """
                iget-object v0, p1, $SELECTION_ITEM->c:Ljava/lang/Object;
                invoke-static {v0}, $AWARDS->handoffUrl(Ljava/lang/Object;)Ljava/lang/String;
                move-result-object v0
                if-eqz v0, :award
                invoke-virtual {p0}, Landroidx/fragment/app/Fragment;->B0()Landroidx/fragment/app/FragmentActivity;
                move-result-object v1
                invoke-static {v1, v0}, Ly7/a;->X(Landroid/content/Context;Ljava/lang/String;)V
                invoke-virtual {p0}, $SHEET_DISMISS
                return-void
                :award
                nop
            """.trimIndent())
        }

        // Listing parser (c9.c): award entries without icon arrays, or gildings that isn't an
        // object, threw from the post/comment parse.
        val parserTarget = classes.singleOrNull { it.type == "Lc9/c;" && it.sourceFile == "PostParserHelper.java" }
            ?: throw PatchException("Expected Sync PostParserHelper was not found.")
        val parser = proxy(parserTarget).mutableClass
        fun sites(key: String, call: String) = parser.methods.flatMap { m ->
            val code = m.implementation?.instructions ?: return@flatMap emptyList()
            code.indices.filter { i ->
                code[i].stringValue() == key && i + 2 < code.size &&
                    code[i + 1].methodRef()?.let { it.definingClass == JSON_OBJECT && it.name == call } == true
            }.map { m to it }
        }
        val gildings = sites("gildings", "has")
        if (gildings.size != 1) throw PatchException("Expected one gildings check, found ${gildings.size}.")
        run {
            val (method, index) = gildings.single()
            val code = method.implementation!!.instructions
            val invoke = code[index + 1] as FiveRegisterInstruction
            val key = code[index].registerA()!!
            layout(code[index + 2].opcode == Opcode.MOVE_RESULT && invoke.registerCount == 2 &&
                invoke.registerD == key && invoke.registerC < 16 && key < 16 &&
                (index + 3 until minOf(code.size, index + 8)).any { i ->
                    code[i].methodRef()?.let { it.name == "getJSONObject" } == true
                }, "gildings check")
            val result = code[index + 2].registerA()!!
            method.addInstructions(index + 3, """
                invoke-static {v${invoke.registerC}, v$key}, $AWARDS->hasObject(${JSON_OBJECT}Ljava/lang/String;)Z
                move-result v$result
            """.trimIndent())
        }
        val awardings = sites("all_awardings", "optJSONArray")
        if (awardings.size != 1) throw PatchException("Expected one listing all_awardings read, found ${awardings.size}.")
        run {
            val (method, index) = awardings.single()
            val code = method.implementation!!.instructions
            layout(code[index + 2].opcode == Opcode.MOVE_RESULT_OBJECT &&
                code.subList(index, minOf(code.size, index + 40)).any { it.stringValue() == "resized_static_icons" },
                "listing all_awardings read")
            val list = code[index + 2].registerA()!!
            method.addInstructions(index + 3, """
                invoke-static/range {v$list .. v$list}, $AWARDS->listingAwardings(Lorg/json/JSONArray;)Lorg/json/JSONArray;
                move-result-object v$list
            """.trimIndent())
        }
    }
}
