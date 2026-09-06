package in.gov.ipie.common.utils.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ValidationUtilsTest {

    @Test
    void validatesEmailFormat() {
        assertThat(ValidationUtils.isValidEmail("arsh.kumar@example.com")).isTrue();
        assertThat(ValidationUtils.isValidEmail("not-an-email")).isFalse();
        assertThat(ValidationUtils.isValidEmail(null)).isFalse();
    }

    @Test
    void validatesIndianMobileFormat() {
        assertThat(ValidationUtils.isValidIndianMobile("9876543210")).isTrue();
        assertThat(ValidationUtils.isValidIndianMobile("5876543210")).isFalse();
        assertThat(ValidationUtils.isValidIndianMobile("98765432100")).isFalse();
    }

    @Test
    void validatesPanFormat() {
        assertThat(ValidationUtils.isValidPan("ABCDE1234F")).isTrue();
        assertThat(ValidationUtils.isValidPan("abcde1234f")).isFalse();
        assertThat(ValidationUtils.isValidPan("ABCDE1234")).isFalse();
    }

    @Test
    void validatesAadhaarFormatIgnoringSeparators() {
        assertThat(ValidationUtils.isValidAadhaar("234512349012")).isTrue();
        assertThat(ValidationUtils.isValidAadhaar("2345 1234 9012")).isTrue();
        assertThat(ValidationUtils.isValidAadhaar("1234 5678 9012")).isFalse();
    }

    @Test
    void validatesPincodeFormat() {
        assertThat(ValidationUtils.isValidPincode("110001")).isTrue();
        assertThat(ValidationUtils.isValidPincode("000001")).isFalse();
    }

    @Test
    void validatesUuidFormat() {
        assertThat(ValidationUtils.isValidUuid("123e4567-e89b-12d3-a456-426614174000")).isTrue();
        assertThat(ValidationUtils.isValidUuid("not-a-uuid")).isFalse();
    }

    @Test
    void checksIntegerRange() {
        assertThat(ValidationUtils.isInRange(5, 1, 10)).isTrue();
        assertThat(ValidationUtils.isInRange(0, 1, 10)).isFalse();
    }
}
