package exchange;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for repository models, fingerprinting, and pure logic.
 */
class RepositoryUnitTest {

    @Test
    @DisplayName("Request hash is deterministic for identical parameters")
    void deterministicRequestHash() {
        String hash1 = IdempotencyRepository.computeRequestHash(
                100L, "RELIANCE.NS", OrderSide.BUY, OrderType.LIMIT, 50, new BigDecimal("100.00")
        );
        String hash2 = IdempotencyRepository.computeRequestHash(
                100L, "RELIANCE.NS", OrderSide.BUY, OrderType.LIMIT, 50, new BigDecimal("100")
        );
        assertEquals(hash1, hash2, "Trailing zeros in price should normalize to same hash");
    }

    @Test
    @DisplayName("Request hash differs when any order parameter changes")
    void differentParametersProduceDifferentHash() {
        String baseHash = IdempotencyRepository.computeRequestHash(
                100L, "RELIANCE.NS", OrderSide.BUY, OrderType.LIMIT, 50, new BigDecimal("100.00")
        );
        String diffQty = IdempotencyRepository.computeRequestHash(
                100L, "RELIANCE.NS", OrderSide.BUY, OrderType.LIMIT, 51, new BigDecimal("100.00")
        );
        String diffPrice = IdempotencyRepository.computeRequestHash(
                100L, "RELIANCE.NS", OrderSide.BUY, OrderType.LIMIT, 50, new BigDecimal("101.00")
        );
        String diffSide = IdempotencyRepository.computeRequestHash(
                100L, "RELIANCE.NS", OrderSide.SELL, OrderType.LIMIT, 50, new BigDecimal("100.00")
        );

        assertNotEquals(baseHash, diffQty);
        assertNotEquals(baseHash, diffPrice);
        assertNotEquals(baseHash, diffSide);
    }

    @Test
    @DisplayName("AccountRecord calculations reflect available cash accurately")
    void accountRecordCalculation() {
        JdbcAccountRepository.AccountRecord record = new JdbcAccountRepository.AccountRecord(
                1L, new BigDecimal("500.00"), new BigDecimal("150.00"), new BigDecimal("350.00")
        );
        assertEquals(new BigDecimal("350.00"), record.availableCash());
        assertEquals(1L, record.userId());
    }

    @Test
    @DisplayName("HoldingRecord calculations reflect available shares accurately")
    void holdingRecordCalculation() {
        JdbcAccountRepository.HoldingRecord record = new JdbcAccountRepository.HoldingRecord(
                1L, "TCS.NS", 100L, 30L, 70L
        );
        assertEquals(70L, record.availableShares());
        assertEquals("TCS.NS", record.symbol());
    }
}
