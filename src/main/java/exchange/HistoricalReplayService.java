package exchange;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Service to replay historical market reference data at controllable speeds.
 *
 * <p>CRITICAL FINANCIAL NOTE:
 * Emitting a candle updates reference market display only.
 * It NEVER creates trades or bypasses order matching.
 */
public class HistoricalReplayService {

    public enum Status { STOPPED, RUNNING, PAUSED, COMPLETED }

    private final String symbol;
    private final List<Candle> dataset;
    private final CopyOnWriteArrayList<Consumer<Candle>> candleListeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger currentIndex = new AtomicInteger(0);
    private volatile Status status = Status.STOPPED;

    public HistoricalReplayService(String symbol, List<Candle> dataset) {
        this.symbol = Objects.requireNonNull(symbol);
        this.dataset = List.copyOf(dataset);
    }

    public void addCandleListener(Consumer<Candle> listener) {
        candleListeners.add(listener);
    }

    public void start() {
        currentIndex.set(0);
        status = Status.RUNNING;
    }

    public void pause() {
        if (status == Status.RUNNING) {
            status = Status.PAUSED;
        }
    }

    public void resume() {
        if (status == Status.PAUSED) {
            status = Status.RUNNING;
        }
    }

    public void stop() {
        status = Status.STOPPED;
        currentIndex.set(0);
    }

    /**
     * Advances replay by one step deterministically. Safe for testing and tick loops.
     * @return true if a candle was emitted, false if finished or not running
     */
    public boolean step() {
        if (status != Status.RUNNING) {
            return false;
        }

        int idx = currentIndex.getAndIncrement();
        if (idx < dataset.size()) {
            Candle candle = dataset.get(idx);
            for (Consumer<Candle> l : candleListeners) {
                try {
                    l.accept(candle);
                } catch (Exception e) {
                    System.err.println("[HistoricalReplayService] Listener error: " + e.getMessage());
                }
            }
            if (idx == dataset.size() - 1) {
                status = Status.COMPLETED;
            }
            return true;
        } else {
            status = Status.COMPLETED;
            return false;
        }
    }

    public Status getStatus() { return status; }
    public int getCurrentIndex() { return currentIndex.get(); }
    public int getTotalCount() { return dataset.size(); }
    public String getSymbol() { return symbol; }
}
