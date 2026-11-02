.class public Lr8/g;
.super Ljava/lang/Object;
.source "RedditVideoRequest.java"

.field private c:Ljava/lang/String;

# The end of RedditVideoRequest.parseNetworkResponse: build the result from the parsed links.
.method public parseNetworkResponse(Lcom/android/volley/NetworkResponse;)Lcom/android/volley/Response;
    .registers 10
    const/4 v3, 0x0
    const/4 v4, 0x0
    new-instance v0, Lo8/h;
    iget-object v1, p0, Lr8/g;->c:Ljava/lang/String;
    invoke-direct {v0, v1, v3, v4}, Lo8/h;-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
    const-wide/32 v1, 0x36ee80
    const/4 p1, 0x0
    return-object p1
.end method
