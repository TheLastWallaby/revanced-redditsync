package u8;

import com.android.volley.VolleyError;

/**
 * Compile signature only: Sync 23033's ImgurUploadError. Sync's upload callers show the message of
 * this error type and "Failed to upload image" for any other. The APK keeps its own class; tests
 * use tests/u8/.
 */
public class b extends VolleyError {
    public b() { super("Upload to Imgur is not currently available"); }
}
