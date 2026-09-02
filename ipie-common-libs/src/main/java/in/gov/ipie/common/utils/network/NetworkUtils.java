package in.gov.ipie.common.utils.network;

import java.util.regex.Pattern;

/**
 * Dependency-free IP address helpers. Takes header/attribute values as plain strings rather than
 * a servlet request - this module intentionally has no web dependency, so request extraction
 * (e.g. {@code common-audit}'s {@code AuditAspect}) stays there and calls into this for the
 * parsing/validation/masking logic instead of reimplementing it.
 */
public final class NetworkUtils {

    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)){3}$");

    // Standard IPv6 forms including "::" zero-compression; matched with no I/O, unlike InetAddress.getByName
    // (which performs a real DNS lookup for anything that isn't a literal address - not safe for a pure validator).
    private static final Pattern IPV6 = Pattern.compile(
            "^(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}"
                    + "|([0-9a-fA-F]{1,4}:){1,7}:"
                    + "|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}"
                    + "|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}"
                    + "|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}"
                    + "|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}"
                    + "|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}"
                    + "|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})"
                    + "|:((:[0-9a-fA-F]{1,4}){1,7}|:))$");

    private NetworkUtils() {
    }

    /**
     * The originating client address from an {@code X-Forwarded-For} header value, which proxies
     * append to as a comma-separated chain - the first entry is the original client.
     */
    public static String firstForwardedFor(String forwardedForHeader) {
        if (forwardedForHeader == null || forwardedForHeader.isBlank()) {
            return null;
        }
        return forwardedForHeader.split(",")[0].trim();
    }

    public static boolean isValidIpv4(String value) {
        return value != null && IPV4.matcher(value).matches();
    }

    public static boolean isValidIpv6(String value) {
        return value != null && IPV6.matcher(value).matches();
    }

    /** {@code 192.168.1.***} - masks the last IPv4 octet for logs, per the platform's PII-in-logs standard. */
    public static String maskIpv4(String ipv4) {
        if (!isValidIpv4(ipv4)) {
            return ipv4;
        }
        int lastDot = ipv4.lastIndexOf('.');
        return ipv4.substring(0, lastDot + 1) + "***";
    }
}
