package in.gov.ipie.common.core.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import in.gov.ipie.common.core.exception.ValidationFailedException;

class CursorTest {

    @Test
    void encodeThenDecode_roundTrips() {
        Cursor original = new Cursor(Instant.parse("2026-07-14T10:15:30.123Z"), UUID.randomUUID());

        Cursor decoded = Cursor.decode(original.encode());

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void encode_producesAnOpaqueTokenDifferentFromTheRawFields() {
        Cursor cursor = new Cursor(Instant.parse("2026-07-14T10:15:30.123Z"), UUID.randomUUID());

        String token = cursor.encode();

        assertThat(token).doesNotContain(cursor.id().toString());
    }

    @Test
    void decode_rejectsGarbageInput() {
        assertThatThrownBy(() -> Cursor.decode("not-a-valid-cursor-!!!"))
                .isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void decode_rejectsWellFormedBase64ThatIsNotACursor() {
        String tamperedButValidBase64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("nonsense-without-separator".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> Cursor.decode(tamperedButValidBase64))
                .isInstanceOf(ValidationFailedException.class);
    }
}
