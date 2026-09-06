package in.gov.ipie.common.utils.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonUtilsTest {

    record Sample(String name, int count, Instant createdAt) {
    }

    @Test
    void roundTripsAPlainObject() {
        Sample original = new Sample("widget", 3, Instant.parse("2026-07-18T10:15:30Z"));

        String json = JsonUtils.toJson(original);
        Sample restored = JsonUtils.fromJson(json, Sample.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void deserializesAGenericTypeViaTypeReference() {
        String json = "[{\"a\":1},{\"a\":2}]";

        List<Map<String, Integer>> result = JsonUtils.fromJson(json, new TypeReference<List<Map<String, Integer>>>() {
        });

        assertThat(result).containsExactly(Map.of("a", 1), Map.of("a", 2));
    }

    @Test
    void ignoresUnknownPropertiesOnDeserialize() {
        Sample restored = JsonUtils.fromJson("{\"name\":\"widget\",\"count\":1,\"extra\":\"ignored\"}", Sample.class);

        assertThat(restored.name()).isEqualTo("widget");
    }

    @Test
    void readsAJsonTree() {
        assertThat(JsonUtils.readTree("{\"a\":1}").get("a").asInt()).isEqualTo(1);
    }

    @Test
    void validatesJsonSyntax() {
        assertThat(JsonUtils.isValidJson("{\"a\":1}")).isTrue();
        assertThat(JsonUtils.isValidJson("not json")).isFalse();
    }

    @Test
    void returnsNullForNullOrBlankInput() {
        assertThat(JsonUtils.toJson(null)).isNull();
        assertThat(JsonUtils.fromJson(null, Sample.class)).isNull();
        assertThat(JsonUtils.fromJson("  ", Sample.class)).isNull();
        assertThat(JsonUtils.readTree(null)).isNull();
    }

    @Test
    void wrapsMalformedJsonInAnUncheckedException() {
        assertThatThrownBy(() -> JsonUtils.fromJson("{not valid", Sample.class))
                .isInstanceOf(JsonSerializationException.class);
    }
}
