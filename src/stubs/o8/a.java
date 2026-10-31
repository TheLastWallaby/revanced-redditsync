package o8;

/**
 * Compile/test signature only: Sync 23033's Award model (Award.java). The APK keeps its
 * own class; the award patch checks this field layout before installing its hooks.
 */
public class a implements java.io.Serializable {
    /** count */
    public int a;
    /** name */
    public String b;
    /** description */
    public String c;
    /** icon_url (detail sheet image) */
    public String q;
    /** resized icon (list row image) */
    public String r;
}
