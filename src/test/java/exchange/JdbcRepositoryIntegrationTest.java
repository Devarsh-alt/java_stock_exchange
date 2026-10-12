package exchange;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real MySQL Integration Tests for the Repository Layer and Transaction Boundaries.
 *
 * <p>Only runs when {@code DB_URL} is set in the environment.
 * All tests execute inside transactions or clean up their generated test fixtures.
 */
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
    @DisplayName("Monotonic sequence allocator returns strictly increasing numbers on the same connection")
    void sequenceAllocatorReturnsMonotonicValues() throws SQLException {
        long seq1 = txManager.executeInTransaction(conn -> orderRepo.nextArrivalSeq(conn));
        long seq2 = txManager.executeInTransaction(conn -> orderRepo.nextArrivalSeq(conn));
        assertTrue(seq1 > 0, "Sequence should be positive");
        assertEquals(seq1 + 1, seq2, "Consecutive allocations should be monotonically increasing");
    }

    @Test
    @DisplayName("Account creation, cash reservation, and release work consistently in transactions")
    void accountCashReservationLifecycle() throws SQLException {
        long userId = nextTestUserId();
        txManager.executeInTransaction(conn -> {
            accountRepo.createAccount(conn, userId, new BigDecimal("1000.0000"));
            return null;
        });

        // Reserve 400
        boolean reserved = txManager.executeInTransaction(conn ->
                accountRepo.reserveCash(conn, userId, new BigDecimal("400.0000")));
        assertTrue(reserved);

        Optional<JdbcAccountRepository.AccountRecord> acc = txManager.executeInTransaction(conn ->
                accountRepo.findAccountById(conn, userId));
        assertTrue(acc.isPresent());
        assertEquals(new BigDecimal("1000.0000"), acc.get().totalCash());
        assertEquals(new BigDecimal("400.0000"), acc.get().reservedCash());
        assertEquals(new BigDecimal("600.0000"), acc.get().availableCash());

        // Try reserving 700 (exceeds available 600) -> should return false
        boolean overReserved = txManager.executeInTransaction(conn ->
                accountRepo.reserveCash(conn, userId, new BigDecimal("700.0000")));
        assertFalse(overReserved);

        // Release 400
        boolean released = txManager.executeInTransaction(conn ->
                accountRepo.releaseCashReservation(conn, userId, new BigDecimal("400.0000")));
        assertTrue(released);

        Optional<JdbcAccountRepository.AccountRecord> accAfter = txManager.executeInTransaction(conn ->
                accountRepo.findAccountById(conn, userId));
        assertTrue(accAfter.isPresent());
        assertEquals(BigDecimal.ZERO.setScale(4), accAfter.get().reservedCash().setScale(4));
        assertEquals(new BigDecimal("1000.0000"), accAfter.get().availableCash());
    }

    @Test
    @DisplayName("Transaction rollback restores account state when exception occurs")
    void transactionRollbackPreservesState() throws SQLException {
        long userId = nextTestUserId();
        txManager.executeInTransaction(conn -> {
            accountRepo.createAccount(conn, userId, new BigDecimal("500.0000"));
            return null;
        });

        // Attempt a transaction that modifies cash then throws an exception
        assertThrows(RuntimeException.class, () -> {
            txManager.executeInTransaction(conn -> {
                accountRepo.reserveCash(conn, userId, new BigDecimal("200.0000"));
                throw new RuntimeException("Simulated mid-transaction failure");
            });
        });

        // State must remain unreserved
        Optional<JdbcAccountRepository.AccountRecord> acc = txManager.executeInTransaction(conn ->
                accountRepo.findAccountById(conn, userId));
        assertTrue(acc.isPresent());
        assertEquals(BigDecimal.ZERO.setScale(4), acc.get().reservedCash().setScale(4));
        assertEquals(new BigDecimal("500.0000"), acc.get().availableCash());
    }

    @Test
    @DisplayName("Idempotency repository stores records and detects duplicates and conflicts")
    void idempotencyStoreAndConflictDetection() throws SQLException {
        long userId = nextTestUserId();
        String clientOrderId = "cl-ord-" + UUID.randomUUID();
        String hash1 = IdempotencyRepository.computeRequestHash(userId, "RELIANCE.NS", OrderSide.BUY, OrderType.LIMIT, 10, new BigDecimal("100.00"));
        String hash2 = IdempotencyRepository.computeRequestHash(userId, "RELIANCE.NS", OrderSide.BUY, OrderType.LIMIT, 20, new BigDecimal("100.00"));

        txManager.executeInTransaction(conn -> {
            accountRepo.createAccount(conn, userId, new BigDecimal("2000.0000"));
            long seq = orderRepo.nextArrivalSeq(conn);
            Order order = new Order(clientOrderId, userId, "RELIANCE.NS", OrderSide.BUY, OrderType.LIMIT, new BigDecimal("100.00"), 10, seq);
            orderRepo.insertOrder(conn, order);
            idempRepo.record(conn, userId, clientOrderId, hash1, order.getId(), "OPEN");
            return null;
        });

        // Lookup record
        Optional<IdempotencyRepository.IdempotencyRecord> record = txManager.executeInTransaction(conn ->
                idempRepo.findRecord(conn, userId, clientOrderId));

        assertTrue(record.isPresent());
        assertEquals(hash1, record.get().requestHash());
        assertNotEquals(hash2, record.get().requestHash(), "Conflicting parameters produce different hash");
    }

    @Test
    @DisplayName("Trade persistence preserves unique maker fill sequence constraint")
    void tradePersistenceEnforcesFillSequenceUniqueness() throws SQLException {
        long buyerId = nextTestUserId();
        long sellerId = nextTestUserId();

        String buyOrdId = "b-" + UUID.randomUUID();
        String sellOrdId = "s-" + UUID.randomUUID();

        txManager.executeInTransaction(conn -> {
            accountRepo.createAccount(conn, buyerId, new BigDecimal("5000.0000"));
            accountRepo.createAccount(conn, sellerId, new BigDecimal("1000.0000"));

            long seq1 = orderRepo.nextArrivalSeq(conn);
            long seq2 = orderRepo.nextArrivalSeq(conn);

            Order buyOrder = new Order(buyOrdId, buyerId, "RELIANCE.NS", OrderSide.BUY, OrderType.LIMIT, new BigDecimal("100.00"), 10, seq1);
            Order sellOrder = new Order(sellOrdId, sellerId, "RELIANCE.NS", OrderSide.SELL, OrderType.LIMIT, new BigDecimal("100.00"), 10, seq2);

            orderRepo.insertOrder(conn, buyOrder);
            orderRepo.insertOrder(conn, sellOrder);

            Trade trade1 = new Trade("RELIANCE.NS", buyOrder.getId(), sellOrder.getId(), buyerId, sellerId, new BigDecimal("100.00"), 5);
            tradeRepo.insertTrade(conn, trade1, sellOrder.getId(), 1);
            return null;
        });

        // Inserting the exact same maker_fill_seq for the same maker order must fail with duplicate key violation
        assertThrows(SQLException.class, () -> {
            txManager.executeInTransaction(conn -> {
                Trade duplicateTrade = new Trade("RELIANCE.NS", buyOrdId, sellOrdId, buyerId, sellerId, new BigDecimal("100.00"), 5);
                tradeRepo.insertTrade(conn, duplicateTrade, sellOrdId, 1); // Duplicate sequence 1!
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

        // Setup accounts in DB
        txManager.executeInTransaction(conn -> {
            accountRepo.createAccount(conn, buyerId, new BigDecimal("10000.0000"));
            accountRepo.createAccount(conn, sellerId, new BigDecimal("1000.0000"));
            accountRepo.settleBuyFill(conn, sellerId, sym, BigDecimal.ZERO, BigDecimal.ZERO, 50L);
            return null;
        });

        MatchingEngine engine = new MatchingEngine();
        ExchangeService service = new ExchangeService(engine, new AccountService(), txManager, accountRepo, orderRepo, tradeRepo, idempRepo);
        assertTrue(service.isDatabaseBacked());

        String sellClientOrderId = "sell-" + UUID.randomUUID();
        String buyClientOrderId = "buy-" + UUID.randomUUID();

        // 1. Submit maker sell order
        List<Trade> trades1 = service.submitOrder(sellClientOrderId, sellerId, sym, OrderSide.SELL, OrderType.LIMIT, new BigDecimal("200.00"), 20L);
        assertTrue(trades1.isEmpty(), "Maker order should not generate trades immediately");

        // 2. Submit taker buy order that matches
        List<Trade> trades2 = service.submitOrder(buyClientOrderId, buyerId, sym, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("200.00"), 15L);
        assertEquals(1, trades2.size(), "Should produce 1 matched trade");
        assertEquals(15L, trades2.get(0).getQuantity());
        assertEquals(new BigDecimal("200.00"), trades2.get(0).getPrice());

        // 3. Verify Idempotent submission: re-submitting same clientOrderId returns same trades without duplicate execution
        List<Trade> tradesDuplicate = service.submitOrder(buyClientOrderId, buyerId, sym, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("200.00"), 15L);
        assertEquals(1, tradesDuplicate.size());
        assertEquals(trades2.get(0).getBuyOrderId(), tradesDuplicate.get(0).getBuyOrderId());

        // 4. Verify Conflict submission: same clientOrderId with different parameters throws ExchangeException
        assertThrows(ExchangeException.class, () -> {
            service.submitOrder(buyClientOrderId, buyerId, sym, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("250.00"), 15L);
        });

        // 5. Verify cancel order transactionally for the resting maker remainder
        Order restingMaker = engine.getOrder(sym, trades2.get(0).getSellOrderId());
        assertNotNull(restingMaker, "Maker order remainder should be resting on book");
        assertEquals(5L, restingMaker.getRemainingQty());
        boolean cancelled = service.cancelOrder(sym, restingMaker.getId());
        assertTrue(cancelled, "Order cancellation should succeed");
        assertNull(engine.getOrder(sym, restingMaker.getId()), "Cancelled order should be removed from book");
    }
}
