# Hand-written model of Sync 23033's RandomRequest (r8.d) URL choice; not the original body.
.class public Lr8/d;
.super Lx8/a;
.source "RandomRequest.java"

.method static b(Landroid/content/Context;Z)Ljava/lang/String;
    .registers 2
    if-eqz p1, :sfw
    const-string p0, "r/randnsfw.json"
    return-object p0
    :sfw
    const-string p0, "r/random.json"
    return-object p0
.end method
