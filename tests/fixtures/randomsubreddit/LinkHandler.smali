# Hand-written model of Sync 23033's subreddit link opener (LinkHandler.T, y7.a.T) and subreddit
# name reader (y7.a.o); not the original bodies.
.class public final Ly7/a;
.super Ljava/lang/Object;
.source "LinkHandler.java"

.method public static T(Landroid/content/Context;Ljava/lang/String;)V
    .registers 5
    new-instance v0, Ljava/lang/StringBuilder;
    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
    const-string v1, "Opening subreddit link: "
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, Lwc/i;->e(Ljava/lang/String;)V
    instance-of v0, p0, Lcom/laurencedawson/reddit_sync/ui/activities/BaseActivity;
    if-eqz v0, :open
    move-object v0, p0
    check-cast v0, Lcom/laurencedawson/reddit_sync/ui/activities/BaseActivity;
    invoke-static {p1}, Ly7/c;->n(Ljava/lang/String;)Z
    move-result v1
    if-nez v1, :open
    invoke-static {p1}, Ly7/a;->o(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    const-string v2, "random_nsfw"
    invoke-virtual {v2, v1}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
    move-result v2
    if-eqz v2, :not_nsfw
    const-class p0, Lt9/m1;
    invoke-virtual {v0}, Landroidx/fragment/app/FragmentActivity;->G()Landroidx/fragment/app/FragmentManager;
    move-result-object p1
    const/4 v0, 0x1
    invoke-static {v0}, Lt9/m1;->u4(Z)Landroid/os/Bundle;
    move-result-object v0
    invoke-static {p0, p1, v0}, Ls9/g;->e(Ljava/lang/Class;Landroidx/fragment/app/FragmentManager;Landroid/os/Bundle;)V
    return-void
    :not_nsfw
    const-string v2, "random"
    invoke-virtual {v2, v1}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
    move-result v1
    if-eqz v1, :open
    const-class p0, Lt9/m1;
    invoke-virtual {v0}, Landroidx/fragment/app/FragmentActivity;->G()Landroidx/fragment/app/FragmentManager;
    move-result-object p1
    const/4 v0, 0x0
    invoke-static {v0}, Lt9/m1;->u4(Z)Landroid/os/Bundle;
    move-result-object v0
    invoke-static {p0, p1, v0}, Ls9/g;->e(Ljava/lang/Class;Landroidx/fragment/app/FragmentManager;Landroid/os/Bundle;)V
    return-void
    :open
    invoke-static {p0, p1}, Lcom/laurencedawson/reddit_sync/ui/activities/CasualActivity;->c1(Landroid/content/Context;Ljava/lang/String;)V
    return-void
.end method

# The name after the last "r/", up to the next "/", "?" or "#".
.method public static o(Ljava/lang/String;)Ljava/lang/String;
    .registers 4
    const-string v0, "r/"
    invoke-virtual {p0, v0}, Ljava/lang/String;->lastIndexOf(Ljava/lang/String;)I
    move-result v0
    add-int/lit8 v0, v0, 0x2
    invoke-virtual {p0, v0}, Ljava/lang/String;->substring(I)Ljava/lang/String;
    move-result-object p0
    const-string v0, "[/?#].*"
    const-string v1, ""
    invoke-virtual {p0, v0, v1}, Ljava/lang/String;->replaceAll(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object p0
    return-object p0
.end method
