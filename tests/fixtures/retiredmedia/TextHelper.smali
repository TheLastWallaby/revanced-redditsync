# Hand-written model of Sync 23033's empty-text check (wc.m.a); not the original method bodies.
.class public final Lwc/m;
.super Ljava/lang/Object;
.source "TextHelper.java"

.method public static a(Ljava/lang/CharSequence;)Z
    .registers 2
    const/4 v0, 0x1
    if-eqz p0, :empty
    invoke-interface {p0}, Ljava/lang/CharSequence;->length()I
    move-result v1
    if-eqz v1, :empty
    const/4 v0, 0x0
    :empty
    return v0
.end method
