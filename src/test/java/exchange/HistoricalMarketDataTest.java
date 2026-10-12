package exchange;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoricalMarketDataTest {

    @Test
    @DisplayName("CsvMarketDataProvider parses valid CSV into immutable Candles")
    void testValidCsvParsing() throws IOException {
        String csv = """
                timestamp,open,high,low,close,volume
                2026-01-01T09:15:00Z,100.00,105.00,99.00,104.00,1000
                2026-01-01T09:16:00Z,104.00,106.00,103.50,105.00,1500
                """;

        CsvMarketDataProvider provider = new CsvMarketDataProvider();
        List<Candle> candles = provider.loadFromString("TEST.NS", csv);

        assertEquals(2, candles.size());
        Candle c1 = candles.get(0);
        assertEquals("TEST.NS", c1.symbol());
        assertEquals(new BigDecimal("100.00"), c1.open());
        assertEquals(new BigDecimal("105.00"), c1.high());
        assertEquals(new BigDecimal("99.00"), c1.low());
        assertEquals(new BigDecimal("104.00"), c1.close());
        assertEquals(1000L, c1.volume());
    }

    @Test
    @DisplayName("Invalid OHLC invariants or non-chronological timestamps throw descriptive exceptions")
    void testInvalidCsvData() {
        CsvMarketDataProvider provider = new CsvMarketDataProvider();

        // High lower than open
        String invalidHigh = """
                timestamp,open,high,low,close,volume
                2026-01-01T09:15:00Z,100.00,95.00,90.00,94.00,1000
                """;
        assertThrows(IllegalArgumentException.class, () -> provider.loadFromString("TEST.NS", invalidHigh));

        // Duplicate/reversed timestamp
        String nonChronological = """
                timestamp,open,high,low,close,volume
                2026-01-01T09:16:00Z,100.00,105.00,99.00,104.00,1000
                2026-01-01T09:15:00Z,104.00,106.00,103.50,105.00,1500
                """;
        assertThrows(IllegalArgumentException.class, () -> provider.loadFromString("TEST.NS", nonChronological));
    }

    @Test
    @DisplayName("HistoricalReplayService provides deterministic stepping, start, pause, resume, and stop controls")
    void testHistoricalReplayServiceLifecycle() throws IOException {
        String csv = """
                timestamp,open,high,low,close,volume
                2026-01-01T09:15:00Z,100.00,105.00,99.00,104.00,1000
                2026-01-01T09:16:00Z,104.00,106.00,103.50,105.00,1500
                """;

        List<Candle> candles = new CsvMarketDataProvider().loadFromString("TEST.NS", csv);
        HistoricalReplayService replay = new HistoricalReplayService("TEST.NS", candles);

        List<Candle> received = new ArrayList<>();
        replay.addCandleListener(received::add);

        assertEquals(HistoricalReplayService.Status.STOPPED, replay.getStatus());
        assertFalse(replay.step(), "Step while stopped should return false");

        replay.start();
        assertEquals(HistoricalReplayService.Status.RUNNING, replay.getStatus());

        assertTrue(replay.step());
        assertEquals(1, received.size());

        replay.pause();
        assertEquals(HistoricalReplayService.Status.PAUSED, replay.getStatus());
        assertFalse(replay.step(), "Step while paused should return false");

        replay.resume();
        assertTrue(replay.step());
        assertEquals(2, received.size());
        assertEquals(HistoricalReplayService.Status.COMPLETED, replay.getStatus());
    }
}
