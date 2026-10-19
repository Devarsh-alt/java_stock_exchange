package exchange;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Seeds simulator accounts and holdings and cleans up abandoned simulator
 * orders from previous runs.
 *
 * Existing account cash balances and order/trade history are preserved.
 */
public final class SimulatorSeeder {

    private SimulatorSeeder() {
    }

    /**
     * Cancels outstanding simulator-generated orders and reconciles reservation
     * balances for simulator users.
     *
     * Only client order IDs beginning with "sim-" are cancelled. Other active
     * orders remain active and continue to reserve their required resources.
     *
     * Bulk SQL is used to avoid one database round-trip per order.
     */
    public static int cancelOutstandingSimulatorOrders(
            ConnectionFactory factory,
            long firstUserId,
            int traderCount) {

        if (traderCount <= 0) {
            System.out.println(
                    "[SimulatorSeeder] No simulator accounts configured; cleanup skipped.");
            return 0;
        }

        final long endUserIdExclusive;

        try {
            endUserIdExclusive =
                    Math.addExact(firstUserId, (long) traderCount);
        } catch (ArithmeticException e) {
            throw new ExchangeException(
                    "Invalid simulator user ID range", e);
        }

        final String lockAccountsSql =
                "SELECT user_id FROM accounts " +
                "WHERE user_id >= ? AND user_id < ? " +
                "FOR UPDATE";

        final String cancelOrdersSql =
                "UPDATE orders " +
                "SET status = 'CANCELLED' " +
                "WHERE user_id >= ? AND user_id < ? " +
                "AND client_order_id LIKE 'sim-%' " +
                "AND status IN ('OPEN', 'PARTIALLY_FILLED')";

        final String updateIdempotencySql =
                "UPDATE idempotency_records ir " +
                "JOIN orders o " +
                "ON o.user_id = ir.user_id " +
                "AND o.client_order_id = ir.client_order_id " +
                "SET ir.response_status = 'CANCELLED' " +
                "WHERE o.user_id >= ? AND o.user_id < ? " +
                "AND o.client_order_id LIKE 'sim-%' " +
                "AND o.status = 'CANCELLED'";

        /*
         * Reserved cash must correspond to the limit-price value of remaining
         * active BUY quantities.
         *
         * Reconcile from active orders rather than blindly setting the balance
         * to zero.
         */
        final String reconcileCashSql =
                "UPDATE accounts a " +
                "LEFT JOIN ( " +
                "    SELECT user_id, " +
                "           SUM(reserved_price * remaining_qty) AS expected_cash " +
                "    FROM orders " +
                "    WHERE user_id >= ? AND user_id < ? " +
                "      AND side = 'BUY' " +
                "      AND status IN ('OPEN', 'PARTIALLY_FILLED') " +
                "      AND reserved_price IS NOT NULL " +
                "    GROUP BY user_id " +
                ") active_buys " +
                "ON active_buys.user_id = a.user_id " +
                "SET a.reserved_cash = " +
                "    COALESCE(active_buys.expected_cash, 0.0000) " +
                "WHERE a.user_id >= ? AND a.user_id < ?";

        /*
         * Reserved shares must correspond to the remaining quantities of
         * active SELL orders for each user and symbol.
         */
        final String reconcileSharesSql =
                "UPDATE holdings h " +
                "LEFT JOIN ( " +
                "    SELECT user_id, symbol, " +
                "           SUM(remaining_qty) AS expected_shares " +
                "    FROM orders " +
                "    WHERE user_id >= ? AND user_id < ? " +
                "      AND side = 'SELL' " +
                "      AND status IN ('OPEN', 'PARTIALLY_FILLED') " +
                "    GROUP BY user_id, symbol " +
                ") active_sells " +
                "ON active_sells.user_id = h.user_id " +
                "AND active_sells.symbol = h.symbol " +
                "SET h.reserved_shares = " +
                "    COALESCE(active_sells.expected_shares, 0) " +
                "WHERE h.user_id >= ? AND h.user_id < ?";

        int cancelledCount;
        int accountCount = 0;

        System.out.println(
                "[SimulatorSeeder] Starting startup cleanup for users " +
                firstUserId + " through " + (endUserIdExclusive - 1) + ".");

        System.out.println(
                "[SimulatorSeeder] Opening database connection...");

        try (Connection conn = factory.getConnection()) {

            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            try {
                /*
                 * Lock the existing simulator account rows first.
                 * This serializes cleanup with account updates that use the
                 * same database rows.
                 */
                System.out.println(
                        "[SimulatorSeeder] Locking simulator account rows...");

                try (PreparedStatement ps =
                             conn.prepareStatement(lockAccountsSql)) {

                    ps.setLong(1, firstUserId);
                    ps.setLong(2, endUserIdExclusive);

                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            accountCount++;
                        }
                    }
                }

                System.out.println(
                        "[SimulatorSeeder] Found " + accountCount +
                        " existing accounts. Cancelling old simulator orders...");

                /*
                 * Cancel only simulator-generated orders.
                 * No order or trade history is deleted.
                 */
                try (PreparedStatement ps =
                             conn.prepareStatement(cancelOrdersSql)) {

                    ps.setLong(1, firstUserId);
                    ps.setLong(2, endUserIdExclusive);

                    cancelledCount = ps.executeUpdate();
                }

                System.out.println(
                        "[SimulatorSeeder] Cancelled " + cancelledCount +
                        " orders. Updating idempotency records...");

                try (PreparedStatement ps =
                             conn.prepareStatement(updateIdempotencySql)) {

                    ps.setLong(1, firstUserId);
                    ps.setLong(2, endUserIdExclusive);

                    ps.executeUpdate();
                }

                /*
                 * Recalculate reservations from all active orders belonging
                 * to these users, including any legitimate manual orders.
                 */
                System.out.println(
                        "[SimulatorSeeder] Recalculating reserved cash...");

                try (PreparedStatement ps =
                             conn.prepareStatement(reconcileCashSql)) {

                    ps.setLong(1, firstUserId);
                    ps.setLong(2, endUserIdExclusive);
                    ps.setLong(3, firstUserId);
                    ps.setLong(4, endUserIdExclusive);

                    ps.executeUpdate();
                }

                System.out.println(
                        "[SimulatorSeeder] Recalculating reserved shares...");

                try (PreparedStatement ps =
                             conn.prepareStatement(reconcileSharesSql)) {

                    ps.setLong(1, firstUserId);
                    ps.setLong(2, endUserIdExclusive);
                    ps.setLong(3, firstUserId);
                    ps.setLong(4, endUserIdExclusive);

                    ps.executeUpdate();
                }

                /*
                 * Order cancellations and reservation reconciliation commit
                 * atomically.
                 */
                System.out.println(
                        "[SimulatorSeeder] Committing cleanup transaction...");

                conn.commit();

                System.out.println(
                        "[SimulatorSeeder] Cleanup transaction committed.");

            } catch (SQLException | RuntimeException e) {

                try {
                    if (!conn.isClosed()) {
                        conn.rollback();
                    }
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }

                throw e;

            } finally {
                restoreAutoCommitSafely(
                        conn, originalAutoCommit, "startup cleanup");
            }

        } catch (SQLException e) {
            throw new ExchangeException(
                    "Failed to clean up outstanding simulator orders: " +
                    e.getMessage(), e);
        }

        System.out.println(
                "[SimulatorSeeder] Startup cleanup complete. Cancelled " +
                cancelledCount +
                " simulator orders and reconciled reservations for " +
                accountCount +
                " accounts. Other active orders and total cash were preserved.");

        return cancelledCount;
    }

    /**
     * Creates simulator accounts and holdings only when they do not already
     * exist. Existing total_cash balances are never overwritten.
     */
    public static void seed(
            ConnectionFactory factory,
            long firstUserId,
            int traderCount,
            List<String> symbols,
            BigDecimal initialCash,
            long initialShares) {

        final String accountSql =
                "INSERT IGNORE INTO accounts " +
                "(user_id, total_cash, reserved_cash) " +
                "VALUES (?, ?, 0.0000)";

        final String holdingSql =
                "INSERT INTO holdings " +
                "(user_id, symbol, owned_shares, reserved_shares) " +
                "VALUES (?, ?, ?, 0) " +
                "ON DUPLICATE KEY UPDATE " +
                "owned_shares = GREATEST(owned_shares, VALUES(owned_shares))";

        System.out.println(
                "[SimulatorSeeder] Preparing simulator account seeding...");

        try (Connection conn = factory.getConnection()) {

            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            try (PreparedStatement account =
                         conn.prepareStatement(accountSql);
                 PreparedStatement holding =
                         conn.prepareStatement(holdingSql)) {

                for (int i = 0; i < traderCount; i++) {

                    long userId = firstUserId + i;

                    account.setLong(1, userId);
                    account.setBigDecimal(2, initialCash);
                    account.addBatch();

                    for (String symbol : symbols) {
                        holding.setLong(1, userId);
                        holding.setString(2, symbol);
                        holding.setLong(3, initialShares);
                        holding.addBatch();
                    }
                }

                System.out.println(
                        "[SimulatorSeeder] Writing simulator accounts and holdings...");

                account.executeBatch();
                holding.executeBatch();

                conn.commit();

            } catch (SQLException | RuntimeException e) {

                try {
                    if (!conn.isClosed()) {
                        conn.rollback();
                    }
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }

                throw e;

            } finally {
                restoreAutoCommitSafely(
                        conn, originalAutoCommit, "account seeding");
            }

            System.out.println(
                    "[SimulatorSeeder] Seeded " +
                    traderCount +
                    " simulator accounts in database.");

        } catch (SQLException e) {
            throw new ExchangeException(
                    "Failed to seed simulator accounts: " +
                    e.getMessage(), e);
        }
    }

    /**
     * Restores connection state when possible without hiding the original
     * transaction result if the database or pool has closed the connection.
     */
    private static void restoreAutoCommitSafely(
            Connection conn,
            boolean originalAutoCommit,
            String operation) {

        try {
            if (!conn.isClosed()) {
                conn.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException e) {
            System.err.println(
                    "[SimulatorSeeder] Warning: could not restore auto-commit " +
                    "after " + operation + ": " + e.getMessage());
        }
    }
}