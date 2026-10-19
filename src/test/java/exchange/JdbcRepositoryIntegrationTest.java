
package exchange;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class JdbcRepositoryIntegrationTest {

    static ConnectionFactory factory;
    static TransactionManager txManager;
    static JdbcAccountRepository accountRepo;
    static JdbcOrderRepository orderRepo;
    static JdbcTradeRepository tradeRepo;
    static IdempotencyRepository idempRepo;

    @BeforeAll
    static void setUpAll() {
        DatabaseConfig config = DatabaseConfig.fromEnv();
        factory = new ConnectionFactory(config);
        factory.initializeSchema("/schema.sql");

        txManager = new TransactionManager(factory);
        accountRepo = new JdbcAccountRepository();
        orderRepo = new JdbcOrderRepository();
        tradeRepo = new JdbcTradeRepository();
        idempRepo = new IdempotencyRepository();
    }

    private static long nextTestUserId() {
        return 700000000L + (long) (Math.random() * 900000000L);
    }

    @Test
    @DisplayName("Simulator startup cleanup cancels abandoned orders and reconciles reservations")
    void simulatorStartupCleanupCancelsOrdersAndReleasesReservations() throws SQLException {
        long userId = nextTestUserId();
        String symbol = "CLEANUP.TEST";
        String clientOrderId = "sim-cleanup-" + UUID.randomUUID();
        BigDecimal price = new BigDecimal("100.0000");
        long quantity = 5L;
        String requestHash = IdempotencyRepository.computeRequestHash(
                userId, symbol, OrderSide.BUY, OrderType.LIMIT, quantity, price);

        String orderId = txManager.executeInTransaction(conn -> {
            accountRepo.createAccount(conn, userId, new BigDecimal("10000.0000"));
            assertTrue(accountRepo.reserveCash(conn, userId, new BigDecimal("500.0000")));

            Order order = new Order(
                    clientOrderId, userId, symbol, OrderSide.BUY, OrderType.LIMIT,
                    price, price, quantity, orderRepo.nextArrivalSeq(conn));
            order.open();
            orderRepo.insertOrder(conn, order);
            idempRepo.record(conn, userId, clientOrderId, requestHash, order.getId(), "OPEN");
            return order.getId();
        });

        int cancelled = SimulatorSeeder.cancelOutstandingSimulatorOrders(factory, userId, 1);
        assertEquals(1, cancelled);

        JdbcAccountRepository.AccountRecord account = txManager.executeInTransaction(conn ->
                accountRepo.findAccountById(conn, userId).orElseThrow());
        assertEquals(0, BigDecimal.ZERO.compareTo(account.reservedCash()));
        assertEquals(0, new BigDecimal("10000.0000").compareTo(account.availableCash()));

        JdbcOrderRepository.OrderRecord order = txManager.executeInTransaction(conn ->
                orderRepo.findById(conn, orderId).orElseThrow());
        assertEquals(OrderStatus.CANCELLED, order.status());

        IdempotencyRepository.IdempotencyRecord idempotency = txManager.executeInTransaction(conn ->
                idempRepo.findRecord(conn, userId, clientOrderId).orElseThrow());
        assertEquals("CANCELLED", idempotency.responseStatus());
    }

    @Test
    @DisplayName("Simulator cleanup preserves manual orders and keeps their required reservations")
    void simulatorCleanupPreservesManualOrdersAndReconcilesReservations() throws SQLException {
        long userId = nextTestUserId();
        String symbol = "CLEANUP.MIXED";
        BigDecimal simulatorPrice = new BigDecimal("100.0000");
        BigDecimal manualPrice = new BigDecimal("80.0000");
        String simulatorClientId = "sim-cleanup-" + UUID.randomUUID();
        String manualClientId = "manual-cleanup-" + UUID.randomUUID();

        String[] orderIds = txManager.executeInTransaction(conn -> {
            accountRepo.createAccount(conn, userId, new BigDecimal("10000.0000"));
            assertTrue(accountRepo.reserveCash(conn, userId, new BigDecimal("660.0000")));

            Order simulatorOrder = new Order(simulatorClientId, userId, symbol, OrderSide.BUY,
                    OrderType.LIMIT, simulatorPrice, simulatorPrice, 5L, orderRepo.nextArrivalSeq(conn));
            simulatorOrder.open();
            orderRepo.insertOrder(conn, simulatorOrder);
            idempRepo.record(conn, userId, simulatorClientId,
                    IdempotencyRepository.computeRequestHash(userId, symbol, OrderSide.BUY,
                            OrderType.LIMIT, 5L, simulatorPrice), simulatorOrder.getId(), "OPEN");

            Order manualOrder = new Order(manualClientId, userId, symbol, OrderSide.BUY,
                    OrderType.LIMIT, manualPrice, manualPrice, 2L, orderRepo.nextArrivalSeq(conn));
            manualOrder.open();
            orderRepo.insertOrder(conn, manualOrder);
            return new String[]{simulatorOrder.getId(), manualOrder.getId()};
        });

        assertEquals(1, SimulatorSeeder.cancelOutstandingSimulatorOrders(factory, userId, 1));

        JdbcOrderRepository.OrderRecord simulatorOrder = txManager.executeInTransaction(conn ->
                orderRepo.findById(conn, orderIds[0]).orElseThrow());
        JdbcOrderRepository.OrderRecord manualOrder = txManager.executeInTransaction(conn ->
                orderRepo.findById(conn, orderIds[1]).orElseThrow());
        JdbcAccountRepository.AccountRecord account = txManager.executeInTransaction(conn ->
                accountRepo.findAccountById(conn, userId).orElseThrow());

        assertEquals(OrderStatus.CANCELLED, simulatorOrder.status());
        assertEquals(OrderStatus.OPEN, manualOrder.status());
        assertEquals(0, new BigDecimal("160.0000").compareTo(account.reservedCash()));
        assertEquals(0, new BigDecimal("9840.0000").compareTo(account.availableCash()));
    }

    @Test
    @DisplayName("Sequence allocator returns strictly increasing values across transactions")
    void sequenceAllocatorReturnsMonotonicValues() throws SQLException {
        // Other app instances or integration tests may allocate sequence values
        // between these transactions. Global arrival sequence values must be
        // unique and increasing, but they are not guaranteed to be gap-free.
        long seq1 = txManager.executeInTransaction(
                conn -> orderRepo.nextArrivalSeq(conn)
        );

        long seq2 = txManager.executeInTransaction(
                conn -> orderRepo.nextArrivalSeq(conn)
        );

        assertTrue(seq1 > 0, "Sequence should be positive");
        assertTrue(seq2 > seq1,
                "Arrival sequence must increase; gaps are allowed when other transactions allocate values");
    }

    @Test
    @DisplayName("Account creation, cash reservation, and release work consistently in transactions")
    void accountCashReservationLifecycle() throws SQLException {
        long userId = nextTestUserId();

        txManager.executeInTransaction(conn -> {
            accountRepo.createAccount(
                    conn, userId, new BigDecimal("1000.0000")
            );
            return null;
        });

        boolean reserved = txManager.executeInTransaction(conn ->
                accountRepo.reserveCash(
                        conn, userId, new BigDecimal("400.0000")
                )
        );

        assertTrue(reserved);

        Optional<JdbcAccountRepository.AccountRecord> acc =
                txManager.executeInTransaction(conn ->
                        accountRepo.findAccountById(conn, userId)
                );

        assertTrue(acc.isPresent());
        assertEquals(new BigDecimal("1000.0000"), acc.get().totalCash());
        assertEquals(new BigDecimal("400.0000"), acc.get().reservedCash());
        assertEquals(new BigDecimal("600.0000"), acc.get().availableCash());

        // Attempt to reserve 700 when only 600 is available.
        boolean overReserved = txManager.executeInTransaction(conn ->
                accountRepo.reserveCash(
                        conn, userId, new BigDecimal("700.0000")
                )
        );

        assertFalse(overReserved);

        boolean released = txManager.executeInTransaction(conn ->
                accountRepo.releaseCashReservation(
                        conn, userId, new BigDecimal("400.0000")
                )
        );

        assertTrue(released);

        Optional<JdbcAccountRepository.AccountRecord> accAfter =
                txManager.executeInTransaction(conn ->
                        accountRepo.findAccountById(conn, userId)
                );

        assertTrue(accAfter.isPresent());

        assertEquals(
                BigDecimal.ZERO.setScale(4),
                accAfter.get().reservedCash().setScale(4)
        );

        assertEquals(
                new BigDecimal("1000.0000"),
                accAfter.get().availableCash()
        );
    }

    @Test
    @DisplayName("Transaction rollback restores account state when exception occurs")
    void transactionRollbackPreservesState() throws SQLException {
        long userId = nextTestUserId();

        txManager.executeInTransaction(conn -> {
            accountRepo.createAccount(
                    conn, userId, new BigDecimal("500.0000")
            );
            return null;
        });

        assertThrows(RuntimeException.class, () -> {
            txManager.executeInTransaction(conn -> {
                accountRepo.reserveCash(
                        conn, userId, new BigDecimal("200.0000")
                );

                throw new RuntimeException(
                        "Simulated mid-transaction failure"
                );
            });
        });

        Optional<JdbcAccountRepository.AccountRecord> acc =
                txManager.executeInTransaction(conn ->
                        accountRepo.findAccountById(conn, userId)
                );

        assertTrue(acc.isPresent());

        assertEquals(
                BigDecimal.ZERO.setScale(4),
                acc.get().reservedCash().setScale(4)
        );

        assertEquals(
                new BigDecimal("500.0000"),
                acc.get().availableCash()
        );
    }

    @Test
    @DisplayName("Idempotency repository stores records and detects duplicates and conflicts")
    void idempotencyStoreAndConflictDetection() throws SQLException {
        long userId = nextTestUserId();
        String clientOrderId = "cl-ord-" + UUID.randomUUID();

        String hash1 = IdempotencyRepository.computeRequestHash(
                userId,
                "RELIANCE.NS",
                OrderSide.BUY,
                OrderType.LIMIT,
                10,
                new BigDecimal("100.00")
        );

        String hash2 = IdempotencyRepository.computeRequestHash(
                userId,
                "RELIANCE.NS",
                OrderSide.BUY,
                OrderType.LIMIT,
                20,
                new BigDecimal("100.00")
        );

        txManager.executeInTransaction(conn -> {
            accountRepo.createAccount(
                    conn, userId, new BigDecimal("2000.0000")
            );

            long seq = orderRepo.nextArrivalSeq(conn);

            Order order = new Order(
                    clientOrderId,
                    userId,
                    "RELIANCE.NS",
                    OrderSide.BUY,
                    OrderType.LIMIT,
                    new BigDecimal("100.00"),
                    10,
                    seq
            );

            orderRepo.insertOrder(conn, order);

            idempRepo.record(
                    conn, userId, clientOrderId,
                    hash1, order.getId(), "OPEN"
            );

            return null;
        });

        Optional<IdempotencyRepository.IdempotencyRecord> record =
                txManager.executeInTransaction(conn ->
                        idempRepo.findRecord(conn, userId, clientOrderId)
                );

        assertTrue(record.isPresent());
        assertEquals(hash1, record.get().requestHash());

        assertNotEquals(
                hash2,
                record.get().requestHash(),
                "Conflicting parameters produce different hash"
        );
    }

    @Test
    @DisplayName("Trade persistence preserves unique maker fill sequence constraint")
    void tradePersistenceEnforcesFillSequenceUniqueness()
            throws SQLException {

        long buyerId = nextTestUserId();
        long sellerId = nextTestUserId();

        String buyOrdId = "b-" + UUID.randomUUID();
        String sellOrdId = "s-" + UUID.randomUUID();

        txManager.executeInTransaction(conn -> {
            accountRepo.createAccount(
                    conn, buyerId, new BigDecimal("5000.0000")
            );

            accountRepo.createAccount(
                    conn, sellerId, new BigDecimal("1000.0000")
            );

            long seq1 = orderRepo.nextArrivalSeq(conn);
            long seq2 = orderRepo.nextArrivalSeq(conn);

            Order buyOrder = new Order(
                    buyOrdId,
                    buyerId,
                    "RELIANCE.NS",
                    OrderSide.BUY,
                    OrderType.LIMIT,
                    new BigDecimal("100.00"),
                    10,
                    seq1
            );

            Order sellOrder = new Order(
                    sellOrdId,
                    sellerId,
                    "RELIANCE.NS",
                    OrderSide.SELL,
                    OrderType.LIMIT,
                    new BigDecimal("100.00"),
                    10,
                    seq2
            );

            orderRepo.insertOrder(conn, buyOrder);
            orderRepo.insertOrder(conn, sellOrder);

            Trade trade1 = new Trade(
                    "RELIANCE.NS",
                    buyOrder.getId(),
                    sellOrder.getId(),
                    buyerId,
                    sellerId,
                    new BigDecimal("100.00"),
                    5
            );

            tradeRepo.insertTrade(conn, trade1, sellOrder.getId(), 1);

            return null;
        });

        // Reusing the same maker fill sequence must violate the unique constraint.
        assertThrows(SQLException.class, () -> {
            txManager.executeInTransaction(conn -> {
                Trade duplicateTrade = new Trade(
                        "RELIANCE.NS",
                        buyOrdId,
                        sellOrdId,
                        buyerId,
                        sellerId,
                        new BigDecimal("100.00"),
                        5
                );

                tradeRepo.insertTrade(
                        conn, duplicateTrade, sellOrdId, 1
                );

                return null;
            });
        });
    }

    @Test
    @DisplayName("ExchangeService database-backed order submission executes atomically with matching and idempotency")
    void exchangeServiceDatabaseBackedSubmission() throws SQLException {
        long buyerId = nextTestUserId();
        long sellerId = nextTestUserId();
        String sym = "TCS.NS";

        txManager.executeInTransaction(conn -> {
            accountRepo.createAccount(
                    conn, buyerId, new BigDecimal("10000.0000")
            );

            accountRepo.createAccount(
                    conn, sellerId, new BigDecimal("1000.0000")
            );

            accountRepo.settleBuyFill(
                    conn, sellerId, sym,
                    BigDecimal.ZERO, BigDecimal.ZERO, 50L
            );

            return null;
        });

        MatchingEngine engine = new MatchingEngine();

        ExchangeService service = new ExchangeService(
                engine,
                new AccountService(),
                txManager,
                accountRepo,
                orderRepo,
                tradeRepo,
                idempRepo
        );

        assertTrue(service.isDatabaseBacked());

        String sellClientOrderId = "sell-" + UUID.randomUUID();
        String buyClientOrderId = "buy-" + UUID.randomUUID();

        // Submit the maker sell order.
        List<Trade> trades1 = service.submitOrder(
                sellClientOrderId,
                sellerId,
                sym,
                OrderSide.SELL,
                OrderType.LIMIT,
                new BigDecimal("200.00"),
                20L
        );

        assertTrue(
                trades1.isEmpty(),
                "Maker order should not generate trades immediately"
        );

        // Submit a matching taker buy order.
        List<Trade> trades2 = service.submitOrder(
                buyClientOrderId,
                buyerId,
                sym,
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("200.00"),
                15L
        );

        assertEquals(1, trades2.size(), "Should produce 1 matched trade");
        assertEquals(15L, trades2.get(0).getQuantity());
        assertEquals(new BigDecimal("200.00"), trades2.get(0).getPrice());

        // Repeating the same request should not execute it twice.
        List<Trade> tradesDuplicate = service.submitOrder(
                buyClientOrderId,
                buyerId,
                sym,
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("200.00"),
                15L
        );

        assertEquals(1, tradesDuplicate.size());

        assertEquals(
                trades2.get(0).getBuyOrderId(),
                tradesDuplicate.get(0).getBuyOrderId()
        );

        // Reusing the ID with different parameters must fail.
        assertThrows(ExchangeException.class, () ->
                service.submitOrder(
                        buyClientOrderId,
                        buyerId,
                        sym,
                        OrderSide.BUY,
                        OrderType.LIMIT,
                        new BigDecimal("250.00"),
                        15L
                )
        );

        // Cancel the remaining maker quantity.
        Order restingMaker = engine.getOrder(
                sym, trades2.get(0).getSellOrderId()
        );

        assertNotNull(
                restingMaker,
                "Maker order remainder should be resting on book"
        );

        assertEquals(5L, restingMaker.getRemainingQty());

        boolean cancelled = service.cancelOrder(
                sym, restingMaker.getId()
        );

        assertTrue(cancelled, "Order cancellation should succeed");

        assertNull(
                engine.getOrder(sym, restingMaker.getId()),
                "Cancelled order should be removed from book"
        );
    }

    @Test
    @DisplayName("Startup recovery: Reconstructing order book from committed database state restores active orders and price-time priority")
    void startupRecoveryFromDatabaseState() throws SQLException {
        long sellerId = nextTestUserId();
        String sym = "INFY" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + ".NS";

        txManager.executeInTransaction(conn -> {
            accountRepo.createAccount(
                    conn, sellerId, new BigDecimal("1000.0000")
            );

            accountRepo.settleBuyFill(
                    conn, sellerId, sym,
                    BigDecimal.ZERO, BigDecimal.ZERO, 100L
            );

            return null;
        });

        MatchingEngine engine1 = new MatchingEngine();

        ExchangeService service1 = new ExchangeService(
                engine1,
                new AccountService(),
                txManager,
                accountRepo,
                orderRepo,
                tradeRepo,
                idempRepo
        );

        // Submit two resting sell orders.
        String ord1 = "ord-" + UUID.randomUUID();
        String ord2 = "ord-" + UUID.randomUUID();

        service1.submitOrder(
                ord1,
                sellerId,
                sym,
                OrderSide.SELL,
                OrderType.LIMIT,
                new BigDecimal("150.00"),
                10L
        );

        service1.submitOrder(
                ord2,
                sellerId,
                sym,
                OrderSide.SELL,
                OrderType.LIMIT,
                new BigDecimal("155.00"),
                20L
        );

        // Simulate a restart with a new matching engine.
        MatchingEngine engineRestarted = new MatchingEngine();

        ExchangeService serviceRestarted = new ExchangeService(
                engineRestarted,
                new AccountService(),
                txManager,
                accountRepo,
                orderRepo,
                tradeRepo,
                idempRepo
        );

        // Before recovery, the restarted engine has an empty book.
        OrderBookSnapshot initialSnapshot = engineRestarted.snapshot(sym);

        assertTrue(
                initialSnapshot.getAsks().isEmpty(),
                "Restarted engine starts empty before recovery"
        );

        // Recover the order book from committed database state.
        serviceRestarted.recoverSymbolFromDatabase(sym);

        OrderBookSnapshot recoveredSnapshot = engineRestarted.snapshot(sym);

        assertEquals(
                2,
                recoveredSnapshot.getAsks().size(),
                "Both active orders must be recovered"
        );

        // BigDecimal.compareTo compares numeric value independently of scale.
        assertEquals(
                0,
                new BigDecimal("150.00").compareTo(
                        recoveredSnapshot.getAsks().get(0).getPrice()
                ),
                "First recovered ask price should be 150.00"
        );

        assertEquals(
                10L,
                recoveredSnapshot.getAsks().get(0).getRemainingQty()
        );

        assertEquals(
                0,
                new BigDecimal("155.00").compareTo(
                        recoveredSnapshot.getAsks().get(1).getPrice()
                ),
                "Second recovered ask price should be 155.00"
        );

        assertEquals(
                20L,
                recoveredSnapshot.getAsks().get(1).getRemainingQty()
        );

        assertFalse(serviceRestarted.isSymbolQuarantined(sym));
    }
}
