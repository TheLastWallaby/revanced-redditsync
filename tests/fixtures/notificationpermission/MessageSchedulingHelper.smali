# Hand-written model of Sync 23033's MessageSchedulingHelper (z7.b). g is Sync's "should check the
# inbox" rule: message_preference_new (default "3", every 60 minutes) read as a number, on above 0.
.class public final Lz7/b;
.super Ljava/lang/Object;
.source "MessageSchedulingHelper.java"

.method private static g(Landroid/content/Context;)Z
    .registers 3
    const-string v0, "MessagingService"
    invoke-static {p0, v0}, Lt7/z;->f(Landroid/content/Context;Ljava/lang/String;)Landroid/content/SharedPreferences;
    move-result-object p0
    const-string v0, "message_preference_new"
    const-string v1, "3"
    invoke-interface {p0, v0, v1}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object p0
    invoke-static {p0}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I
    move-result p0
    if-lez p0, :off
    const/4 p0, 0x1
    return p0
    :off
    const/4 p0, 0x0
    return p0
.end method
