# Hand-written model of Sync 23033's ImgurUploadError (u8.b); not the original body.
.class public Lu8/b;
.super Lcom/android/volley/VolleyError;
.source "ImgurUploadError.java"

.method public constructor <init>()V
    .registers 2
    const-string v0, "Upload to Imgur is not currently available"
    invoke-direct {p0, v0}, Lcom/android/volley/VolleyError;-><init>(Ljava/lang/String;)V
    return-void
.end method
