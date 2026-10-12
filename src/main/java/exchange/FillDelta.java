package exchange;

import java.util.Objects;

/**
 * Immutable record representing the proposed fill delta for an order in a staged match.
 *
 * @param orderId the unique ID of the order
 * @param fillQty quantity filled in this execution
 * @param newRemainingQty calculated remaining quantity after fill
 * @param newFilledQty calculated cumulative filled quantity after fill
 * @param newStatus proposed OrderStatus after fill
 * @param evictFromBook true if order is fully completed and should be evicted from resting book
 */
public record FillDelta(
        String orderId,
        long fillQty,
        long newRemainingQty,
        long newFilledQty,
        OrderStatus newStatus,
        boolean evictFromBook
) {
    public FillDelta {
        Objects.requireNonNull(orderId, "orderId cannot be null");
        Objects.requireNonNull(newStatus, "newStatus cannot be null");
        if (fillQty <= 0) throw new IllegalArgumentException("fillQty must be positive");
        if (newRemainingQty < 0) throw new IllegalArgumentException("newRemainingQty cannot be negative");
        if (newFilledQty <= 0) throw new IllegalArgumentException("newFilledQty must be positive");
    }
}
