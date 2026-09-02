package in.gov.ipie.common.filestorage.exception;

import in.gov.ipie.common.core.exception.ErrorCode;

/** Stable error codes for the file-upload pipeline (master standards doc, 5.4). */
public enum FileStorageErrorCode implements ErrorCode {
    MALWARE_DETECTED,
    SCAN_UNAVAILABLE;

    @Override
    public String code() {
        return name();
    }
}
