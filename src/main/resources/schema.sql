-- ============================================================================
-- Stock Exchange Simulator - Authoritative MySQL 8 / InnoDB Persistence Schema
-- ============================================================================

-- 0. Sequence Generator: Monotonic, unique sequence allocator
CREATE TABLE IF NOT EXISTS sequence_generator (
    sequence_name VARCHAR(32) PRIMARY KEY,
    next_val      BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO sequence_generator (sequence_name, next_val) VALUES ('order_arrival_seq', 0);

-- 1. Accounts: User cash balance and committed order reservations
CREATE TABLE IF NOT EXISTS accounts (
    user_id          BIGINT PRIMARY KEY,
    total_cash       DECIMAL(19, 4) NOT NULL,
    reserved_cash    DECIMAL(19, 4) NOT NULL DEFAULT 0.0000,
    created_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_cash_bounds CHECK (
        total_cash >= 0.0000 AND 
        reserved_cash >= 0.0000 AND 
        reserved_cash <= total_cash
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. Holdings: Per-symbol share inventory and open sell reservations
CREATE TABLE IF NOT EXISTS holdings (
    user_id          BIGINT NOT NULL,
    symbol           VARCHAR(20) NOT NULL,
    owned_shares     BIGINT NOT NULL,
    reserved_shares  BIGINT NOT NULL DEFAULT 0,
    created_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, symbol),
    FOREIGN KEY (user_id) REFERENCES accounts(user_id),
    CONSTRAINT chk_shares_bounds CHECK (
        owned_shares >= 0 AND 
        reserved_shares >= 0 AND 
        reserved_shares <= owned_shares
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. Orders: Persistent order state, arrival sequences, and price levels
CREATE TABLE IF NOT EXISTS orders (
    id               VARCHAR(36) PRIMARY KEY,
    client_order_id  VARCHAR(64) NOT NULL,
    user_id          BIGINT NOT NULL,
    symbol           VARCHAR(20) NOT NULL,
    side             ENUM('BUY', 'SELL') NOT NULL,
    type             ENUM('LIMIT', 'MARKET') NOT NULL,
    price            DECIMAL(19, 4) NULL,                -- NULL for MARKET
    reserved_price   DECIMAL(19, 4) NULL,                -- Unit cash reserved for BUY limit
    original_qty     BIGINT NOT NULL,
    remaining_qty    BIGINT NOT NULL,
    filled_qty       BIGINT NOT NULL DEFAULT 0,
    arrival_seq      BIGINT NOT NULL,
    status           ENUM('NEW', 'OPEN', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'REJECTED') NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    FOREIGN KEY (user_id) REFERENCES accounts(user_id),
    UNIQUE KEY uk_arrival_seq (arrival_seq),
    INDEX idx_symbol_status_seq (symbol, status, price, arrival_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. Idempotency Records: Prevents duplicate client requests across retries
CREATE TABLE IF NOT EXISTS idempotency_records (
    user_id          BIGINT NOT NULL,
    client_order_id  VARCHAR(64) NOT NULL,
    request_hash     CHAR(64) NOT NULL,                  -- SHA-256 fingerprint of order parameters
    order_id         VARCHAR(36) NOT NULL,
    response_status  VARCHAR(20) NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, client_order_id),
    FOREIGN KEY (order_id) REFERENCES orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. Trades: Immutable execution ledger with maker fill sequence uniqueness
CREATE TABLE IF NOT EXISTS trades (
    id               VARCHAR(36) PRIMARY KEY,
    symbol           VARCHAR(20) NOT NULL,
    buy_order_id     VARCHAR(36) NOT NULL,
    sell_order_id    VARCHAR(36) NOT NULL,
    maker_order_id   VARCHAR(36) NOT NULL,
    maker_fill_seq   INT NOT NULL,                       -- 1, 2, 3... per resting maker order
    buy_user_id      BIGINT NOT NULL,
    sell_user_id     BIGINT NOT NULL,
    price            DECIMAL(19, 4) NOT NULL,
    quantity         BIGINT NOT NULL,
    executed_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (buy_order_id) REFERENCES orders(id),
    FOREIGN KEY (sell_order_id) REFERENCES orders(id),
    FOREIGN KEY (buy_user_id) REFERENCES accounts(user_id),
    FOREIGN KEY (sell_user_id) REFERENCES accounts(user_id),
    CONSTRAINT uk_maker_fill UNIQUE (maker_order_id, maker_fill_seq),
    CONSTRAINT chk_trade_positive CHECK (price > 0.0000 AND quantity > 0),
    CONSTRAINT chk_distinct_parties CHECK (buy_order_id <> sell_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
