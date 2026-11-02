# Hand-written model of Sync 23033's multireddit link check (y7.c.n); not the original body.
.class public final Ly7/c;
.super Ljava/lang/Object;
.source "LinkTypeHelper.java"

.method public static n(Ljava/lang/String;)Z
    .registers 2
    const-string v0, "/m/"
    invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result p0
    return p0
.end method
