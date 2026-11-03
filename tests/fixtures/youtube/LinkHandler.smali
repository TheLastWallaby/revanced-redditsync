# Hand-written model of Sync 23033's LinkHandler.a0 (y7.a.a0) and the openers it calls.
.class public abstract Ly7/a;
.super Ljava/lang/Object;
.source "LinkHandler.java"

# The embedded player when Sync's "YouTube videos" link setting is on and the link does not
# contain "shared"; otherwise LinkHandler.X. p1 (the shared-element pairs) is overwritten first.
.method public static a0(Landroid/content/Context;[Lr0/d;Ljava/lang/String;Ljava/lang/String;)V
    .registers 4
    new-instance p1, Ljava/lang/StringBuilder;
    invoke-direct {p1}, Ljava/lang/StringBuilder;-><init>()V
    const-string p3, "Opening video link: "
    invoke-virtual {p1, p3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {p1, p2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {p1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object p1
    invoke-static {p1}, Lwc/i;->e(Ljava/lang/String;)V
    invoke-static {}, Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton;->d()Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton;
    move-result-object p1
    invoke-virtual {p1}, Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton;->j()Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton$Settings;
    move-result-object p1
    iget-boolean p1, p1, Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton$Settings;->embedYouTube:Z
    if-eqz p1, :cond_38
    const-string p1, "shared"
    invoke-virtual {p2, p1}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result p1
    if-nez p1, :cond_38
    new-instance p1, Landroid/content/Intent;
    const-class p3, Lcom/laurencedawson/reddit_sync/ui/activities/CustomYouTubeVideoActivity;
    invoke-direct {p1, p0, p3}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
    const-string p3, "url"
    invoke-virtual {p1, p3, p2}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;
    invoke-static {p0, p1}, Ly7/a;->c0(Landroid/content/Context;Landroid/content/Intent;)V
    goto :goto_3b
    :cond_38
    invoke-static {p0, p2}, Ly7/a;->X(Landroid/content/Context;Ljava/lang/String;)V
    :goto_3b
    return-void
.end method

# Opens the link with a plain ACTION_VIEW intent (the suite records the call).
.method public static X(Landroid/content/Context;Ljava/lang/String;)V
    .registers 2
    return-void
.end method

# Starts an activity (the suite records the call).
.method public static c0(Landroid/content/Context;Landroid/content/Intent;)V
    .registers 2
    return-void
.end method

# Sync's browser choice for an ordinary link (the suite records the call).
.method public static V(Landroid/content/Context;Ljava/lang/String;)V
    .registers 2
    return-void
.end method
