package in.gov.ipie.common.filestorage.scanning;

/**
 * Result of one {@link VirusScanner} pass. {@code detail} carries the malware signature name when
 * {@code status} is {@link ScanStatus#INFECTED}, or a short reason when {@link ScanStatus#ERROR}
 * (e.g. "scanner unreachable") - never exposed to API callers verbatim (master standards doc,
 * 5.4), but essential for the audit trail and operator troubleshooting.
 */
public record ScanResult(ScanStatus status, String detail) {

    public static ScanResult clean() {
        return new ScanResult(ScanStatus.CLEAN, null);
    }

    public static ScanResult infected(String signatureName) {
        return new ScanResult(ScanStatus.INFECTED, signatureName);
    }

    public static ScanResult error(String reason) {
        return new ScanResult(ScanStatus.ERROR, reason);
    }

    public boolean isClean() {
        return status == ScanStatus.CLEAN;
    }
}
