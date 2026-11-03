# Hand-written model of Sync 23033's link router LinkHelper.c (y7.b.c): its signature and
# register count, opening log, empty-link check, scheme handling, the YouTube branch as in the
# base, and the default-link branch. The other classifier branches are left out.
.class public abstract Ly7/b;
.super Ljava/lang/Object;
.source "LinkHelper.java"

.method public static c(Landroid/content/Context;[Lr0/d;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZZ)Z
    .registers 21
    move-object v0, p0
    move-object v1, p3
    new-instance v2, Ljava/lang/StringBuilder;
    invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V
    const-string v3, "Opening link: "
    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v2, p3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v2
    const-string v3, "LinkHelper"
    invoke-static {v3, v2}, Lwc/i;->f(Ljava/lang/String;Ljava/lang/String;)V
    invoke-static {p3}, Lwc/m;->a(Ljava/lang/CharSequence;)Z
    move-result v2
    if-eqz v2, :cond_4c
    const/4 v0, 0x0
    return v0
    :cond_4c
    const/4 v10, 0x1
    # Sync lower-cases only an "https" or "http" scheme prefix.
    sget-object v2, Ljava/util/Locale;->ENGLISH:Ljava/util/Locale;
    invoke-virtual {v1, v2}, Ljava/lang/String;->toLowerCase(Ljava/util/Locale;)Ljava/lang/String;
    move-result-object v4
    const-string v6, "https"
    invoke-virtual {v4, v6}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
    move-result v4
    if-eqz v4, :cond_133
    new-instance v2, Ljava/lang/StringBuilder;
    invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V
    invoke-virtual {v2, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const/4 v4, 0x5
    invoke-virtual {v1}, Ljava/lang/String;->length()I
    move-result v6
    invoke-virtual {v1, v4, v6}, Ljava/lang/String;->substring(II)Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v2, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v1
    goto :goto_157
    :cond_133
    invoke-virtual {v1, v2}, Ljava/lang/String;->toLowerCase(Ljava/util/Locale;)Ljava/lang/String;
    move-result-object v2
    const-string v4, "http"
    invoke-virtual {v2, v4}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
    move-result v2
    if-eqz v2, :cond_157
    new-instance v2, Ljava/lang/StringBuilder;
    invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V
    invoke-virtual {v2, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const/4 v4, 0x4
    invoke-virtual {v1}, Ljava/lang/String;->length()I
    move-result v6
    invoke-virtual {v1, v4, v6}, Ljava/lang/String;->substring(II)Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v2, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v1
    :cond_157
    :goto_157
    move-object v4, v1
    invoke-static {v4}, Ly7/c;->K(Ljava/lang/String;)Z
    move-result v1
    if-eqz v1, :cond_1cb
    const-string v1, "Was YouTube"
    invoke-static {v3, v1}, Lwc/i;->f(Ljava/lang/String;Ljava/lang/String;)V
    move-object v1, p1
    move-object v6, p4
    invoke-static {p0, p1, v4, p4}, Ly7/a;->a0(Landroid/content/Context;[Lr0/d;Ljava/lang/String;Ljava/lang/String;)V
    return v10
    :cond_1cb
    # Log strings of Sync's Reddit video branches, so PatchInteractionTest can also install the
    # comment-video link hook on this model.
    const-string v1, "reddit.com/link/"
    const-string v1, "Opening strange reddit video link"
    const-string v1, "Was reddit video"
    const-string v1, "Was default link"
    invoke-static {v3, v1}, Lwc/i;->f(Ljava/lang/String;Ljava/lang/String;)V
    invoke-static {p0, v4}, Ly7/a;->V(Landroid/content/Context;Ljava/lang/String;)V
    return v10
.end method
