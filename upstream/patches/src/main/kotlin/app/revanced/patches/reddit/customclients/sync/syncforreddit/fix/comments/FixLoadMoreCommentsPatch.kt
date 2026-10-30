package app.revanced.patches.reddit.customclients.sync.syncforreddit.fix.comments

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod.Companion.toMutable
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.reddit.customclients.sync.syncforreddit.extension.sharedExtensionPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/syncforreddit/FixLoadMoreCommentsPatch;"

private const val NETWORK_RESPONSE_DESCRIPTOR = "Lcom/android/volley/NetworkResponse;"

@Suppress("unused")
val fixLoadMoreCommentsPatch = bytecodePatch(
    name = "Fix loading more comments",
    description = "Fixes the \"Error loading more comments\" error when tapping \"View more\" in comments.",
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(
        "com.laurencedawson.reddit_sync",
        "com.laurencedawson.reddit_sync.pro",
        "com.laurencedawson.reddit_sync.dev",
    )

    // Reddit blocks /api/morechildren. Load the hidden comments one by one from
    // /comments/{post}/comment/{comment} instead and convert them to the /api/morechildren format.
    apply {
        moreCommentsRequestConstructorMethodMatch.classDef.apply {
            fun publicMethod(
                name: String,
                parameterTypes: List<String>,
                returnType: String,
                registers: Int,
                smali: String,
            ) = ImmutableMethod(
                type,
                name,
                parameterTypes.map { ImmutableMethodParameter(it, null, null) },
                returnType,
                AccessFlags.PUBLIC.value,
                null,
                null,
                MutableMethodImplementation(registers),
            ).toMutable().apply { addInstructions(smali) }

            // The request now loads a single comment, which uses GET.
            methods.add(
                publicMethod(
                    "getMethod",
                    emptyList(),
                    "I",
                    1,
                    """
                        # Request.Method.GET
                        const/4 v0, 0x0
                        return v0
                    """,
                ),
            )

            methods.add(
                publicMethod(
                    "getUrl",
                    emptyList(),
                    "Ljava/lang/String;",
                    3,
                    """
                        invoke-super { p0 }, $superclass->getUrl()Ljava/lang/String;
                        move-result-object v0
                        invoke-virtual { p0 }, Lcom/android/volley/Request;->getParams()Ljava/util/Map;
                        move-result-object v1
                        invoke-static { v0, v1 }, $EXTENSION_CLASS_DESCRIPTOR->getUrl(Ljava/lang/String;Ljava/util/Map;)Ljava/lang/String;
                        move-result-object v0
                        return-object v0
                    """,
                ),
            )

            val getMoreChildrenResponseMethod = publicMethod(
                "patch_getMoreChildrenResponse",
                listOf(NETWORK_RESPONSE_DESCRIPTOR),
                NETWORK_RESPONSE_DESCRIPTOR,
                5,
                """
                    invoke-super { p0 }, $superclass->getUrl()Ljava/lang/String;
                    move-result-object v0
                    invoke-virtual { p0 }, Lcom/android/volley/Request;->getParams()Ljava/util/Map;
                    move-result-object v1
                    invoke-virtual { p0 }, Lcom/android/volley/Request;->getHeaders()Ljava/util/Map;
                    move-result-object v2
                    invoke-static { p1, v0, v1, v2 }, $EXTENSION_CLASS_DESCRIPTOR->getMoreChildrenResponse(${NETWORK_RESPONSE_DESCRIPTOR}Ljava/lang/String;Ljava/util/Map;Ljava/util/Map;)$NETWORK_RESPONSE_DESCRIPTOR
                    move-result-object v0
                    return-object v0
                """,
            )
            methods.add(getMoreChildrenResponseMethod)

            // Replace the response before it is parsed.
            // The range form allows the parameter registers to be above v15.
            methods.first { it.name == "parseNetworkResponse" }.addInstructions(
                0,
                """
                    invoke-virtual/range { p0 .. p1 }, $getMoreChildrenResponseMethod
                    move-result-object p1
                """,
            )
        }
    }
}
