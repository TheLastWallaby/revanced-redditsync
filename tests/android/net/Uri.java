package android.net;

import java.net.URI;
import java.net.URLEncoder;

/** Small JVM adapter for the Android Uri operations exercised by these tests. */
public final class Uri {
    private final URI uri;
    private Uri(String value) { uri = URI.create(value); }
    public static Uri parse(String value) { return new Uri(value); }
    public String getPath() { return uri.getPath(); }
    public String getHost() { return uri.getHost(); }
    public Builder buildUpon() { return new Builder(uri); }
    @Override public String toString() { return uri.toString(); }
    public static final class Builder {
        private final URI original;
        private String path;
        private String query;
        private Builder(URI uri) { original=uri;path=uri.getRawPath();query=uri.getRawQuery(); }
        public Builder encodedPath(String value) { path=value;return this; }
        public Builder appendQueryParameter(String name,String value) {
            try {
                String pair=URLEncoder.encode(name,"UTF-8")+"="+URLEncoder.encode(value,"UTF-8");
                query=query==null||query.isEmpty()?pair:query+"&"+pair;
                return this;
            } catch (Exception e) { throw new AssertionError(e); }
        }
        public Uri build() {
            return Uri.parse(original.getScheme()+"://"+original.getRawAuthority()+path
                    +(query==null?"":"?"+query)+(original.getRawFragment()==null?"":"#"+original.getRawFragment()));
        }
    }
}
