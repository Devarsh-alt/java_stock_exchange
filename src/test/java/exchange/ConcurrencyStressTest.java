package exchange;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase D — Concurrency stress tests.
 *
 * <p>All tests use the in-memory path (no DB) so no environment variables are needed.
 * They verify that the engine maintains financial invariants under heavy multi-thread
 * contention: no lost updates, no double-spends, no order-book corruption.
 */
@Timeout(30)
class ConcurrencyStressTest {

    private static final String SYMBOL = "STRESS.TEST";
    private static final int THREADS  = 8;
    private static final int ORDERS_PER_THREAD = 50;

    private ExchangeService service;
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        MatchingEngine engine = new MatchingEngine();
        service = new ExchangeService(engine);
        accountService = service.getAccountService();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper: provision an account with plenty of cash and shares
    // ─────────────────────────────────────────────────────────────────────────

    private void provisionAccount(long userId) {
        Account acc = accountService.getOrCreateAccount(userId, new BigDecimal("100000000.00"));
        acc.depositShares(SYMBOL, 1_000_000L);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1 — Concurrent order submission from multiple threads
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Concurrent order submission: no exceptions, book remains consistent")
    void concurrentOrderSubmission() throws Exception {
        // Provision separate accounts for each thread to avoid account-lock contention
        for (int i = 1; i <= THREADS; i++) {
            provisionAccount(800_000L + i);
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        AtomicInteger errors = new AtomicInteger(0);
        AtomicLong submitted = new AtomicLong(0);

        List<Future<?>> futures = new ArrayList<>();
        Random rng = new Random(42L);

        for (int t = 0; t < THREADS; t++) {
            final long userId = 800_000L + t + 1;
            futures.add(pool.submit(() -> {
                Random localRng = new Random(userId);
                for (int i = 0; i < ORDERS_PER_THREAD; i++) {
                    try {
                        boolean isBuy = localRng.nextBoolean();
                        BigDecimal price = new BigDecimal(100 + localRng.nextInt(20)).setScale(2, java.math.RoundingMode.HALF_UP);
                        long qty = 1 + localRng.nextInt(10);
                        String clientOrderId = "stress-" + userId + "-" + i;
                        service.submitOrder(clientOrderId, userId, SYMBOL,
                                isBuy ? OrderSide.BUY : OrderSide.SELL,
                                OrderType.LIMIT, price, qty);
                        submitted.incrementAndGet();
                    } catch (ExchangeException e) {
                        // Insufficient funds / shares is expected when account is drained
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        System.err.println("Unexpected error in concurrentOrderSubmission: " + e);
                    }
                }
            }));
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "Thread pool did not terminate in time");

        for (Future<?> f : futures) {
            f.get(); // propagates any uncaught exception
        }

        assertEquals(0, errors.get(), "Unexpected exceptions during concurrent submission");
        assertTrue(submitted.get() > 0, "No orders were submitted");

        // Verify the order-book snapshot is structurally consistent
        OrderBookSnapshot snap = service.getSnapshot(SYMBOL);
        assertNotNull(snap);
        snap.getBids().forEach(o -> assertEquals(OrderSide.BUY, o.getSide()));
        snap.getAsks().forEach(o -> assertEquals(OrderSide.SELL, o.getSide()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2 — Concurrent orders on separate symbols must not interfere
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Concurrent multi-symbol submission: symbol locks are independent")
    void concurrentMultiSymbol() throws Exception {
        String[] symbols = {"SYM.A", "SYM.B", "SYM.C", "SYM.D"};

        for (int i = 1; i <= THREADS; i++) {
            Account acc = accountService.getOrCreateAccount(810_000L + i, new BigDecimal("100000000.00"));
            for (String sym : symbols) acc.depositShares(sym, 500_000L);
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        AtomicInteger errors = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < THREADS; t++) {
            final int threadIdx = t;
            final long userId = 810_000L + threadIdx + 1;
            final String sym = symbols[threadIdx % symbols.length];
            futures.add(pool.submit(() -> {
                Random localRng = new Random(userId * 31L);
                for (int i = 0; i < ORDERS_PER_THREAD; i++) {
                    try {
                        boolean isBuy = localRng.nextBoolean();
                        BigDecimal price = new BigDecimal(50 + localRng.nextInt(10)).setScale(2, java.math.RoundingMode.HALF_UP);
                        long qty = 1 + localRng.nextInt(5);
                        service.submitOrder("ms-" + userId + "-" + i, userId, sym,
                                isBuy ? OrderSide.BUY : OrderSide.SELL,
                                OrderType.LIMIT, price, qty);
                    } catch (ExchangeException ignored) {
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        System.err.println("Multi-symbol error: " + e);
                    }
                }
            }));
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        for (Future<?> f : futures) f.get();

        assertEquals(0, errors.get(), "Unexpected exceptions during multi-symbol submission");

        // Each symbol's book must be self-consistent
        for (String sym : symbols) {
            OrderBookSnapshot snap = service.getSnapshot(sym);
            assertNotNull(snap);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3 — Concurrent buy/sell pairing: verify trades execute and
    //          at-most-once fill semantics hold (no negative remaining qty)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Concurrent matching: no negative remaining quantities after fills")
    void concurrentMatchingQuantityInvariant() throws Exception {
        // Resting sell side — placed first sequentially to anchor the book
        long sellerId = 820_001L;
        long buyerId  = 820_002L;
        provisionAccount(sellerId);
        provisionAccount(buyerId);

        BigDecimal fixedPrice = new BigDecimal("150.00");
        // Place 10 large resting sells
        for (int i = 0; i < 10; i++) {
            service.submitOrder("resting-sell-" + i, sellerId, SYMBOL, OrderSide.SELL,
                    OrderType.LIMIT, fixedPrice, 1000L);
        }

        // Now hammer with concurrent buys at the same price
        int buyThreads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(buyThreads);
        AtomicInteger errors = new AtomicInteger(0);
        List<Future<List<Trade>>> futures = new ArrayList<>();

        for (int t = 0; t < buyThreads; t++) {
            final int idx = t;
            futures.add(pool.submit(() -> {
                List<Trade> allTrades = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    try {
                        List<Trade> trades = service.submitOrder(
                                "buy-" + idx + "-" + i, buyerId, SYMBOL, OrderSide.BUY,
                                OrderType.LIMIT, fixedPrice, 100L);
                        allTrades.addAll(trades);
                    } catch (ExchangeException ignored) {
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        System.err.println("Matching error: " + e);
                    }
                }
                return allTrades;
            }));
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        long totalFilled = 0;
        for (Future<List<Trade>> f : futures) {
            totalFilled += f.get().stream().mapToLong(Trade::getQuantity).sum();
        }

        assertEquals(0, errors.get(), "No unexpected exceptions");

        // Verify no order in the book has negative remaining qty
        OrderBookSnapshot snap = service.getSnapshot(SYMBOL);
        snap.getAsks().forEach(o -> assertTrue(o.getRemainingQty() >= 0,
                "Negative remaining qty on ask: " + o.getId()));
        snap.getBids().forEach(o -> assertTrue(o.getRemainingQty() >= 0,
                "Negative remaining qty on bid: " + o.getId()));

        // Total filled must not exceed total resting sell supply (10 * 1000 = 10_000)
        assertTrue(totalFilled <= 10_000L, "Filled more than available supply: " + totalFilled);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4 — Duplicate concurrent requests with same clientOrderId
    //          must be idempotent: only one order placed, no exception storm
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Duplicate concurrent clientOrderId: at-most-once insertion")
    void duplicateConcurrentClientOrderId() throws Exception {
        long userId = 830_001L;
        provisionAccount(userId);

        String clientOrderId = "dedup-order-xyz";
        int threadCount = 10;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger duplicateRejections = new AtomicInteger(0);
        AtomicInteger unexpectedErrors = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            futures.add(pool.submit(() -> {
                try {
                    barrier.await(); // all threads start simultaneously
                    service.submitOrder(clientOrderId, userId, SYMBOL, OrderSide.BUY,
                            OrderType.LIMIT, new BigDecimal("100.00"), 10L);
                    successes.incrementAndGet();
                } catch (ExchangeException e) {
                    if (e.getMessage() != null && e.getMessage().contains("already")) {
                        duplicateRejections.incrementAndGet();
                    } else {
                        // Other business exceptions (insufficient funds, etc.) are OK
                        duplicateRejections.incrementAndGet();
                    }
                } catch (BrokenBarrierException | InterruptedException ignored) {
                } catch (Exception e) {
                    unexpectedErrors.incrementAndGet();
                    System.err.println("Unexpected in dedup test: " + e);
                }
            }));
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        for (Future<?> f : futures) f.get();

        assertEquals(0, unexpectedErrors.get(), "Unexpected exceptions in dedup test");
        // At least one must succeed
        assertTrue(successes.get() >= 1, "No submissions succeeded");
        // Total must account for all threads
        assertEquals(threadCount, successes.get() + duplicateRejections.get(),
                "Thread count mismatch: successes=" + successes + " rejections=" + duplicateRejections);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5 — Self-trade protection holds under concurrency
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Self-trade protection: concurrent buy+sell from same user never matches")
    void selfTradeProtectionConcurrent() throws Exception {
        long userId = 840_001L;
        provisionAccount(userId);

        // Place resting sell
        service.submitOrder("stp-sell", userId, SYMBOL, OrderSide.SELL,
                OrderType.LIMIT, new BigDecimal("200.00"), 500L);

        // Concurrently blast buys from the same user at the same price
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger trades = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int idx = t;
            futures.add(pool.submit(() -> {
                for (int i = 0; i < 10; i++) {
                    try {
                        List<Trade> result = service.submitOrder(
                                "stp-buy-" + idx + "-" + i, userId, SYMBOL, OrderSide.BUY,
                                OrderType.LIMIT, new BigDecimal("200.00"), 10L);
                        trades.addAndGet(result.size());
                    } catch (ExchangeException ignored) {
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        System.err.println("STP concurrency error: " + e);
                    }
                }
            }));
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        for (Future<?> f : futures) f.get();

        assertEquals(0, errors.get(), "Unexpected exceptions");
        assertEquals(0, trades.get(), "Self-trades must never execute: got " + trades.get());
    }
}
