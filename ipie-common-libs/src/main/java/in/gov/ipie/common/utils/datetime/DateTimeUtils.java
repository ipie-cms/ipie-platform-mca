package in.gov.ipie.common.utils.datetime;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** ISO 8601 helpers (master standards doc, 5.3: "Dates - ISO 8601"). */
public final class DateTimeUtils {

    private DateTimeUtils() {
    }

    /** Current instant truncated to millisecond precision - avoids noisy nanosecond diffs in tests/logs. */
    public static Instant nowUtc() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public static String toIso8601(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    public static Instant parseIso8601(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    public static String toIso8601Date(Instant instant) {
        return instant == null ? null : instant.atZone(ZoneOffset.UTC).toLocalDate().toString();
    }
}
