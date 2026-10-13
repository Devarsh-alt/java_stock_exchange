package exchange;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * Seeds simulator trader accounts and share holdings into MySQL.
 *
 * <p>In database-backed mode, orders reserve cash/shares against rows in the
 * {@code accounts} and {@code holdings} tables. The simulator previously only
 * provisioned accounts in memory, so every simulated order was rejected.
 * Seeding is idempotent: existing rows are never reset.</p>
 */
public final class SimulatorSeeder {

    private SimulatorSeeder() {}

    public static void seed(ConnectionFactory factory,
                            long firstUserId,
                            int traderCount,
                            List<String> symbols,
                            BigDecimal initialCash,
                            long initialShares) {
        String accountSql = "INSERT IGNORE INTO accounts (user_id, total_cash, reserved_cash) VALUES (?, ?, 0.0000)";
        String holdingSql = "INSERT INTO holdings (user_id, symbol, owned_shares, reserved_shares) VALUES (?, ?, ?, 0) "
                + "ON DUPLICATE KEY UPDATE owned_shares = GREATEST(owned_shares, VALUES(owned_shares))";

        try (Connection conn = factory.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement acc = conn.prepareStatement(accountSql);
                 PreparedStatement hold = conn.prepareStatement(holdingSql)) {

                for (int i = 0; i < traderCount; i++) {
                    long userId = firstUserId + i;

                    acc.setLong(1, userId);
                    acc.setBigDecimal(2, initialCash);
                    acc.addBatch();

                    for (String symbol : symbols) {
                        hold.setLong(1, userId);
                        hold.setString(2, symbol);
                        hold.setLong(3, initialShares);
                        hold.addBatch();
                    }
                }
                acc.executeBatch();   // accounts first: holdings has a foreign key to accounts
                hold.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
            System.out.println("[SimulatorSeeder] Seeded " + traderCount + " simulator accounts in database.");
        } catch (SQLException e) {
            throw new ExchangeException("Failed to seed simulator accounts: " + e.getMessage(), e);
        }
    }
}