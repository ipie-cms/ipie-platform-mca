package in.gov.ipie.common.audit.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Proves {@link AuditValueMasker} masks known-PII field names wherever they appear in the
 * serialized tree - top-level, nested inside another object, and inside an array - while leaving
 * every other field untouched, and that it never fails outright on a {@code null} value.
 */
class AuditValueMaskerTest {

    private record Contact(String email, String phoneNumber) {
    }

    private record Person(String fullName, String email, String phoneNumber, String verificationToken) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void maskAndSerialize_returnsNull_forANullValue() {
        assertThat(AuditValueMasker.maskAndSerialize(null, objectMapper)).isNull();
    }

    @Test
    void maskAndSerialize_masksKnownPiiFields_andLeavesEverythingElseUntouched() throws Exception {
        Person person = new Person("Jane Doe", "jane.doe@example.com", "+91 9800000001", "tok-abc-123");

        String json = AuditValueMasker.maskAndSerialize(person, objectMapper);

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        assertThat(parsed.get("fullName")).isEqualTo("Jane Doe");
        assertThat(parsed.get("email")).asString().startsWith("ja").doesNotContain("jane.doe@example.com");
        assertThat(parsed.get("phoneNumber")).asString().endsWith("0001").doesNotContain("+91 9800000001");
        assertThat(parsed.get("verificationToken")).isEqualTo("*".repeat("tok-abc-123".length()));
    }

    @Test
    void maskAndSerialize_masksPiiFieldsNestedInsideAnotherObject() throws Exception {
        Map<String, Object> wrapper = Map.of("owner", new Contact("owner@example.com", "9800000002"));

        String json = AuditValueMasker.maskAndSerialize(wrapper, objectMapper);

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> owner = (Map<String, Object>) parsed.get("owner");
        assertThat(owner.get("email")).asString().doesNotContain("owner@example.com");
        assertThat(owner.get("phoneNumber")).asString().doesNotContain("9800000002");
    }

    @Test
    void maskAndSerialize_masksPiiFieldsInsideAnArray() throws Exception {
        List<Contact> contacts = List.of(new Contact("a@example.com", "9800000003"), new Contact("b@example.com", "9800000004"));

        String json = AuditValueMasker.maskAndSerialize(contacts, objectMapper);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parsed = objectMapper.readValue(json, List.class);
        assertThat(parsed).extracting(entry -> entry.get("email"))
                .doesNotContain("a@example.com", "b@example.com");
        assertThat(parsed).allSatisfy(entry -> assertThat(entry.get("phoneNumber")).asString().startsWith("*"));
    }
}
