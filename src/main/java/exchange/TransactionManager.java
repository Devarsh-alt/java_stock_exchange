package exchange;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Transaction manager that coordinates JDBC transactions across repositories.
 *
 * <p>Enforces:
 * <ul>
 *   <li>{@code autoCommit = false} during execution</li>
 *   <li>Explicit {@code commit()} on success</li>
 *   <li>Safe {@code rollback()} on any checked or unchecked exception</li>
 *   <li>Restoration of the connection's original autoCommit state</li>
 * </ul>
 */
public class TransactionManager {

    private final ConnectionFactory connectionFactory;

    public TransactionManager(ConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory cannot be null");
    }

    /**
     * Executes a transactional callback, passing the single active {@link Connection}
     * through all repository calls in the transaction.
     *
     * @param callback the transactional operations to execute
     * @param <T> the return type
     * @return the result returned by the callback
     * @throws SQLException if a database error occurs or transaction is rolled back
     */
    public <T> T executeInTransaction(TransactionCallback<T> callback) throws SQLException {
        // MySQL resolves a deadlock by rolling one transaction back, so nothing was
        // committed and the work can be safely retried from the start.
        for (int attempt = 1; ; attempt++) {
            try {
                return executeOnce(callback);
            } catch (SQLException e) {
                if (!isRetryable(e) || attempt >= MAX_ATTEMPTS) throw e;
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(5L, 25L) * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static final int MAX_ATTEMPTS = 5;

    /** Deadlock (1213 / SQLSTATE 40001) or lock wait timeout (1205): the transaction was rolled back. */
    private static boolean isRetryable(Throwable t) {
        for (int depth = 0; t != null && depth < 5; depth++, t = t.getCause()) {
            if (t instanceof SQLException se
                    && (se.getErrorCode() == 1213 || se.getErrorCode() == 1205 || "40001".equals(se.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    private <T> T executeOnce(TransactionCallback<T> callback) throws SQLException {
        try (Connection conn = connectionFactory.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                T result = callback.doInTransaction(conn);
                conn.commit();
                return result;
            } catch (Throwable t) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    t.addSuppressed(rollbackEx);
                }
                if (t instanceof SQLException sqle) {
                    throw sqle;
                } else if (t instanceof RuntimeException re) {
                    throw re;
                } else if (t instanceof Error err) {
                    throw err;
                } else {
                    throw new SQLException("Transaction aborted due to checked exception: " + t.getMessage(), t);
                }
            } finally {
                try {
                    conn.setAutoCommit(originalAutoCommit);
                } catch (SQLException ignored) {
                }
            }
        }
    }
}