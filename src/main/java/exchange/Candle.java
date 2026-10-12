package exchange;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Historical Market Data candle (OHLCV).
 *
 * <p>CRITICAL FINANCIAL SEPARATION:
 * Historical reference prices and simulated exchange executions are separate concepts.
 * Historical data supplies reference prices and charts.
 * Reference-price changes NEVER create simulated trades or bypass the matching engine.
 */
public record Candle(
        String symbol,
        Instant timestamp,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume
) {
    public Candle {
        Objects.requireNonNull(symbol, "symbol cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        Objects.requireNonNull(open, "open cannot be null");
        Objects.requireNonNull(high, "high cannot be null");
        Objects.requireNonNull(low, "low cannot be null");
        Objects.requireNonNull(close, "close cannot be null");

        if (low.compareTo(open) > 0 || low.compareTo(close) > 0 || low.compareTo(high) > 0) {
            throw new IllegalArgumentException("Low price cannot exceed Open, Close, or High");
        }
        if (high.compareTo(open) < 0 || high.compareTo(close) < 0 || high.compareTo(low) < 0) {
            throw new IllegalArgumentException("High price cannot be less than Open, Close, or Low");
        }
        if (volume < 0) {
            throw new IllegalArgumentException("Volume cannot be negative");
        }
    }
}
