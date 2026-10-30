package app.revanced.patches.reddit.customclients.sync.syncforreddit.fix.comments

import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.instructions
import app.revanced.patcher.invoke
import app.revanced.patcher.patch.BytecodePatchContext

internal val BytecodePatchContext.moreCommentsRequestConstructorMethodMatch by composingFirstMethod {
    instructions("api/morechildren"(String::startsWith))
}
