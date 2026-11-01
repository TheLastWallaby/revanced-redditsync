.class public Lt8/a;
.super Lcom/android/volley/Request;

# Hand-written layout model of Sync 23033's GrabIpRequest (t8.a), not copied from the APK: it
# requests https://api.redgifs.com/info and reads remote-addr from the JSON answer. Only the
# superclass and these two strings are checked by the patch.

.method public constructor <init>()V
    .registers 2
    const-string v0, "https://api.redgifs.com/info"
    return-void
.end method

.method public parse()Ljava/lang/String;
    .registers 2
    const-string v0, "remote-addr"
    return-object v0
.end method
