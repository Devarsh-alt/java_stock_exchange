package exchange;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

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
