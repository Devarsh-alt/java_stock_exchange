package exchange;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Service layer coordinating order validation, financial reservations,
 * matching engine submission, trade settlement, and listener notifications.
 *
 * <h2>Consistency Protocol</h2>
 * <ul>
 *   <li>Pre-trade reservation: Cash or shares are reserved atomically on the user's Account.</li>
 *   <li>Submission & Matching: Order is submitted to MatchingEngine under the per-symbol lock.</li>
 *   <li>Trade Settlement: Each matched trade is atomically settled between buyer and seller using AccountService
 *       with deterministic user ID lock acquisition to prevent deadlocks.</li>
 *   <li>Post-matching reservation adjustments: Unfilled market order quantities or remaining reservations are adjusted.</li>
 *   <li>Cancellation: Order is removed from the book under symbol lock, and remaining reservations are released.</li>
 *   <li>Exception Isolation: Listener errors are caught so they cannot roll back or corrupt financial state.</li>
 * </ul>
 */
public class ExchangeService {

    private final MatchingEngine engine;
    private final AccountService accountService;
    private final TransactionManager txManager;
    private final JdbcAccountRepository jdbcAccountRepo;
    private final JdbcOrderRepository jdbcOrderRepo;
    private final JdbcTradeRepository jdbcTradeRepo;
    private final IdempotencyRepository idempRepo;
    private final CopyOnWriteArrayList<TradeListener> listeners = new CopyOnWriteArrayList<>();

    public ExchangeService(MatchingEngine engine) {
        this(engine, new AccountService(), null, null, null, null, null);
    }

    public ExchangeService(MatchingEngine engine, AccountService accountService) {
        this(engine, accountService, null, null, null, null, null);
    }

    public ExchangeService(MatchingEngine engine,
                           AccountService accountService,
                           TransactionManager txManager,
                           JdbcAccountRepository jdbcAccountRepo,
                           JdbcOrderRepository jdbcOrderRepo,
                           JdbcTradeRepository jdbcTradeRepo,
                           IdempotencyRepository idempRepo) {
        this.engine = Objects.requireNonNull(engine, "engine cannot be null");
        this.accountService = Objects.requireNonNull(accountService, "accountService cannot be null");
        this.txManager = txManager;
        this.jdbcAccountRepo = jdbcAccountRepo;
        this.jdbcOrderRepo = jdbcOrderRepo;
        this.jdbcTradeRepo = jdbcTradeRepo;
        this.idempRepo = idempRepo;
    }

    private final java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean> quarantinedSymbols =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public boolean isSymbolQuarantined(String symbol) {
        return quarantinedSymbols.contains(symbol);
    }

    public void quarantineSymbol(String symbol) {
        quarantinedSymbols.add(symbol);
        System.err.println("[ExchangeService] QUARANTINED symbol: " + symbol);
    }

    public void unquarantineSymbol(String symbol) {
        quarantinedSymbols.remove(symbol);
        System.out.println("[ExchangeService] UNQUARANTINED symbol: " + symbol);
    }

    /**
     * Reconstructs in-memory order book for a symbol from committed database state,
     * ensuring strict price-time priority and accurate projection.
     */
    public void recoverSymbolFromDatabase(String symbol) {
        if (!isDatabaseBacked()) return;

        java.util.concurrent.locks.ReentrantLock lock = engine.lockFor(symbol);
        lock.lock();
        try {
            OrderBook book = engine.bookFor(symbol);
            book.clear();

            List<JdbcOrderRepository.OrderRecord> activeOrders = txManager.executeInTransaction(conn ->
                    jdbcOrderRepo.findActiveOrders(conn, symbol));

            for (JdbcOrderRepository.OrderRecord rec : activeOrders) {
                Order order = new Order(
                        rec.id(),
                        rec.clientOrderId(),
                        rec.userId(),
                        rec.symbol(),
                        rec.side(),
                        rec.type(),
                        rec.price(),
                        rec.reservedPrice(),
                        rec.originalQty(),
                        rec.remainingQty(),
                        rec.filledQty(),
                        rec.arrivalSeq(),
                        rec.status(),
                        rec.createdAt()
                );
                book.addLimitOrder(order);
            }
            unquarantineSymbol(symbol);
        } catch (java.sql.SQLException e) {
            quarantineSymbol(symbol);
            throw new ExchangeException("Failed to recover symbol " + symbol + " from database: " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    public boolean isDatabaseBacked() {
        return txManager != null && jdbcAccountRepo != null && jdbcOrderRepo != null && jdbcTradeRepo != null && idempRepo != null;
    }

    public AccountService getAccountService() {
        return accountService;
    }

    public MatchingEngine getEngine() {
        return engine;
    }

    // ── Order submission ──────────────────────────────────────────────────────

    public List<Trade> submitOrder(String clientOrderId, long userId, String symbol,
                                   OrderSide side, OrderType type,
                                   BigDecimal price, long quantity) {
        // 1. Validation
        if (clientOrderId == null || clientOrderId.isBlank()) throw new ExchangeException("clientOrderId required");
        if (symbol == null || symbol.isBlank())               throw new ExchangeException("symbol required");
        if (side == null)                                     throw new ExchangeException("side required");
        if (type == null)                                     throw new ExchangeException("type required");
        if (quantity <= 0)                                    throw new ExchangeException("quantity must be positive");
        if (type == OrderType.LIMIT) {
            if (price == null)                                throw new ExchangeException("price required for LIMIT");
            if (price.compareTo(BigDecimal.ZERO) <= 0)       throw new ExchangeException("price must be positive");
        }

        if (isDatabaseBacked()) {
            return submitOrderTransactional(clientOrderId, userId, symbol, side, type, price, quantity);
        } else {
            return submitOrderInMemory(clientOrderId, userId, symbol, side, type, price, quantity);
        }
    }

    private List<Trade> submitOrderTransactional(String clientOrderId, long userId, String symbol,
                                                OrderSide side, OrderType type,
                                                BigDecimal price, long quantity) {
        String requestHash = IdempotencyRepository.computeRequestHash(userId, symbol, side, type, quantity, price);

        if (isSymbolQuarantined(symbol)) {
            throw new ExchangeException("Symbol " + symbol + " is quarantined due to an unresolved error. Reconcile before trading.");
        }

        java.util.concurrent.locks.ReentrantLock lock = engine.lockFor(symbol);
        lock.lock();
        try {
            if (isSymbolQuarantined(symbol)) {
                throw new ExchangeException("Symbol " + symbol + " is quarantined due to an unresolved error. Reconcile before trading.");
            }
            OrderBook book = engine.bookFor(symbol);

            // 1. Check idempotency under lock before allocating sequence or matching
            try {
                java.util.Optional<IdempotencyRepository.IdempotencyRecord> existing =
                        txManager.executeInTransaction(conn -> idempRepo.findRecord(conn, userId, clientOrderId));
                if (existing.isPresent()) {
                    if (!existing.get().requestHash().equals(requestHash)) {
                        throw new ExchangeException("Conflict: clientOrderId '" + clientOrderId + "' already used with different parameters");
                    }
                    // Idempotent duplicate: return existing trades
                    return txManager.executeInTransaction(conn -> {
                        List<JdbcTradeRepository.TradeRecord> records = jdbcTradeRepo.findTradesForOrder(conn, existing.get().orderId());
                        return records.stream().map(r -> new Trade(
                                r.symbol(), r.buyOrderId(), r.sellOrderId(),
                                r.buyUserId(), r.sellUserId(), r.price(), r.quantity()
                        )).toList();
                    });
                }
            } catch (java.sql.SQLException e) {
                quarantineSymbol(symbol);
                throw new ExchangeException("Failed to verify idempotency: " + e.getMessage(), e);
            }

            // 2. Compute non-mutating speculative match
            // Create a temporary uncommitted order object to stage the match
            BigDecimal reservedPrice = (side == OrderSide.BUY) ? price : null;
            Order simulatedOrder = new Order(clientOrderId, userId, symbol, side, type, price, reservedPrice, quantity, 0L);
            StagedMatch staged = engine.stageMatch(simulatedOrder, book);

            // 3. Execute database transaction: allocate sequence, reserve, insert order, insert trades, settle, record idempotency
            final Order persistentOrder;
            try {
                persistentOrder = txManager.executeInTransaction(conn -> {
                    // Check idempotency again inside the atomic transaction to prevent concurrent duplicate submissions
                    java.util.Optional<IdempotencyRepository.IdempotencyRecord> innerCheck =
                            idempRepo.findRecord(conn, userId, clientOrderId);
                    if (innerCheck.isPresent()) {
                        if (!innerCheck.get().requestHash().equals(requestHash)) {
                            throw new ExchangeException("Conflict: clientOrderId '" + clientOrderId + "' already used with different parameters");
                        }
                        throw new IdempotentDuplicateException(innerCheck.get().orderId());
                    }

                    // A. Reserve cash or shares conditionally
                    if (side == OrderSide.BUY) {
                        if (type == OrderType.LIMIT) {
                            BigDecimal cashToReserve = price.multiply(BigDecimal.valueOf(quantity));
                            boolean reserved = jdbcAccountRepo.reserveCash(conn, userId, cashToReserve);
                            if (!reserved) {
                                throw new ExchangeException("Insufficient available cash in database account");
                            }
                        } else {
                            // Market buy check
                            java.util.Optional<JdbcAccountRepository.AccountRecord> acc = jdbcAccountRepo.findAccountById(conn, userId);
                            if (acc.isEmpty() || acc.get().availableCash().compareTo(BigDecimal.ZERO) <= 0) {
                                throw new ExchangeException("Insufficient available cash for market order");
                            }
                        }
                    } else {
                        boolean reserved = jdbcAccountRepo.reserveShares(conn, userId, symbol, quantity);
                        if (!reserved) {
                            throw new ExchangeException("Insufficient available shares in database holding");
                        }
                    }

                    // B. Allocate monotonic arrival sequence from sequence_generator
                    long seq = jdbcOrderRepo.nextArrivalSeq(conn);

                    // C. Construct the final Order with the exact same ID used in staged matching
                    Order order = new Order(simulatedOrder.getId(), clientOrderId, userId, symbol, side, type, price, reservedPrice, quantity, seq);

                    // If incoming order received fills during staged match, set its committed state before insert
                    if (staged.incomingFill() != null) {
                        order.applyCommittedState(
                                staged.incomingFill().newRemainingQty(),
                                staged.incomingFill().newFilledQty(),
                                staged.incomingFill().newStatus()
                        );
                    } else if (type == OrderType.LIMIT) {
                        order.open();
                    } else if (type == OrderType.MARKET) {
                        order.cancel();
                    }

                    jdbcOrderRepo.insertOrder(conn, order);

                    // D. Insert each trade, update maker orders, and settle balances
                    for (Trade trade : staged.trades()) {
                        String makerOrderId = (side == OrderSide.BUY) ? trade.getSellOrderId() : trade.getBuyOrderId();
                        int makerFillSeq = jdbcTradeRepo.nextMakerFillSeq(conn, makerOrderId);
                        jdbcTradeRepo.insertTrade(conn, trade, makerOrderId, makerFillSeq);

                        // Settle buyer
                        BigDecimal buyerUnitReserved = (side == OrderSide.BUY) ? order.getReservedPrice() : trade.getPrice();
                        jdbcAccountRepo.settleBuyFill(conn, trade.getBuyUserId(), symbol, buyerUnitReserved, trade.getPrice(), trade.getQuantity());

                        // Settle seller
                        jdbcAccountRepo.settleSellFill(conn, trade.getSellUserId(), symbol, trade.getPrice(), trade.getQuantity());
                    }

                    // E. Update resting maker orders' fill quantities in DB
                    for (FillDelta delta : staged.restingFills()) {
                        jdbcOrderRepo.updateOrderFill(conn, delta.orderId(), delta.newRemainingQty(), delta.newFilledQty(), delta.newStatus());
                    }

                    // F. Handle unconsumed reservation for market order cancellation
                    if (type == OrderType.MARKET && staged.cancelIncomingRemainder()) {
                        long unconsumed = staged.incomingFill() != null ? staged.incomingFill().newRemainingQty() : quantity;
                        if (side == OrderSide.SELL && unconsumed > 0) {
                            jdbcAccountRepo.releaseShareReservation(conn, userId, symbol, unconsumed);
                        }
                    }

                    // G. Record idempotency record atomically
                    idempRepo.record(conn, userId, clientOrderId, requestHash, order.getId(), order.getStatus().name());

                    return order;
                });
            } catch (IdempotentDuplicateException ide) {
                try {
                    return txManager.executeInTransaction(conn -> {
                        List<JdbcTradeRepository.TradeRecord> records = jdbcTradeRepo.findTradesForOrder(conn, ide.orderId);
                        return records.stream().map(r -> new Trade(
                                r.symbol(), r.buyOrderId(), r.sellOrderId(),
                                r.buyUserId(), r.sellUserId(), r.price(), r.quantity()
                        )).toList();
                    });
                } catch (java.sql.SQLException ex) {
                    quarantineSymbol(symbol);
                    throw new ExchangeException("Failed to retrieve existing trades: " + ex.getMessage(), ex);
                }
            } catch (java.sql.SQLException sqle) {
                // If the error might be an uncertain outcome, quarantine the symbol
                quarantineSymbol(symbol);
                throw new ExchangeException("Transaction failed: " + sqle.getMessage(), sqle);
            }

            // 4. Now that the DB transaction has safely COMMITTED, apply staged plan to live in-memory state
            StagedMatch finalStaged = new StagedMatch(
                    persistentOrder,
                    staged.trades(),
                    staged.restingFills(),
                    staged.incomingFill(),
                    staged.restIncomingOnBook(),
                    staged.cancelIncomingRemainder()
            );
            try {
                engine.applyCommittedMatch(finalStaged, book);
            } catch (Throwable t) {
                // Failure after commit: do NOT retry DB transaction. Quarantine symbol and rebuild from DB.
                quarantineSymbol(symbol);
                try {
                    recoverSymbolFromDatabase(symbol);
                } catch (Exception recEx) {
                    t.addSuppressed(recEx);
                }
                throw new ExchangeException("In-memory projection error after commit; symbol recovered/quarantined: " + t.getMessage(), t);
            }

            // 5. Notify listeners (fail-safe)
            notifyListeners(finalStaged.trades());
            return finalStaged.trades();

        } finally {
            lock.unlock();
        }
    }

    private static class IdempotentDuplicateException extends RuntimeException {
        final String orderId;
        IdempotentDuplicateException(String orderId) { this.orderId = orderId; }
    }

    private List<Trade> submitOrderInMemory(String clientOrderId, long userId, String symbol,
                                            OrderSide side, OrderType type,
                                            BigDecimal price, long quantity) {
        Account account = accountService.getAccount(userId);
        if (account == null) {
            throw new ExchangeException("Account not found for user: " + userId);
        }

        // Financial Reservation
        BigDecimal reservedPrice = (side == OrderSide.BUY) ? price : null;
        BigDecimal cashToReserve = BigDecimal.ZERO;
        long sharesToReserve = 0;

        if (side == OrderSide.BUY) {
            if (type == OrderType.LIMIT) {
                cashToReserve = price.multiply(BigDecimal.valueOf(quantity));
                boolean ok = account.reserveCash(cashToReserve);
                if (!ok) {
                    throw new ExchangeException("Insufficient available cash to place order. Available: "
                            + account.getAvailableCash() + ", required: " + cashToReserve);
                }
            } else {
                if (account.getAvailableCash().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new ExchangeException("Insufficient available cash for market order");
                }
            }
        } else {
            sharesToReserve = quantity;
            boolean ok = account.reserveShares(symbol, sharesToReserve);
            if (!ok) {
                throw new ExchangeException("Insufficient available shares to place order. Available: "
                        + account.getAvailableShares(symbol) + ", required: " + sharesToReserve);
            }
        }

        // Construct Order & Submit to Matching Engine
        long seq = engine.nextArrivalSeq();
        Order order = new Order(clientOrderId, userId, symbol, side, type, price, reservedPrice, quantity, seq);

        MatchingEngine.OrderResult result;
        try {
            result = engine.submitOrderDetailed(order);
        } catch (Throwable t) {
            if (cashToReserve.compareTo(BigDecimal.ZERO) > 0) {
                account.releaseCashReservation(cashToReserve);
            }
            if (sharesToReserve > 0) {
                account.releaseShareReservation(symbol, sharesToReserve);
            }
            throw t;
        }

        // Trade Settlement
        List<Trade> trades = result.trades();
        for (Trade trade : trades) {
            BigDecimal buyerReservedPrice = (order.getSide() == OrderSide.BUY) ? order.getReservedPrice() : null;
            if (buyerReservedPrice == null) {
                buyerReservedPrice = trade.getPrice();
            }
            accountService.settleTrade(trade, buyerReservedPrice);
        }

        // Post-matching reservation adjustments
        if (order.getType() == OrderType.MARKET && order.getRemainingQty() > 0) {
            if (order.getSide() == OrderSide.SELL) {
                account.releaseShareReservation(symbol, order.getRemainingQty());
            }
        }

        notifyListeners(trades);
        return trades;
    }

    // ── Cancellation ──────────────────────────────────────────────────────────

    public boolean cancelOrder(String symbol, String orderId) {
        if (symbol == null || symbol.isBlank()) throw new ExchangeException("symbol required");
        if (orderId == null || orderId.isBlank()) throw new ExchangeException("orderId required");

        if (isDatabaseBacked()) {
            java.util.concurrent.locks.ReentrantLock lock = engine.lockFor(symbol);
            lock.lock();
            try {
                // Check if order exists in memory
                Order order = engine.getOrder(symbol, orderId);
                if (order == null || !order.isActive()) {
                    return false;
                }

                long remaining = order.getRemainingQty();
                BigDecimal reservedPrice = order.getReservedPrice();
                OrderSide side = order.getSide();
                long userId = order.getUserId();

                // 1. Transactionally cancel in database and release reservations
                try {
                    txManager.executeInTransaction(conn -> {
                        boolean cancelledInDb = jdbcOrderRepo.cancelOrder(conn, orderId);
                        if (!cancelledInDb) {
                            throw new SQLException("Failed to cancel order " + orderId + " in DB");
                        }
                        if (remaining > 0) {
                            if (side == OrderSide.BUY && reservedPrice != null) {
                                BigDecimal unusedCash = reservedPrice.multiply(BigDecimal.valueOf(remaining));
                                jdbcAccountRepo.releaseCashReservation(conn, userId, unusedCash);
                            } else if (side == OrderSide.SELL) {
                                jdbcAccountRepo.releaseShareReservation(conn, userId, symbol, remaining);
                            }
                        }
                        return null;
                    });
                } catch (java.sql.SQLException e) {
                    throw new ExchangeException("Database cancellation failed: " + e.getMessage(), e);
                }

                // 2. Safely remove from live in-memory book
                engine.cancelOrderDetailed(symbol, orderId);
                return true;
            } finally {
                lock.unlock();
            }
        } else {
            Order cancelledOrder = engine.cancelOrderDetailed(symbol, orderId);
            if (cancelledOrder == null) {
                return false;
            }

            // Release unused reservation for the remaining quantity
            long remaining = cancelledOrder.getRemainingQty();
            if (remaining > 0) {
                Account account = accountService.getAccount(cancelledOrder.getUserId());
                if (account != null) {
                    if (cancelledOrder.getSide() == OrderSide.BUY && cancelledOrder.getReservedPrice() != null) {
                        BigDecimal unusedCash = cancelledOrder.getReservedPrice().multiply(BigDecimal.valueOf(remaining));
                        account.releaseCashReservation(unusedCash);
                    } else if (cancelledOrder.getSide() == OrderSide.SELL) {
                        account.releaseShareReservation(symbol, remaining);
                    }
                }
            }
            return true;
        }
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────

    public OrderBookSnapshot getSnapshot(String symbol) {
        if (symbol == null || symbol.isBlank()) throw new ExchangeException("symbol required");
        return engine.snapshot(symbol);
    }

    // ── Listener management ───────────────────────────────────────────────────

    public void addTradeListener(TradeListener l)    { listeners.add(l); }
    public void removeTradeListener(TradeListener l) { listeners.remove(l); }

    private void notifyListeners(List<Trade> trades) {
        if (trades.isEmpty()) return;
        for (TradeListener l : listeners) {
            try {
                l.onTrades(trades);
            } catch (Exception e) {
                System.err.println("[ExchangeService] listener error: " + e.getMessage());
            }
        }
    }

    @FunctionalInterface
    public interface TradeListener {
        void onTrades(List<Trade> trades);
    }
}
