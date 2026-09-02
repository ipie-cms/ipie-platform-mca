package in.gov.ipie.common.utils.masking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DataMaskingTest {

    @Test
    void masksEmailKeepingDomainVisible() {
        assertThat(DataMasking.maskEmail("arsh.kumar@example.com")).isEqualTo("ar********@example.com");
    }

    @Test
    void masksShortEmailLocalPartEntirely() {
        assertThat(DataMasking.maskEmail("ab@example.com")).isEqualTo("ab***@example.com");
    }

    @Test
    void masksPanKeepingFirstFiveAndLastCharacter() {
        assertThat(DataMasking.maskPan("ABCDE1234F")).isEqualTo("ABCDE****F");
    }

    @Test
    void masksAadhaarShowingOnlyLastFourDigits() {
        assertThat(DataMasking.maskAadhaar("1234 5678 9012")).isEqualTo("XXXX XXXX 9012");
    }

    @Test
    void masksArbitraryValueKeepingRequestedVisibleSuffix() {
        assertThat(DataMasking.mask("4111111111111111", 4)).isEqualTo("************1111");
    }

    @Test
    void returnsNullForNullInput() {
        assertThat(DataMasking.maskEmail(null)).isNull();
        assertThat(DataMasking.maskPan(null)).isNull();
        assertThat(DataMasking.maskAadhaar(null)).isNull();
    }
}
