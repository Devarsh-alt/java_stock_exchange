package exchange;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JDBC repository for Trade records.
 *
 * <p>Enforces unique executions via {@code (maker_order_id, maker_fill_seq)}.
 */
public class JdbcTradeRepository {

    public record TradeRecord(
            String id,
            String symbol,
            String buyOrderId,
            String sellOrderId,
            String makerOrderId,
            int makerFillSeq,
            long buyUserId,
            long sellUserId,
            BigDecimal price,
            long quantity,
            Instant executedAt
    ) {}

    /**
     * Inserts a trade record into the {@code trades} table.
     *
     * @param conn active transaction connection
     * @param trade domain Trade object
     * @param makerOrderId the resting order ID
     * @param makerFillSeq sequential fill counter for the maker order (1, 2, ...)
     * @throws SQLException if insert fails or duplicate fill sequence occurs
     */
    public void insertTrade(Connection conn, Trade trade, String makerOrderId, int makerFillSeq) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(trade, "Trade cannot be null");
        Objects.requireNonNull(makerOrderId, "makerOrderId cannot be null");

        String sql = "INSERT INTO trades (id, symbol, buy_order_id, sell_order_id, maker_order_id, maker_fill_seq, " +
                     "buy_user_id, sell_user_id, price, quantity, executed_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, trade.getId());
            stmt.setString(2, trade.getSymbol());
            stmt.setString(3, trade.getBuyOrderId());
            stmt.setString(4, trade.getSellOrderId());
            stmt.setString(5, makerOrderId);
            stmt.setInt(6, makerFillSeq);
            stmt.setLong(7, trade.getBuyUserId());
            stmt.setLong(8, trade.getSellUserId());
            stmt.setBigDecimal(9, trade.getPrice());
            stmt.setLong(10, trade.getQuantity());
            stmt.setTimestamp(11, Timestamp.from(trade.getExecutedAt()));
            stmt.executeUpdate();
        }
    }

    /**
     * Determines the next sequential fill counter for a resting maker order (1, 2, ...).
     */
    public int nextMakerFillSeq(Connection conn, String makerOrderId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(makerOrderId, "makerOrderId cannot be null");
        String sql = "SELECT COALESCE(MAX(maker_fill_seq), 0) + 1 FROM trades WHERE maker_order_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, makerOrderId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 1;
            }
        }
    }

    /**
     * Retrieves all trades for an order (buy or sell).
     */
    public List<TradeRecord> findTradesForOrder(Connection conn, String orderId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, symbol, buy_order_id, sell_order_id, maker_order_id, maker_fill_seq, " +
                     "buy_user_id, sell_user_id, price, quantity, executed_at " +
                     "FROM trades WHERE buy_order_id = ? OR sell_order_id = ? ORDER BY executed_at ASC";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, orderId);
            stmt.setString(2, orderId);
            try (ResultSet rs = stmt.executeQuery()) {
                List<TradeRecord> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(new TradeRecord(
                            rs.getString("id"),
                            rs.getString("symbol"),
                            rs.getString("buy_order_id"),
                            rs.getString("sell_order_id"),
                            rs.getString("maker_order_id"),
                            rs.getInt("maker_fill_seq"),
                            rs.getLong("buy_user_id"),
                            rs.getLong("sell_user_id"),
                            rs.getBigDecimal("price"),
                            rs.getLong("quantity"),
                            rs.getTimestamp("executed_at").toInstant()
                    ));
                }
                return list;
            }
        }
    }
}
