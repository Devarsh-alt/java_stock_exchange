package exchange;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads and validates historical OHLCV candles from CSV format.
 * Validates header structure, timestamp ordering, numeric formats, and OHLC relationships.
 */
public class CsvMarketDataProvider {

    /**
     * Loads candles for the given symbol from a CSV file.
     */
    public List<Candle> loadFromPath(String symbol, Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            return loadFromReader(symbol, reader);
        }
    }

    /**
     * Loads candles from a string containing CSV data.
     */
    public List<Candle> loadFromString(String symbol, String csvContent) throws IOException {
        try (BufferedReader reader = new BufferedReader(new StringReader(csvContent))) {
            return loadFromReader(symbol, reader);
        }
    }

    public List<Candle> loadFromReader(String symbol, BufferedReader reader) throws IOException {
        String headerLine = reader.readLine();
        if (headerLine == null) {
            throw new IllegalArgumentException("CSV input is empty");
        }

        String[] headers = headerLine.split(",");
        if (headers.length < 6) {
            throw new IllegalArgumentException("Invalid CSV header format: expected timestamp,open,high,low,close,volume");
        }

        List<Candle> candles = new ArrayList<>();
        String line;
        int lineNum = 1;
        Instant lastTimestamp = null;

        while ((line = reader.readLine()) != null) {
            lineNum++;
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split(",");
            if (parts.length < 6) {
                throw new IllegalArgumentException("Malformed CSV row at line " + lineNum + ": " + line);
            }

            try {
                Instant timestamp = Instant.parse(parts[0].trim());
                if (lastTimestamp != null && !timestamp.isAfter(lastTimestamp)) {
                    throw new IllegalArgumentException("Duplicate or non-chronological timestamp at line " + lineNum + ": " + parts[0]);
                }
                lastTimestamp = timestamp;

                BigDecimal open = new BigDecimal(parts[1].trim());
                BigDecimal high = new BigDecimal(parts[2].trim());
                BigDecimal low = new BigDecimal(parts[3].trim());
                BigDecimal close = new BigDecimal(parts[4].trim());
                long volume = Long.parseLong(parts[5].trim());

                candles.add(new Candle(symbol, timestamp, open, high, low, close, volume));
            } catch (Exception e) {
                if (e instanceof IllegalArgumentException) throw (IllegalArgumentException) e;
                throw new IllegalArgumentException("Data parsing error at line " + lineNum + ": " + e.getMessage(), e);
            }
        }

        return List.copyOf(candles);
    }
}
