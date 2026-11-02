@file:Suppress("unused")
package app.revanced.local.synccompat

// Ports of the official ReVanced Sync for Reddit patches (ReVanced Patches v6.1.0, GPLv3,
// https://gitlab.com/ReVanced/revanced-patches; originals under upstream/revanced-patches-v6.1.0/).
// Since v0.7.0 this bundle applies them itself, so stock Sync 23033 is patched in one pass.
// Each port targets build 23033's exact methods, checks their layout first and makes the same
// edit the official patch makes. Spoof client also accepts a redirect URI and User-Agent.

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.InstructionExtensions.removeInstruction
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.stringOption
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableClassDef
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.Base64

private const val UTILS = "Lapp/revanced/extension/shared/Utils;"
private const val APPLICATION = "Lcom/laurencedawson/reddit_sync/RedditApplication;"
private const val FIX_SLINKS = "Lapp/revanced/extension/syncforreddit/FixSLinksPatch;"
private const val FIX_REDGIFS = "Lapp/revanced/extension/syncforreddit/FixRedgifsApiPatch;"
private const val FIX_VIDEO_DOWNLOAD = "Lapp/revanced/extension/syncforreddit/FixRedditVideoDownloadPatch;"
private const val ACCOUNT = "Lcom/laurencedawson/reddit_sync/singleton/a;"
private const val OAUTH_LOGIN = "Lw8/b;"
private const val DEFAULT_REDIRECT_URI = "http://redditsync/auth"
private const val SYNC = "com.laurencedawson.reddit_sync"
private const val SYNC_VERSION = "v23.06.30-13:39"

/** A bundled official patch whose code another patch builds on did not apply. */
internal fun officialPatchMissing(patch: String) = PatchException(
    "This APK is missing the \"$patch\" patch's changes. This bundle includes the official ReVanced Sync " +
        "patches; apply it to stock Sync 23033 (v23.06.30-13:39).")

private fun BytecodePatchContext.syncClass(type: String, source: String): MutableClassDef =
    proxy(classes.singleOrNull { it.type == type && it.sourceFile == source } ?: throw PatchException(
        "Expected Sync $source was not found. This patch targets build 23033 only.")).mutableClass

private fun MutableClassDef.syncMethod(name: String, parameters: List<String>, returns: String): MutableMethod =
    methods.singleOrNull { it.name == name && it.parameterTypes == parameters && it.returnType == returns }
        ?: throw PatchException("Unsupported Sync ${type.removePrefix("L").removeSuffix(";")}->$name. This patch targets build 23033 only.")

private fun MutableMethod.code() = implementation!!.instructions
private fun MutableMethod.hasString(predicate: (String) -> Boolean) = code().any { it.stringValue()?.let(predicate) == true }
private fun MutableMethod.calls(owner: String) = code().any { it.methodRef()?.definingClass == owner }
private fun MutableMethod.targeted(index: Int): Boolean {
    val target = code()[index]
    return code().any { it is BuilderOffsetInstruction && it.target.location.instruction === target }
}

/** Returns [value] from the start of a method, as the official patches' `returnEarly` does. */
private fun MutableMethod.returnStringFirst(value: String) =
    addInstructions(0, "const-string v0, \"$value\"\nreturn-object v0")

// Values are written into smali string literals; reject anything that would need escaping.
private fun plainText(value: String?) = value != null && value.isNotEmpty() && value.none { it == '"' || it == '\\' || it < ' ' }

/** Stock Sync only. A previously patched APK would be patched twice. */
private val stockSyncPatch = bytecodePatch {
    apply {
        if (classes.any { it.type.startsWith("Lapp/revanced/") }) throw PatchException(
            "This APK already contains ReVanced code. This bundle includes the official ReVanced Sync patches " +
                "since v0.7.0: apply it to stock Sync 23033 (v23.06.30-13:39) without other patches.")
    }
}

/** Merges this bundle's extension and gives it Sync's application context, as the official shared extension does. */
internal val extensionPatch = bytecodePatch {
    dependsOn(stockSyncPatch)
    extendWith("extensions/synccompat.rve")
    apply {
        val onCreate = syncClass(APPLICATION, "RedditApplication.java").syncMethod("onCreate", emptyList(), "V")
        if (onCreate.calls(UTILS)) throw PatchException("The extension context hook is already installed.")
        layout(onCreate.code().firstOrNull()?.methodRef()?.let {
            it.definingClass == "Landroid/app/Application;" && it.name == "onCreate"
        } == true, "Sync application start")
        onCreate.addInstruction(0, "invoke-static/range {p0 .. p0}, $UTILS->setContext(Landroid/content/Context;)V")
    }
}

val fixSLinksPatch = bytecodePatch(
    name = "Fix /s/ links",
    description = "Fixes the issue where /s/ links do not work.",
) {
    compatibleWith(SYNC(SYNC_VERSION))
    dependsOn(extensionPatch)
    apply {
        // LinkHelper.c handles every link; p3 is the URL. Resolve /s/ links before Sync does.
        val open = syncClass("Ly7/b;", "LinkHelper.java").syncMethod("c",
            listOf("Landroid/content/Context;", "[Lr0/d;") + List(5) { "Ljava/lang/String;" } + listOf("Z", "Z", "Z"), "Z")
        if (open.calls(FIX_SLINKS)) throw PatchException("Fix /s/ links is already installed.")
        layout(open.hasString { it == "Link title: " } && open.implementation!!.registerCount == 21, "Sync LinkHelper")
        // AccountSingleton.e builds the authorization headers from the access token in p0.
        val headers = syncClass(ACCOUNT, "AccountSingleton.java").syncMethod("e", listOf("Ljava/lang/String;"), "Ljava/util/HashMap;")
        layout(headers.hasString { it == "Authorization" } && headers.hasString { it == "bearer " }, "Sync authorization header")

        open.addInstructionsWithLabels(0, """
            invoke-static {p3}, $FIX_SLINKS->patchResolveSLink(Ljava/lang/String;)Z
            move-result v2
            if-eqz v2, :continue
            return v2
        """.trimIndent(), ExternalLabel("continue", open.code()[0]))

        headers.addInstruction(0, "invoke-static {p0}, $FIX_SLINKS->patchSetAccessToken(Ljava/lang/String;)V")
    }
}

val fixRedgifsApiPatch = bytecodePatch(
    name = "Fix Redgifs API",
    description = "Fixes RedGifs requests by using RedGifs' temporary tokens with Sync's User-Agent.",
) {
    compatibleWith(SYNC(SYNC_VERSION))
    dependsOn(extensionPatch)
    apply {
        // OkHttpHelper.k builds the client Sync's Volley requests use; add the RedGifs interceptor.
        val client = syncClass("La8/a;", "OkHttpHelper.java").syncMethod("k", emptyList(), "V")
        if (client.calls(FIX_REDGIFS)) throw PatchException("Fix Redgifs API is already installed.")
        val code = client.code()
        val build = code.singleIndex("OkHttpHelper.k client build") {
            code[it].methodRef()?.let { m -> m.definingClass == "Lokhttp3/OkHttpClient\$Builder;" && m.name == "build" } == true
        }
        layout(code.count { it.opcode == Opcode.NEW_INSTANCE } == 1 && code[build].opcode == Opcode.INVOKE_VIRTUAL &&
            code.getOrNull(build + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT, "OkHttpHelper.k")
        // RedGifs checks that later requests use the User-Agent the token was issued for: Sync's.
        val userAgent = syncClass(ACCOUNT, "AccountSingleton.java").syncMethod("g", emptyList(), "Ljava/lang/String;")
        layout(userAgent.hasString { it.contains("android:com.laurencedawson.reddit_sync") }, "Sync User-Agent")
        val defaultUserAgent = proxy(classes.singleOrNull { it.type == FIX_REDGIFS }
            ?: throw PatchException("This bundle's RedGifs extension class is missing.")).mutableClass
            .syncMethod("getDefaultUserAgent", emptyList(), "Ljava/lang/String;")
        if (defaultUserAgent.calls(ACCOUNT)) throw PatchException("Fix Redgifs API is already installed.")

        val builder = (code[build] as FiveRegisterInstruction).registerC
        client.replaceInstruction(build,
            "invoke-static {v$builder}, $FIX_REDGIFS->install(Lokhttp3/OkHttpClient\$Builder;)Lokhttp3/OkHttpClient;")
        defaultUserAgent.addInstructions(0, """
            invoke-static {}, $ACCOUNT->g()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """.trimIndent())
    }
}

val spoofClientPatch = bytecodePatch(
    name = "Spoof client",
    description = "Restores functionality of the app by using custom client ID.",
) {
    compatibleWith(SYNC(SYNC_VERSION))
    val clientIdOption = stringOption(
        name = "OAuth client ID",
        description = "The Reddit OAuth client ID. You can get your client ID from https://www.reddit.com/prefs/apps. " +
            "The application type has to be \"Installed app\" and the redirect URI has to match the Redirect URI option " +
            "(by default \"$DEFAULT_REDIRECT_URI\").",
        required = true,
    ) { it != null && it.matches(Regex("[A-Za-z0-9_-]+")) }
    val redirectUriOption = stringOption(
        name = "Redirect URI",
        default = DEFAULT_REDIRECT_URI,
        description = "The redirect URI registered for the OAuth client. Sync's own is \"$DEFAULT_REDIRECT_URI\".",
        required = true,
    ) { plainText(it) && it!!.contains("://") && it.none(Char::isWhitespace) }
    val userAgentOption = stringOption(
        name = "User-Agent",
        description = "The User-Agent Sync sends to Reddit. Leave empty for a random one, as the official patch uses.",
    ) { it.isNullOrEmpty() || plainText(it) }
    apply {
        val clientId = clientIdOption.value ?: throw PatchException("Spoof client needs an OAuth client ID.")
        val redirectUri = redirectUriOption.value ?: DEFAULT_REDIRECT_URI
        val userAgent = userAgentOption.value?.takeIf { it.isNotEmpty() }
            ?: (0..100000).random().let { "$it:app.revanced.$it:v1.0.0 (by /u/revanced)" }

        val login = syncClass(OAUTH_LOGIN, "OAuthLoginHelper.java")
        val bearer = login.syncMethod("c", emptyList(), "Ljava/lang/String;")
        if (bearer.code().firstOrNull()?.stringValue()?.startsWith("Basic ") == true &&
            bearer.code().getOrNull(1)?.opcode == Opcode.RETURN_OBJECT) throw PatchException("Spoof client is already installed.")
        val authorize = login.syncMethod("d", listOf("Landroid/content/Context;"), "Ljava/lang/String;")
        val redirect = login.syncMethod("f", emptyList(), "Ljava/lang/String;")
        val account = syncClass(ACCOUNT, "AccountSingleton.java").syncMethod("g", emptyList(), "Ljava/lang/String;")
        val imgur = syncClass("Lu8/c;", "UploadImgurRequest.java")
        layout(bearer.hasString { it == "Basic " } && redirect.hasString { it == DEFAULT_REDIRECT_URI } &&
            account.hasString { it.contains("android:com.laurencedawson.reddit_sync") }, "Sync OAuth client")

        // Redirects from ssl.reddit.com to www.reddit.com break sign-in; Sync 23033 has eight such URLs.
        val ssl = classes.filter { c ->
            c.methods.any { m -> m.implementation?.instructions?.any { it.stringValue()?.contains("ssl.reddit.com") == true } == true }
        }.map { proxy(it).mutableClass }
        val sslSites = ssl.flatMap { c -> c.methods.filter { it.implementation != null }.flatMap { m ->
            m.code().indices.filter { m.code()[it].stringValue()?.contains("ssl.reddit.com") == true }.map { m to it }
        } }
        layout(sslSites.size == 8 && ssl.map { it.type }.toSet() ==
            setOf("Lb9/a;", "Lb9/b;", "Lb9/c;", "Lb9/d;", "Lw8/a\$a;", OAUTH_LOGIN, "Ly8/k0;"), "Sync ssl.reddit.com URLs")
        val authorizeUrl = authorize.code().singleOrNull { it.stringValue()?.contains("authorize.compact?client_id=") == true }
        layout(authorizeUrl != null, "Sync authorization URL")
        // Sync's Imgur upload went through a RapidAPI proxy; Imgur's API is used directly.
        val upload = imgur.methods.filter { it.implementation != null }.flatMap { m ->
            m.code().indices.filter { m.code()[it].stringValue() == "https://imgur-apiv3.p.rapidapi.com/3/image" }.map { m to it }
        }
        layout(upload.size == 1, "Sync Imgur upload")

        for ((method, index) in sslSites) {
            val insn = method.code()[index]
            method.replaceInstruction(index,
                "const-string v${insn.registerA()}, \"${insn.stringValue()!!.replace("ssl.reddit.com", "www.reddit.com")}\"")
        }

        // Client ID: in the token request's Basic credentials and in the authorization URL.
        val credentials = Base64.getEncoder().encodeToString("$clientId:".toByteArray(Charsets.UTF_8))
        bearer.returnStringFirst("Basic $credentials")
        val urlIndex = authorize.code().indexOfFirst { it.stringValue()?.contains("authorize.compact?client_id=") == true }
        val url = authorize.code()[urlIndex]
        authorize.replaceInstruction(urlIndex, "const-string v${url.registerA()}, \"" +
            url.stringValue()!!.replace(Regex("client_id=.*?&"), "client_id=$clientId&") + "\"")
        if (redirectUri != DEFAULT_REDIRECT_URI) redirect.returnStringFirst(redirectUri)

        account.returnStringFirst(userAgent)

        val (uploadMethod, uploadIndex) = upload.single()
        uploadMethod.replaceInstruction(uploadIndex,
            "const-string v${uploadMethod.code()[uploadIndex].registerA()}, \"https://api.imgur.com/3/image\"")
    }
}

val disableAdsPatch = bytecodePatch(
    name = "Disable ads",
) {
    compatibleWith(SYNC(SYNC_VERSION))
    apply {
        // SyncIapHelper.b reports whether ads are enabled.
        val ads = syncClass("Ll9/a;", "SyncIapHelper.java").syncMethod("b", listOf("Landroid/content/Context;"), "Z")
        val first = ads.code().firstOrNull()
        if (first?.opcode == Opcode.CONST_4 && ads.code().getOrNull(1)?.opcode == Opcode.RETURN)
            throw PatchException("Disable ads is already installed.")
        layout(ads.hasString { it == "ADS ENABLED ID: " } && ads.hasString { it == "SyncIapHelper" } &&
            ads.implementation!!.registerCount >= 2, "Sync ads check")
        ads.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
    }
}

val disableSyncForLemmyBottomSheetPatch = bytecodePatch(
    name = "Disable Sync for Lemmy bottom sheet",
    description = "Disables the bottom sheet at the startup that asks you to signup to \"Sync for Lemmy\".",
) {
    compatibleWith(SYNC(SYNC_VERSION))
    apply {
        val onCreate = syncClass("Lcom/laurencedawson/reddit_sync/ui/activities/MainActivity;", "MainActivity.java")
            .syncMethod("onCreate", listOf("Landroid/os/Bundle;"), "V")
        val code = onCreate.code()
        val sheet = code.indices.filter { i ->
            code[i].methodRef()?.let { it.definingClass == "Ls9/g;" && it.name == "f" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Class;", "Landroidx/fragment/app/FragmentManager;") } == true
        }
        if (sheet.isEmpty()) throw PatchException("Disable Sync for Lemmy bottom sheet is already installed or unsupported.")
        layout(sheet.size == 1 && sheet.single() == code.lastIndex - 1 && code.last().opcode == Opcode.RETURN_VOID &&
            !onCreate.targeted(sheet.single()) &&
            ((code[sheet.single() - 3] as? ReferenceInstruction)?.reference as? TypeReference)?.type == "Laa/c;",
            "Sync for Lemmy bottom sheet")
        onCreate.removeInstruction(sheet.single())
    }
}

val fixPostThumbnailsPatch = bytecodePatch(
    name = "Fix post thumbnails",
    description = "Fixes loading post thumbnails by correcting their URLs.",
) {
    compatibleWith(SYNC(SYNC_VERSION))
    apply {
        // Image URLs contain escaped ampersands (&amp;); CustomImageView.q loads them as they are.
        val load = syncClass("Lcom/laurencedawson/reddit_sync/ui/views/images/CustomImageView;", "CustomImageView.java")
            .syncMethod("q", listOf("Ljava/lang/String;", "Z", "Z", "I", "I"), "V")
        if (load.code().firstOrNull()?.stringValue() == "&amp;") throw PatchException("Fix post thumbnails is already installed.")
        layout(load.implementation!!.registerCount == 14, "Sync image loader")
        load.addInstructions(0, """
            const-string v0, "&amp;"
            const-string v1, "&"
            invoke-virtual {p1, v0, v1}, Ljava/lang/String;->replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;
            move-result-object p1
        """.trimIndent())
    }
}

val fixVideoDownloadsPatch = bytecodePatch(
    name = "Fix video downloads",
    description = "Fixes a bug in Sync's MPD parser resulting in only the audio-track being saved.",
) {
    compatibleWith(SYNC(SYNC_VERSION))
    dependsOn(extensionPatch)
    apply {
        val parse = syncClass("Lr8/g;", "RedditVideoRequest.java")
            .syncMethod("parseNetworkResponse", listOf("Lcom/android/volley/NetworkResponse;"), "Lcom/android/volley/Response;")
        if (parse.calls(FIX_VIDEO_DOWNLOAD)) throw PatchException("Fix video downloads is already installed.")
        val code = parse.code()
        // new-instance o8.h; iget-object; invoke-direct o8.h(String, video, audio); const-wide/32
        val result = code.singleIndex("video download result") { i ->
            code[i].opcode == Opcode.NEW_INSTANCE && ((code[i] as ReferenceInstruction).reference as TypeReference).type == "Lo8/h;"
        }
        val create = code.getOrNull(result + 2) as? FiveRegisterInstruction
        layout(parse.implementation!!.registerCount == 10 && code.getOrNull(result + 1)?.opcode == Opcode.IGET_OBJECT &&
            create != null && code[result + 2].opcode == Opcode.INVOKE_DIRECT && create.registerCount == 4 &&
            code[result + 2].methodRef()?.let { it.definingClass == "Lo8/h;" && it.name == "<init>" } == true &&
            code.getOrNull(result + 3)?.opcode == Opcode.CONST_WIDE_32, "Sync video download parser")
        parse.addInstructions(result + 1, """
            iget-object v2, p1, Lcom/android/volley/NetworkResponse;->data:[B
            invoke-static {v2}, $FIX_VIDEO_DOWNLOAD->getLinks([B)[Ljava/lang/String;
            move-result-object v2
            const/4 v5, 0x0
            aget-object v${create!!.registerE}, v2, v5
            const/4 v6, 0x1
            aget-object v${create.registerF}, v2, v6
        """.trimIndent())
    }
}

val useUserEndpointPatch = bytecodePatch(
    name = "Use /user/ endpoint",
    description = "Replaces the deprecated endpoint for viewing user profiles /u with /user, that used to fix a bug.",
    use = false,
) {
    compatibleWith(SYNC(SYNC_VERSION))
    apply {
        // Request class -> the profile path strings it builds.
        val targets = listOf(
            Triple("Ly8/o;", "OAuthFriendRequest.java", listOf("https://oauth.reddit.com/u/")),
            Triple("Ly8/a1;", "OAuthUnfriendRequest.java", listOf("https://oauth.reddit.com/u/")),
            Triple("Ly8/b1;", "OAuthUserIdRequest.java", listOf("u/")),
            Triple("Ly8/c1;", "OAuthUserInfoRequest.java", listOf("u/")),
            Triple("Ly8/y0;", "OAuthSubredditInfoRequest.java", listOf("u/", "u/")),
        ).map { (type, source, strings) -> Triple(syncClass(type, source), source, strings) }
        val sites = targets.map { (clazz, source, strings) ->
            val found = clazz.methods.filter { it.implementation != null }.flatMap { m ->
                m.code().indices.filter { m.code()[it].stringValue()?.let { s -> s.endsWith("u/") && "user/" !in s } == true }.map { m to it }
            }
            if (found.isEmpty() && clazz.methods.any { m -> m.implementation != null && m.hasString { it.endsWith("user/") } })
                throw PatchException("Use /user/ endpoint is already installed.")
            layout(found.map { (m, i) -> m.code()[i].stringValue() } == strings, "Sync $source profile path")
            found
        }.flatten()
        for ((method, index) in sites) {
            val insn = method.code()[index]
            method.replaceInstruction(index, "const-string v${insn.registerA()}, \"${insn.stringValue()!!.replace("u/", "user/")}\"")
        }
    }
}
