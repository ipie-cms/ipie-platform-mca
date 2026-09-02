package in.gov.ipie.common.persistence.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The routing rule, asserted directly. Lives in the same package so the protected lookup method can
 * be called without a real database behind it.
 */
class ReadWriteRoutingDataSourceTest {

    private final DataSource primary = mock(DataSource.class);
    private final DataSource replica = mock(DataSource.class);
    private final ReadWriteRoutingDataSource routing = new ReadWriteRoutingDataSource(primary, replica);

    @AfterEach
    void clearTransactionState() {
        // The flag is thread-bound and would otherwise leak into whichever test runs next on this
        // thread - which would look like the routing rule being wrong.
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @Test
    @DisplayName("a read-only transaction routes to the replica")
    void readOnlyGoesToReplica() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThat(routing.determineCurrentLookupKey())
                .isEqualTo(ReadWriteRoutingDataSource.Target.REPLICA);
    }

    @Test
    @DisplayName("a writable transaction routes to the primary")
    void writableGoesToPrimary() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        assertThat(routing.determineCurrentLookupKey())
                .isEqualTo(ReadWriteRoutingDataSource.Target.PRIMARY);
    }

    @Test
    @DisplayName("no transaction at all routes to the primary, not the replica")
    void noTransactionGoesToPrimary() {
        // The safe direction. Guessing "no transaction means a read" would send an autocommit
        // write to a read-only replica; an un-annotated read merely costs a little primary capacity.
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(routing.determineCurrentLookupKey())
                .isEqualTo(ReadWriteRoutingDataSource.Target.PRIMARY);
    }
}
