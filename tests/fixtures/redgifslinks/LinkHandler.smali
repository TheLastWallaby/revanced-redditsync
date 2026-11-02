.class public final Ly7/a;
.super Ljava/lang/Object;
.source "LinkHandler.java"

# Hand-written model of Sync 23033's RedGifs ID reader (LinkHandler.d, "getGfycatId"), following its
# behavior: drop one trailing "/", take the text after the last "/", remove the file and size
# suffixes, keep the part before the first "-", then cut at the last "?" and the last "#". On an
# exception it returns null. It has the method shape and strings the patch checks; the real method
# is checked by running RedgifsLinkPatchTest with a local stock APK.
.method public static d(Ljava/lang/String;)Ljava/lang/String;
    .registers 9

    :try_start
    const-string v1, "/"
    invoke-virtual {p0, v1}, Ljava/lang/String;->endsWith(Ljava/lang/String;)Z
    move-result v2
    if-eqz v2, :last_segment
    invoke-virtual {p0, v1}, Ljava/lang/String;->lastIndexOf(Ljava/lang/String;)I
    move-result v2
    const/4 v3, 0x0
    invoke-virtual {p0, v3, v2}, Ljava/lang/String;->substring(II)Ljava/lang/String;
    move-result-object p0

    :last_segment
    invoke-virtual {p0, v1}, Ljava/lang/String;->lastIndexOf(Ljava/lang/String;)I
    move-result v2
    add-int/lit8 v2, v2, 0x1
    invoke-virtual {p0}, Ljava/lang/String;->length()I
    move-result v3
    invoke-virtual {p0, v2, v3}, Ljava/lang/String;->substring(II)Ljava/lang/String;
    move-result-object v0

    const-string v4, ""
    const-string v5, ".gif"
    invoke-virtual {v0, v5, v4}, Ljava/lang/String;->replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;
    move-result-object v0
    const-string v5, ".mp4"
    invoke-virtual {v0, v5, v4}, Ljava/lang/String;->replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;
    move-result-object v0
    const-string v5, ".webm"
    invoke-virtual {v0, v5, v4}, Ljava/lang/String;->replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;
    move-result-object v0
    const-string v5, "-mobile"
    invoke-virtual {v0, v5, v4}, Ljava/lang/String;->replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;
    move-result-object v0
    const-string v5, "-size_restricted"
    invoke-virtual {v0, v5, v4}, Ljava/lang/String;->replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;
    move-result-object v0
    const-string v5, "-max-1mb-poster"
    invoke-virtual {v0, v5, v4}, Ljava/lang/String;->replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;
    move-result-object v0

    const-string v5, "-"
    invoke-virtual {v0, v5}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v6
    if-eqz v6, :query
    invoke-virtual {v0, v5}, Ljava/lang/String;->split(Ljava/lang/String;)[Ljava/lang/String;
    move-result-object v6
    const/4 v7, 0x0
    aget-object v0, v6, v7

    :query
    const-string v5, "?"
    invoke-virtual {v0, v5}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v6
    if-eqz v6, :fragment
    invoke-virtual {v0, v5}, Ljava/lang/String;->lastIndexOf(Ljava/lang/String;)I
    move-result v6
    const/4 v7, 0x0
    invoke-virtual {v0, v7, v6}, Ljava/lang/String;->substring(II)Ljava/lang/String;
    move-result-object v0

    :fragment
    const-string v5, "#"
    invoke-virtual {v0, v5}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v6
    if-eqz v6, :done
    invoke-virtual {v0, v5}, Ljava/lang/String;->lastIndexOf(Ljava/lang/String;)I
    move-result v6
    const/4 v7, 0x0
    invoke-virtual {v0, v7, v6}, Ljava/lang/String;->substring(II)Ljava/lang/String;
    move-result-object v0
    :try_end
    .catch Ljava/lang/Exception; {:try_start .. :try_end} :failed

    :done
    return-object v0

    :failed
    const-string v1, "Crashing getGfycatId() URL: "
    const/4 v0, 0x0
    return-object v0
.end method
