package exchange;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC repository for Orders and monotonic Arrival Sequences.
 *
 * <p>All methods execute within the caller's transaction boundary using the passed {@link Connection}.
 */
public class JdbcOrderRepository {

    public record OrderRecord(
            String id,
            String clientOrderId,
            long userId,
            String symbol,
            OrderSide side,
            OrderType type,
            BigDecimal price,
            BigDecimal reservedPrice,
            long originalQty,
            long remainingQty,
            long filledQty,
            long arrivalSeq,
            OrderStatus status,
            Instant createdAt
    ) {}

    /**
     * Allocates the next monotonic arrival sequence number from {@code sequence_generator}.
     * Executes on the active transaction connection.
     *
     * @param conn active transaction connection
     * @return strictly monotonic, unique sequence number starting at 1
     * @throws SQLException if allocation fails
     */
    public long nextArrivalSeq(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String updateSql = "UPDATE sequence_generator SET next_val = LAST_INSERT_ID(next_val + 1) WHERE sequence_name = 'order_arrival_seq'";
        try (PreparedStatement stmt = conn.prepareStatement(updateSql);
             Statement query = conn.createStatement()) {

            int updated = stmt.executeUpdate();
            if (updated == 0) {
                throw new SQLException("Sequence row 'order_arrival_seq' not found in sequence_generator");
            }

            try (ResultSet rs = query.executeQuery("SELECT LAST_INSERT_ID()")) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                throw new SQLException("Failed to retrieve LAST_INSERT_ID()");
            }
        }
    }

    /**
     * Inserts an order into the {@code orders} table.
     */
    public void insertOrder(Connection conn, Order order) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(order, "Order cannot be null");

        String sql = "INSERT INTO orders (id, client_order_id, user_id, symbol, side, type, price, reserved_price, " +
                     "original_qty, remaining_qty, filled_qty, arrival_seq, status, created_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, order.getId());
            stmt.setString(2, order.getClientOrderId());
            stmt.setLong(3, order.getUserId());
            stmt.setString(4, order.getSymbol());
            stmt.setString(5, order.getSide().name());
            stmt.setString(6, order.getType().name());
            stmt.setBigDecimal(7, order.getPrice());
            stmt.setBigDecimal(8, order.getReservedPrice());
            stmt.setLong(9, order.getOriginalQty());
            stmt.setLong(10, order.getRemainingQty());
            stmt.setLong(11, order.getFilledQty());
            stmt.setLong(12, order.getArrivalSeq());
            stmt.setString(13, order.getStatus().name());
            stmt.setTimestamp(14, Timestamp.from(order.getCreatedAt()));
            stmt.executeUpdate();
        }
    }

    /**
     * Updates an order's fill quantities and status.
     */
    public boolean updateOrderFill(Connection conn, String orderId, long newRemainingQty, long newFilledQty, OrderStatus newStatus) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "UPDATE orders SET remaining_qty = ?, filled_qty = ?, status = ? WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, newRemainingQty);
            stmt.setLong(2, newFilledQty);
            stmt.setString(3, newStatus.name());
            stmt.setString(4, orderId);
            return stmt.executeUpdate() > 0;
        }
    }

    /**
     * Cancels an order conditionally if currently OPEN or PARTIALLY_FILLED.
     */
    public boolean cancelOrder(Connection conn, String orderId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "UPDATE orders SET status = 'CANCELLED' WHERE id = ? AND status IN ('OPEN', 'PARTIALLY_FILLED')";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, orderId);
            return stmt.executeUpdate() > 0;
        }
    }

    /**
     * Finds an order by its UUID.
     */
    public Optional<OrderRecord> findById(Connection conn, String orderId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, client_order_id, user_id, symbol, side, type, price, reserved_price, " +
                     "original_qty, remaining_qty, filled_qty, arrival_seq, status, created_at " +
                     "FROM orders WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, orderId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapOrderRecord(rs));
                }
                return Optional.empty();
            }
        }
    }

    /**
     * Loads all active orders (OPEN or PARTIALLY_FILLED) for crash recovery,
     * ordered by symbol, price (bids DESC / asks ASC via symbol grouping), and arrival_seq ASC.
     */
    public List<OrderRecord> findActiveOrders(Connection conn, String symbol) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, client_order_id, user_id, symbol, side, type, price, reserved_price, " +
                     "original_qty, remaining_qty, filled_qty, arrival_seq, status, created_at " +
                     "FROM orders WHERE symbol = ? AND status IN ('OPEN', 'PARTIALLY_FILLED') " +
                     "ORDER BY arrival_seq ASC";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, symbol);
            try (ResultSet rs = stmt.executeQuery()) {
                List<OrderRecord> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapOrderRecord(rs));
                }
                return list;
            }
        }
    }

    private OrderRecord mapOrderRecord(ResultSet rs) throws SQLException {
        return new OrderRecord(
                rs.getString("id"),
                rs.getString("client_order_id"),
                rs.getLong("user_id"),
                rs.getString("symbol"),
                OrderSide.valueOf(rs.getString("side")),
                OrderType.valueOf(rs.getString("type")),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("reserved_price"),
                rs.getLong("original_qty"),
                rs.getLong("remaining_qty"),
                rs.getLong("filled_qty"),
                rs.getLong("arrival_seq"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
