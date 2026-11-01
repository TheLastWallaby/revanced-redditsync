# Offline build-23033 LinkHelper fixture. The signature, register count, the inherited
# share-link hook, the first log and the gallery branch are original; the other
# classifier branches are abbreviated to their log strings.
.class public abstract Ly7/b;
.super Ljava/lang/Object;
.source "LinkHelper.java"

.method public static c(Landroid/content/Context;[Lr0/d;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZZ)Z
    .registers 21
    invoke-static {p3}, Lapp/revanced/extension/syncforreddit/FixSLinksPatch;->patchResolveSLink(Ljava/lang/String;)Z
    move-result v2
    if-eqz v2, :cond_7
    return v2
    :cond_7
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
    const/4 v10, 0x1
    move-object v4, v1
    invoke-static {v4}, Ly7/c;->q(Ljava/lang/String;)Z
    move-result v1
    if-eqz v1, :cond_176
    const-string v1, "Was gallery"
    invoke-static {v3, v1}, Lwc/i;->f(Ljava/lang/String;Ljava/lang/String;)V
    move-object v0, p0
    move-object v1, p2
    move-object v2, v4
    move-object v3, p4
    move-object/from16 v4, p5
    move-object/from16 v5, p6
    invoke-static/range {v0 .. v5}, Ly7/a;->N(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
    return v10
    :cond_176
    const-string v1, "Was shortened"
    const-string v1, "Was comments"
    const-string v1, "Was reddit user"
    const-string v1, "reddit.com/link/"
    const-string v1, "Opening strange reddit video link"
    const-string v1, "Was reddit video"
    invoke-static {p0, v4}, Ly7/a;->V(Landroid/content/Context;Ljava/lang/String;)V
    return v10
.end method
