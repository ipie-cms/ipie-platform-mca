package in.gov.ipie.common.persistence.replica;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * Sends read-only transactions to the replica and everything else to the primary.
 *
 * <p>The routing key is {@link TransactionSynchronizationManager#isCurrentTransactionReadOnly()},
 * which is set by {@code @Transactional(readOnly = true)}. That has one consequence worth stating
 * plainly, because it changes what an existing annotation means: before this existed,
 * {@code readOnly = true} was a hint that let Hibernate skip dirty checking. With a replica
 * configured it also decides <em>which database the statement reaches</em>. A method that reads and
 * then writes must not be marked read-only, or the write arrives at a replica and fails.
 *
 * <p><b>Anything outside a transaction goes to the primary.</b> When no transaction is active the
 * flag is false, so an unannotated repository call routes to the writer. That is the safe default -
 * an un-annotated read costs a little primary capacity, whereas guessing "no transaction means
 * read" would send an autocommit write to a read-only replica.
 *
 * <p><b>This class only works behind a {@code LazyConnectionDataSourceProxy}.</b> Spring opens the
 * connection when the transaction begins, which is before the read-only flag has been published to
 * {@code TransactionSynchronizationManager}; the routing decision would then always see false and
 * every read would go to the primary - silently, with no error and no clue. The lazy proxy defers
 * acquisition to the first statement, by which time the flag is set. {@code ReadWriteRoutingDataSourceAutoConfiguration}
 * is what guarantees the wrapping; do not register this type as a {@code DataSource} bean directly.
 */
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {

    /** Routing keys. Deliberately an enum rather than strings, so a typo cannot silently miss. */
    public enum Target {
        PRIMARY, REPLICA
    }

    public ReadWriteRoutingDataSource(DataSource primary, DataSource replica) {
        setTargetDataSources(Map.of(Target.PRIMARY, primary, Target.REPLICA, replica));
        // Anything the map does not answer for falls back to the primary rather than failing.
        setDefaultTargetDataSource(primary);
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ? Target.REPLICA
                : Target.PRIMARY;
    }
}
