package exchange;

import java.math.BigDecimal;
import java.util.*;

/**
 * Per-symbol order book with price-time priority.
 *
 * <h2>Data structures</h2>
 * Bids: TreeMap (price DESC) → LinkedList&lt;Order&gt; (FIFO within level)
 * Asks: TreeMap (price ASC)  → LinkedList&lt;Order&gt; (FIFO within level)
 * Index: HashMap&lt;orderId, Order&gt; for O(1) cancellation lookup.
 *
 * <h2>Thread safety</h2>
 * NOT thread-safe. All mutations must run under the per-symbol lock in
 * {@link MatchingEngine}.
 */
public class OrderBook {

    private final String symbol;

    // Bid side: highest price first
    private final NavigableMap<BigDecimal, LinkedList<Order>> bids =
            new TreeMap<>(Comparator.reverseOrder());

    // Ask side: lowest price first
    private final NavigableMap<BigDecimal, LinkedList<Order>> asks = new TreeMap<>();

    // O(1) cancellation index
    private final Map<String, Order> index = new HashMap<>();

    public OrderBook(String symbol) { this.symbol = symbol; }

    // ── Add a resting LIMIT order ─────────────────────────────────────────────
    public void addLimitOrder(Order order) {
        if (order.getType() != OrderType.LIMIT)
            throw new IllegalArgumentException("Only LIMIT orders rest on the book");
        if (order.isCompleted())
            throw new IllegalStateException("Cannot add completed order");
        NavigableMap<BigDecimal, LinkedList<Order>> side =
                order.getSide() == OrderSide.BUY ? bids : asks;
        side.computeIfAbsent(order.getPrice(), p -> new LinkedList<>()).addLast(order);
        index.put(order.getId(), order);
    }

    // ── Lookup ────────────────────────────────────────────────────────────────
    public Order findById(String orderId) { return index.get(orderId); }

    // ── Cancel (remove from queue + index) ────────────────────────────────────
    public boolean cancel(String orderId) {
        Order order = index.remove(orderId);
        if (order == null) return false;
        NavigableMap<BigDecimal, LinkedList<Order>> side =
                order.getSide() == OrderSide.BUY ? bids : asks;
        LinkedList<Order> level = side.get(order.getPrice());
        if (level != null) {
            level.remove(order);
            if (level.isEmpty()) side.remove(order.getPrice());
        }
        return true;
    }

    // ── Book-top helpers ──────────────────────────────────────────────────────
    public BigDecimal bestBid() { return bids.isEmpty() ? null : bids.firstKey(); }
    public BigDecimal bestAsk() { return asks.isEmpty() ? null : asks.firstKey(); }

    public Order peekBid() { return peekFirst(bids); }
    public Order peekAsk() { return peekFirst(asks); }

    private Order peekFirst(NavigableMap<BigDecimal, LinkedList<Order>> side) {
        if (side.isEmpty()) return null;
        LinkedList<Order> level = side.firstEntry().getValue();
        return level.isEmpty() ? null : level.getFirst();
    }

    /** Remove and return the best bid (also removes from index). */
    public Order pollBid() { return poll(bids); }

    /** Remove and return the best ask (also removes from index). */
    public Order pollAsk() { return poll(asks); }

    private Order poll(NavigableMap<BigDecimal, LinkedList<Order>> side) {
        if (side.isEmpty()) return null;
        Map.Entry<BigDecimal, LinkedList<Order>> entry = side.firstEntry();
        LinkedList<Order> level = entry.getValue();
        Order head = level.pollFirst();
        if (level.isEmpty()) side.remove(entry.getKey());
        if (head != null) index.remove(head.getId());
        return head;
    }

    // ── Snapshots & Read-Only Views ──────────────────────────────────────────
    public List<Order> bidSnapshot() { return snapshot(bids); }
    public List<Order> askSnapshot() { return snapshot(asks); }

    /**
     * Exposes an unmodifiable view of bids mapped to unmodifiable list of orders.
     */
    public NavigableMap<BigDecimal, List<Order>> bidsView() {
        NavigableMap<BigDecimal, List<Order>> view = new TreeMap<>(Comparator.reverseOrder());
        for (Map.Entry<BigDecimal, LinkedList<Order>> entry : bids.entrySet()) {
            view.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableNavigableMap(view);
    }

    /**
     * Exposes an unmodifiable view of asks mapped to unmodifiable list of orders.
     */
    public NavigableMap<BigDecimal, List<Order>> asksView() {
        NavigableMap<BigDecimal, List<Order>> view = new TreeMap<>();
        for (Map.Entry<BigDecimal, LinkedList<Order>> entry : asks.entrySet()) {
            view.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableNavigableMap(view);
    }

    /**
     * Explicitly evicts an order from its price level and index upon confirmed completion.
     */
    public boolean removeOrder(Order order) {
        if (order == null) return false;
        Order indexed = index.remove(order.getId());
        if (indexed == null) return false;
        NavigableMap<BigDecimal, LinkedList<Order>> side =
                order.getSide() == OrderSide.BUY ? bids : asks;
        LinkedList<Order> level = side.get(order.getPrice());
        if (level != null) {
            level.remove(order);
            if (level.isEmpty()) side.remove(order.getPrice());
        }
        return true;
    }

    private List<Order> snapshot(NavigableMap<BigDecimal, LinkedList<Order>> side) {
        List<Order> out = new ArrayList<>();
        for (LinkedList<Order> level : side.values()) out.addAll(level);
        return out;
    }

    public void clear() {
        bids.clear();
        asks.clear();
        index.clear();
    }

    public boolean isEmpty() { return bids.isEmpty() && asks.isEmpty(); }
    public int     size()    { return index.size(); }
    public String  getSymbol() { return symbol; }
}
