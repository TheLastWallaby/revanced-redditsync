@file:Suppress("unused")
package app.revanced.local.synccompat

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
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
