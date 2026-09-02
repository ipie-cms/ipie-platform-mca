package in.gov.ipie.common.client.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * Proves the Idempotency-Key control (Development_Environment_Configuration.md, Section 15) is
 * enforced at request-build time for every state-changing method, and left alone for GET.
 */
class ServiceRequestTest {

    @Test
    void get_doesNotRequireAnIdempotencyKey() {
        ServiceRequest request = ServiceRequest.get("user-service", "/api/v1/users/42").build();

        assertThat(request.serviceName()).isEqualTo("user-service");
        assertThat(request.method()).isEqualTo(HttpMethod.GET);
        assertThat(request.path()).isEqualTo("/api/v1/users/42");
        assertThat(request.idempotencyKey()).isNull();
    }

    @Test
    void post_withoutAnIdempotencyKey_refusesToBuild() {
        assertThatThrownBy(() -> ServiceRequest.post("user-service", "/api/v1/users").body("{}").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
    }

    @Test
    void post_withABlankIdempotencyKey_refusesToBuild() {
        assertThatThrownBy(() -> ServiceRequest.post("user-service", "/api/v1/users")
                        .idempotencyKey("   ")
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void put_patch_delete_allRequireAnIdempotencyKeyToo() {
        assertThatThrownBy(() -> ServiceRequest.put("user-service", "/api/v1/users/42").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ServiceRequest.patch("user-service", "/api/v1/users/42").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ServiceRequest.delete("user-service", "/api/v1/users/42").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void post_withAnIdempotencyKey_buildsSuccessfully() {
        ServiceRequest request = ServiceRequest.post("user-service", "/api/v1/users")
                .body("{\"name\":\"a\"}")
                .idempotencyKey("key-123")
                .queryParam("dryRun", "true")
                .header("X-Extra", "value")
                .build();

        assertThat(request.idempotencyKey()).isEqualTo("key-123");
        assertThat(request.queryParams()).containsEntry("dryRun", "true");
        assertThat(request.headers()).containsEntry("X-Extra", "value");
        assertThat(request.body()).isEqualTo("{\"name\":\"a\"}");
    }
}
