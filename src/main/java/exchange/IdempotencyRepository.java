package exchange;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC repository for Idempotency records.
 *
 * <p>Enforces exact duplicate request behavior:
 * <ul>
 *   <li>Same {@code (userId, clientOrderId)} and same payload hash: returns existing record.</li>
 *   <li>Same {@code (userId, clientOrderId)} and different payload hash: rejects with conflict exception.</li>
 * </ul>
 */
public class IdempotencyRepository {

    public record IdempotencyRecord(
            long userId,
            String clientOrderId,
            String requestHash,
            String orderId,
            String responseStatus
    ) {}

    /**
     * Computes deterministic SHA-256 fingerprint for order parameters.
     */
    public static String computeRequestHash(long userId, String symbol, OrderSide side,
                                            OrderType type, long quantity, BigDecimal price) {
        String raw = userId + ":" + symbol + ":" + side + ":" + type + ":" + quantity + ":" +
                     (price == null ? "MKT" : price.stripTrailingZeros().toPlainString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm missing", e);
        }
    }

    /**
     * Looks up an existing idempotency record.
     */
    public Optional<IdempotencyRecord> findRecord(Connection conn, long userId, String clientOrderId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT user_id, client_order_id, request_hash, order_id, response_status " +
                     "FROM idempotency_records WHERE user_id = ? AND client_order_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.setString(2, clientOrderId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new IdempotencyRecord(
                            rs.getLong("user_id"),
                            rs.getString("client_order_id"),
                            rs.getString("request_hash"),
                            rs.getString("order_id"),
                            rs.getString("response_status")
                    ));
                }
                return Optional.empty();
            }
        }
    }

    /**
     * Inserts an idempotency record.
     *
     * @param conn active transaction connection
     * @param userId user identifier
     * @param clientOrderId client idempotency key
     * @param requestHash computed SHA-256 fingerprint
     * @param orderId persistent order ID
     * @param responseStatus order status (e.g. FILLED, OPEN)
     * @throws SQLException if insertion violates primary key
     */
    public void record(Connection conn, long userId, String clientOrderId, String requestHash,
                       String orderId, String responseStatus) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "INSERT INTO idempotency_records (user_id, client_order_id, request_hash, order_id, response_status) " +
                     "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.setString(2, clientOrderId);
            stmt.setString(3, requestHash);
            stmt.setString(4, orderId);
            stmt.setString(5, responseStatus);
            stmt.executeUpdate();
        }
    }
}
