# Hand-written model of Sync 23033's generated Post value class (xa.a): id (c), author (J), body_raw (Z0),
# post_created_utc (j0), selftext_raw (U0) and type (u) fields.
.class abstract Lxa/a;
.super Lxa/d;
.source "$$AutoValue_Post.java"

.field private final J:Ljava/lang/String;
.field private final U0:Ljava/lang/String;
.field private final Z0:Ljava/lang/String;
.field private final c:Ljava/lang/String;
.field private final j0:Ljava/lang/String;
.field private final u:I

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

.method public o()Ljava/lang/String;
    .registers 2
    iget-object v0, p0, Lxa/a;->Z0:Ljava/lang/String;
    return-object v0
.end method

.method public N0()Ljava/lang/String;
    .registers 2
    iget-object v0, p0, Lxa/a;->U0:Ljava/lang/String;
    return-object v0
.end method

.method public Y0()I
    .registers 2
    iget v0, p0, Lxa/a;->u:I
    return v0
.end method
