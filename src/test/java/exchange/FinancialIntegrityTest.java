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
 * Mandatory tests for Financial Integrity and Account Reservations (Phase 2).
 * Verifies strict accounting invariants:
 * 1. Reject buy exceeding cash.
 * 2. Reject sell exceeding shares.
 * 3. Exact available bounds.
 * 4. Multiple buy orders without double spending.
 * 5. Multiple sell orders without overselling.
 * 6. Partial fill buy reservation and settlement.
 * 7. Partial fill sell reservation and settlement.
 * 8. Cancellation releases unused reservations.
 * 9. Concurrent buy submissions racing on account balance.
 * 10. Concurrent sell submissions racing on share holding.
 * 11. Cash and holdings never become negative.
 * 12. Listener exceptions do not corrupt accounting.
 * 13. Full lifecycle ledger reconciliation.
 */
class FinancialIntegrityTest {

    static final String SYM = "RELIANCE.NS";
    static final long BUYER_ID = 101L;
    static final long SELLER_ID = 202L;

    MatchingEngine engine;
    AccountService accountService;
    ExchangeService service;

    Account buyer;
    Account seller;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        accountService = new AccountService();
        service = new ExchangeService(engine, accountService);

        buyer = accountService.createAccount(BUYER_ID, bd("10000")); // 10,000 cash
        seller = accountService.createAccount(SELLER_ID, bd("1000")); // 1,000 cash
        seller.depositShares(SYM, 100); // 100 shares
    }

    static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }

    @Test
    @DisplayName("1. Reject buy order that exceeds available cash")
    void rejectBuyExceedingCash() {
        // Cash is 10,000; order requires 10,000.01 (100.0001 * 100 or 100 * 101)
        ExchangeException ex = assertThrows(ExchangeException.class, () ->
                service.submitOrder("b-over", BUYER_ID, SYM, OrderSide.BUY, OrderType.LIMIT, bd("101"), 100));
        assertTrue(ex.getMessage().contains("Insufficient available cash"));
        assertEquals(bd("10000"), buyer.getTotalCash());
        assertEquals(BigDecimal.ZERO, buyer.getReservedCash());
        assertEquals(bd("10000"), buyer.getAvailableCash());
    }

    @Test
    @DisplayName("2. Reject sell order that exceeds available shares")
    void rejectSellExceedingShares() {
        // Seller owns 100 shares; try to sell 101
        ExchangeException ex = assertThrows(ExchangeException.class, () ->
                service.submitOrder("s-over", SELLER_ID, SYM, OrderSide.SELL, OrderType.LIMIT, bd("100"), 101));
        assertTrue(ex.getMessage().contains("Insufficient available shares"));
        assertEquals(100L, seller.getOwnedShares(SYM));
        assertEquals(0L, seller.getReservedShares(SYM));
        assertEquals(100L, seller.getAvailableShares(SYM));
    }

    @Test
    @DisplayName("3. Accept orders that fit exactly within available funds or inventory")
    void acceptExactBoundsOrders() {
        // Exactly spend all 10,000 cash: 100 shares @ 100
        service.submitOrder("b-exact", BUYER_ID, SYM, OrderSide.BUY, OrderType.LIMIT, bd("100"), 100);
        assertEquals(bd("10000"), buyer.getTotalCash());
        assertEquals(bd("10000"), buyer.getReservedCash());
        assertEquals(BigDecimal.ZERO, buyer.getAvailableCash());

        // Exactly reserve all 100 shares: 100 shares @ 105
        service.submitOrder("s-exact", SELLER_ID, SYM, OrderSide.SELL, OrderType.LIMIT, bd("105"), 100);
        assertEquals(100L, seller.getOwnedShares(SYM));
        assertEquals(100L, seller.getReservedShares(SYM));
        assertEquals(0L, seller.getAvailableShares(SYM));
    }

    @Test
    @DisplayName("4. Reserve funds for multiple open buy orders without double spending")
    void multipleBuyOrdersCashReservations() {
        // Order 1: 30 shares @ 100 = 3,000
        service.submitOrder("b1", BUYER_ID, SYM, OrderSide.BUY, OrderType.LIMIT, bd("100"), 30);
        assertEquals(bd("3000"), buyer.getReservedCash());
        assertEquals(bd("7000"), buyer.getAvailableCash());

        // Order 2: 50 shares @ 100 = 5,000
        service.submitOrder("b2", BUYER_ID, SYM, OrderSide.BUY, OrderType.LIMIT, bd("100"), 50);
        assertEquals(bd("8000"), buyer.getReservedCash());
        assertEquals(bd("2000"), buyer.getAvailableCash());

        // Order 3: 25 shares @ 100 = 2,500 -> Exceeds remaining 2,000 -> must reject
        assertThrows(ExchangeException.class, () ->
                service.submitOrder("b3", BUYER_ID, SYM, OrderSide.BUY, OrderType.LIMIT, bd("100"), 25));

        assertEquals(bd("8000"), buyer.getReservedCash());
        assertEquals(bd("2000"), buyer.getAvailableCash());
    }

    @Test
    @DisplayName("5. Reserve shares for multiple open sell orders without overselling")
    void multipleSellOrdersShareReservations() {
        // Order 1: 40 shares @ 120
        service.submitOrder("s1", SELLER_ID, SYM, OrderSide.SELL, OrderType.LIMIT, bd("120"), 40);
        assertEquals(40L, seller.getReservedShares(SYM));
        assertEquals(60L, seller.getAvailableShares(SYM));

        // Order 2: 50 shares @ 125
        service.submitOrder("s2", SELLER_ID, SYM, OrderSide.SELL, OrderType.LIMIT, bd("125"), 50);
        assertEquals(90L, seller.getReservedShares(SYM));
        assertEquals(10L, seller.getAvailableShares(SYM));

        // Order 3: 15 shares @ 130 -> Exceeds remaining 10 shares -> must reject
        assertThrows(ExchangeException.class, () ->
                service.submitOrder("s3", SELLER_ID, SYM, OrderSide.SELL, OrderType.LIMIT, bd("130"), 15));

        assertEquals(90L, seller.getReservedShares(SYM));
        assertEquals(10L, seller.getAvailableShares(SYM));
    }

    @Test
    @DisplayName("6. Partially fill a buy and verify cash reservation and settlement")
    void partialFillBuySettlement() {
        // Resting sell: 40 shares @ 100 = 4,000
        service.submitOrder("s1", SELLER_ID, SYM, OrderSide.SELL, OrderType.LIMIT, bd("100"), 40);

        // Buyer sends limit order for 70 shares @ 100 (reserving 7,000)
        List<Trade> trades = service.submitOrder("b1", BUYER_ID, SYM, OrderSide.BUY, OrderType.LIMIT, bd("100"), 70);

        assertEquals(1, trades.size());
        assertEquals(40L, trades.get(0).getQuantity());

        // Buyer ledger check:
        // Original cash: 10,000. Filled 40 @ 100 = 4,000 deducted -> totalCash = 6,000.
        // Remaining 30 resting on book -> reservedCash = 3,000. Available cash = 3,000.
        assertEquals(bd("6000"), buyer.getTotalCash());
        assertEquals(bd("3000"), buyer.getReservedCash());
        assertEquals(bd("3000"), buyer.getAvailableCash());
        assertEquals(40L, buyer.getOwnedShares(SYM));

        // Seller ledger check:
        // Seller sold 40 @ 100 = 4,000 cash added -> totalCash = 1,000 + 4,000 = 5,000.
        // Shares: 100 - 40 = 60 remaining owned, 0 reserved.
        assertEquals(bd("5000"), seller.getTotalCash());
        assertEquals(60L, seller.getOwnedShares(SYM));
        assertEquals(0L, seller.getReservedShares(SYM));
    }

    @Test
    @DisplayName("7. Partially fill a sell and verify share reservation and settlement")
    void partialFillSellSettlement() {
        // Resting buy: 30 shares @ 100
        service.submitOrder("b1", BUYER_ID, SYM, OrderSide.BUY, OrderType.LIMIT, bd("100"), 30);

        // Incoming sell: 80 shares @ 100 (reserves 80 shares)
        List<Trade> trades = service.submitOrder("s1", SELLER_ID, SYM, OrderSide.SELL, OrderType.LIMIT, bd("100"), 80);

        assertEquals(1, trades.size());
        assertEquals(30L, trades.get(0).getQuantity());

        // Seller ledger check:
        // Sold 30 @ 100 -> cash +3,000 = 4,000 totalCash.
        // Shares: 100 owned initially, 30 delivered -> 70 owned.
        // Remaining 50 resting on book -> 50 reservedShares. Available = 20.
        assertEquals(bd("4000"), seller.getTotalCash());
        assertEquals(70L, seller.getOwnedShares(SYM));
        assertEquals(50L, seller.getReservedShares(SYM));
        assertEquals(20L, seller.getAvailableShares(SYM));

        // Buyer ledger check:
        // Bought 30 @ 100 -> cash -3,000 = 7,000. 0 reserved.
        assertEquals(bd("7000"), buyer.getTotalCash());
        assertEquals(BigDecimal.ZERO, buyer.getReservedCash());
        assertEquals(30L, buyer.getOwnedShares(SYM));
    }

    @Test
    @DisplayName("8. Cancel open and partially filled orders and verify unused reservations are released")
    void cancelReleasesReservations() {
        // Buyer submits 50 @ 100 = 5,000 reserved
        service.submitOrder("b1", BUYER_ID, SYM, OrderSide.BUY, OrderType.LIMIT, bd("100"), 50);
        assertEquals(bd("5000"), buyer.getReservedCash());

        OrderBookSnapshot snap = service.getSnapshot(SYM);
        String b1Id = snap.getBids().get(0).getId();

        // Cancel b1
        assertTrue(service.cancelOrder(SYM, b1Id));
        assertEquals(BigDecimal.ZERO, buyer.getReservedCash(), "All reserved cash released on cancel");
        assertEquals(bd("10000"), buyer.getAvailableCash());

        // Partially fill then cancel
        service.submitOrder("s-fill", SELLER_ID, SYM, OrderSide.SELL, OrderType.LIMIT, bd("100"), 20);
        service.submitOrder("b-partial", BUYER_ID, SYM, OrderSide.BUY, OrderType.LIMIT, bd("100"), 50);

        // 20 filled (cost 2,000), 30 resting (3,000 reserved)
        assertEquals(bd("8000"), buyer.getTotalCash());
        assertEquals(bd("3000"), buyer.getReservedCash());

        Order resting = service.getSnapshot(SYM).getBids().get(0);
        assertTrue(service.cancelOrder(SYM, resting.getId()));

        assertEquals(bd("8000"), buyer.getTotalCash(), "Cash remains after 2,000 executed");
        assertEquals(BigDecimal.ZERO, buyer.getReservedCash(), "Unfilled reservation 3,000 released");
        assertEquals(bd("8000"), buyer.getAvailableCash());
    }

    @Test
    @DisplayName("9. Race concurrent buy submissions against a fixed account balance")
    void raceConcurrentBuysAgainstBalance() throws InterruptedException {
        // Buyer has 10,000 cash.
        // 20 threads submit buy order of 10 shares @ 100 = 1,000 each.
        // Maximum 10 orders can succeed; 10 must be rejected. Available cash must never drop below 0.
        int threads = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int id = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    service.submitOrder("b-race-" + id, BUYER_ID, SYM, OrderSide.BUY, OrderType.LIMIT, bd("100"), 10);
                    successes.incrementAndGet();
                } catch (ExchangeException e) {
                    failures.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(10, successes.get(), "Exactly 10 buy orders can fit in 10,000 cash");
        assertEquals(10, failures.get(), "Remaining 10 buy orders must be rejected");
        assertEquals(bd("10000"), buyer.getTotalCash());
        assertEquals(bd("10000"), buyer.getReservedCash());
        assertEquals(BigDecimal.ZERO, buyer.getAvailableCash());
    }

    @Test
    @DisplayName("10. Race concurrent sell submissions against a fixed share holding")
    void raceConcurrentSellsAgainstHolding() throws InterruptedException {
        // Seller has 100 shares.
        // 20 threads submit sell order of 10 shares @ 100 each.
        // Maximum 10 orders can succeed; 10 must be rejected. Available shares must never drop below 0.
        int threads = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int id = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    service.submitOrder("s-race-" + id, SELLER_ID, SYM, OrderSide.SELL, OrderType.LIMIT, bd("100"), 10);
                    successes.incrementAndGet();
                } catch (ExchangeException e) {
                    failures.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(10, successes.get(), "Exactly 10 sell orders can fit in 100 shares");
        assertEquals(10, failures.get(), "Remaining 10 sell orders must be rejected");
        assertEquals(100L, seller.getOwnedShares(SYM));
        assertEquals(100L, seller.getReservedShares(SYM));
        assertEquals(0L, seller.getAvailableShares(SYM));
    }

    @Test
    @DisplayName("11. Verify that cash and holdings never become negative")
    void cashAndHoldingsNeverNegative() {
        assertTrue(buyer.getTotalCash().compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(buyer.getAvailableCash().compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(seller.getTotalCash().compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(seller.getAvailableCash().compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(buyer.getOwnedShares(SYM) >= 0);
        assertTrue(buyer.getAvailableShares(SYM) >= 0);
        assertTrue(seller.getOwnedShares(SYM) >= 0);
        assertTrue(seller.getAvailableShares(SYM) >= 0);
    }

    @Test
    @DisplayName("12. Verify that listener exceptions and rejected orders do not corrupt accounting")
    void listenerAndRejectionResilience() {
        service.addTradeListener(trades -> {
            throw new RuntimeException("Listener exploded!");
        });

        // Rejected order due to excessive quantity
        assertThrows(ExchangeException.class, () ->
                service.submitOrder("b-bad", BUYER_ID, SYM, OrderSide.BUY, OrderType.LIMIT, bd("100"), 500));

        // State remains strictly untouched
        assertEquals(bd("10000"), buyer.getTotalCash());
        assertEquals(BigDecimal.ZERO, buyer.getReservedCash());

        // Executed order with faulty listener
        service.submitOrder("s1", SELLER_ID, SYM, OrderSide.SELL, OrderType.LIMIT, bd("100"), 10);
        List<Trade> trades = service.submitOrder("b1", BUYER_ID, SYM, OrderSide.BUY, OrderType.LIMIT, bd("100"), 10);

        assertEquals(1, trades.size());
        assertEquals(bd("9000"), buyer.getTotalCash());
        assertEquals(BigDecimal.ZERO, buyer.getReservedCash());
        assertEquals(10L, buyer.getOwnedShares(SYM));

        assertEquals(bd("2000"), seller.getTotalCash());
        assertEquals(90L, seller.getOwnedShares(SYM));
    }

    @Test
    @DisplayName("13. Verify that the accounting ledger reconciles after a sequence of buys, sells, partial fills, and cancellations")
    void fullLedgerReconciliation() {
        BigDecimal initialSystemCash = buyer.getTotalCash().add(seller.getTotalCash());
        long initialSystemShares = buyer.getOwnedShares(SYM) + seller.getOwnedShares(SYM);

        // Seller posts 50 @ 100
        service.submitOrder("s1", SELLER_ID, SYM, OrderSide.SELL, OrderType.LIMIT, bd("100"), 50);
        // Buyer buys 30 @ 100 (partially fills s1)
        service.submitOrder("b1", BUYER_ID, SYM, OrderSide.BUY, OrderType.LIMIT, bd("100"), 30);
        // Buyer posts buy 40 @ 95
        service.submitOrder("b2", BUYER_ID, SYM, OrderSide.BUY, OrderType.LIMIT, bd("95"), 40);

        // Cancel remaining s1 (20 shares resting)
        Order restingAsk = service.getSnapshot(SYM).getAsks().get(0);
        service.cancelOrder(SYM, restingAsk.getId());

        // Cancel b2 (40 shares resting @ 95 = 3,800 reserved)
        Order restingBid = service.getSnapshot(SYM).getBids().get(0);
        service.cancelOrder(SYM, restingBid.getId());

        // Ledger conservation check:
        // No cash or shares entered or left the closed two-party system:
        BigDecimal finalSystemCash = buyer.getTotalCash().add(seller.getTotalCash());
        long finalSystemShares = buyer.getOwnedShares(SYM) + seller.getOwnedShares(SYM);

        assertEquals(0, initialSystemCash.compareTo(finalSystemCash), "System cash must be conserved");
        assertEquals(initialSystemShares, finalSystemShares, "System shares must be conserved");

        // Zero lingering reservations
        assertEquals(BigDecimal.ZERO, buyer.getReservedCash());
        assertEquals(BigDecimal.ZERO, seller.getReservedCash());
        assertEquals(0L, buyer.getReservedShares(SYM));
        assertEquals(0L, seller.getReservedShares(SYM));

        // Books are empty
        assertTrue(service.getSnapshot(SYM).getBids().isEmpty());
        assertTrue(service.getSnapshot(SYM).getAsks().isEmpty());
    }
}
