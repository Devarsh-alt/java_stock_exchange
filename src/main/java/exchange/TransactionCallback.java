package exchange;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Functional interface for database operations executing within an active JDBC transaction.
 *
 * @param <T> the type of result produced by the callback
 */
@FunctionalInterface
public interface TransactionCallback<T> {
    T doInTransaction(Connection conn) throws SQLException;
}
