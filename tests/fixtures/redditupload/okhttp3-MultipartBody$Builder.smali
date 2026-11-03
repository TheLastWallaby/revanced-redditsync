.class public final Lokhttp3/MultipartBody$Builder;
.super Ljava/lang/Object;

# Signature-only skeleton of the member the image upload patch checks; generated, not from the APK.

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public native setType(Lokhttp3/MediaType;)Lokhttp3/MultipartBody$Builder;
.end method

.method public native addFormDataPart(Ljava/lang/String;Ljava/lang/String;)Lokhttp3/MultipartBody$Builder;
.end method

.method public native addFormDataPart(Ljava/lang/String;Ljava/lang/String;Lokhttp3/RequestBody;)Lokhttp3/MultipartBody$Builder;
.end method

.method public native build()Lokhttp3/MultipartBody;
.end method
