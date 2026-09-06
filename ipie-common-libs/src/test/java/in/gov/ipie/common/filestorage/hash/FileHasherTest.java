package in.gov.ipie.common.filestorage.hash;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class FileHasherTest {

    @Test
    void sha256Hex_isDeterministicForTheSameContent() {
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);

        assertThat(FileHasher.sha256Hex(content)).isEqualTo(FileHasher.sha256Hex(content));
    }

    @Test
    void sha256Hex_isA64CharacterLowercaseHexString() {
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);

        assertThat(FileHasher.sha256Hex(content)).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void sha256Hex_differsForDifferentContent() {
        assertThat(FileHasher.sha256Hex("a".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(FileHasher.sha256Hex("b".getBytes(StandardCharsets.UTF_8)));
    }
}
