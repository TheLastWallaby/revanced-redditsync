@file:Suppress("unused")
package app.revanced.local.synccompat

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
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
private const val MARKDOWN = "Lapp/revanced/extension/synccompat/MarkdownCompat;"
private const val GALLERY = "Lapp/revanced/extension/synccompat/GalleryCompat;"
private const val GALLERY_RESULT = "Lapp/revanced/extension/synccompat/GalleryCompat\$Result;"

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
    description = "Load native Reddit galleries through the comment-page API, preserve unavailable item slots and captions, resolve matching crosspost media and refresh explicitly expired URLs once.",
) {
    compatibleWith("com.laurencedawson.reddit_sync"("v23.06.30-13:39"))
    dependsOn(extensionPatch)
    apply {
        val target = classes.singleOrNull { it.type == "Ly8/q;" && it.sourceFile == "OAuthGalleryRequest.java" }
            ?: throw PatchException("Expected Sync OAuthGalleryRequest was not found. This patch targets build 23033 only.")
        val clazz = proxy(target).mutableClass
        val url = clazz.methods.single { it.name == "b" && it.parameterTypes == listOf("Ljava/lang/String;") }
        val parse = clazz.methods.single { it.name == "parseNetworkResponse" && it.parameterTypes == listOf(NETWORK) }
        if (url.implementation!!.instructions.any {
            it is ReferenceInstruction && it.reference.toString().contains("synccompat/RequestCompat")
        } || parse.implementation!!.instructions.any { it.methodRef()?.definingClass == GALLERY })
            throw PatchException("Gallery compatibility patch is already installed.")
        val model = classes.singleOrNull { it.type == "Lo8/b;" && it.sourceFile == "Gallery.java" }
            ?: throw PatchException("Expected Sync Gallery model was not found.")
        layout(listOf("a" to "I", "b" to "[Ljava/lang/String;", "c" to "[Ljava/lang/String;",
            "d" to "[Ljava/lang/String;").all { (name, type) ->
            model.fields.any { it.name == name && it.type == type && AccessFlags.PUBLIC.isSet(it.accessFlags) }
        } && model.methods.any { it.name == "<init>" && it.parameterTypes == listOf("I") }, "Gallery model")
        val original = parse.implementation!!.instructions
        val cache = original.mapNotNull { it.methodRef() }.singleOrNull {
            it.definingClass == "Ld9/a;" && it.parameterTypes == listOf(NETWORK, "J") &&
                it.returnType == "Lcom/android/volley/Cache\$Entry;"
        }
        layout(cache != null && listOf("media_metadata", "gallery_data", "media_id", "Could not load gallery").all { value ->
            original.any { it.stringValue() == value }
        }, "gallery request parser")
        url.resetBody(1, """
            invoke-static {p0}, $EXT->galleryUrl(Ljava/lang/String;)Ljava/lang/String;
            move-result-object p0
            return-object p0
        """.trimIndent())
        // Replace the strict parser and its raw-response logger. Cache the canonical listing
        // so MultiImageActivity can still save/reopen its position; a failed refresh is uncached.
        parse.resetBody(6, """
            invoke-static {p1, p0}, $GALLERY->parseGallery(${NETWORK}Lcom/android/volley/Request;)$GALLERY_RESULT
            move-result-object v0
            iget-object v1, v0, $GALLERY_RESULT->error:$VOLLEY_ERROR
            if-eqz v1, :success
            invoke-static {v1}, Lcom/android/volley/Response;->error($VOLLEY_ERROR)Lcom/android/volley/Response;
            move-result-object v0
            return-object v0
            :success
            iget-object v1, v0, $GALLERY_RESULT->gallery:Lo8/b;
            iget-object v0, v0, $GALLERY_RESULT->response:$NETWORK
            if-nez v0, :cache
            const/4 v0, 0x0
            goto :ready
            :cache
            const-wide/32 v2, 0x36ee80
            invoke-static {v0, v2, v3}, $cache
            move-result-object v0
            :ready
            invoke-static {v1, v0}, Lcom/android/volley/Response;->success(Ljava/lang/Object;Lcom/android/volley/Cache${'$'}Entry;)Lcom/android/volley/Response;
            move-result-object v0
            return-object v0
        """.trimIndent())
    }
}

val fixSyncMoreComments = bytecodePatch(
    name = "Sync: load more comments in batches",
    description = "Experimental: batch-load hidden comments through the normal thread endpoint, retaining failed requests for retry. Each tap makes a bounded number of requests, follows Reddit's rate limits and stops when Sync cancels the load.",
) {
    compatibleWith("com.laurencedawson.reddit_sync"("v23.06.30-13:39"))
    dependsOn(extensionPatch)
    apply {
        val target = classes.singleOrNull { it.type == "Ly8/f;" && it.sourceFile == "OAuthCommentsMoreRequest.java" }
            ?: throw PatchException("Expected Sync OAuthCommentsMoreRequest was not found. This patch targets build 23033 only.")
        // The extension reports its extra requests to Sync's OAuthRateHelper and reads its
        // stored window, so the helper's static methods must have exactly these signatures.
        val rateHelper = classes.singleOrNull { it.type == "Ld9/d;" && it.sourceFile == "OAuthRateHelper.java" }
            ?: throw PatchException("Expected Sync OAuthRateHelper was not found. This patch targets build 23033 only.")
        for ((name, parameters, returns) in listOf(
            Triple("e", listOf(NETWORK, "Ljava/lang/String;"), "V"),
            Triple("b", emptyList(), "I"),
            Triple("c", emptyList(), "J"),
        )) {
            if (rateHelper.methods.none {
                    it.name == name && it.parameterTypes == parameters && it.returnType == returns &&
                        AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
                }) throw PatchException("Sync OAuthRateHelper.$name has an unexpected signature.")
        }
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
        // Passing the request itself lets the bounded fetcher stop when Sync cancels
        // it, and read Sync's refreshed headers once after a 401.
        adapter.resetBody(8, """
            move-object v0, p1
            invoke-super {p0}, Lcom/android/volley/Request;->getUrl()Ljava/lang/String;
            move-result-object v1
            invoke-super {p0}, Lx8/a;->getParams()Ljava/util/Map;
            move-result-object v2
            invoke-super {p0}, Lx8/a;->getHeaders()Ljava/util/Map;
            move-result-object v3
            iget-object v4, p0, $parentField
            move-object v5, p0
            invoke-static/range {v0 .. v5}, $BATCH->getMoreChildrenResponse(${NETWORK}Ljava/lang/String;Ljava/util/Map;Ljava/util/Map;Ljava/lang/String;Lcom/android/volley/Request;)$NETWORK
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

val preserveSyncMarkdownLiterals = bytecodePatch(
    name = "Sync: preserve literal Markdown replacements",
    description = "Preserve dollar signs and backslashes in fenced code, spoiler links and emote URLs without treating content as regex replacement groups.",
) {
    compatibleWith("com.laurencedawson.reddit_sync"("v23.06.30-13:39"))
    dependsOn(extensionPatch)
    apply {
        fun target(type: String, source: String) = proxy(classes.singleOrNull {
            it.type == type && it.sourceFile == source
        } ?: throw PatchException("Expected Sync $source was not found. This patch targets build 23033 only.")).mutableClass
        val helper = target("Ld7/f;", "RedditHelper.java")
        val parser = target("Lc9/c;", "PostParserHelper.java")
        val code = helper.methods.singleOrNull {
            it.name == "e" && it.parameterTypes == listOf("Ljava/lang/String;") && it.returnType == "Ljava/lang/String;"
        } ?: throw PatchException("Unsupported Sync fenced-code method.")
        val spoilers = helper.methods.singleOrNull {
            it.name == "g" && it.parameterTypes == listOf("Ljava/lang/String;") && it.returnType == "Ljava/lang/String;"
        } ?: throw PatchException("Unsupported Sync spoiler-link method.")
        val posts = parser.methods.singleOrNull {
            it.name == "a" && it.parameterTypes == listOf("Landroid/content/Context;", "I", "Lorg/json/JSONObject;",
                "Ljava/util/ArrayList;", "I", "Z", "Z", "Ljava/lang/String;") && it.returnType == "V"
        } ?: throw PatchException("Unsupported Sync post parser.")
        val methods = listOf(code, spoilers, posts)
        if (methods.any { m -> m.implementation?.instructions?.any { it.methodRef()?.definingClass == MARKDOWN } == true }) {
            throw PatchException("Literal Markdown patch is already installed.")
        }
        val append = "Ljava/util/regex/Matcher;->appendReplacement(Ljava/lang/StringBuffer;Ljava/lang/String;)Ljava/util/regex/Matcher;"
        val replace = "Ljava/lang/String;->replaceAll(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
        val quote = "Ljava/util/regex/Pattern;->quote(Ljava/lang/String;)Ljava/lang/String;"
        // Validate every site before editing. Replace the invoke itself so branch targets,
        // try ranges, register counts and move-result consumers keep their original positions.
        fun appendSites(method: MutableMethod, count: Int): List<Int> {
            val sites = method.implementation!!.instructions.indices.filter {
                method.implementation!!.instructions[it].methodRef()?.toString() == append
            }
            layout(sites.size == count, "literal Markdown appendReplacement")
            return sites
        }
        val codeSites = appendSites(code, 1)
        val spoilerSites = appendSites(spoilers, 3)
        val instructions = posts.implementation!!.instructions
        val emoteSites = instructions.indices.filter { instructions[it].methodRef()?.toString() == quote }.map { index ->
            layout(index + 2 < instructions.size && instructions[index].opcode == Opcode.INVOKE_STATIC &&
                instructions[index + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
                instructions[index + 2].methodRef()?.toString() == replace &&
                (instructions[index + 2] as? FiveRegisterInstruction)?.registerD == instructions[index + 1].registerA(),
                "emote literal search")
            index + 2
        }
        layout(emoteSites.size == 2 && instructions.any { it.stringValue() == "emote|" }, "emote replacements")
        val edits = listOf(Triple(code, codeSites, "$MARKDOWN->appendReplacement(Ljava/util/regex/Matcher;Ljava/lang/StringBuffer;Ljava/lang/String;)Ljava/util/regex/Matcher;"),
            Triple(spoilers, spoilerSites, "$MARKDOWN->appendReplacement(Ljava/util/regex/Matcher;Ljava/lang/StringBuffer;Ljava/lang/String;)Ljava/util/regex/Matcher;"),
            Triple(posts, emoteSites, "$MARKDOWN->replaceAll(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"))
        for ((method, sites, _) in edits) for (index in sites) {
            val invoke = method.implementation!!.instructions[index]
            layout(invoke.opcode == Opcode.INVOKE_VIRTUAL && (invoke as? FiveRegisterInstruction)?.registerCount == 3,
                "literal replacement invoke")
        }
        for ((method, sites, destination) in edits) for (index in sites) {
            val invoke = method.implementation!!.instructions[index] as FiveRegisterInstruction
            method.replaceInstruction(index, "invoke-static {v${invoke.registerC}, v${invoke.registerD}, v${invoke.registerE}}, $destination")
        }
    }
}

private fun inlineImageParser(parser: MutableClassDef): MutableMethod =
    parser.methods.singleOrNull {
        it.name == "a" && it.returnType == "V" && it.parameterTypes == listOf(
            "Landroid/content/Context;", "I", "Lorg/json/JSONObject;", "Ljava/util/ArrayList;",
            "I", "Z", "Z", "Ljava/lang/String;")
    } ?: throw PatchException("Unsupported comment image parser method.")

/** Capture dimensions for both text posts and comments, including URLs without a height. */
@Throws(PatchException::class)
fun captureSyncInlineImageMetadata(parser: MutableClassDef) {
    val parse = inlineImageParser(parser)
    val code = parse.implementation!!.instructions
    if (code.any { it.methodRef()?.let { ref -> ref.definingClass == MEDIA && ref.name == "rememberItemMetadata" } == true }) {
        throw PatchException("Inline image metadata capture is already installed.")
    }
    val itemRegister = parse.implementation!!.registerCount - parse.parameterTypes.size + 2
    layout(AccessFlags.STATIC.isSet(parse.accessFlags) && code.take(5).any {
        it.opcode == Opcode.MOVE_OBJECT_FROM16 && (it as TwoRegisterInstruction).registerA == 2 &&
            it.registerB == itemRegister
    }, "inline image parser item parameter")
    // p2 is the item's data object in Sync 23033, not a listing/thing wrapper. The original
    // comment-only read never runs for selftext posts. No scratch registers or URL changes.
    parse.addInstructions(0, "invoke-static/range {p2 .. p2}, $MEDIA->rememberItemMetadata(Lorg/json/JSONObject;)V")
}

/** Keep the signed source URL when Sync prepares comment image links for its renderer. */
@Throws(PatchException::class)
fun preserveSyncCommentImageUrls(parser: MutableClassDef) {
    val parse = inlineImageParser(parser)
    val code = parse.implementation!!.instructions
    val height = code.singleIndex("comment image height suffix") { code[it].stringValue() == "&height=" }
    fun call(index: Int, name: String, parameters: List<String>, registers: List<Int>): Boolean {
        val instruction = code.getOrNull(index) ?: return false
        val ref = instruction.methodRef() ?: return false
        val args = instruction as? FiveRegisterInstruction ?: return false
        return instruction.opcode == Opcode.INVOKE_VIRTUAL && ref.name == name &&
            ref.parameterTypes == parameters && args.registerCount == registers.size &&
            listOf(args.registerC, args.registerD, args.registerE).take(registers.size) == registers
    }
    layout(height >= 3 && height + 6 < code.size && code[height].registerA() == 9 &&
        code[height - 3].opcode == Opcode.NEW_INSTANCE && code[height - 3].registerA() == 4 &&
        call(height - 1, "append", listOf("Ljava/lang/String;"), listOf(4, 0)) &&
        call(height + 1, "append", listOf("Ljava/lang/String;"), listOf(4, 9)) &&
        call(height + 2, "append", listOf("I"), listOf(4, 1)) &&
        call(height + 3, "toString", emptyList(), listOf(4)) &&
        call(height + 5, "replace", listOf("Ljava/lang/CharSequence;", "Ljava/lang/CharSequence;"), listOf(14, 0, 1)) &&
        code[height + 6].opcode == Opcode.MOVE_RESULT_OBJECT && code[height + 6].registerA() == 14 &&
        (height - 1..height + 3).filter { it != height }.all {
            code[it].methodRef()?.definingClass == "Ljava/lang/StringBuilder;"
        } && code[height + 5].methodRef()?.definingClass == "Ljava/lang/String;", "comment image height suffix")
    val result = code[height + 4]
    if (result.opcode == Opcode.MOVE_OBJECT && result.registerA() == 1 &&
        (result as TwoRegisterInstruction).registerB == 0) {
        throw PatchException("Comment image URL preservation is already installed.")
    }
    layout(result.opcode == Opcode.MOVE_RESULT_OBJECT && result.registerA() == 1, "comment image height result")
    // Sync appends &height=<metadata y> to a signed CDN URL, which Reddit rejects with 403.
    // Reuse the source URL as the replacement instead. Keep instruction widths, branches and
    // try blocks unchanged; rememberMetadata already supplies the renderer's dimensions.
    parse.replaceInstruction(height + 4, "move-object v1, v0")
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
        preserveSyncCommentImageUrls(parser)
        captureSyncInlineImageMetadata(parser)
    }
}

private const val VIDEO = "Lapp/revanced/extension/synccompat/CommentVideo;"

val showSyncCommentVideoPreviews = bytecodePatch(
    name = "Sync: show comment video previews",
    description = "Draw Reddit-hosted and Imgur MP4/gifv comment videos as a still frame with a play button within the reply width, and open reddit.com/link/.../video/... links in Sync's video viewer instead of a browser redirect to a missing post.",
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
        // CommentVideo.load preserves this binding across Glide's target-clear callback.
        layout(spanClass.fields.any { it.name == "c" && it.type == "Ljava/lang/ref/WeakReference;" } &&
            spanClass.methods.any { it.name == "p" && it.parameterTypes == listOf("Landroid/widget/TextView;") &&
                it.returnType == "V" }, "inline image span weak TextView binding")
        // Verified against nb.c's constructor, getSize and draw: s/t are the actual box metrics.
        val dimensions = listOf("s", "t").map { name ->
            spanClass.fields.singleOrNull { it.name == name && it.type == "I" && AccessFlags.FINAL.isSet(it.accessFlags) }
                ?: throw PatchException("Unsupported Sync image span dimension: $name")
        }
        val constructor = spanClass.methods.singleOrNull {
            it.name == "<init>" && it.parameterTypes == listOf("Ljava/lang/String;", "I", "I", "Z")
        } ?: throw PatchException("Unsupported Sync image span constructor.")
        val writes = constructor.implementation!!.instructions.filter { it.opcode == Opcode.IPUT }
        layout(writes.size == 2 && writes.indices.all { i ->
            val instruction = writes[i] as TwoRegisterInstruction
            instruction.registerA == i + 2 && instruction.registerB == 0 &&
                ((writes[i] as ReferenceInstruction).reference as FieldReference).name == dimensions[i].name
        }, "inline image span dimensions")
        dimensions.forEach { it.accessFlags = it.accessFlags and AccessFlags.FINAL.value.inv() }
        if (spanClass.methods.any { it.name == "syncCompatResize" }) throw PatchException("Comment video resize patch is already installed.")
        val resizeMethod = MutableMethod(ImmutableMethod(span, "syncCompatResize",
            listOf("I", "I").map { com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter(it, null, null) },
            "Z", AccessFlags.PUBLIC.value, emptySet(), emptySet(), null))
        resizeMethod.resetBody(4, """
            if-lez p1, :unchanged
            if-lez p2, :unchanged
            iget v0, p0, $span->s:I
            if-ne v0, p1, :resize
            iget v0, p0, $span->t:I
            if-eq v0, p2, :unchanged
            :resize
            iput p1, p0, $span->s:I
            iput p2, p0, $span->t:I
            const/4 v0, 0x1
            return v0
            :unchanged
            const/4 v0, 0x0
            return v0
        """.trimIndent())
        spanClass.methods.add(resizeMethod)
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
private const val POST = "Lxa/d;"
private const val AWARDS_VIEW = "Lcom/laurencedawson/reddit_sync/ui/views/text/spannable/children/image_components/awards/AwardsTextView;"
private const val SELECTION_ITEM = "Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet\$h;"
private const val SHEET_DISMISS = "Lcom/google/android/material/bottomsheet/BottomSheetDialogFragment;->x3()V"
private const val JSON_OBJECT = "Lorg/json/JSONObject;"

val handleSyncAwardData = bytecodePatch(
    name = "Sync: handle unavailable award data",
    description = "Treat missing or changed award metadata as optional: listings stay usable, the awards sheet loads from the item's comment page instead of /api/info, shows an accurate message for failed, empty or unavailable award data, and offers the item on reddit.com when Reddit doesn't list its awards.",
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

        // Awards sheet lookup (y8.c). The original requested GET /api/info/?id=, threw on any
        // missing field and reported "Could not load gallery".
        val request = classes.singleOrNull { it.type == "Ly8/c;" && it.sourceFile == "OAuthAwardsRequest.java" }
            ?: throw PatchException("Expected Sync OAuthAwardsRequest was not found. This patch targets build 23033 only.")
        val requestClass = proxy(request).mutableClass
        val parse = requestClass.methods.singleOrNull {
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
        // Its URL builder, b("t3_" or "t1_", id), appended both to /api/info/?id= (issue #48).
        val lookupUrl = requestClass.methods.singleOrNull {
            it.name == "b" && it.parameterTypes == listOf("Ljava/lang/String;", "Ljava/lang/String;") &&
                it.returnType == "Ljava/lang/String;" && AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: throw PatchException("Unsupported awards request URL builder.")
        layout(lookupUrl.implementation!!.instructions.any { it.stringValue() == "https://oauth.reddit.com/api/info/?id=" },
            "awards request URL builder")
        // The comment sheet knows only the comment ID, so record its post where Sync opens the
        // sheet: AwardsTextView.H launches t9.g with Post.U() for a comment (kind 11).
        val view = classes.singleOrNull { it.type == AWARDS_VIEW && it.sourceFile == "AwardsTextView.java" }
            ?: throw PatchException("Expected Sync AwardsTextView was not found. This patch targets build 23033 only.")
        val viewClass = proxy(view).mutableClass
        val opener = viewClass.methods.singleOrNull {
            it.name == "H" && it.parameterTypes == listOf("Landroid/view/View;") && it.returnType == "V"
        } ?: throw PatchException("Unsupported awards sheet opener.")
        val openCode = opener.implementation!!.instructions
        val commentSheet = openCode.singleIndex("comment awards sheet launch") {
            openCode[it].opcode == Opcode.CONST_CLASS && (openCode[it] as ReferenceInstruction).reference.toString() == "Lt9/g;"
        }
        val launch = openCode.drop(commentSheet).take(12).mapNotNull { it.methodRef()?.toString() }
        layout(viewClass.fields.any { it.name == "E" && it.type == POST } && opener.implementation!!.registerCount >= 4 &&
            commentSheet > 0 && openCode[commentSheet - 1].opcode == Opcode.IF_NE &&
            openCode.none { it is BuilderOffsetInstruction && it.target.location.index == commentSheet } &&
            "$POST->U()Ljava/lang/String;" in launch &&
            "Ls9/g;->g(Ljava/lang/Class;Landroidx/fragment/app/FragmentManager;Ljava/lang/String;)V" in launch &&
            openCode.none { it.methodRef()?.definingClass == AWARDS }, "awards sheet opener")
        // Post.U() is the item's _id and Post.j0() its link_id (AutoValue_Post's column mapping).
        val post = classes.singleOrNull { it.type == POST && it.sourceFile == "Post.java" }
            ?: throw PatchException("Expected Sync Post model was not found. This patch targets build 23033 only.")
        val columns = classes.singleOrNull { it.type == "Lxa/c;" && it.sourceFile == "AutoValue_Post.java" }?.methods?.singleOrNull {
            it.name == "c1" && it.parameterTypes.isEmpty() && it.returnType == "Landroid/content/ContentValues;"
        }?.implementation?.instructions?.toList() ?: emptyList()
        fun column(getter: String): String? = columns.indexOfFirst {
            it.methodRef()?.let { call -> call.name == getter && call.parameterTypes.isEmpty() } == true
        }.takeIf { it >= 0 }?.let { index -> columns.drop(index).take(4).firstNotNullOfOrNull { it.stringValue() } }
        layout(listOf("U", "j0").all { name ->
            post.methods.any { it.name == name && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" }
        } && column("U") == "_id" && column("j0") == "link_id", "Post model")

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
        lookupUrl.resetBody(2, """
            invoke-static {p0, p1}, $AWARDS->awardsUrl(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
            move-result-object p0
            return-object p0
        """.trimIndent())
        // E is non-null here (H returned earlier otherwise); v0 and v1 are rewritten right after.
        opener.addInstructions(commentSheet, """
            iget-object v0, p0, $AWARDS_VIEW->E:$POST
            invoke-virtual {v0}, $POST->U()Ljava/lang/String;
            move-result-object v1
            invoke-virtual {v0}, $POST->j0()Ljava/lang/String;
            move-result-object v0
            invoke-static {v1, v0}, $AWARDS->rememberComment(Ljava/lang/String;Ljava/lang/String;)V
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

private const val PROXY = "Lapp/revanced/extension/synccompat/MediaProxyCompat;"
private const val LISTENER = "Lcom/android/volley/Response\$Listener;"
private const val ERROR_LISTENER = "Lcom/android/volley/Response\$ErrorListener;"
private const val GLIDE_REQUEST = "Lcom/bumptech/glide/f;"

val replaceSyncMediaProxies = bytecodePatch(
    name = "Sync: replace legacy media proxies",
    description = "Resolve Imgur albums, gallery posts and album link-card thumbnails directly over HTTPS instead of the defunct images.syncforreddit.com and ap.syncforreddit.com proxies; resolve enhanced autoplay of RedGifs posts through Sync's RedGifs resolver; show link cards instead of proxy website previews, Gfycat thumbnails and web pages handed to the image decoder.",
) {
    compatibleWith("com.laurencedawson.reddit_sync"("v23.06.30-13:39"))
    dependsOn(extensionPatch)
    apply {
        fun target(type: String, source: String): MutableClassDef = proxy(
            classes.singleOrNull { it.type == type && it.sourceFile == source }
                ?: throw PatchException("Expected Sync $source was not found. This patch targets build 23033 only.")
        ).mutableClass
        fun MutableClassDef.method(name: String, parameters: List<String>, returns: String): MutableMethod =
            methods.singleOrNull { it.name == name && it.parameterTypes == parameters && it.returnType == returns }
                ?: throw PatchException("Unsupported Sync method $type->$name.")
        fun MutableMethod.code() = implementation!!.instructions
        fun MutableMethod.calls(owner: String, name: String) = code().any {
            it.methodRef()?.let { m -> m.definingClass == owner && m.name == name } == true
        }
        fun MutableMethod.hasString(value: String) = code().any { it.stringValue() == value }
        fun MutableMethod.requireUnpatched() {
            if (code().any { it.methodRef()?.definingClass == PROXY }) {
                throw PatchException("Media proxy patch is already installed.")
            }
        }

        // Imgur albums and gallery posts (s8.b): images.syncforreddit.com/a/<md5>/<id> becomes the
        // album's public embed page; its item list is adapted to the JSON shape parsed below.
        val album = target("Ls8/b;", "ImgurGalleryRequest.java")
        val albumUrl = album.method("a", listOf("Ljava/lang/String;"), "Ljava/lang/String;")
        val albumParse = album.method("parseNetworkResponse", listOf(NETWORK), "Lcom/android/volley/Response;")
        val albumError = album.method("parseNetworkError", listOf(VOLLEY_ERROR), VOLLEY_ERROR)
        listOf(albumUrl, albumParse, albumError).forEach { it.requireUnpatched() }
        layout(albumUrl.calls("Lg8/b;", "a") && albumParse.hasString("images") && albumParse.hasString("mp4") &&
            albumParse.hasString("description") && albumError.hasString("Unable to find an album with the id"), "Imgur album request")
        albumUrl.resetBody(1, """
            invoke-static {p0}, $PROXY->albumRequestUrl(Ljava/lang/String;)Ljava/lang/String;
            move-result-object p0
            return-object p0
        """.trimIndent())
        albumParse.addInstructions(0, """
            invoke-static/range {p1 .. p1}, $PROXY->albumResponse($NETWORK)$NETWORK
            move-result-object p1
        """.trimIndent())
        // A missing album (HTTP 404) takes Sync's existing "single image" fallback below.
        albumError.addInstructions(0, """
            invoke-static/range {p1 .. p1}, $PROXY->albumError($VOLLEY_ERROR)$VOLLEY_ERROR
            move-result-object p1
        """.trimIndent())

        // Single Imgur items (s8.d), Sync's fallback when a gallery link is not an album.
        val image = target("Ls8/d;", "ImgurSingleImageRequest.java")
        val imageUrl = image.method("a", listOf("Ljava/lang/String;"), "Ljava/lang/String;")
        val imageParse = image.method("parseNetworkResponse", listOf(NETWORK), "Lcom/android/volley/Response;")
        listOf(imageUrl, imageParse).forEach { it.requireUnpatched() }
        layout(imageUrl.calls("Lg8/d;", "a") && imageParse.hasString("link") &&
            imageParse.implementation!!.registerCount <= 15, "Imgur single image request")
        imageUrl.resetBody(1, """
            invoke-static {p0}, $PROXY->imageRequestUrl(Ljava/lang/String;)Ljava/lang/String;
            move-result-object p0
            return-object p0
        """.trimIndent())
        // v0 is written before it is read in the original body.
        imageParse.addInstructions(0, """
            invoke-virtual {p0}, Lcom/android/volley/Request;->getUrl()Ljava/lang/String;
            move-result-object v0
            invoke-static {p1, v0}, $PROXY->imageResponse(${NETWORK}Ljava/lang/String;)$NETWORK
            move-result-object p1
        """.trimIndent())

        // Website previews (wc.q): ap.syncforreddit.com/image?url=<page> rendered a page as a
        // picture. Only YouTube's own thumbnail stays; other links get Sync's link card.
        val website = target("Lwc/q;", "WebsitePreviewHelper.java")
        val previewAllowed = website.method("a", listOf("Ljava/lang/String;"), "Z")
        val previewImage = website.method("b", listOf("Ljava/lang/String;"), "Ljava/lang/String;")
        listOf(previewAllowed, previewImage).forEach { it.requireUnpatched() }
        layout(previewAllowed.code().any { (it as? ReferenceInstruction)?.reference.let { r ->
                r is FieldReference && r.name == "ultra_website_previews" } } &&
            previewAllowed.calls("Lwc/q;", "c") &&
            previewImage.hasString("https://ap.syncforreddit.com/image?url=") &&
            previewImage.hasString("https://i.ytimg.com/vi/") && previewImage.hasString("/hqdefault.jpg") &&
            previewImage.calls("Ly7/c;", "K") && previewImage.calls("Ly7/a;", "v"), "website preview helper")
        previewAllowed.addInstructionsWithLabels(0, """
            invoke-static {p0}, $PROXY->websitePreviewAllowed(Ljava/lang/String;)Z
            move-result v0
            if-nez v0, :original
            const/4 v0, 0x0
            return v0
            :original
            nop
        """.trimIndent())
        previewImage.resetBody(3, """
            invoke-static {p0}, Ly7/c;->K(Ljava/lang/String;)Z
            move-result v0
            if-eqz v0, :link
            invoke-static {p0}, Ly7/a;->v(Ljava/lang/String;)Ljava/lang/String;
            move-result-object p0
            new-instance v0, Ljava/lang/StringBuilder;
            invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
            const-string v1, "https://i.ytimg.com/vi/"
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v1, "/hqdefault.jpg"
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object p0
            return-object p0
            :link
            const-string p0, ""
            return-object p0
        """.trimIndent())

        // Link-card thumbnails (oc.c.o, nb.d branch). Register roles are fixed by build 23033 and
        // checked: v1 = card span, v9 = thumbnail model, v2 = Glide context, v10 free.
        val builder = target("Loc/c;", "SpannableTextViewStringBuilder.java")
        val load = builder.methods.singleOrNull {
            it.name == "o" && it.parameterTypes.size == 1 && it.returnType == "V" &&
                it.implementation?.instructions?.any { i -> i.opcode == Opcode.INSTANCE_OF &&
                    (i as ReferenceInstruction).reference.toString() == "Lnb/d;" } == true
        } ?: throw PatchException("Unsupported Sync span loader.")
        load.requireUnpatched()
        val code = load.code()
        val albumProxy = code.singleIndex("album thumbnail proxy") {
            code[it].stringValue() == "https://ap.syncforreddit.com/image?url=imgur-album-"
        }
        val context = (albumProxy until code.size).firstOrNull {
            code[it].methodRef()?.let { m -> m.definingClass == "Lcom/laurencedawson/reddit_sync/RedditApplication;" && m.name == "f" } == true
        } ?: throw PatchException("Unsupported link-card thumbnail load.")
        val into = (context until code.size).firstOrNull {
            code[it].methodRef()?.let { m -> m.definingClass == GLIDE_REQUEST && m.name == "y0" } == true
        } ?: throw PatchException("Unsupported link-card thumbnail load.")
        fun FiveRegisterInstruction.registers() = listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        val chain = code.subList(context, into + 1).mapNotNull { it.methodRef()?.toString() }
        val glideWith = "Lcom/bumptech/glide/b;->u(Landroid/content/Context;)Lcom/bumptech/glide/g;"
        val glideLoad = "Lcom/bumptech/glide/g;->w(Ljava/lang/Object;)$GLIDE_REQUEST"
        val dp = "Lt7/f0;->c(I)I"
        val size = "Lt3/a;->Y(II)Lt3/a;"
        val priority = "Lt3/a;->Z(Lw2/c;)Lt3/a;"
        val transforms = "Lt3/a;->n0([La3/m;)Lt3/a;"
        val crop = "Lk3/i;-><init>()V"
        val corners = "Lk3/s;-><init>(FFFF)V"
        val glideInto = "$GLIDE_REQUEST->y0(Lu3/j;)Lu3/j;"
        val loadIndex = (context until into).firstOrNull { code[it].methodRef()?.toString() == glideLoad }
        layout(load.implementation!!.registerCount == 14 &&
            code[context + 1].opcode == Opcode.MOVE_RESULT_OBJECT && code[context + 1].registerA() == 2 &&
            code[context + 2].methodRef()?.toString() == glideWith &&
            (code[context + 2] as FiveRegisterInstruction).registers() == listOf(2) &&
            loadIndex != null && (code[loadIndex] as FiveRegisterInstruction).registers() == listOf(2, 9) &&
            (code[into] as FiveRegisterInstruction).registers() == listOf(2, 1) &&
            code[into + 1].opcode in setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32) &&
            listOf(dp, size, priority, transforms, crop, corners, glideInto).all { it in chain } &&
            code.subList(context, into).any { it.opcode == Opcode.SGET_OBJECT &&
                (it as ReferenceInstruction).reference.toString() == "Lw2/c;->q:Lw2/c;" } &&
            code.subList(0, albumProxy).any { it.opcode == Opcode.CHECK_CAST && it.registerA() == 1 &&
                (it as ReferenceInstruction).reference.toString() == "Lnb/d;" } &&
            code.subList(0, albumProxy).any { it.opcode == Opcode.CONST_16 && it.registerA() == 8 &&
                (it as NarrowLiteralInstruction).narrowLiteral == 0x38 } &&
            code.subList(0, albumProxy).any { it.opcode == Opcode.CONST_16 && it.registerA() == 7 &&
                (it as NarrowLiteralInstruction).narrowLiteral == 0xc }, "link-card thumbnail load")
        val next = code[into + 1].branchTarget()
        // After Sync's own model choice and its Glide context: no branch targets this point.
        load.addInstructionsWithLabels(context + 2, """
            invoke-virtual {v1}, Lnb/d;->n()Ljava/lang/String;
            move-result-object v10
            invoke-static {v1, v10, v9}, $PROXY->thumbnail(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v9
            if-nez v9, :load
            goto/16 :next
            :load
            nop
        """.trimIndent(), ExternalLabel("next", next))

        // Link-card span (nb.d): syncCompatThumbnail(model) repeats the card's Glide request above
        // (56dp, low priority, centre crop, 12dp left corners) once an album cover is resolved.
        val card = target("Lnb/d;", "InlineImageSpan.java")
        layout(card.interfaces.contains("Lu3/j;") &&
            card.methods.any { it.name == "n" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" } &&
            card.methods.any { it.name == "s" && it.parameterTypes == listOf("Landroid/widget/TextView;") }, "link-card span")
        if (card.methods.any { it.name == "syncCompatThumbnail" }) throw PatchException("Media proxy patch is already installed.")
        val thumbnail = MutableMethod(ImmutableMethod(card.type, "syncCompatThumbnail",
            listOf(com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
            "V", AccessFlags.PUBLIC.value, emptySet(), emptySet(), null))
        thumbnail.resetBody(7, """
            invoke-static {}, Lcom/laurencedawson/reddit_sync/RedditApplication;->f()Landroid/content/Context;
            move-result-object v0
            invoke-static {v0}, $glideWith
            move-result-object v0
            invoke-virtual {v0, p1}, $glideLoad
            move-result-object v0
            const/16 v1, 0x38
            invoke-static {v1}, $dp
            move-result v1
            invoke-virtual {v0, v1, v1}, $size
            move-result-object v0
            check-cast v0, $GLIDE_REQUEST
            sget-object v1, Lw2/c;->q:Lw2/c;
            invoke-virtual {v0, v1}, $priority
            move-result-object v0
            check-cast v0, $GLIDE_REQUEST
            const/4 v1, 0x2
            new-array v1, v1, [La3/m;
            new-instance v2, Lk3/i;
            invoke-direct {v2}, $crop
            const/4 v3, 0x0
            aput-object v2, v1, v3
            new-instance v2, Lk3/s;
            const/16 v3, 0xc
            invoke-static {v3}, $dp
            move-result v3
            int-to-float v3, v3
            const/4 v4, 0x0
            invoke-direct {v2, v3, v4, v4, v3}, $corners
            const/4 v3, 0x1
            aput-object v2, v1, v3
            invoke-virtual {v0, v1}, $transforms
            move-result-object v0
            check-cast v0, $GLIDE_REQUEST
            invoke-virtual {v0, p0}, $glideInto
            return-void
        """.trimIndent())
        card.methods.add(thumbnail)

        // Enhanced autoplay (lc.f.z): ap.syncforreddit.com/image?url=redgifs-<id> and gfycat-<id>.
        // RedGifs posts go through Sync's own resolver (t8.d via m8.a.e), whose RedGifs token is
        // separate from Reddit's; the player starts from its callback. Others use Sync's normal URL.
        val player = target("Llc/f;", "SimpleExoPlayerTextureView.java")
        val autoplay = player.method("z", listOf("Lxa/d;", "Z", "Z"), "V")
        autoplay.requireUnpatched()
        val wrapper = classes.singleOrNull { it.type == "Lt8/d;" && it.sourceFile == "RedgifRequestWrapper.java" }
        val manager = classes.singleOrNull { it.type == "Lm8/a;" && it.sourceFile == "OAuthRequestManager.java" }
        val settings = classes.singleOrNull { it.type == SETTINGS_VALUES }
        layout(autoplay.hasString("https://ap.syncforreddit.com/image?url=redgifs-") &&
            autoplay.hasString("https://ap.syncforreddit.com/image?url=gfycat-") &&
            autoplay.calls("Llc/f;", "x") && autoplay.calls("Lxa/d;", "e1") && autoplay.calls("Lxa/d;", "g1") &&
            player.methods.any { it.name == "x" && it.parameterTypes == listOf("Ljava/lang/String;", "Z", "Z") &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) } &&
            wrapper?.methods?.any { it.name == "<init>" &&
                it.parameterTypes == listOf("Ljava/lang/String;", "Z", LISTENER, ERROR_LISTENER) } == true &&
            manager?.methods?.any { it.name == "e" && it.parameterTypes == listOf("Lt8/d;") &&
                AccessFlags.STATIC.isSet(it.accessFlags) } == true &&
            settings?.fields?.any { it.name == "enhancedAutoPlay" && it.type == "Z" } == true, "enhanced autoplay")
        autoplay.resetBody(13, """
            invoke-virtual {p1}, Lxa/d;->e1()Ljava/lang/String;
            move-result-object v4
            invoke-virtual {p1}, Lxa/d;->g1()Ljava/lang/String;
            move-result-object v5
            invoke-static {}, $SETTINGS->x()$SETTINGS_VALUES
            move-result-object v6
            iget-boolean v6, v6, $SETTINGS_VALUES->enhancedAutoPlay:Z
            move-object v3, p0
            move v7, p2
            move v8, p3
            invoke-static/range {v3 .. v8}, $PROXY->autoplayUrl(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;ZZZ)Ljava/lang/String;
            move-result-object v0
            if-eqz v0, :wait
            invoke-virtual {p0, v0, p2, p3}, Llc/f;->x(Ljava/lang/String;ZZ)V
            return-void
            :wait
            invoke-static {p0}, $PROXY->takeRedgifsRequest(Ljava/lang/Object;)Ljava/lang/String;
            move-result-object v0
            if-eqz v0, :done
            invoke-static {v0}, $PROXY->redgifsListener(Ljava/lang/String;)$LISTENER
            move-result-object v1
            invoke-static {v0}, $PROXY->redgifsErrorListener(Ljava/lang/String;)$ERROR_LISTENER
            move-result-object v2
            new-instance v3, Lt8/d;
            const/4 v4, 0x0
            invoke-direct {v3, v0, v4, v1, v2}, Lt8/d;-><init>(Ljava/lang/String;Z${LISTENER}$ERROR_LISTENER)V
            invoke-static {v3}, Lm8/a;->e(Lt8/d;)V
            :done
            return-void
        """.trimIndent())

        // The replaced builders no longer reach either proxy host.
        for (method in listOf(albumUrl, imageUrl, previewImage, autoplay)) {
            layout(method.code().none { it.stringValue()?.contains("syncforreddit.com") == true } &&
                method.code().none { it.methodRef()?.definingClass in setOf("Lg8/b;", "Lg8/d;") }, "proxy-free media builder")
        }
    }
}

private const val LINKS = "Lapp/revanced/extension/synccompat/LinkNormalizer;"
private const val LINK_HELPER = "Ly7/b;"
private const val LINK_TYPES = "Ly7/c;"
private const val INHERITED_SLINK_HOOK = "Lapp/revanced/extension/syncforreddit/FixSLinksPatch;"
private const val MAIN_ACTIVITY = "Lcom/laurencedawson/reddit_sync/ui/activities/MainActivity;"

val normalizeSyncRedditLinks = bytecodePatch(
    name = "Sync: normalize Reddit links",
    description = "Open equivalent Reddit post, comment, share, gallery, profile, subreddit, redd.it and media-wrapper " +
        "links at the same place in Sync; keep context, sort and signed media parameters and drop known tracking parameters.",
) {
    compatibleWith("com.laurencedawson.reddit_sync"("v23.06.30-13:39"))
    dependsOn(extensionPatch)
    apply {
        fun mutable(type: String, what: String): MutableClassDef =
            proxy(classes.singleOrNull { it.type == type } ?: throw PatchException(
                "Expected Sync $what was not found. This patch targets build 23033 only.")).mutableClass
        fun MutableMethod.code() = implementation!!.instructions
        fun MutableMethod.hasString(value: String) = code().any { it.stringValue() == value }
        fun MutableMethod.targeted(index: Int): Boolean {
            val target = code()[index]
            return code().any { it is BuilderOffsetInstruction && it.target.location.instruction === target }
        }
        val strings = List(5) { "Ljava/lang/String;" }

        // Every in-app tap, VIEW/SEND intent and warm start reaches this LinkHelper entry.
        val helper = mutable(LINK_HELPER, "LinkHelper").methods.singleOrNull {
            it.name == "c" && it.returnType == "Z" && it.parameterTypes == listOf("Landroid/content/Context;", "[Lr0/d;") +
                strings + listOf("Z", "Z", "Z")
        } ?: throw PatchException("Unsupported Sync LinkHelper entry. This patch targets build 23033 only.")
        if (helper.code().any { it.methodRef()?.definingClass == LINKS })
            throw PatchException("Reddit link normalization is already installed.")
        // p3 is the link. The inherited share-link hook must still be the first instruction.
        val url = helper.implementation!!.registerCount - 10 + 3
        val first = helper.code().firstOrNull()
        layout(first?.opcode == Opcode.INVOKE_STATIC && first.methodRef()?.let {
            it.definingClass == INHERITED_SLINK_HOOK && it.name == "patchResolveSLink"
        } == true && (first as FiveRegisterInstruction).registerC == url && !helper.targeted(0) &&
            listOf("LinkHelper", "Was gallery", "Was shortened", "Was comments", "Was reddit user").all(helper::hasString) &&
            helper.code().any { it.methodRef()?.let { m -> m.definingClass == LINK_TYPES && m.name == "q" } == true },
            "Sync LinkHelper")

        // Sync's gallery classifier only knows its own synthetic ?is_gallery=true form.
        val gallery = mutable(LINK_TYPES, "link classifier").methods.singleOrNull {
            it.name == "q" && it.parameterTypes == listOf("Ljava/lang/String;") && it.returnType == "Z"
        } ?: throw PatchException("Unsupported Sync gallery link classifier.")
        layout(gallery.implementation!!.registerCount >= 2 && gallery.hasString("?is_gallery=true") &&
            gallery.hasString("https://www.reddit.com/r/"), "Sync gallery link classifier")

        // Cold start checks for a subreddit before handing the link to LinkHelper.
        val start = mutable(MAIN_ACTIVITY, "MainActivity").methods.singleOrNull {
            it.name == "onCreate" && it.parameterTypes == listOf("Landroid/os/Bundle;") && it.returnType == "V"
        } ?: throw PatchException("Unsupported Sync MainActivity.")
        val startCode = start.code()
        val subreddit = startCode.singleIndex("cold-start subreddit check") { i ->
            startCode[i].methodRef()?.let { it.definingClass == LINK_TYPES && it.name == "B" } == true
        }
        val coldLink = (startCode[subreddit] as FiveRegisterInstruction).registerC
        layout(startCode.subList(maxOf(0, subreddit - 4), subreddit).any {
            it.methodRef()?.name == "removeExtra"
        } && startCode.drop(subreddit).any {
            it is FiveRegisterInstruction && it.registerCount == 2 && it.registerD == coldLink &&
                it.methodRef()?.let { m -> m.definingClass == LINK_HELPER && m.name == "b" } == true
        } && !start.targeted(subreddit), "cold-start link handoff")

        // All layouts verified; edit.
        helper.addInstructions(0, """
            invoke-static {p3}, $LINKS->normalize(Ljava/lang/String;)Ljava/lang/String;
            move-result-object p3
        """.trimIndent())
        gallery.addInstructionsWithLabels(0, """
            invoke-static {p0}, $LINKS->isGalleryLink(Ljava/lang/String;)Z
            move-result v0
            if-eqz v0, :original
            const/4 v0, 0x1
            return v0
        """.trimIndent(), ExternalLabel("original", gallery.code()[0]))
        start.addInstructions(subreddit, """
            invoke-static {v$coldLink}, $LINKS->normalize(Ljava/lang/String;)Ljava/lang/String;
            move-result-object v$coldLink
        """.trimIndent())
    }
}

private const val REDGIFS_REFRESH = "Lapp/revanced/extension/synccompat/RedgifsTokenRefresh;"
private const val OKHTTP_HELPER = "La8/a;"
private const val REDGIFS_INSTALLER = "Lapp/revanced/extension/syncforreddit/FixRedgifsApiPatch;"
private const val REDGIFS_INTERCEPTOR = "Lapp/revanced/extension/shared/fixes/redgifs/BaseFixRedgifsApiPatch;"
private const val REDGIFS_TOKENS = "Lapp/revanced/extension/shared/fixes/redgifs/RedgifsTokenManager;"
private const val REDGIFS_TOKEN = "Lapp/revanced/extension/shared/fixes/redgifs/RedgifsTokenManager\$RedgifsToken;"
private const val OKHTTP_CLIENT = "Lokhttp3/OkHttpClient;"
private const val OKHTTP_BUILDER = "Lokhttp3/OkHttpClient\$Builder;"
private const val OKHTTP_RESPONSE_BUILDER = "Lokhttp3/Response\$Builder;"
private const val REDGIFS_IP_REQUEST = "Lt8/a;"

/** OkHttp members the refresh calls; each must exist in the base's OkHttp. */
private val REDGIFS_OKHTTP_MEMBERS = mapOf(
    OKHTTP_CLIENT to listOf("newBuilder()$OKHTTP_BUILDER", "newCall(Lokhttp3/Request;)Lokhttp3/Call;"),
    OKHTTP_BUILDER to listOf("interceptors()Ljava/util/List;", "networkInterceptors()Ljava/util/List;",
        "addInterceptor(Lokhttp3/Interceptor;)$OKHTTP_BUILDER", "followRedirects(Z)$OKHTTP_BUILDER",
        "followSslRedirects(Z)$OKHTTP_BUILDER", "connectTimeout(JLjava/util/concurrent/TimeUnit;)$OKHTTP_BUILDER",
        "readTimeout(JLjava/util/concurrent/TimeUnit;)$OKHTTP_BUILDER", "writeTimeout(JLjava/util/concurrent/TimeUnit;)$OKHTTP_BUILDER",
        "callTimeout(JLjava/util/concurrent/TimeUnit;)$OKHTTP_BUILDER", "build()$OKHTTP_CLIENT"),
    "Lokhttp3/Interceptor;" to listOf("intercept(Lokhttp3/Interceptor\$Chain;)Lokhttp3/Response;"),
    "Lokhttp3/Interceptor\$Chain;" to listOf("request()Lokhttp3/Request;", "proceed(Lokhttp3/Request;)Lokhttp3/Response;"),
    "Lokhttp3/Call;" to listOf("execute()Lokhttp3/Response;"),
    "Lokhttp3/Request;" to listOf("url()Lokhttp3/HttpUrl;", "method()Ljava/lang/String;",
        "header(Ljava/lang/String;)Ljava/lang/String;", "newBuilder()Lokhttp3/Request\$Builder;"),
    "Lokhttp3/Request\$Builder;" to listOf("<init>()V", "url(Ljava/lang/String;)Lokhttp3/Request\$Builder;",
        "header(Ljava/lang/String;Ljava/lang/String;)Lokhttp3/Request\$Builder;", "get()Lokhttp3/Request\$Builder;",
        "build()Lokhttp3/Request;"),
    "Lokhttp3/HttpUrl;" to listOf("isHttps()Z", "host()Ljava/lang/String;", "encodedPath()Ljava/lang/String;"),
    "Lokhttp3/Response;" to listOf("code()I", "body()Lokhttp3/ResponseBody;", "request()Lokhttp3/Request;", "close()V"),
    "Lokhttp3/ResponseBody;" to listOf("contentLength()J", "byteStream()Ljava/io/InputStream;",
        "create(Ljava/lang/String;Lokhttp3/MediaType;)Lokhttp3/ResponseBody;"),
    "Lokhttp3/MediaType;" to listOf("get(Ljava/lang/String;)Lokhttp3/MediaType;"),
    OKHTTP_RESPONSE_BUILDER to listOf("<init>()V", "request(Lokhttp3/Request;)$OKHTTP_RESPONSE_BUILDER",
        "protocol(Lokhttp3/Protocol;)$OKHTTP_RESPONSE_BUILDER", "code(I)$OKHTTP_RESPONSE_BUILDER",
        "message(Ljava/lang/String;)$OKHTTP_RESPONSE_BUILDER",
        "header(Ljava/lang/String;Ljava/lang/String;)$OKHTTP_RESPONSE_BUILDER",
        "body(Lokhttp3/ResponseBody;)$OKHTTP_RESPONSE_BUILDER", "build()Lokhttp3/Response;"),
)

val refreshSyncRedgifsTokens = bytecodePatch(
    name = "Sync: refresh RedGifs tokens",
    description = "Answer Sync's RedGifs address lookup (RedGifs retired /info) from a temporary-token response, and when " +
        "RedGifs rejects the base's cached RedGifs token with HTTP 401 or 403, fetch one new temporary token " +
        "(shared by concurrent requests, bounded in time and size, never logged) and retry the request once.",
) {
    compatibleWith("com.laurencedawson.reddit_sync"("v23.06.30-13:39"))
    dependsOn(extensionPatch)
    apply {
        fun inherited(type: String, what: String) = classes.singleOrNull { it.type == type } ?: throw PatchException(
            "Expected inherited ReVanced $what was not found. Use the supported ReVanced-patched Sync 23033 base.")
        fun Iterable<Instruction>.calls(owner: String, name: String) =
            any { it.methodRef()?.let { m -> m.definingClass == owner && m.name == name } == true }
        val public = AccessFlags.PUBLIC
        val static = AccessFlags.STATIC

        // The inherited cache the refresh updates by reflection: User-Agent -> RedgifsToken.
        val tokens = inherited(REDGIFS_TOKENS, "RedGifs token cache")
        layout(tokens.fields.any { it.name == "tokenMap" && it.type == "Ljava/util/Map;" && static.isSet(it.accessFlags) } &&
            tokens.methods.any { it.name == "refreshToken" && it.parameterTypes == listOf("Ljava/lang/String;") &&
                it.returnType == REDGIFS_TOKEN && static.isSet(it.accessFlags) }, "inherited RedGifs token cache")
        val token = inherited(REDGIFS_TOKEN, "RedGifs token")
        layout(public.isSet(token.accessFlags) && token.methods.any {
            it.name == "<init>" && it.parameterTypes == listOf("Ljava/lang/String;", "J") && public.isSet(it.accessFlags)
        } && token.methods.any {
            it.name == "getAccessToken" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" && public.isSet(it.accessFlags)
        }, "inherited RedGifs token")

        // The inherited interceptor whose cached-token retry this refresh completes.
        val interceptor = inherited(REDGIFS_INTERCEPTOR, "RedGifs interceptor")
        val intercept = interceptor.methods.singleOrNull {
            it.name == "intercept" && it.parameterTypes == listOf("Lokhttp3/Interceptor\$Chain;") && it.returnType == "Lokhttp3/Response;"
        }?.implementation?.instructions?.toList()
        layout(interceptor.interfaces.contains("Lokhttp3/Interceptor;") && intercept != null &&
            listOf("api.redgifs.com", "/v2/oauth/client", "Authorization", "User-Agent").all { s -> intercept.any { it.stringValue() == s } } &&
            intercept.calls(REDGIFS_TOKENS, "refreshToken") && intercept.calls("Lokhttp3/Response;", "isSuccessful"),
            "inherited RedGifs interceptor")
        val installer = inherited(REDGIFS_INSTALLER, "Sync RedGifs installer")
        val install = installer.methods.singleOrNull {
            it.name == "install" && it.parameterTypes == listOf(OKHTTP_BUILDER) && it.returnType == OKHTTP_CLIENT &&
                static.isSet(it.accessFlags) && public.isSet(it.accessFlags)
        }?.implementation?.instructions?.toList()
        layout(installer.superclass == REDGIFS_INTERCEPTOR && install != null &&
            install.calls(OKHTTP_BUILDER, "addInterceptor") && install.calls(OKHTTP_BUILDER, "build"), "inherited RedGifs installer")

        // Every OkHttp member the refresh calls must exist in this base's OkHttp.
        for ((type, members) in REDGIFS_OKHTTP_MEMBERS) {
            val clazz = classes.singleOrNull { it.type == type }
                ?: throw PatchException("Sync's OkHttp lacks $type. This patch targets Sync build 23033 only.")
            val present = clazz.methods.map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }.toSet()
            for (member in members) if (member !in present) {
                throw PatchException("Sync's OkHttp lacks $type->$member. This patch targets Sync build 23033 only.")
            }
        }

        val protocol = classes.singleOrNull { it.type == "Lokhttp3/Protocol;" }
        layout(protocol?.fields?.any { it.name == "HTTP_1_1" && it.type == "Lokhttp3/Protocol;" && static.isSet(it.accessFlags) } == true,
            "Sync OkHttp Protocol")

        // Sync's GrabIpRequest asks the retired /info endpoint for remote-addr; the refresh answers it locally.
        val ipRequest = classes.singleOrNull { it.type == REDGIFS_IP_REQUEST } ?: throw PatchException(
            "Expected Sync GrabIpRequest was not found. This patch targets build 23033 only.")
        layout(ipRequest.superclass == "Lcom/android/volley/Request;" && listOf("https://api.redgifs.com/info", "remote-addr").all { s ->
            ipRequest.methods.any { m -> m.implementation?.instructions?.any { it.stringValue() == s } == true }
        }, "Sync GrabIpRequest")

        // OkHttpHelper builds the client Sync's Volley network uses and installs the inherited interceptor once.
        val helper = proxy(classes.singleOrNull { it.type == OKHTTP_HELPER } ?: throw PatchException(
            "Expected Sync OkHttpHelper was not found. This patch targets build 23033 only.")).mutableClass
        if (helper.methods.any { m -> m.implementation?.instructions?.any { it.methodRef()?.definingClass == REDGIFS_REFRESH } == true }) {
            throw PatchException("RedGifs token refresh is already installed.")
        }
        val sites = helper.methods.flatMap { m ->
            m.implementation?.instructions?.withIndex()?.filter { (_, insn) ->
                insn.methodRef()?.let { it.definingClass == REDGIFS_INSTALLER && it.name == "install" } == true
            }?.map { m to it.index } ?: emptyList()
        }
        if (sites.size != 1) throw PatchException("Expected one inherited RedGifs install in Sync's OkHttpHelper, found ${sites.size}.")
        val (method, index) = sites.single()
        val code = method.implementation!!.instructions
        val call = code[index] as? FiveRegisterInstruction
        val result = code.getOrNull(index + 1)
        val store = code.getOrNull(index + 2)
        val stored = (store as? ReferenceInstruction)?.reference as? FieldReference
        layout(call != null && code[index].opcode == Opcode.INVOKE_STATIC && call.registerCount == 1 &&
            code[index].methodRef()?.let { m ->
                m.parameterTypes.map { it.toString() } == listOf(OKHTTP_BUILDER) && m.returnType == OKHTTP_CLIENT
            } == true &&
            result?.opcode == Opcode.MOVE_RESULT_OBJECT && store?.opcode == Opcode.SPUT_OBJECT &&
            result?.registerA() != null && store?.registerA() == result?.registerA() &&
            stored?.definingClass == OKHTTP_HELPER && stored?.type == OKHTTP_CLIENT,
            "Sync OkHttpHelper RedGifs install")

        // All layouts verified; edit. The refresh calls the inherited install, then adds itself inside it.
        method.replaceInstruction(index,
            "invoke-static {v${call!!.registerC}}, $REDGIFS_REFRESH->install($OKHTTP_BUILDER)$OKHTTP_CLIENT")
    }
}

private const val REDDIT_VIDEO = "Lapp/revanced/extension/synccompat/RedditVideoCompat;"
private const val VIDEO_PLAYER = "Lcom/laurencedawson/reddit_sync/ui/views/video/CustomExoPlayerView;"
private const val EXO_UTILS = "Lxc/a;"
private const val MEDIA_SOURCE = "Lcom/google/android/exoplayer2/source/MediaSource;"
private const val DASH_FACTORY = "Lcom/google/android/exoplayer2/source/dash/DashMediaSource\$Factory;"
private const val HLS_FACTORY = "Lcom/google/android/exoplayer2/source/hls/HlsMediaSource\$Factory;"

val playSyncRedditVideosViaDash = bytecodePatch(
    name = "Sync: play Reddit videos via DASH",
    description = "Open Reddit-hosted videos through their DASH manifest, which Reddit still serves without a signature, " +
        "instead of the unsigned HLS playlist Sync builds, which Reddit now refuses (HTTP 403).",
) {
    compatibleWith("com.laurencedawson.reddit_sync"("v23.06.30-13:39"))
    dependsOn(extensionPatch)
    apply {
        fun mutable(type: String, what: String): MutableClassDef =
            proxy(classes.singleOrNull { it.type == type } ?: throw PatchException(
                "Expected Sync $what was not found. This patch targets build 23033 only.")).mutableClass
        fun MutableMethod.code() = implementation!!.instructions
        fun MutableMethod.calls(owner: String, name: String) =
            code().any { it.methodRef()?.let { m -> m.definingClass == owner && m.name == name } == true }
        fun MutableMethod.hasString(value: String) = code().any { it.stringValue() == value }
        fun MutableMethod.targeted(index: Int): Boolean {
            val target = code()[index]
            return code().any { it is BuilderOffsetInstruction && it.target.location.instruction === target }
        }

        // Sync's player picks DASH for v.redd.it + .mpd (y7.c.s) and HLS for v.redd.it + .m3u8 (y7.c.t).
        val types = mutable(LINK_TYPES, "link classifier")
        val dash = types.methods.singleOrNull { it.name == "s" && it.parameterTypes == listOf("Ljava/lang/String;") && it.returnType == "Z" }
        val hls = types.methods.singleOrNull { it.name == "t" && it.parameterTypes == listOf("Ljava/lang/String;") && it.returnType == "Z" }
        layout(dash != null && dash.hasString("v.redd.it") && dash.hasString(".mpd") &&
            hls != null && hls.hasString("v.redd.it") && hls.hasString(".m3u8"), "Sync Reddit video classifier")

        // Full-screen viewer, pager and peek: rc.b.setSource stores the source, then starts the player.
        val player = mutable(VIDEO_PLAYER, "video player")
        val setSource = player.methods.singleOrNull {
            it.name == "setSource" && it.parameterTypes == listOf("Ljava/lang/String;", "Z", "Z") && it.returnType == "V"
        } ?: throw PatchException("Unsupported Sync video player setSource.")
        val initPlayer = player.methods.singleOrNull {
            it.name == "initPlayer" && it.parameterTypes == listOf("Z") && it.returnType == "V"
        } ?: throw PatchException("Unsupported Sync video player initPlayer.")
        // Feed autoplay: ExoplayerUtils.a(String) builds the media source itself.
        val utils = mutable(EXO_UTILS, "ExoplayerUtils")
        val autoplay = utils.methods.singleOrNull {
            it.name == "a" && it.parameterTypes == listOf("Ljava/lang/String;") && it.returnType == MEDIA_SOURCE &&
                AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: throw PatchException("Unsupported Sync ExoplayerUtils media source helper.")

        if (setSource.code().any { it.methodRef()?.definingClass == REDDIT_VIDEO } ||
            autoplay.code().any { it.methodRef()?.definingClass == REDDIT_VIDEO }) {
            throw PatchException("Reddit video DASH playback is already installed.")
        }
        val source = setSource.implementation!!.registerCount - 4 + 1
        val store = setSource.code().firstOrNull()
        val stored = (store as? ReferenceInstruction)?.reference as? FieldReference
        layout(store?.opcode == Opcode.IPUT_OBJECT && store?.registerA() == source && stored?.definingClass == VIDEO_PLAYER &&
            stored?.name == "mSource" && stored?.type == "Ljava/lang/String;" && !setSource.targeted(0) &&
            setSource.calls(VIDEO_PLAYER, "initPlayer"), "Sync video player setSource")
        for ((method, what) in listOf(initPlayer to "Sync video player initPlayer", autoplay to "Sync ExoplayerUtils media source")) {
            layout(method.calls(LINK_TYPES, "s") && method.calls(LINK_TYPES, "t") &&
                method.calls(DASH_FACTORY, "createMediaSource") && method.calls(HLS_FACTORY, "createMediaSource"), what)
        }
        val url = autoplay.implementation!!.registerCount - 1
        layout(!autoplay.targeted(0) && autoplay.code().any { insn ->
            insn.methodRef()?.let { it.definingClass == LINK_TYPES && it.name == "s" } == true &&
                (insn as? FiveRegisterInstruction)?.registerC == url
        }, "Sync ExoplayerUtils media source")

        // All layouts verified; edit.
        setSource.addInstructions(0, """
            invoke-static {p1}, $REDDIT_VIDEO->playbackSource(Ljava/lang/String;)Ljava/lang/String;
            move-result-object p1
        """.trimIndent())
        autoplay.addInstructions(0, """
            invoke-static {p0}, $REDDIT_VIDEO->playbackSource(Ljava/lang/String;)Ljava/lang/String;
            move-result-object p0
        """.trimIndent())
    }
}
