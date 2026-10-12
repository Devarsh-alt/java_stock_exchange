package exchange;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC repository for Accounts and Holdings.
 *
 * <p>All methods accept an active {@link Connection} to ensure operations execute
 * within the caller's transaction boundary. Uses pure JDBC and prepared statements.
 */
public class JdbcAccountRepository {

    public record AccountRecord(
            long userId,
            BigDecimal totalCash,
            BigDecimal reservedCash,
            BigDecimal availableCash
    ) {}

    public record HoldingRecord(
            long userId,
            String symbol,
            long ownedShares,
            long reservedShares,
            long availableShares
    ) {}

    /**
     * Inserts a new account.
     *
     * @param conn active transaction connection
     * @param userId user identifier
     * @param initialCash initial cash balance
     * @throws SQLException if insert fails or user already exists
     */
    public void createAccount(Connection conn, long userId, BigDecimal initialCash) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(initialCash, "Initial cash cannot be null");
        String sql = "INSERT INTO accounts (user_id, total_cash, reserved_cash) VALUES (?, ?, 0.0000)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.setBigDecimal(2, initialCash);
            stmt.executeUpdate();
        }
    }

    /**
     * Finds an account by user ID.
     */
    public Optional<AccountRecord> findAccountById(Connection conn, long userId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT user_id, total_cash, reserved_cash FROM accounts WHERE user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    BigDecimal total = rs.getBigDecimal("total_cash");
                    BigDecimal reserved = rs.getBigDecimal("reserved_cash");
                    return Optional.of(new AccountRecord(
                            rs.getLong("user_id"),
                            total,
                            reserved,
                            total.subtract(reserved)
                    ));
                }
                return Optional.empty();
            }
        }
    }

    /**
     * Atomically reserves cash for a BUY order if sufficient available cash exists.
     *
     * @param conn active transaction connection
     * @param userId user identifier
     * @param amount cash amount to reserve
     * @return true if reservation succeeded, false if available cash is insufficient
     */
    public boolean reserveCash(Connection conn, long userId, BigDecimal amount) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(amount, "Amount cannot be null");
        if (amount.compareTo(BigDecimal.ZERO) <= 0) return true;

        String sql = "UPDATE accounts SET reserved_cash = reserved_cash + ? " +
                     "WHERE user_id = ? AND (total_cash - reserved_cash) >= ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBigDecimal(1, amount);
            stmt.setLong(2, userId);
            stmt.setBigDecimal(3, amount);
            return stmt.executeUpdate() > 0;
        }
    }

    /**
     * Releases previously reserved cash (e.g. on order cancellation).
     */
    public boolean releaseCashReservation(Connection conn, long userId, BigDecimal amount) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(amount, "Amount cannot be null");
        if (amount.compareTo(BigDecimal.ZERO) <= 0) return true;

        String sql = "UPDATE accounts SET reserved_cash = reserved_cash - ? " +
                     "WHERE user_id = ? AND reserved_cash >= ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBigDecimal(1, amount);
            stmt.setLong(2, userId);
            stmt.setBigDecimal(3, amount);
            return stmt.executeUpdate() > 0;
        }
    }

    /**
     * Settles a BUY fill: debits actual notional from total_cash and releases the reserved cash portion.
     */
    public void settleBuyFill(Connection conn, long userId, String symbol,
                              BigDecimal reservedPerUnit, BigDecimal execPrice, long fillQty) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(symbol, "Symbol cannot be null");

        BigDecimal reservedPortion = reservedPerUnit.multiply(BigDecimal.valueOf(fillQty));
        BigDecimal actualNotional = execPrice.multiply(BigDecimal.valueOf(fillQty));

        // 1. Update cash & reservation
        String cashSql = "UPDATE accounts SET total_cash = total_cash - ?, reserved_cash = reserved_cash - ? " +
                         "WHERE user_id = ? AND total_cash >= ? AND reserved_cash >= ?";
        try (PreparedStatement stmt = conn.prepareStatement(cashSql)) {
            stmt.setBigDecimal(1, actualNotional);
            stmt.setBigDecimal(2, reservedPortion);
            stmt.setLong(3, userId);
            stmt.setBigDecimal(4, actualNotional);
            stmt.setBigDecimal(5, reservedPortion);
            if (stmt.executeUpdate() == 0) {
                throw new SQLException("Failed to settle buy cash for user " + userId + ": insufficient cash/reservation");
            }
        }

        // 2. Credit shares in holdings (UPSERT)
        String holdingSql = "INSERT INTO holdings (user_id, symbol, owned_shares, reserved_shares) VALUES (?, ?, ?, 0) " +
                            "ON DUPLICATE KEY UPDATE owned_shares = owned_shares + ?";
        try (PreparedStatement stmt = conn.prepareStatement(holdingSql)) {
            stmt.setLong(1, userId);
            stmt.setString(2, symbol);
            stmt.setLong(3, fillQty);
            stmt.setLong(4, fillQty);
            stmt.executeUpdate();
        }
    }

    /**
     * Settles a SELL fill: releases reserved shares, debits owned shares, credits cash.
     */
    public void settleSellFill(Connection conn, long userId, String symbol,
                               BigDecimal execPrice, long fillQty) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(symbol, "Symbol cannot be null");

        BigDecimal actualNotional = execPrice.multiply(BigDecimal.valueOf(fillQty));

        // 1. Debit shares & release share reservation
        String holdingSql = "UPDATE holdings SET owned_shares = owned_shares - ?, reserved_shares = reserved_shares - ? " +
                            "WHERE user_id = ? AND symbol = ? AND owned_shares >= ? AND reserved_shares >= ?";
        try (PreparedStatement stmt = conn.prepareStatement(holdingSql)) {
            stmt.setLong(1, fillQty);
            stmt.setLong(2, fillQty);
            stmt.setLong(3, userId);
            stmt.setString(4, symbol);
            stmt.setLong(5, fillQty);
            stmt.setLong(6, fillQty);
            if (stmt.executeUpdate() == 0) {
                throw new SQLException("Failed to settle sell shares for user " + userId + " on " + symbol + ": insufficient shares");
            }
        }

        // 2. Credit cash to total_cash
        String cashSql = "UPDATE accounts SET total_cash = total_cash + ? WHERE user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(cashSql)) {
            stmt.setBigDecimal(1, actualNotional);
            stmt.setLong(2, userId);
            stmt.executeUpdate();
        }
    }

    /**
     * Finds holding for a specific symbol.
     */
    public Optional<HoldingRecord> findHolding(Connection conn, long userId, String symbol) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT user_id, symbol, owned_shares, reserved_shares FROM holdings WHERE user_id = ? AND symbol = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.setString(2, symbol);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long owned = rs.getLong("owned_shares");
                    long reserved = rs.getLong("reserved_shares");
                    return Optional.of(new HoldingRecord(
                            rs.getLong("user_id"),
                            rs.getString("symbol"),
                            owned,
                            reserved,
                            owned - reserved
                    ));
                }
                return Optional.empty();
            }
        }
    }

    /**
     * Reserves shares for a SELL order if available shares >= quantity.
     */
    public boolean reserveShares(Connection conn, long userId, String symbol, long quantity) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        if (quantity <= 0) return true;

        String sql = "UPDATE holdings SET reserved_shares = reserved_shares + ? " +
                     "WHERE user_id = ? AND symbol = ? AND (owned_shares - reserved_shares) >= ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, quantity);
            stmt.setLong(2, userId);
            stmt.setString(3, symbol);
            stmt.setLong(4, quantity);
            return stmt.executeUpdate() > 0;
        }
    }

    /**
     * Releases previously reserved shares.
     */
    public boolean releaseShareReservation(Connection conn, long userId, String symbol, long quantity) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        if (quantity <= 0) return true;

        String sql = "UPDATE holdings SET reserved_shares = reserved_shares - ? " +
                     "WHERE user_id = ? AND symbol = ? AND reserved_shares >= ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, quantity);
            stmt.setLong(2, userId);
            stmt.setString(3, symbol);
            stmt.setLong(4, quantity);
            return stmt.executeUpdate() > 0;
        }
    }
}
