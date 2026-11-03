package x8;

import android.content.Context;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.errors.OAuthError;
import java.util.HashMap;
import java.util.Map;

/**
 * Test stand-in for Sync 23033's RedditRequest (x8.a), transcribed from the base: OAuthRequestManager
 * passes the account's token to a(String); getHeaders() is AccountSingleton.e(token): Sync's
 * User-Agent and, when there is a token, "Authorization: bearer <token>"; getParams() returns s;
 * deliverResponse hands the result to the listener unless canceled; parseNetworkError turns
 * Reddit's invalid_token challenge into OAuthError.
 */
public abstract class a<T> extends Request<T> {
    public static final String USER_AGENT = "android:com.laurencedawson.reddit_sync:test";

    protected final Response.Listener<T> q;
    protected String r;
    protected Map<String, String> s;

    public a(Context context, int method, String url, Response.Listener<T> listener, Response.ErrorListener errorListener) {
        super(method, url, errorListener);
        q = listener;
        setShouldCache(false);
    }

    public void a(String token) { r = token; }

    @Override public void deliverResponse(T response) {
        if (q != null && !isCanceled()) q.onResponse(response);
    }

    @Override public Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        if (r != null && !r.isEmpty()) headers.put("Authorization", "bearer " + r);
        return headers;
    }

    @Override protected Map<String, String> getParams() { return s; }

    @Override public VolleyError parseNetworkError(VolleyError error) {
        if (error != null && error.networkResponse != null && error.networkResponse.headers != null
                && "Bearer realm=\"reddit\", error=\"invalid_token\"".equals(error.networkResponse.headers.get("www-authenticate")))
            return new OAuthError(error);
        return super.parseNetworkError(error);
    }
}
