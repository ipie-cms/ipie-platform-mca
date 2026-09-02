package in.gov.ipie.common.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import in.gov.ipie.common.persistence.IdCollisionException;
import in.gov.ipie.common.persistence.IntegrityViolations;
import in.gov.ipie.common.i18n.MessageResolver;

class GlobalExceptionHandlerTest {

    // Empty StaticMessageSource: every resolve() call falls through to the caller-supplied
    // default message, so this test's assertions read exactly like the pre-i18n English text -
    // MessageResolverTest is where the actual translation-lookup behaviour is proven.
    // No IntegrityViolations registered: a service without a database declares none, and the
    // handler must behave exactly as it did before for every other exception type.
    private final ObjectProvider<IntegrityViolations> noViolations =
            new DefaultListableBeanFactory().getBeanProvider(IntegrityViolations.class);

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(new MessageResolver(new StaticMessageSource()), noViolations);

    @Test
    void handleTypeMismatch_returnsApiErrorShapeInstead_ofSpringsDefaultProblemDetail() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users");
        TypeMismatchException ex = new TypeMismatchException("BOGUS", String.class);
        ex.initPropertyName("sortBy");

        ResponseEntity<Object> response = handler.handleTypeMismatch(
                ex, new HttpHeaders(), HttpStatus.BAD_REQUEST, new ServletWebRequest(request));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiError body = (ApiError) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(400);
        assertThat(body.errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.fieldErrors()).hasSize(1);
        assertThat(body.fieldErrors().getFirst().field()).isEqualTo("sortBy");
        assertThat(body.fieldErrors().getFirst().message()).contains("BOGUS", "sortBy");
    }

    @Test
    void handleTypeMismatch_fallsBackToAGenericFieldName_whenThePropertyNameIsUnknown() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users");
        TypeMismatchException ex = new TypeMismatchException("BOGUS", String.class);

        ResponseEntity<Object> response = handler.handleTypeMismatch(
                ex, new HttpHeaders(), HttpStatus.BAD_REQUEST, new ServletWebRequest(request));

        ApiError body = (ApiError) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.fieldErrors().getFirst().field()).isEqualTo("request");
    }

    @Test
    void handleUnexpected_returnsAStableNonLeakingBody_evenWhenTheExceptionIsAWrapperAroundARootCause() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/users");
        RuntimeException rootCause = new IllegalStateException("connection reset");
        RuntimeException wrapper = new RuntimeException("listener invocation failed", rootCause);

        ResponseEntity<ApiError> response = handler.handleUnexpected(wrapper, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.message()).doesNotContain("connection reset", "IllegalStateException");
    }

    @Test
    void handleUnexpected_doesNotFail_whenTheExceptionHasNoCause() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/users");

        ResponseEntity<ApiError> response = handler.handleUnexpected(new RuntimeException("boom"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // --- Database integrity violations -------------------------------------------------------
    // These reach the boundary rather than the repository whenever Hibernate defers the insert to
    // flush: the transaction commits after the repository method returned, so the catch beside the
    // save never runs. See IntegrityViolationsTest for the translation rules themselves.

    private static GlobalExceptionHandler handlerWith(IntegrityViolations... declarations) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        for (int i = 0; i < declarations.length; i++) {
            beanFactory.registerSingleton("violations" + i, declarations[i]);
        }
        return new GlobalExceptionHandler(new MessageResolver(new StaticMessageSource()),
                beanFactory.getBeanProvider(IntegrityViolations.class));
    }

    /** What Spring hands the boundary: its own exception wrapping Hibernate's, wrapping the driver's. */
    private static DataIntegrityViolationException violationOf(String constraintName) {
        org.hibernate.exception.ConstraintViolationException hibernate =
                new org.hibernate.exception.ConstraintViolationException(
                        "duplicate key value violates unique constraint",
                        new SQLException("23505"), constraintName);
        return new DataIntegrityViolationException("could not execute statement", hibernate);
    }

    private static final IntegrityViolations USERS = IntegrityViolations.forTable()
            .primaryKey("users_pkey")
            .conflict("uq_users_phone_number", "A user with this phone number already exists")
            .build();

    @Test
    void aDeclaredConstraintBecomesA409NamingTheFieldTheCallerRepeated() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/users");

        ResponseEntity<ApiError> response =
                handlerWith(USERS).handleDataIntegrityViolation(violationOf("uq_users_phone_number"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.message()).contains("phone number");
    }

    @Test
    void anIdCollisionIsReportedAsThePlatformsProblem_notAsADuplicateField() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/users");

        ResponseEntity<ApiError> response =
                handlerWith(USERS).handleDataIntegrityViolation(violationOf("users_pkey"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.message()).contains("identifier", "users_pkey");
        assertThat(body.message()).doesNotContain("phone number");
    }

    @Test
    void anUndeclaredConstraintStillReachesTheUnexpectedErrorPath() {
        // A foreign key or a check constraint is a bug to see in full, not a 409 telling the caller
        // to change something they never sent.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/users");

        ResponseEntity<ApiError> response = handlerWith(USERS)
                .handleDataIntegrityViolation(violationOf("fk_users_organisation_id"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void oneTablesFallbackDoesNotAnswerForAnotherTablesConstraint() {
        // The boundary holds every table's declaration at once. A declaration with a fallback
        // message answers any constraint it is asked about, so it must only be asked about its own.
        IntegrityViolations organisations = IntegrityViolations.forTable()
                .primaryKey("organisations_pkey")
                .otherwise("The organisation could not be saved")
                .build();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/users");

        ResponseEntity<ApiError> response = handlerWith(organisations, USERS)
                .handleDataIntegrityViolation(violationOf("fk_users_organisation_id"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void aServiceThatDeclaresNothingBehavesExactlyAsBefore() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/users");

        ResponseEntity<ApiError> response =
                handler.handleDataIntegrityViolation(violationOf("uq_users_phone_number"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void aCollisionThrownByARepositoryGetsTheSameStatusAsOneCaughtAtTheBoundary() {
        // Without its own handler this falls to the IpieException catch-all and comes out as 422,
        // which says the caller sent something wrong. Nothing they sent was wrong.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/users");

        ResponseEntity<ApiError> response =
                handler.handleIdCollision(new IdCollisionException("users_pkey"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("users_pkey");
    }
}
