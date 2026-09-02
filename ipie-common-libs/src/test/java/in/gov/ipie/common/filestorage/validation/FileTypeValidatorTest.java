package in.gov.ipie.common.filestorage.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.Test;

import in.gov.ipie.common.filestorage.exception.UnsupportedFileTypeException;

class FileTypeValidatorTest {

    private final FileTypeValidator validator = new FileTypeValidator();

    // %PDF- is the real magic-byte signature Tika sniffs for application/pdf.
    private static final byte[] PDF_MAGIC_BYTES = "%PDF-1.4\n%fake-but-detectable-pdf-content".getBytes(StandardCharsets.US_ASCII);

    @Test
    void validate_acceptsContentMatchingTheWhitelist() {
        assertThatCode(() -> validator.validate(PDF_MAGIC_BYTES, Set.of(AllowedFileType.PDF))).doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsContentNotInTheWhitelist_evenIfExtensionWouldHaveMatched() {
        // Plain text content, but only PNG is whitelisted - proves this checks real content, not a filename.
        byte[] plainTextMasqueradingAsAnything = "just plain text, not an image".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validator.validate(plainTextMasqueradingAsAnything, Set.of(AllowedFileType.PNG)))
                .isInstanceOf(UnsupportedFileTypeException.class);
    }

    @Test
    void validate_rejectsWhenWhitelistIsEmpty() {
        assertThatThrownBy(() -> validator.validate(PDF_MAGIC_BYTES, Set.of()))
                .isInstanceOf(UnsupportedFileTypeException.class);
    }
}
