.class public abstract Lc9/c;
.super Ljava/lang/Object;
.source "PostParserHelper.java"

# Hand-written model of the part of PostParserHelper.a (c9.c.a) in Sync 23033 that
# "Sync: use subreddit suggested comment sort" edits: the item JSON copied from p2 into v2, one
# ContentValues in v5, and a post's suggested sort read with optString, cleaned with c8.d.e
# ("null" or empty becomes null) and stored in the suggested_sort column, then the single add to
# the result list.

.method public static a(Landroid/content/Context;ILorg/json/JSONObject;Ljava/util/ArrayList;IZZLjava/lang/String;)V
    .registers 16

    move-object v2, p2
    new-instance v5, Landroid/content/ContentValues;
    invoke-direct {v5}, Landroid/content/ContentValues;-><init>()V

    const-string v0, "id"
    invoke-virtual {v2, v0}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v6
    const-string v7, "_id"
    invoke-virtual {v5, v7, v6}, Landroid/content/ContentValues;->put(Ljava/lang/String;Ljava/lang/String;)V

    const-string v0, "title"
    invoke-virtual {v2, v0}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v6
    invoke-virtual {v5, v0, v6}, Landroid/content/ContentValues;->put(Ljava/lang/String;Ljava/lang/String;)V

    const-string v3, "suggested_sort"
    invoke-virtual {v2, v3}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v3
    invoke-static {v3}, Lc8/d;->e(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v3
    const-string v9, "suggested_sort"
    invoke-virtual {v5, v9, v3}, Landroid/content/ContentValues;->put(Ljava/lang/String;Ljava/lang/String;)V

    move-object v1, p3
    invoke-virtual {v1, v5}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    return-void
.end method
