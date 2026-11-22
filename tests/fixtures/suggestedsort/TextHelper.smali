.class public final Lc8/d;
.super Ljava/lang/Object;

# Hand-written model of c8.d.e in Sync 23033: "null" or empty text becomes null, other text is trimmed.

.method public static e(Ljava/lang/String;)Ljava/lang/String;
    .registers 3

    const/4 v1, 0x0
    if-eqz p0, :cond_null
    const-string v0, "null"
    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-nez v0, :cond_null
    invoke-virtual {p0}, Ljava/lang/String;->isEmpty()Z
    move-result v0
    if-nez v0, :cond_null
    invoke-virtual {p0}, Ljava/lang/String;->trim()Ljava/lang/String;
    move-result-object p0
    return-object p0

    :cond_null
    return-object v1
.end method
