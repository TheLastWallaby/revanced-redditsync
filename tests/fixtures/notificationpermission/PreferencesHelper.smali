# Hand-written model of Sync 23033's PreferencesHelper (t7.z). f returns the signed-in account's
# preferences when it has its own, otherwise the default preferences (reduced to the latter).
.class public final Lt7/z;
.super Ljava/lang/Object;
.source "PreferencesHelper.java"

.method public static f(Landroid/content/Context;Ljava/lang/String;)Landroid/content/SharedPreferences;
    .registers 2
    invoke-static {p0}, Landroid/preference/PreferenceManager;->getDefaultSharedPreferences(Landroid/content/Context;)Landroid/content/SharedPreferences;
    move-result-object p0
    return-object p0
.end method
