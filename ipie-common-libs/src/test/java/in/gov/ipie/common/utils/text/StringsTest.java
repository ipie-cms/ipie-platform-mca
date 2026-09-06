package in.gov.ipie.common.utils.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StringsTest {

    @Test
    void isBlankIsTrueForNullEmptyAndWhitespace() {
        assertThat(Strings.isBlank(null)).isTrue();
        assertThat(Strings.isBlank("")).isTrue();
        assertThat(Strings.isBlank("   ")).isTrue();
        assertThat(Strings.isBlank(" a ")).isFalse();
    }

    @Test
    void isNotBlankIsTheInverseOfIsBlank() {
        assertThat(Strings.isNotBlank(null)).isFalse();
        assertThat(Strings.isNotBlank("")).isFalse();
        assertThat(Strings.isNotBlank("value")).isTrue();
    }

    @Test
    void truncateShortensValuesLongerThanMaxLength() {
        assertThat(Strings.truncate("abcdef", 3)).isEqualTo("abc");
    }

    @Test
    void truncateLeavesValuesAtOrUnderMaxLengthUnchanged() {
        assertThat(Strings.truncate("abc", 3)).isEqualTo("abc");
        assertThat(Strings.truncate("ab", 3)).isEqualTo("ab");
    }

    @Test
    void truncateReturnsNullForNullInput() {
        assertThat(Strings.truncate(null, 3)).isNull();
    }

    @Test
    void defaultIfBlankReturnsFallbackOnlyWhenValueIsBlank() {
        assertThat(Strings.defaultIfBlank(null, "fallback")).isEqualTo("fallback");
        assertThat(Strings.defaultIfBlank("  ", "fallback")).isEqualTo("fallback");
        assertThat(Strings.defaultIfBlank("value", "fallback")).isEqualTo("value");
    }
}
