package exchange;

/**
 * Lifecycle state of an order.
 *
 *  NEW → OPEN → PARTIALLY_FILLED → FILLED
 *              ↘ CANCELLED
 *  NEW → REJECTED
 */
public enum OrderStatus {
    NEW,             // received, not yet on book
    OPEN,            // resting on book, no fills yet
    PARTIALLY_FILLED,// one or more fills; quantity remains
    FILLED,          // fully executed
    CANCELLED,       // removed by user or market-order policy
    REJECTED         // rejected before reaching the book
}
