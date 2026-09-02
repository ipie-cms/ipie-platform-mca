package in.gov.ipie.common.filestorage.exception;

import in.gov.ipie.common.core.exception.IpieException;

/**
 * Raised when no virus scan result could be obtained (scanner unreachable, or none configured -
 * see {@code FailClosedVirusScanner}). Uploads must fail closed here, never fall back to treating
 * unscanned content as clean (master standards doc, file-upload rules, section 3).
 */
public class ScanUnavailableException extends IpieException {

    public ScanUnavailableException() {
        super(FileStorageErrorCode.SCAN_UNAVAILABLE, "File could not be scanned; try again shortly");
    }
}
