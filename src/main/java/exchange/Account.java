package exchange;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe financial account tracking cash balances and per-symbol share holdings,
 * along with their respective reservations for open orders.
 *
 * <h2>Accounting Invariants</h2>
 * <ul>
 *   <li>totalCash >= 0</li>
 *   <li>reservedCash >= 0</li>
 *   <li>availableCash = totalCash - reservedCash >= 0</li>
 *   <li>For every symbol: ownedShares >= 0, reservedShares >= 0, availableShares = ownedShares - reservedShares >= 0</li>
 *   <li>Cash arithmetic is strictly performed using {@link BigDecimal}.</li>
 * </ul>
 */
public class Account {

    private final long userId;
    private final ReentrantLock lock = new ReentrantLock();

    private BigDecimal totalCash;
    private BigDecimal reservedCash;

    private final Map<String, Long> ownedShares = new ConcurrentHashMap<>();
    private final Map<String, Long> reservedShares = new ConcurrentHashMap<>();

    public Account(long userId, BigDecimal initialCash) {
        if (initialCash == null || initialCash.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Initial cash cannot be negative");
        }
        this.userId = userId;
        this.totalCash = initialCash;
        this.reservedCash = BigDecimal.ZERO;
    }

    public long getUserId() {
        return userId;
    }

    public ReentrantLock getLock() {
        return lock;
    }

    // ── Queries (Thread-Safe under Account Lock) ─────────────────────────────

    public BigDecimal getTotalCash() {
        lock.lock();
        try {
            return totalCash;
        } finally {
            lock.unlock();
        }
    }

    public BigDecimal getReservedCash() {
        lock.lock();
        try {
            return reservedCash;
        } finally {
            lock.unlock();
        }
    }

    public BigDecimal getAvailableCash() {
        lock.lock();
        try {
            return totalCash.subtract(reservedCash);
        } finally {
            lock.unlock();
        }
    }

    public long getOwnedShares(String symbol) {
        lock.lock();
        try {
            return ownedShares.getOrDefault(symbol, 0L);
        } finally {
            lock.unlock();
        }
    }

    public long getReservedShares(String symbol) {
        lock.lock();
        try {
            return reservedShares.getOrDefault(symbol, 0L);
        } finally {
            lock.unlock();
        }
    }

    public long getAvailableShares(String symbol) {
        lock.lock();
        try {
            return getOwnedShares(symbol) - getReservedShares(symbol);
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Long> getAllOwnedShares() {
        lock.lock();
        try {
            return Collections.unmodifiableMap(new ConcurrentHashMap<>(ownedShares));
        } finally {
            lock.unlock();
        }
    }

    // ── Deposit & Inventory Setup ─────────────────────────────────────────────

    public void depositCash(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Deposit amount must be positive");
        }
        lock.lock();
        try {
            totalCash = totalCash.add(amount);
        } finally {
            lock.unlock();
        }
    }

    public void depositShares(String symbol, long quantity) {
        Objects.requireNonNull(symbol, "Symbol required");
        if (quantity <= 0) {
            throw new IllegalArgumentException("Shares deposit quantity must be positive");
        }
        lock.lock();
        try {
            ownedShares.put(symbol, ownedShares.getOrDefault(symbol, 0L) + quantity);
        } finally {
            lock.unlock();
        }
    }

    // ── Cash Reservation & Release (Must be executed under Account Lock) ──────

    /**
     * Reserves cash for an order.
     * @return true if successfully reserved, false if available cash is insufficient.
     */
    public boolean reserveCash(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Reservation amount cannot be negative");
        }
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            return true;
        }
        lock.lock();
        try {
            BigDecimal available = totalCash.subtract(reservedCash);
            if (available.compareTo(amount) < 0) {
                return false;
            }
            reservedCash = reservedCash.add(amount);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void releaseCashReservation(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Amount cannot be negative");
        }
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        lock.lock();
        try {
            if (reservedCash.compareTo(amount) < 0) {
                throw new IllegalStateException("Cannot release " + amount + " cash; only " + reservedCash + " reserved");
            }
            reservedCash = reservedCash.subtract(amount);
        } finally {
            lock.unlock();
        }
    }

    // ── Share Reservation & Release (Must be executed under Account Lock) ─────

    /**
     * Reserves shares for a sell order.
     * @return true if successfully reserved, false if available shares are insufficient.
     */
    public boolean reserveShares(String symbol, long quantity) {
        Objects.requireNonNull(symbol, "Symbol required");
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity cannot be negative");
        }
        if (quantity == 0) {
            return true;
        }
        lock.lock();
        try {
            long owned = ownedShares.getOrDefault(symbol, 0L);
            long reserved = reservedShares.getOrDefault(symbol, 0L);
            long available = owned - reserved;
            if (available < quantity) {
                return false;
            }
            reservedShares.put(symbol, reserved + quantity);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void releaseShareReservation(String symbol, long quantity) {
        Objects.requireNonNull(symbol, "Symbol required");
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity cannot be negative");
        }
        if (quantity == 0) {
            return;
        }
        lock.lock();
        try {
            long reserved = reservedShares.getOrDefault(symbol, 0L);
            if (reserved < quantity) {
                throw new IllegalStateException("Cannot release " + quantity + " shares of " + symbol + "; only " + reserved + " reserved");
            }
            long updated = reserved - quantity;
            if (updated == 0) {
                reservedShares.remove(symbol);
            } else {
                reservedShares.put(symbol, updated);
            }
        } finally {
            lock.unlock();
        }
    }

    // ── Trade Settlement Operations (Must be executed under Account Lock) ─────

    /**
     * Settles a BUY fill for the buyer:
     * - Releases reserved cash for the fill quantity at reserved limit price.
     * - Deducts actual notional (fillQty * execPrice) from totalCash.
     * - Credits fillQty of symbol to ownedShares.
     * Any price improvement (reservedPrice > execPrice) automatically remains as available cash.
     */
    public void settleBuyFill(String symbol, BigDecimal reservedCashPerUnit, BigDecimal execPrice, long fillQty) {
        Objects.requireNonNull(symbol, "Symbol required");
        if (fillQty <= 0) {
            throw new IllegalArgumentException("Fill quantity must be positive");
        }
        BigDecimal reservedPortion = reservedCashPerUnit.multiply(BigDecimal.valueOf(fillQty));
        BigDecimal actualNotional = execPrice.multiply(BigDecimal.valueOf(fillQty));

        lock.lock();
        try {
            if (reservedCash.compareTo(reservedPortion) < 0) {
                throw new IllegalStateException("Insufficient reserved cash " + reservedCash + " to settle fill portion " + reservedPortion);
            }
            if (totalCash.compareTo(actualNotional) < 0) {
                throw new IllegalStateException("Insufficient total cash " + totalCash + " for actual notional " + actualNotional);
            }
            reservedCash = reservedCash.subtract(reservedPortion);
            totalCash = totalCash.subtract(actualNotional);
            ownedShares.put(symbol, ownedShares.getOrDefault(symbol, 0L) + fillQty);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Settles a SELL fill for the seller:
     * - Releases fillQty from reservedShares.
     * - Deducts fillQty from ownedShares.
     * - Credits actual notional (fillQty * execPrice) to totalCash.
     */
    public void settleSellFill(String symbol, BigDecimal execPrice, long fillQty) {
        Objects.requireNonNull(symbol, "Symbol required");
        if (fillQty <= 0) {
            throw new IllegalArgumentException("Fill quantity must be positive");
        }
        BigDecimal actualNotional = execPrice.multiply(BigDecimal.valueOf(fillQty));

        lock.lock();
        try {
            long reserved = reservedShares.getOrDefault(symbol, 0L);
            long owned = ownedShares.getOrDefault(symbol, 0L);
            if (reserved < fillQty) {
                throw new IllegalStateException("Insufficient reserved shares " + reserved + " for fill " + fillQty);
            }
            if (owned < fillQty) {
                throw new IllegalStateException("Insufficient owned shares " + owned + " for fill " + fillQty);
            }
            long updatedReserved = reserved - fillQty;
            if (updatedReserved == 0) {
                reservedShares.remove(symbol);
            } else {
                reservedShares.put(symbol, updatedReserved);
            }

            long updatedOwned = owned - fillQty;
            if (updatedOwned == 0) {
                ownedShares.remove(symbol);
            } else {
                ownedShares.put(symbol, updatedOwned);
            }

            totalCash = totalCash.add(actualNotional);
        } finally {
            lock.unlock();
        }
    }
}
