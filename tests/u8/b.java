package u8;

import com.android.volley.VolleyError;

/** Test stand-in for Sync 23033's ImgurUploadError (u8.b), with its fixed message. */
public class b extends VolleyError {
    public b() { super("Upload to Imgur is not currently available"); }
}
