package in.gov.ipie.common.utils.datetime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

class DateTimeUtilsTest {

    @Test
    void nowUtcIsTruncatedToMillisecondPrecision() {
        Instant now = DateTimeUtils.nowUtc();

        assertThat(now).isEqualTo(now.truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void toIso8601FormatsAnInstant() {
        Instant instant = Instant.parse("2026-07-11T09:48:36.629Z");

        assertThat(DateTimeUtils.toIso8601(instant)).isEqualTo("2026-07-11T09:48:36.629Z");
    }

    @Test
    void toIso8601ReturnsNullForNullInput() {
        assertThat(DateTimeUtils.toIso8601(null)).isNull();
    }

    @Test
    void parseIso8601RoundTripsWithToIso8601() {
        Instant instant = Instant.parse("2026-07-11T09:48:36.629Z");

        assertThat(DateTimeUtils.parseIso8601(DateTimeUtils.toIso8601(instant))).isEqualTo(instant);
    }

    @Test
    void parseIso8601ReturnsNullForNullOrBlankInput() {
        assertThat(DateTimeUtils.parseIso8601(null)).isNull();
        assertThat(DateTimeUtils.parseIso8601("  ")).isNull();
    }

    @Test
    void toIso8601DateFormatsTheCalendarDateInUtc() {
        Instant instant = Instant.parse("2026-07-11T23:30:00Z");

        assertThat(DateTimeUtils.toIso8601Date(instant)).isEqualTo("2026-07-11");
    }

    @Test
    void toIso8601DateReturnsNullForNullInput() {
        assertThat(DateTimeUtils.toIso8601Date(null)).isNull();
    }
}
