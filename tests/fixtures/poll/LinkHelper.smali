.class public abstract Ly7/b;
.super Ljava/lang/Object;
.source "LinkHelper.java"

# Hand-written model of the start of Sync 23033's LinkHelper.c (y7.b.c) up to its poll branch:
# a reddit.com/poll/ link is logged ("Was poll") and opened in a Custom Tab (LinkHandler.W); this
# model sends every other link to LinkHandler.X. Same signature, register count and branch shape.

.method public static c(Landroid/content/Context;[Lr0/d;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZZ)Z
    .registers 21
    move-object v0, p0
    move-object v1, p3
    const-string v3, "LinkHelper"
    invoke-static {p3}, Lwc/m;->a(Ljava/lang/CharSequence;)Z
    move-result v2
    if-eqz v2, :cond_link
    const/4 v0, 0x0
    return v0
    :cond_link
    const/4 v10, 0x1
    const-string v2, "reddit.com/poll/"
    invoke-virtual {p3, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v2
    if-eqz v2, :cond_other
    const-string v2, "Was poll"
    invoke-static {v3, v2}, Lwc/i;->f(Ljava/lang/String;Ljava/lang/String;)V
    invoke-static {p0, p3}, Ly7/a;->W(Landroid/content/Context;Ljava/lang/String;)V
    return v10
    :cond_other
    invoke-static {p0, p3}, Ly7/a;->X(Landroid/content/Context;Ljava/lang/String;)V
    return v10
.end method
