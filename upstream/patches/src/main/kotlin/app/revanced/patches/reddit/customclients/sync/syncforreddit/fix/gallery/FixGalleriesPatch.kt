package app.revanced.patches.reddit.customclients.sync.syncforreddit.fix.gallery

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.reddit.customclients.sync.syncforreddit.extension.sharedExtensionPatch
import app.revanced.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/syncforreddit/FixGalleriesPatch;"

@Suppress("unused")
val fixGalleriesPatch = bytecodePatch(
    name = "Fix galleries",
    description = "Fixes galleries failing to load.",
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(
        "com.laurencedawson.reddit_sync",
        "com.laurencedawson.reddit_sync.pro",
        "com.laurencedawson.reddit_sync.dev",
    )

    // Reddit blocks /api/info. Load the gallery post from /comments/{post} instead
    // and convert the response to the /api/info format.
    apply {
        getGalleryUrlMethodMatch.method.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_OBJECT)
            val urlRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA

            addInstructions(
                returnIndex,
                """
                    invoke-static { v$urlRegister }, $EXTENSION_CLASS_DESCRIPTOR->getUrl(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$urlRegister
                """,
            )
        }

        // Replace the response before it is parsed.
        // The range form allows the parameter register to be above v15.
        getGalleryUrlMethodMatch.classDef.methods.first { it.name == "parseNetworkResponse" }.addInstructions(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS_DESCRIPTOR->getResponse(Lcom/android/volley/NetworkResponse;)Lcom/android/volley/NetworkResponse;
                move-result-object p1
            """,
        )
    }
}
