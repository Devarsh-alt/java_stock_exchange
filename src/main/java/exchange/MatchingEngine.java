package exchange;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Central matching engine.
 *
 * <h2>Concurrency model</h2>
 * - One {@link ReentrantLock} per symbol. All book mutations happen under
 *   that symbol's lock only — independent symbols run truly concurrently.
 * - A global {@link AtomicLong} assigns arrival sequences before locking,
 *   so sequences are unique across symbols.
 * - Cancellation acquires the same per-symbol lock to prevent races.
 * - Snapshot reads also acquire the lock for internal consistency.
 *
 * <h2>Market-order policy</h2>
 * Matched immediately; any unfilled remainder is CANCELLED (never rests).
 *
 * <h2>Limit-order policy</h2>
 * Fills what it can, then rests on the book as OPEN if any quantity remains.
 *
 * <h2>Execution price</h2>
 * Always the resting (maker) order's price — the aggressor pays the posted price.
 */
public class MatchingEngine {

    private final AtomicLong arrivalSeq = new AtomicLong(0);

    private final ConcurrentHashMap<String, OrderBook>     books = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    public static record OrderResult(Order order, List<Trade> trades) {}

    public OrderResult submitOrderDetailed(Order order) {
        ReentrantLock lock = lockFor(order.getSymbol());
        lock.lock();
        try {
            OrderBook book = bookFor(order.getSymbol());
            StagedMatch staged = stageMatch(order, book);
            applyCommittedMatch(staged, book);
            return new OrderResult(order, staged.trades());
        } finally {
            lock.unlock();
        }
    }

    public OrderResult submitOrderDetailed(String clientOrderId, long userId, String symbol,
                                           OrderSide side, OrderType type,
                                           BigDecimal price, long quantity) {
        long seq = arrivalSeq.incrementAndGet();
        Order order = new Order(clientOrderId, userId, symbol, side, type, price, quantity, seq);
        return submitOrderDetailed(order);
    }

    public long nextArrivalSeq() {
        return arrivalSeq.incrementAndGet();
    }

    public List<Trade> submitOrder(String clientOrderId, long userId, String symbol,
                                   OrderSide side, OrderType type,
                                   BigDecimal price, long quantity) {
        return submitOrderDetailed(clientOrderId, userId, symbol, side, type, price, quantity).trades();
    }

    public Order cancelOrderDetailed(String symbol, String orderId) {
        ReentrantLock lock = lockFor(symbol);
        lock.lock();
        try {
            OrderBook book = bookFor(symbol);
            Order order = book.findById(orderId);
            if (order == null) return null;
            book.cancel(orderId);
            order.cancel();
            return order;
        } finally {
            lock.unlock();
        }
    }

    public boolean cancelOrder(String symbol, String orderId) {
        return cancelOrderDetailed(symbol, orderId) != null;
    }

    public Order getOrder(String symbol, String orderId) {
        ReentrantLock lock = lockFor(symbol);
        lock.lock();
        try {
            return bookFor(symbol).findById(orderId);
        } finally {
            lock.unlock();
        }
    }

    public OrderBookSnapshot snapshot(String symbol) {
        ReentrantLock lock = lockFor(symbol);
        lock.lock();
        try {
            OrderBook book = bookFor(symbol);
            return new OrderBookSnapshot(symbol, book.bidSnapshot(), book.askSnapshot());
        } finally {
            lock.unlock();
        }
    }

    // ── Pure Non-Mutating Staged Matching ─────────────────────────────────────

    /**
     * Calculates an execution plan without mutating the incoming order, resting orders, or OrderBook.
     */
    public StagedMatch stageMatch(Order incoming, OrderBook book) {
        List<Trade> trades = new ArrayList<>();
        List<FillDelta> restingFills = new ArrayList<>();

        long simRemaining = incoming.getRemainingQty();
        long simFilled = incoming.getFilledQty();

        NavigableMap<BigDecimal, List<Order>> oppositeLevels =
                incoming.getSide() == OrderSide.BUY ? book.asksView() : book.bidsView();

        for (Map.Entry<BigDecimal, List<Order>> entry : oppositeLevels.entrySet()) {
            if (simRemaining == 0) break;

            BigDecimal levelPrice = entry.getKey();
            if (incoming.getType() == OrderType.LIMIT) {
                if (incoming.getSide() == OrderSide.BUY && incoming.getPrice().compareTo(levelPrice) < 0) {
                    break; // bid < ask -> cannot match
                }
                if (incoming.getSide() == OrderSide.SELL && incoming.getPrice().compareTo(levelPrice) > 0) {
                    break; // ask > bid -> cannot match
                }
            }

            for (Order resting : entry.getValue()) {
                if (simRemaining == 0) break;

                // Self-trade protection: prevent trading against self by order ID, client order ID, or user ID
                if (incoming.getId().equals(resting.getId())
                        || incoming.getClientOrderId().equals(resting.getClientOrderId())
                        || incoming.getUserId() == resting.getUserId()) {
                    continue;
                }

                long restingRemain = resting.getRemainingQty();
                long fillQty = Math.min(simRemaining, restingRemain);
                BigDecimal execPrice = resting.getPrice();

                Order buyer  = incoming.getSide() == OrderSide.BUY  ? incoming : resting;
                Order seller = incoming.getSide() == OrderSide.SELL ? incoming : resting;

                trades.add(new Trade(incoming.getSymbol(),
                        buyer.getId(), seller.getId(),
                        buyer.getUserId(), seller.getUserId(),
                        execPrice, fillQty));

                simRemaining -= fillQty;
                simFilled += fillQty;

                long newRestingRemain = restingRemain - fillQty;
                long newRestingFilled = resting.getFilledQty() + fillQty;
                OrderStatus newRestingStatus = (newRestingRemain == 0) ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;

                restingFills.add(new FillDelta(
                        resting.getId(),
                        fillQty,
                        newRestingRemain,
                        newRestingFilled,
                        newRestingStatus,
                        newRestingRemain == 0
                ));
            }
        }

        FillDelta incomingFill = null;
        if (simFilled > incoming.getFilledQty()) {
            long totalFilledThisOrder = simFilled - incoming.getFilledQty();
            OrderStatus incomingStatus = (simRemaining == 0) ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
            incomingFill = new FillDelta(
                    incoming.getId(),
                    totalFilledThisOrder,
                    simRemaining,
                    simFilled,
                    incomingStatus,
                    false
            );
        }

        boolean restOnBook = (simRemaining > 0 && incoming.getType() == OrderType.LIMIT);
        boolean cancelRemainder = (simRemaining > 0 && incoming.getType() == OrderType.MARKET);

        return new StagedMatch(incoming, trades, restingFills, incomingFill, restOnBook, cancelRemainder);
    }

    // ── Apply Committed Match to Live In-Memory State ─────────────────────────

    /**
     * Applies the staged execution plan to live orders and queues under the symbol lock.
     */
    public void applyCommittedMatch(StagedMatch staged, OrderBook book) {
        // 1. Apply fills to resting maker orders
        for (FillDelta delta : staged.restingFills()) {
            Order resting = book.findById(delta.orderId());
            if (resting != null) {
                resting.applyCommittedState(delta.newRemainingQty(), delta.newFilledQty(), delta.newStatus());
                if (delta.evictFromBook()) {
                    book.removeOrder(resting);
                }
            }
        }

        // 2. Apply fill to incoming order
        if (staged.incomingFill() != null) {
            staged.incomingOrder().applyCommittedState(
                    staged.incomingFill().newRemainingQty(),
                    staged.incomingFill().newFilledQty(),
                    staged.incomingFill().newStatus()
            );
        }

        // 3. Rest limit remainder or cancel market remainder
        if (staged.restIncomingOnBook()) {
            if (staged.incomingOrder().getStatus() == OrderStatus.NEW) {
                staged.incomingOrder().open();
            }
            book.addLimitOrder(staged.incomingOrder());
        } else if (staged.cancelIncomingRemainder()) {
            staged.incomingOrder().cancel();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Order oppositeBest(Order incoming, OrderBook book) {
        return incoming.getSide() == OrderSide.BUY ? book.peekAsk() : book.peekBid();
    }

    private boolean pricesCross(Order incoming, Order resting) {
        if (incoming.getType() == OrderType.MARKET) return true;
        if (incoming.getSide() == OrderSide.BUY)
            return incoming.getPrice().compareTo(resting.getPrice()) >= 0; // bid ≥ ask
        else
            return incoming.getPrice().compareTo(resting.getPrice()) <= 0; // ask ≤ bid
    }

    OrderBook     bookFor(String s) { return books.computeIfAbsent(s, OrderBook::new); }
    ReentrantLock lockFor(String s) { return locks.computeIfAbsent(s, k -> new ReentrantLock()); }
}
