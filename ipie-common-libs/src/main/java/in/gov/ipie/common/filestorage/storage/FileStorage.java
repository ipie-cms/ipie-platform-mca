package in.gov.ipie.common.filestorage.storage;

import java.io.InputStream;
import java.time.Duration;

/**
 * Port every service uploads/downloads files through, deliberately silent on which object store
 * is behind it - the same reasoning as {@code EventPublisher} for the messaging broker. A service
 * supplies the concrete binding (see {@code ipie-service-template}'s {@code S3FileStorage}, which
 * covers any S3-API-compatible target - AWS S3, MinIO, or another provider's S3-compatible gateway
 * - selected entirely by configuration, never by code). A backend that does not speak the S3 API
 * at all is a second, additional implementation of this same interface, not a rewrite of anything
 * that calls it.
 *
 * <p>Callers never see a bucket, a region, or a vendor SDK type - only a {@code key} (see {@link
 * in.gov.ipie.common.filestorage.naming.StorageKeyGenerator}) and a stream.
 */
public interface FileStorage {

    /**
     * Uploads {@code content} to {@code key}. {@code key} must already be fully qualified (bucket
     * choice - e.g. quarantine vs. permanent - is encoded in the key/prefix by the caller, per the
     * quarantine-first pattern; this port does not know about scanning).
     */
    void put(String key, InputStream content, long sizeBytes, String contentType);

    /** Copies an object from one key to another (e.g. quarantine prefix -&gt; permanent prefix after a clean scan). */
    void copy(String sourceKey, String destinationKey);

    /** Deletes an object - e.g. removing quarantined bytes for a file that failed scanning. */
    void delete(String key);

    /**
     * A short-lived, signed URL a caller can use to download {@code key} directly from the store,
     * without the request proxying through this service (master standards doc, file-upload rules,
     * section 5: "signed, short-lived URLs for direct download rather than proxying raw storage
     * access").
     */
    String presignedDownloadUrl(String key, Duration expiry);
}
