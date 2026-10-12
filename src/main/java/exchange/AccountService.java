package exchange;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service managing accounts, portfolios, deposits, and financial reservations.
 */
public class AccountService {

    private final ConcurrentHashMap<Long, Account> accounts = new ConcurrentHashMap<>();

    public Account createAccount(long userId, BigDecimal initialCash) {
        Account account = new Account(userId, initialCash);
        Account existing = accounts.putIfAbsent(userId, account);
        if (existing != null) {
            throw new ExchangeException("Account for user " + userId + " already exists");
        }
        return account;
    }

    public Account getAccount(long userId) {
        return accounts.get(userId);
    }

    public Account getOrCreateAccount(long userId, BigDecimal defaultInitialCash) {
        return accounts.computeIfAbsent(userId, id -> new Account(id, defaultInitialCash));
    }

    public void depositCash(long userId, BigDecimal amount) {
        Account acc = accounts.get(userId);
        if (acc == null) throw new ExchangeException("Account not found for user: " + userId);
        acc.depositCash(amount);
    }

    public void depositShares(long userId, String symbol, long quantity) {
        Account acc = accounts.get(userId);
        if (acc == null) throw new ExchangeException("Account not found for user: " + userId);
        acc.depositShares(symbol, quantity);
    }

    /**
     * Executes atomic settlement for a trade between buyer and seller accounts.
     * To prevent deadlocks, accounts are locked in a deterministic order based on userId.
     */
    public void settleTrade(Trade trade, BigDecimal buyerReservedPricePerUnit) {
        Objects.requireNonNull(trade, "Trade cannot be null");
        Account buyer = accounts.get(trade.getBuyUserId());
        Account seller = accounts.get(trade.getSellUserId());

        if (buyer == null) throw new IllegalStateException("Buyer account missing for user: " + trade.getBuyUserId());
        if (seller == null) throw new IllegalStateException("Seller account missing for user: " + trade.getSellUserId());

        if (buyer.getUserId() == seller.getUserId()) {
            // Self-trade scenario if ever permitted
            buyer.getLock().lock();
            try {
                buyer.settleBuyFill(trade.getSymbol(), buyerReservedPricePerUnit, trade.getPrice(), trade.getQuantity());
                buyer.settleSellFill(trade.getSymbol(), trade.getPrice(), trade.getQuantity());
            } finally {
                buyer.getLock().unlock();
            }
            return;
        }

        Account first = buyer.getUserId() < seller.getUserId() ? buyer : seller;
        Account second = buyer.getUserId() < seller.getUserId() ? seller : buyer;

        first.getLock().lock();
        second.getLock().lock();
        try {
            buyer.settleBuyFill(trade.getSymbol(), buyerReservedPricePerUnit, trade.getPrice(), trade.getQuantity());
            seller.settleSellFill(trade.getSymbol(), trade.getPrice(), trade.getQuantity());
        } finally {
            second.getLock().unlock();
            first.getLock().unlock();
        }
    }
}
