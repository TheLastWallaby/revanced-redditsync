# Hand-written model of Sync 23033's UploadImgurRequest (u8.c) constructor; not the original body.
# It reads the picked image (a content:// URI through the resolver) for a base64 Imgur upload.
# No Imgur or RapidAPI credentials are included.
.class public Lu8/c;
.super Lcom/android/volley/Request;
.source "UploadImgurRequest.java"

.field private final a:Lcom/android/volley/Response$Listener;
.field private b:[B

.method public constructor <init>(Landroid/content/Context;Landroid/net/Uri;Lcom/android/volley/Response$Listener;Lcom/android/volley/Response$ErrorListener;)V
    .registers 7
    const/4 v0, 0x1
    const-string v1, "https://imgur-apiv3.p.rapidapi.com/3/image"
    invoke-direct {p0, v0, v1, p4}, Lcom/android/volley/Request;-><init>(ILjava/lang/String;Lcom/android/volley/Response$ErrorListener;)V
    iput-object p3, p0, Lu8/c;->a:Lcom/android/volley/Response$Listener;
    invoke-virtual {p1}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;
    move-result-object p1
    invoke-virtual {p1, p2}, Landroid/content/ContentResolver;->openInputStream(Landroid/net/Uri;)Ljava/io/InputStream;
    move-result-object p1
    invoke-static {p1}, Lorg/apache/commons/io/IOUtils;->toByteArray(Ljava/io/InputStream;)[B
    move-result-object p1
    iput-object p1, p0, Lu8/c;->b:[B
    return-void
.end method
