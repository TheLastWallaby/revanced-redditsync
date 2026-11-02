# Hand-written model of the chip group's ButterKnife binding; the first chip (id 0x7f0900da in
# build 23033) calls onPreviewsClicked. Not the original body.
.class public Lcom/laurencedawson/reddit_sync/sections/posts/toolbar/chip_groups/DefaultSubredditChipGroup_ViewBinding;
.super Ljava/lang/Object;
.source "DefaultSubredditChipGroup_ViewBinding.java"

.field private d:Lcom/laurencedawson/reddit_sync/sections/posts/toolbar/chip_groups/DefaultSubredditChipGroup;
.field private e:Landroid/view/View;
.field private f:Landroid/view/View;

.method public constructor <init>(Lcom/laurencedawson/reddit_sync/sections/posts/toolbar/chip_groups/DefaultSubredditChipGroup;Landroid/view/View;)V
    .registers 5
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Lcom/laurencedawson/reddit_sync/sections/posts/toolbar/chip_groups/DefaultSubredditChipGroup_ViewBinding;->d:Lcom/laurencedawson/reddit_sync/sections/posts/toolbar/chip_groups/DefaultSubredditChipGroup;
    const v0, 0x7f0900da
    const-string v1, "method 'onPreviewsClicked'"
    invoke-static {p2, v0, v1}, Lq2/c;->c(Landroid/view/View;ILjava/lang/String;)Landroid/view/View;
    move-result-object v0
    iput-object v0, p0, Lcom/laurencedawson/reddit_sync/sections/posts/toolbar/chip_groups/DefaultSubredditChipGroup_ViewBinding;->e:Landroid/view/View;
    new-instance v1, Ljava/lang/Object;
    invoke-direct {v1}, Ljava/lang/Object;-><init>()V
    invoke-virtual {v0, v1}, Landroid/view/View;->setTag(Ljava/lang/Object;)V
    const v0, 0x7f0900e5
    const-string v1, "method 'onChangeViewClicked'"
    invoke-static {p2, v0, v1}, Lq2/c;->c(Landroid/view/View;ILjava/lang/String;)Landroid/view/View;
    move-result-object v0
    iput-object v0, p0, Lcom/laurencedawson/reddit_sync/sections/posts/toolbar/chip_groups/DefaultSubredditChipGroup_ViewBinding;->f:Landroid/view/View;
    return-void
.end method
