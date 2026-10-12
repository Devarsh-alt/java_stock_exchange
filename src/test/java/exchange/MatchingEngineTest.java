package exchange;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit and concurrency tests for the MatchingEngine and ExchangeService.
 * Deterministic test cases covering price-time FIFO priority, partial fills,
 * state transitions, invalid orders, cancellations, and multi-threaded execution.
 */
class MatchingEngineTest {

    static final String SYM1 = "RELIANCE.NS";
    static final String SYM2 = "TCS.NS";
    static final long   USER_A = 1L;
    static final long   USER_B = 2L;
    static final long   USER_C = 3L;

    MatchingEngine engine;
    ExchangeService service;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        service = new ExchangeService(engine);
        service.getAccountService().getOrCreateAccount(USER_A, bd("1000000"));
        Account accB = service.getAccountService().getOrCreateAccount(USER_B, bd("1000000"));
        accB.depositShares(SYM1, 10000);
        accB.depositShares(SYM2, 10000);
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    List<Trade> buy(String cid, BigDecimal p, long q) {
        return engine.submitOrder(cid, USER_A, SYM1, OrderSide.BUY, OrderType.LIMIT, p, q);
    }

    List<Trade> sell(String cid, BigDecimal p, long q) {
        return engine.submitOrder(cid, USER_B, SYM1, OrderSide.SELL, OrderType.LIMIT, p, q);
    }

    List<Trade> buyM(String cid, long q) {
        return engine.submitOrder(cid, USER_A, SYM1, OrderSide.BUY, OrderType.MARKET, null, q);
    }

    List<Trade> sellM(String cid, long q) {
        return engine.submitOrder(cid, USER_B, SYM1, OrderSide.SELL, OrderType.MARKET, null, q);
    }

    static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    // ── Basic Matching Tests ──────────────────────────────────────────────────

    @Test
    @DisplayName("No trade when bid < ask")
    void noTrade() {
        buy("b1", bd("100"), 10);
        assertTrue(sell("s1", bd("101"), 10).isEmpty());
        OrderBookSnapshot snap = engine.snapshot(SYM1);
        assertEquals(1, snap.getBids().size());
        assertEquals(1, snap.getAsks().size());
    }

    @Test
    @DisplayName("Full fill: equal qty, prices cross")
    void fullFill() {
        buy("b1", bd("100"), 10);
        List<Trade> t = sell("s1", bd("100"), 10);
        assertEquals(1, t.size());
        assertEquals(10L, t.get(0).getQuantity());
        assertEquals(bd("100"), t.get(0).getPrice());
        assertTrue(engine.snapshot(SYM1).getBids().isEmpty());
        assertTrue(engine.snapshot(SYM1).getAsks().isEmpty());
    }

    @Test
    @DisplayName("Partial fill: incoming larger than resting")
    void partialFillIncoming() {
        buy("b1", bd("100"), 5);
        List<Trade> t = sell("s1", bd("100"), 10);
        assertEquals(1, t.size());
        assertEquals(5L, t.get(0).getQuantity());
        assertEquals(1, engine.snapshot(SYM1).getAsks().size());
        assertEquals(5L, engine.snapshot(SYM1).getAsks().get(0).getRemainingQty());
    }

    @Test
    @DisplayName("Partial fill: resting larger than incoming")
    void partialFillResting() {
        sell("s1", bd("100"), 5);
        List<Trade> t = buy("b1", bd("100"), 10);
        assertEquals(1, t.size());
        assertEquals(5L, t.get(0).getQuantity());
        assertEquals(1, engine.snapshot(SYM1).getBids().size());
        assertEquals(5L, engine.snapshot(SYM1).getBids().get(0).getRemainingQty());
    }

    @Test
    @DisplayName("Price-time priority: first order at same price fills first")
    void priceTimePriority() {
        sell("s1", bd("100"), 5); // arrives first
        sell("s2", bd("100"), 5); // arrives second
        buy("b1", bd("100"), 5);
        List<Order> asks = engine.snapshot(SYM1).getAsks();
        assertEquals(1, asks.size(), "s2 should remain");
        assertEquals("s2", asks.get(0).getClientOrderId());
        assertEquals(5L, asks.get(0).getRemainingQty());
    }

    @Test
    @DisplayName("Best price level fills first")
    void bestPriceFirst() {
        sell("s1", bd("99"),  5); // best ask
        sell("s2", bd("101"), 5);
        List<Trade> t = buy("b1", bd("102"), 5);
        assertEquals(1, t.size());
        assertEquals(bd("99"), t.get(0).getPrice(), "Should match at best ask");
    }

    @Test
    @DisplayName("Sweep multiple ask levels in price order")
    void sweepMultipleLevels() {
        sell("s1", bd("100"), 5);
        sell("s2", bd("101"), 5);
        sell("s3", bd("102"), 5);
        List<Trade> t = buy("b1", bd("102"), 15);
        assertEquals(3, t.size());
        assertEquals(bd("100"), t.get(0).getPrice());
        assertEquals(bd("101"), t.get(1).getPrice());
        assertEquals(bd("102"), t.get(2).getPrice());
        assertTrue(engine.snapshot(SYM1).getAsks().isEmpty());
    }

    @Test
    @DisplayName("Market buy fills available liquidity, remainder cancelled")
    void marketBuy() {
        sell("s1", bd("100"), 3);
        List<Trade> t = buyM("bm1", 10);
        assertEquals(1, t.size());
        assertEquals(3L, t.get(0).getQuantity());
        assertTrue(engine.snapshot(SYM1).getBids().isEmpty());
        assertTrue(engine.snapshot(SYM1).getAsks().isEmpty());
    }

    @Test
    @DisplayName("Market sell fills available liquidity, remainder cancelled")
    void marketSell() {
        buy("b1", bd("100"), 4);
        List<Trade> t = sellM("sm1", 10);
        assertEquals(1, t.size());
        assertEquals(4L, t.get(0).getQuantity());
        assertTrue(engine.snapshot(SYM1).getBids().isEmpty());
    }

    @Test
    @DisplayName("Market order on empty book results in no trades and immediate cancellation")
    void marketEmptyBook() {
        MatchingEngine.OrderResult res = engine.submitOrderDetailed("bm1", USER_A, SYM1,
                OrderSide.BUY, OrderType.MARKET, null, 10);
        assertTrue(res.trades().isEmpty());
        assertEquals(OrderStatus.CANCELLED, res.order().getStatus());
        assertEquals(10L, res.order().getRemainingQty());
        assertTrue(engine.snapshot(SYM1).getBids().isEmpty());
    }

    @Test
    @DisplayName("Limit order state transitions correctly upon full fill vs partial fill")
    void orderStateTransitions() {
        sell("s1", bd("100"), 10);
        // Partially fills against s1, leaving 5 units resting on the book
        MatchingEngine.OrderResult res1 = engine.submitOrderDetailed("b1", USER_A, SYM1,
                OrderSide.BUY, OrderType.LIMIT, bd("100"), 15);
        assertEquals(1, res1.trades().size());
        assertEquals(5L, res1.order().getRemainingQty());
        assertEquals(10L, res1.order().getFilledQty());
        assertEquals(OrderStatus.PARTIALLY_FILLED, res1.order().getStatus());
        assertTrue(res1.order().isActive());

        // Completely fill remainder
        MatchingEngine.OrderResult res2 = engine.submitOrderDetailed("s2", USER_B, SYM1,
                OrderSide.SELL, OrderType.LIMIT, bd("100"), 5);
        assertEquals(1, res2.trades().size());
        assertEquals(OrderStatus.FILLED, res1.order().getStatus());
        assertEquals(OrderStatus.FILLED, res2.order().getStatus());
        assertTrue(res1.order().isCompleted());
        assertTrue(res2.order().isCompleted());
    }

    @Test
    @DisplayName("Cancel resting order removes it from book and marks it CANCELLED")
    void cancelOrder() {
        buy("b1", bd("100"), 10);
        Order resting = engine.snapshot(SYM1).getBids().get(0);
        assertEquals(OrderStatus.OPEN, resting.getStatus());
        assertTrue(engine.cancelOrder(SYM1, resting.getId()));
        assertEquals(OrderStatus.CANCELLED, resting.getStatus());
        assertTrue(engine.snapshot(SYM1).getBids().isEmpty());
    }

    @Test
    @DisplayName("Repeated cancellation returns false and is safe")
    void repeatedCancellation() {
        buy("b1", bd("100"), 10);
        String oid = engine.snapshot(SYM1).getBids().get(0).getId();
        assertTrue(engine.cancelOrder(SYM1, oid));
        assertFalse(engine.cancelOrder(SYM1, oid), "Second cancellation must return false");
        assertFalse(engine.cancelOrder(SYM1, "non-existent-id"));
    }

    @Test
    @DisplayName("Same-side orders never match or trade")
    void noSameSideTrade() {
        buy("b1", bd("100"), 10);
        assertTrue(buy("b2", bd("101"), 10).isEmpty());
        OrderBookSnapshot snap = engine.snapshot(SYM1);
        assertEquals(2, snap.getBids().size());
        assertEquals(bd("101"), snap.bestBid());
    }

    // ── Validation and Service Layer Tests ────────────────────────────────────

    @Test
    @DisplayName("ExchangeService rejects invalid orders with ExchangeException")
    void validationErrors() {
        assertThrows(ExchangeException.class, () ->
                service.submitOrder("", USER_A, SYM1, OrderSide.BUY, OrderType.LIMIT, bd("100"), 10));
        assertThrows(ExchangeException.class, () ->
                service.submitOrder("c1", USER_A, "", OrderSide.BUY, OrderType.LIMIT, bd("100"), 10));
        assertThrows(ExchangeException.class, () ->
                service.submitOrder("c1", USER_A, SYM1, null, OrderType.LIMIT, bd("100"), 10));
        assertThrows(ExchangeException.class, () ->
                service.submitOrder("c1", USER_A, SYM1, OrderSide.BUY, null, bd("100"), 10));
        assertThrows(ExchangeException.class, () ->
                service.submitOrder("c1", USER_A, SYM1, OrderSide.BUY, OrderType.LIMIT, bd("100"), 0));
        assertThrows(ExchangeException.class, () ->
                service.submitOrder("c1", USER_A, SYM1, OrderSide.BUY, OrderType.LIMIT, null, 10));
        assertThrows(ExchangeException.class, () ->
                service.submitOrder("c1", USER_A, SYM1, OrderSide.BUY, OrderType.LIMIT, bd("-5"), 10));
    }

    @Test
    @DisplayName("Trade listeners receive trade events and listener exceptions do not fail orders")
    void listenerNotificationAndResilience() {
        AtomicInteger count = new AtomicInteger(0);
        service.addTradeListener(trades -> {
            throw new RuntimeException("Simulated faulty listener");
        });
        service.addTradeListener(trades -> count.addAndGet(trades.size()));

        service.submitOrder("b1", USER_A, SYM1, OrderSide.BUY, OrderType.LIMIT, bd("100"), 10);
        List<Trade> trades = service.submitOrder("s1", USER_B, SYM1,
                OrderSide.SELL, OrderType.LIMIT, bd("100"), 10);

        assertEquals(1, trades.size());
        assertEquals(1, count.get(), "Healthy listener should still be notified");
    }

    // ── Concurrency & Determinism Tests ───────────────────────────────────────

    @Test
    @DisplayName("Concurrent submissions on distinct symbols make progress independently without deadlock")
    void multiSymbolConcurrentSubmissions() throws InterruptedException {
        int threadCount = 8;
        int ordersPerThread = 200;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int tId = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    String sym = (tId % 2 == 0) ? SYM1 : SYM2;
                    for (int j = 0; j < ordersPerThread; j++) {
                        String cid = "t" + tId + "-ord-" + j;
                        engine.submitOrder(cid, tId, sym, OrderSide.BUY, OrderType.LIMIT, bd("100"), 1);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Concurrent submissions should complete promptly");
        pool.shutdown();

        int halfThreads = threadCount / 2;
        assertEquals(halfThreads * ordersPerThread, engine.snapshot(SYM1).getBids().size());
        assertEquals(halfThreads * ordersPerThread, engine.snapshot(SYM2).getBids().size());
    }

    @Test
    @DisplayName("Concurrent matching on same symbol maintains strict volume invariant and no overselling")
    void sameSymbolContentionVolumeInvariant() throws InterruptedException {
        int sellers = 5;
        int buyers = 5;
        int qtyPerOrder = 10;
        int ordersPerThread = 100;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(sellers + buyers);
        ExecutorService pool = Executors.newFixedThreadPool(sellers + buyers);

        List<Trade> allTrades = Collections.synchronizedList(new ArrayList<>());

        // Submit sellers at price 100
        for (int i = 0; i < sellers; i++) {
            final int sId = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < ordersPerThread; j++) {
                        List<Trade> trades = engine.submitOrder("s-" + sId + "-" + j, USER_B, SYM1,
                                OrderSide.SELL, OrderType.LIMIT, bd("100"), qtyPerOrder);
                        allTrades.addAll(trades);
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Submit buyers at price 100
        for (int i = 0; i < buyers; i++) {
            final int bId = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < ordersPerThread; j++) {
                        List<Trade> trades = engine.submitOrder("b-" + bId + "-" + j, USER_A, SYM1,
                                OrderSide.BUY, OrderType.LIMIT, bd("100"), qtyPerOrder);
                        allTrades.addAll(trades);
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS));
        pool.shutdown();

        long totalTradedQty = allTrades.stream().mapToLong(Trade::getQuantity).sum();
        long restingBidQty = engine.snapshot(SYM1).getBids().stream().mapToLong(Order::getRemainingQty).sum();
        long restingAskQty = engine.snapshot(SYM1).getAsks().stream().mapToLong(Order::getRemainingQty).sum();

        long totalBuyQty = (long) buyers * ordersPerThread * qtyPerOrder;
        long totalSellQty = (long) sellers * ordersPerThread * qtyPerOrder;

        // Invariants: Total traded + resting buy = total buy submitted
        assertEquals(totalBuyQty, totalTradedQty + restingBidQty, "Buy volume conservation invariant");
        // Total traded + resting sell = total sell submitted
        assertEquals(totalSellQty, totalTradedQty + restingAskQty, "Sell volume conservation invariant");
    }

    @Test
    @DisplayName("Race condition between order cancellation and execution completes without errors")
    void cancellationVsMatchingRace() throws InterruptedException {
        int pairs = 200;
        ExecutorService pool = Executors.newFixedThreadPool(4);

        for (int i = 0; i < pairs; i++) {
            final String orderId = "b-race-" + i;
            MatchingEngine.OrderResult res = engine.submitOrderDetailed(orderId, USER_A, SYM1,
                    OrderSide.BUY, OrderType.LIMIT, bd("100"), 10);

            CountDownLatch latch = new CountDownLatch(1);
            Future<Boolean> cancelFuture = pool.submit(() -> {
                latch.await();
                return engine.cancelOrder(SYM1, res.order().getId());
            });

            Future<List<Trade>> matchFuture = pool.submit(() -> {
                latch.await();
                return engine.submitOrder("s-race-" + orderId, USER_B, SYM1,
                        OrderSide.SELL, OrderType.LIMIT, bd("100"), 10);
            });

            latch.countDown();

            try {
                boolean cancelled = cancelFuture.get();
                List<Trade> trades = matchFuture.get();

                if (cancelled) {
                    // If cancelled before execution, the seller order didn't match against it
                    assertTrue(trades.isEmpty(), "If cancelled before match, no fill should occur for seller against it");
                    assertEquals(OrderStatus.CANCELLED, res.order().getStatus());
                } else {
                    // If cancellation failed, the buyer was filled by the incoming seller
                    assertFalse(trades.isEmpty(), "If cancel returned false, order was filled");
                    assertEquals(OrderStatus.FILLED, res.order().getStatus());
                }
                // Clean up any remaining orders from the book for the next iteration
                OrderBookSnapshot snap = engine.snapshot(SYM1);
                for (Order o : snap.getAsks()) {
                    engine.cancelOrder(SYM1, o.getId());
                }
                for (Order o : snap.getBids()) {
                    engine.cancelOrder(SYM1, o.getId());
                }
            } catch (ExecutionException e) {
                fail("Unexpected execution exception: " + e.getMessage());
            }
        }

        pool.shutdown();
    }
}
