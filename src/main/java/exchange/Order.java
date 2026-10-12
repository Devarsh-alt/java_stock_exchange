package exchange;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An order on the exchange.
 *
 * <h2>Concurrency model</h2>
 * Identity fields (id, symbol, side, type, price, originalQty, arrivalSeq) are
 * final — safe to read from any thread.  Mutable state (remainingQty, filledQty,
 * status) is volatile and written only by the per-symbol matching thread, so
 * reads on other threads always see the latest value without synchronization.
 *
 * <h2>Invariants</h2>
 * 0 ≤ remainingQty ≤ originalQty
 * filledQty == originalQty − remainingQty
 * MARKET orders have price == null; LIMIT orders have price > 0.
 */
public class Order {

    // ── Immutable identity ────────────────────────────────────────────────────
    private final String      id;            // exchange UUID
    private final String      clientOrderId; // caller idempotency key
    private final long        userId;
    private final String      symbol;
    private final OrderSide   side;
    private final OrderType   type;
    private final BigDecimal  price;         // null for MARKET
    private final BigDecimal  reservedPrice; // price per unit reserved from buyer cash (null for SELL)
    private final long        originalQty;
    private final Instant     createdAt;
    private final long        arrivalSeq;    // global monotonic counter for FIFO tiebreak

    // ── Mutable state (volatile; written only by matching thread) ─────────────
    private volatile long        remainingQty;
    private volatile long        filledQty;
    private volatile OrderStatus status;

    public Order(String clientOrderId, long userId, String symbol,
                 OrderSide side, OrderType type, BigDecimal price,
                 long originalQty, long arrivalSeq) {
        this(UUID.randomUUID().toString(), clientOrderId, userId, symbol, side, type, price, price, originalQty, arrivalSeq);
    }

    public Order(String clientOrderId, long userId, String symbol,
                 OrderSide side, OrderType type, BigDecimal price,
                 BigDecimal reservedPrice,
                 long originalQty, long arrivalSeq) {
        this(UUID.randomUUID().toString(), clientOrderId, userId, symbol, side, type, price, reservedPrice, originalQty, arrivalSeq);
    }

    public Order(String id, String clientOrderId, long userId, String symbol,
                 OrderSide side, OrderType type, BigDecimal price,
                 BigDecimal reservedPrice,
                 long originalQty, long arrivalSeq) {
        if (originalQty <= 0) throw new IllegalArgumentException("originalQty must be positive");
        if (type == OrderType.LIMIT) {
            Objects.requireNonNull(price, "LIMIT order requires a price");
            if (price.compareTo(BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException("LIMIT price must be positive");
        }
        this.id            = Objects.requireNonNull(id, "id cannot be null");
        this.clientOrderId = Objects.requireNonNull(clientOrderId);
        this.userId        = userId;
        this.symbol        = Objects.requireNonNull(symbol);
        this.side          = Objects.requireNonNull(side);
        this.type          = Objects.requireNonNull(type);
        this.price         = price;
        this.reservedPrice = reservedPrice;
        this.originalQty   = originalQty;
        this.arrivalSeq    = arrivalSeq;
        this.createdAt     = Instant.now();
        this.remainingQty  = originalQty;
        this.filledQty     = 0L;
        this.status        = OrderStatus.NEW;
    }

    public Order(String id, String clientOrderId, long userId, String symbol,
                 OrderSide side, OrderType type, BigDecimal price,
                 BigDecimal reservedPrice,
                 long originalQty, long remainingQty, long filledQty,
                 long arrivalSeq, OrderStatus status, Instant createdAt) {
        if (originalQty <= 0) throw new IllegalArgumentException("originalQty must be positive");
        if (type == OrderType.LIMIT) {
            Objects.requireNonNull(price, "LIMIT order requires a price");
            if (price.compareTo(BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException("LIMIT price must be positive");
        }
        this.id            = Objects.requireNonNull(id, "id cannot be null");
        this.clientOrderId = Objects.requireNonNull(clientOrderId);
        this.userId        = userId;
        this.symbol        = Objects.requireNonNull(symbol);
        this.side          = Objects.requireNonNull(side);
        this.type          = Objects.requireNonNull(type);
        this.price         = price;
        this.reservedPrice = reservedPrice;
        this.originalQty   = originalQty;
        this.remainingQty  = remainingQty;
        this.filledQty     = filledQty;
        this.arrivalSeq    = arrivalSeq;
        this.status        = Objects.requireNonNull(status);
        this.createdAt     = Objects.requireNonNull(createdAt);
    }

    // ── State transitions (called only by matching thread) ────────────────────

    /** Transition NEW → OPEN (placed on book). */
    public void open() {
        if (status != OrderStatus.NEW && status != OrderStatus.PARTIALLY_FILLED)
            throw new IllegalStateException("Cannot open from " + status);
        status = OrderStatus.OPEN;
    }

    /** Record a fill of qty shares. Transitions to PARTIALLY_FILLED or FILLED. */
    public void fill(long qty) {
        if (qty <= 0)           throw new IllegalArgumentException("fill qty must be positive");
        if (qty > remainingQty) throw new IllegalArgumentException(
                "fill " + qty + " exceeds remaining " + remainingQty);
        remainingQty -= qty;
        filledQty    += qty;
        status = (remainingQty == 0) ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    /** Cancel from NEW, OPEN, or PARTIALLY_FILLED. */
    public void cancel() {
        if (status != OrderStatus.NEW && status != OrderStatus.OPEN && status != OrderStatus.PARTIALLY_FILLED)
            throw new IllegalStateException("Cannot cancel from " + status);
        status = OrderStatus.CANCELLED;
    }

    /** Reject before reaching the book. */
    public void reject() {
        if (status != OrderStatus.NEW)
            throw new IllegalStateException("Cannot reject from " + status);
        status = OrderStatus.REJECTED;
    }

    /**
     * Applies an externally committed state (e.g. from a confirmed database transaction).
     */
    void applyCommittedState(long newRemainingQty, long newFilledQty, OrderStatus newStatus) {
        if (newRemainingQty < 0 || newRemainingQty > originalQty)
            throw new IllegalArgumentException("Invalid remainingQty: " + newRemainingQty);
        if (newFilledQty < 0 || newFilledQty > originalQty)
            throw new IllegalArgumentException("Invalid filledQty: " + newFilledQty);
        if (newRemainingQty + newFilledQty != originalQty)
            throw new IllegalArgumentException("Quantity conservation violated: " + newRemainingQty + " + " + newFilledQty + " != " + originalQty);
        this.remainingQty = newRemainingQty;
        this.filledQty = newFilledQty;
        this.status = Objects.requireNonNull(newStatus);
    }

    // ── Query helpers ─────────────────────────────────────────────────────────
    public boolean isActive()    { return status == OrderStatus.OPEN || status == OrderStatus.PARTIALLY_FILLED; }
    public boolean isCompleted() { return status == OrderStatus.FILLED || status == OrderStatus.CANCELLED || status == OrderStatus.REJECTED; }

    // ── Accessors ─────────────────────────────────────────────────────────────
    public String      getId()            { return id; }
    public String      getClientOrderId() { return clientOrderId; }
    public long        getUserId()        { return userId; }
    public String      getSymbol()        { return symbol; }
    public OrderSide   getSide()          { return side; }
    public OrderType   getType()          { return type; }
    public BigDecimal  getPrice()         { return price; }
    public BigDecimal  getReservedPrice() { return reservedPrice; }
    public long        getOriginalQty()   { return originalQty; }
    public long        getArrivalSeq()    { return arrivalSeq; }
    public Instant     getCreatedAt()     { return createdAt; }
    public long        getRemainingQty()  { return remainingQty; }
    public long        getFilledQty()     { return filledQty; }
    public OrderStatus getStatus()        { return status; }

    @Override
    public String toString() {
        return "Order{id=" + id + ", sym=" + symbol + ", " + side + " " + type
                + " price=" + price + " orig=" + originalQty
                + " remain=" + remainingQty + " " + status + "}";
    }
}
