package exchange;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable record of a completed trade between a buyer and a seller.
 * Invariants: price > 0, quantity > 0, buyOrderId != sellOrderId.
 */
public final class Trade {

    private final String     id;
    private final String     symbol;
    private final String     buyOrderId;
    private final String     sellOrderId;
    private final long       buyUserId;
    private final long       sellUserId;
    private final BigDecimal price;
    private final long       quantity;
    private final Instant    executedAt;

    public Trade(String symbol, String buyOrderId, String sellOrderId,
                 long buyUserId, long sellUserId, BigDecimal price, long quantity) {
        if (price.compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Trade price must be positive");
        if (quantity <= 0)
            throw new IllegalArgumentException("Trade quantity must be positive");
        if (Objects.equals(buyOrderId, sellOrderId))
            throw new IllegalArgumentException("Buy and sell order IDs must differ");
        this.id          = UUID.randomUUID().toString();
        this.symbol      = Objects.requireNonNull(symbol);
        this.buyOrderId  = Objects.requireNonNull(buyOrderId);
        this.sellOrderId = Objects.requireNonNull(sellOrderId);
        this.buyUserId   = buyUserId;
        this.sellUserId  = sellUserId;
        this.price       = price;
        this.quantity    = quantity;
        this.executedAt  = Instant.now();
    }

    public String     getId()          { return id; }
    public String     getSymbol()      { return symbol; }
    public String     getBuyOrderId()  { return buyOrderId; }
    public String     getSellOrderId() { return sellOrderId; }
    public long       getBuyUserId()   { return buyUserId; }
    public long       getSellUserId()  { return sellUserId; }
    public BigDecimal getPrice()       { return price; }
    public long       getQuantity()    { return quantity; }
    public Instant    getExecutedAt()  { return executedAt; }
    public BigDecimal notional()       { return price.multiply(BigDecimal.valueOf(quantity)); }

    @Override
    public String toString() {
        return "Trade{sym=" + symbol + " price=" + price + " qty=" + quantity
                + " buyer=" + buyUserId + " seller=" + sellUserId + "}";
    }
}
