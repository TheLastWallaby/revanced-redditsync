import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import com.android.apksig.ApkVerifier;
import com.android.tools.smali.dexlib2.*;

public class VerifyPersonalApk {
  static Set<String> classes(String path) throws Exception {
    Set<String> names = new TreeSet<>();
    var dex = DexFileFactory.loadDexContainer(new File(path), Opcodes.getDefault());
    for (String entry : dex.getDexEntryNames())
      for (var clazz : dex.getEntry(entry).getDexFile().getClasses()) names.add(clazz.getType());
    return names;
  }
  public static void main(String[] args) throws Exception {
    if (args.length != 4 || !args[2].matches("[0-9a-fA-F]{64}"))
      throw new IllegalArgumentException("Usage: BASE APK EXPECTED_CERT_SHA256 REPORT_JSON");
    var result = new ApkVerifier.Builder(new File(args[1])).build().verify();
    if (!result.isVerified() || !result.isVerifiedUsingV2Scheme())
      throw new AssertionError("APK signature verification failed");
    if (result.getSignerCertificates().size() != 1)
      throw new AssertionError("Expected a single app signer");
    String cert = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(result.getSignerCertificates().get(0).getEncoded()));
    if (!cert.equalsIgnoreCase(args[2])) throw new AssertionError("Signing identity differs from installed app");
    var before = classes(args[0]);
    var after = classes(args[1]);
    if (!after.containsAll(before)) throw new AssertionError("An existing class was removed");
    int unchanged = 0;
    try (ZipFile base = new ZipFile(args[0]); ZipFile apk = new ZipFile(args[1])) {
      var entries = base.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        String name = entry.getName();
        // stamp-cert-sha256 is the store's source stamp, a signing artifact that re-signing removes.
        if (entry.isDirectory() || name.matches("classes[0-9]*\\.dex") || name.equals("stamp-cert-sha256") ||
            name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") ||
                                           name.endsWith(".DSA") || name.endsWith(".EC") || name.endsWith("MANIFEST.MF"))) continue;
        var replacement = apk.getEntry(name);
        if (replacement == null || !Arrays.equals(base.getInputStream(entry).readAllBytes(), apk.getInputStream(replacement).readAllBytes()))
          throw new AssertionError("Unexpected resource/manifest change: " + name);
        unchanged++;
      }
    }
    String report = "{\n  \"signature_verified\": true,\n  \"signer_sha256\": \"" + cert +
      "\",\n  \"existing_classes_preserved\": " + before.size() +
      ",\n  \"added_classes\": " + (after.size() - before.size()) +
      ",\n  \"unchanged_noncode_entries\": " + unchanged +
      ",\n  \"device_test\": \"pending\"\n}\n";
    Files.writeString(Path.of(args[3]), report);
    System.out.println("Verified APK signature, update identity, class preservation and " + unchanged + " unchanged resources");
  }
}
