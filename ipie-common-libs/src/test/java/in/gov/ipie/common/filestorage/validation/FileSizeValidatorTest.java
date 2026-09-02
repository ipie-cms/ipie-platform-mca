package in.gov.ipie.common.filestorage.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import in.gov.ipie.common.filestorage.exception.FileTooLargeException;

class FileSizeValidatorTest {

    @Test
    void validate_acceptsSizeAtOrBelowTheLimit() {
        assertThatCode(() -> FileSizeValidator.validate(100, 100)).doesNotThrowAnyException();
        assertThatCode(() -> FileSizeValidator.validate(99, 100)).doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsSizeAboveTheLimit() {
        assertThatThrownBy(() -> FileSizeValidator.validate(101, 100))
                .isInstanceOf(FileTooLargeException.class);
    }
}
