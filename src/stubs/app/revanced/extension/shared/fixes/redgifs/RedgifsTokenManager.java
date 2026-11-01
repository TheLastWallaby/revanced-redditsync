package app.revanced.extension.shared.fixes.redgifs;

/**
 * Compile signature only: the inherited ReVanced RedGifs token cache in the prepared base. Its
 * private static {@code tokenMap} (User-Agent to token) is reached by reflection; the RedGifs
 * patch checks the field and these members before installing. Tests use the double in tests/.
 */
public class RedgifsTokenManager {
    public static class RedgifsToken {
        public RedgifsToken(String accessToken, long refreshTimeInSeconds) { }
        public String getAccessToken() { return null; }
    }
}
