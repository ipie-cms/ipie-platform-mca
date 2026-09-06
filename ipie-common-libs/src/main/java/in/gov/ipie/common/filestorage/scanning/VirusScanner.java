package in.gov.ipie.common.filestorage.scanning;

import java.io.InputStream;

/**
 * Port every uploaded file must be scanned through before it is promoted out of quarantine
 * (master standards doc, file-upload rules, section 3). Deliberately silent on which scanner is
 * behind it - self-hosted (ClamAV, see {@code ipie-service-template}'s {@code
 * ClamAvVirusScanner}) today, swappable later for a cloud-native scanner (e.g. AWS malware
 * protection) once that has organizational approval, purely by adding a new implementation of
 * this interface and selecting it via configuration - no caller of this port changes.
 *
 * <p>There is deliberately no "no scanner configured, assume clean" implementation shipped here -
 * see {@code ipie-service-template}'s {@code FailClosedVirusScanner}, the default when nothing
 * else is configured. Unlike a missing event broker (safe to just log instead), a missing virus
 * scanner is not safe to silently skip.
 */
public interface VirusScanner {

    ScanResult scan(InputStream content, long sizeBytes);
}
