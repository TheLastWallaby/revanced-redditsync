package com.android.volley.errors;

import com.android.volley.VolleyError;

/** Test stand-in for Sync 23033's OAuthError: the rejected request's message, with it as the cause. */
public class OAuthError extends VolleyError {
    public OAuthError(VolleyError cause) { super(cause.getMessage(), cause); }
}
