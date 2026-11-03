/**
 * Compile signatures only: the OkHttp 5 classes in Sync 23033 that the RedGifs token refresh,
 * the Arctic Shift restore and the Reddit image upload call. The APK keeps its own OkHttp; the
 * patches check these methods exist before installing.
 * Tests use the runnable stand-ins in tests/okhttp3/.
 */
package okhttp3;
