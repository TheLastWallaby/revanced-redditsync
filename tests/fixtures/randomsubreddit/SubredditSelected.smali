# Hand-written model of Sync 23033's subreddit-selected event (h7.d0): name and a flag.
.class public Lh7/d0;
.super Ljava/lang/Object;
.source "SubredditSelectedEvent.java"

.field public a:Ljava/lang/String;
.field public b:Z

.method public constructor <init>(Ljava/lang/String;Z)V
    .registers 3
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Lh7/d0;->a:Ljava/lang/String;
    iput-boolean p2, p0, Lh7/d0;->b:Z
    return-void
.end method
