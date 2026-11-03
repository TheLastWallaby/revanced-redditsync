package x8;

import android.content.Context;
import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import java.util.Map;

/**
 * Compile signature only: Sync 23033's RedditRequest, the base of its OAuth requests. Sync's
 * OAuthRequestManager (m8.a) fetches or refreshes the account's token and passes it to a(String);
 * getHeaders() adds it as a bearer Authorization with Sync's User-Agent. The APK keeps its own class;
 * the image upload patch checks these members before installing. Tests use tests/x8/.
 */
public abstract class a<T> extends Request<T> {
    protected Map<String, String> s;

    public a(Context context, int method, String url, Response.Listener<T> listener, Response.ErrorListener errorListener) {
        super(method, url, errorListener);
    }

    public void a(String token) { }

    @Override public void deliverResponse(T response) { }

    @Override public Map<String, String> getHeaders() throws AuthFailureError { return null; }

    @Override protected Map<String, String> getParams() throws AuthFailureError { return s; }

    @Override public VolleyError parseNetworkError(VolleyError error) { return error; }
}
