.class public Lo8/b;
.super Ljava/lang/Object;
.source "Gallery.java"


# instance fields
.field public a:I

.field public b:[Ljava/lang/String;

.field public c:[Ljava/lang/String;

.field public d:[Ljava/lang/String;


# direct methods
.method public constructor <init>(I)V
    .locals 1

    .line 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    .line 2
    new-array v0, p1, [Ljava/lang/String;

    iput-object v0, p0, Lo8/b;->b:[Ljava/lang/String;

    .line 3
    new-array v0, p1, [Ljava/lang/String;

    iput-object v0, p0, Lo8/b;->d:[Ljava/lang/String;

    .line 4
    new-array p1, p1, [Ljava/lang/String;

    iput-object p1, p0, Lo8/b;->c:[Ljava/lang/String;

    return-void
.end method
