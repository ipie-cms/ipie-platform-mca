package in.gov.ipie.common.filestorage.hash;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 file hashing (master standards doc, file-upload rules, section 6: "file hash for
 * dedup/integrity checks"). A pure function over bytes - callers decide what to do with the
 * result (store it on the metadata row, compare against a prior version, etc).
 */
public final class FileHasher {

    private FileHasher() {
    }

    public static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM (JLS-mandated algorithm) - unreachable in practice.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
