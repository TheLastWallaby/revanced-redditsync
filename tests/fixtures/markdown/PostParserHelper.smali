# Focused fixture for c9.c.a's two emote branches. The Pattern.quote / result /
# replaceAll instruction triples and register roles match build 23033.
.class public abstract Lc9/c;
.super Ljava/lang/Object;
.source "PostParserHelper.java"

.method public static a(Landroid/content/Context;ILorg/json/JSONObject;Ljava/util/ArrayList;IZZLjava/lang/String;)V
    .locals 16
    const-string v15, "emote|fixture.*"
    move-object/from16 v1, p7
    if-eqz p5, :gif
    const-string v0, "https://example.invalid/image$12\\still.png?sig=$HOME\\end"
    invoke-static {v15}, Ljava/util/regex/Pattern;->quote(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v3
    invoke-virtual {v1, v3, v0}, Ljava/lang/String;->replaceAll(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    goto :done
    :gif
    const-string v0, "https://example.invalid/clip$12\\still.gif?sig=$HOME\\end"
    invoke-static {v15}, Ljava/util/regex/Pattern;->quote(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v3
    invoke-virtual {v1, v3, v0}, Ljava/lang/String;->replaceAll(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    :done
    const-string v3, "emote|"
    # Deliberate group substitution: the patch must leave this third call alone.
    const-string v0, "(caption)"
    const-string v3, "$1"
    invoke-virtual {v1, v0, v3}, Ljava/lang/String;->replaceAll(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    move-object/from16 v0, p3
    invoke-virtual {v0, v1}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    return-void
.end method
