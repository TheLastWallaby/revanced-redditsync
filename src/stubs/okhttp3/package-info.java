/**
 * Compile signatures only: the OkHttp 5 classes in Sync 23033 that the RedGifs token refresh
 * calls. The APK keeps its own OkHttp; the patch checks these methods exist before installing.
 * Tests use the runnable stand-ins in tests/okhttp3/.
 */
package okhttp3;
