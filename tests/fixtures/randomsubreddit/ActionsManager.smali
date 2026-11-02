# Hand-written model of Sync 23033's ActionsManager (t7.b): f says whether an action is on (5 Random,
# 6 Random NSFW), c is the action list, b resets it. Not the original bodies.
.class public final Lt7/b;
.super Ljava/lang/Object;
.source "ActionsManager.java"

.field a:Ljava/util/ArrayList;

.method public static f(I)Z
    .registers 2
    const/4 v0, 0x0
    packed-switch p0, :actions
    new-instance p0, Ljava/lang/RuntimeException;
    const-string v0, "Unknown action"
    invoke-direct {p0, v0}, Ljava/lang/RuntimeException;-><init>(Ljava/lang/String;)V
    throw p0
    :on
    const/4 v0, 0x1
    return v0
    :random
    invoke-static {}, Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton;->x()Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton$Settings;
    move-result-object p0
    iget-boolean p0, p0, Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton$Settings;->actionsRandom:Z
    return p0
    :random_nsfw
    invoke-static {}, Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton;->x()Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton$Settings;
    move-result-object p0
    iget-boolean p0, p0, Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton$Settings;->actionsRandomNsfw:Z
    return p0
    :actions
    .packed-switch 0x0
        :on
        :on
        :on
        :on
        :on
        :random
        :random_nsfw
        :on
    .end packed-switch
.end method

.method public c()Ljava/util/ArrayList;
    .registers 2
    iget-object v0, p0, Lt7/b;->a:Ljava/util/ArrayList;
    return-object v0
.end method

# Adds every action that is on (0..7) to an empty list.
.method public b()V
    .registers 4
    new-instance v0, Ljava/util/ArrayList;
    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
    iput-object v0, p0, Lt7/b;->a:Ljava/util/ArrayList;
    const/4 v1, 0x0
    :loop
    const/16 v2, 0x8
    if-ge v1, v2, :done
    invoke-static {v1}, Lt7/b;->f(I)Z
    move-result v2
    if-eqz v2, :next
    invoke-static {v1}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
    move-result-object v2
    invoke-virtual {v0, v2}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    :next
    add-int/lit8 v1, v1, 0x1
    goto :loop
    :done
    return-void
.end method
