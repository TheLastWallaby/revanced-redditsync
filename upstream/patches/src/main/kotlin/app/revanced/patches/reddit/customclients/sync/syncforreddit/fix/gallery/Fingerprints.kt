package app.revanced.patches.reddit.customclients.sync.syncforreddit.fix.gallery

import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.instructions
import app.revanced.patcher.invoke
import app.revanced.patcher.patch.BytecodePatchContext

internal val BytecodePatchContext.getGalleryUrlMethodMatch by composingFirstMethod {
    instructions("api/info.json?id=t3_"(String::contains))
}
