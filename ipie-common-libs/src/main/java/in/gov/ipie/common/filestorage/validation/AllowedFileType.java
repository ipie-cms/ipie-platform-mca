package in.gov.ipie.common.filestorage.validation;

/**
 * One entry in a per-use-case whitelist (master standards doc, file-upload rules, section 1:
 * "Whitelist allowed extensions explicitly per use case ... never blacklist"). A service defines
 * its own {@code Set<AllowedFileType>} per upload use case - this module intentionally ships no
 * global default whitelist, since "per use case" is the whole point (a scanned-order upload and a
 * profile-photo upload should not share one list).
 */
public record AllowedFileType(String extension, String mimeType) {

    public AllowedFileType {
        if (extension == null || !extension.startsWith(".")) {
            throw new IllegalArgumentException("extension must start with '.', got: " + extension);
        }
    }

    public static final AllowedFileType PDF = new AllowedFileType(".pdf", "application/pdf");
    public static final AllowedFileType DOCX = new AllowedFileType(
            ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    public static final AllowedFileType XLSX = new AllowedFileType(
            ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    public static final AllowedFileType JPG = new AllowedFileType(".jpg", "image/jpeg");
    public static final AllowedFileType PNG = new AllowedFileType(".png", "image/png");
}
