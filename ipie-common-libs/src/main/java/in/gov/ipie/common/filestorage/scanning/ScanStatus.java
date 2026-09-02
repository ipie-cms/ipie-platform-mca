package in.gov.ipie.common.filestorage.scanning;

/** Outcome of a {@link VirusScanner} pass. {@code ERROR} means "could not determine" - never treated as clean. */
public enum ScanStatus {
    CLEAN,
    INFECTED,
    ERROR
}
