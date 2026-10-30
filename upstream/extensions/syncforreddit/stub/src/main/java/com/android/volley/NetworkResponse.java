package com.android.volley;

import java.util.Map;

public class NetworkResponse {
    public final int statusCode;
    public final byte[] data;
    public final Map<String, String> headers;
    public final boolean notModified;
    public final long networkTimeMs;

    public NetworkResponse(int statusCode, byte[] data, Map<String, String> headers, boolean notModified, long networkTimeMs) {
        throw new UnsupportedOperationException("Stub");
    }
}
