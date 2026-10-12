package exchange;

import java.util.List;

/**
 * Immutable, thread-safe snapshot of one symbol's order book.
 * Taken under the per-symbol lock; safe to read from any thread afterwards.
 */
public final class OrderBookSnapshot {

    private final String      symbol;
    private final List<Order> bids; // highest price first
    private final List<Order> asks; // lowest price first

    public OrderBookSnapshot(String symbol, List<Order> bids, List<Order> asks) {
        this.symbol = symbol;
        this.bids   = List.copyOf(bids);
        this.asks   = List.copyOf(asks);
    }

    public String      getSymbol() { return symbol; }
    public List<Order> getBids()   { return bids; }
    public List<Order> getAsks()   { return asks; }

    public java.math.BigDecimal bestBid() { return bids.isEmpty() ? null : bids.get(0).getPrice(); }
    public java.math.BigDecimal bestAsk() { return asks.isEmpty() ? null : asks.get(0).getPrice(); }
}
