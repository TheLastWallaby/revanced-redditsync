package m8;

import android.content.Context;

/**
 * Compile signature only: Sync 23033's OAuthRequestManager. c(context, request) fetches or
 * refreshes the signed-in account's token and queues the request with it; b(context, token,
 * request) queues it with a known token, or as c does; g(context, request, refreshToken) exchanges
 * another account's refresh token first. The APK keeps its own class.
 */
public final class a {
    public static void b(Context context, String token, x8.a<?> request) { }

    public static void c(Context context, x8.a<?> request) { }

    public static void g(Context context, x8.a<?> request, String refreshToken) { }

    private a() { }
}
