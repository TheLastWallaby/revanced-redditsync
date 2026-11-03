# Hand-written model of Sync 23033's generated Post value class (xa.a): id, body and post_created_utc fields.
.class abstract Lxa/a;
.super Lxa/d;
.source "$$AutoValue_Post.java"

.field private final J:Ljava/lang/String;
.field private final c:Ljava/lang/String;
.field private final j0:Ljava/lang/String;

.method public A0()Ljava/lang/String;
    .registers 2
    iget-object v0, p0, Lxa/a;->j0:Ljava/lang/String;
    return-object v0
.end method

.method public U()Ljava/lang/String;
    .registers 2
    iget-object v0, p0, Lxa/a;->c:Ljava/lang/String;
    return-object v0
.end method

.method public e()Ljava/lang/String;
    .registers 2
    iget-object v0, p0, Lxa/a;->J:Ljava/lang/String;
    return-object v0
.end method
