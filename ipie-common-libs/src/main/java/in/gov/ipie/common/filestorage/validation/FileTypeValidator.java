package in.gov.ipie.common.filestorage.validation;

import java.util.Set;

import org.apache.tika.Tika;

import in.gov.ipie.common.filestorage.exception.UnsupportedFileTypeException;

/**
 * Validates a file's *actual* content type against a caller-supplied, per-use-case whitelist
 * (master standards doc, file-upload rules, section 1) - never trusts the filename's extension
 * alone, which is trivially spoofed. Uses Apache Tika's magic-byte content sniffing, the same
 * technique the {@code file} command uses, rather than the client-supplied
 * {@code Content-Type} header (also trivially spoofed).
 *
 * <p>Operates on the whole file's bytes rather than a single-pass stream, so the same bytes can
 * still be hashed ({@link in.gov.ipie.common.filestorage.hash.FileHasher}) and uploaded afterward -
 * appropriate given the per-file size limits this module's exceptions assume (tens of MB, not
 * streamed multi-GB uploads).
 */
public final class FileTypeValidator {

    private final Tika tika = new Tika();

    /** @throws UnsupportedFileTypeException if the sniffed content type isn't in {@code allowedTypes} */
    public void validate(byte[] content, Set<AllowedFileType> allowedTypes) {
        String detectedMimeType = detectMimeType(content);
        boolean allowed = allowedTypes.stream().anyMatch(type -> type.mimeType().equalsIgnoreCase(detectedMimeType));
        if (!allowed) {
            throw new UnsupportedFileTypeException(detectedMimeType);
        }
    }

    /**
     * The sniffed content type alone, for callers that already know the type is allowed (e.g.
     * just validated it) and want to record it as metadata - keeps Tika an implementation detail
     * of this module rather than a dependency every consuming service needs on its own classpath.
     */
    public String detectMimeType(byte[] content) {
        return tika.detect(content);
    }
}
