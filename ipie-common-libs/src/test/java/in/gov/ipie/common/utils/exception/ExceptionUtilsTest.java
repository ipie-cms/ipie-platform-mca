package in.gov.ipie.common.utils.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ExceptionUtilsTest {

    @Test
    void findsTheInnermostCause() {
        IOException root = new IOException("connection reset");
        RuntimeException wrapped = new RuntimeException("service call failed", root);

        assertThat(ExceptionUtils.getRootCause(wrapped)).isSameAs(root);
    }

    @Test
    void returnsSelfWhenThereIsNoCause() {
        RuntimeException noCause = new RuntimeException("boom");

        assertThat(ExceptionUtils.getRootCause(noCause)).isSameAs(noCause);
    }

    @Test
    void getRootCauseReturnsNullForNullInput() {
        assertThat(ExceptionUtils.getRootCause(null)).isNull();
    }

    @Test
    void stackTraceToStringIncludesTheMessageAndFrames() {
        String trace = ExceptionUtils.stackTraceToString(new RuntimeException("boom"));

        assertThat(trace).contains("RuntimeException: boom").contains("at ");
    }

    @Test
    void findsACauseOfAGivenTypeInTheChain() {
        IOException root = new IOException("connection reset");
        RuntimeException wrapped = new RuntimeException("service call failed", root);

        assertThat(ExceptionUtils.findCause(wrapped, IOException.class)).isSameAs(root);
        assertThat(ExceptionUtils.findCause(wrapped, IllegalStateException.class)).isNull();
    }
}
