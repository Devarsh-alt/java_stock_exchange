package exchange;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Automated trading simulation service.
 * Spawns configurable automated market participants submitting real orders
 * strictly through the ExchangeService and MatchingEngine.
 *
 * <p>Enforces bounded executor pools and rate-limiting to prevent runaway resource consumption.
 */
public class SimulatedTraderService {

    public record SimulationConfig(
            List<String> symbols,
            int traderCount,
            int orderRatePerSec,
            double buyRatio,
            long minQuantity,
            long maxQuantity,
            BigDecimal basePrice,
            double priceSpreadPercent,
            long seed
    ) {
        public SimulationConfig {
            Objects.requireNonNull(symbols, "symbols cannot be null");
            if (symbols.isEmpty()) throw new IllegalArgumentException("At least one symbol required");
            if (traderCount <= 0) throw new IllegalArgumentException("traderCount must be positive");
            if (orderRatePerSec <= 0) throw new IllegalArgumentException("orderRatePerSec must be positive");
            if (buyRatio < 0.0 || buyRatio > 1.0) throw new IllegalArgumentException("buyRatio must be between 0.0 and 1.0");
            if (minQuantity <= 0 || maxQuantity < minQuantity) throw new IllegalArgumentException("Invalid quantity bounds");
            Objects.requireNonNull(basePrice, "basePrice cannot be null");
        }
    }

    public record SimulationMetrics(
            long ordersSubmitted,
            long tradesExecuted,
            long executedVolume,
            long errorsCount,
            double ordersPerSecond,
            double latencyP50Micros,
            double latencyP95Micros,
            double latencyP99Micros
    ) {}

    private final ExchangeService exchangeService;
    private final SimulationConfig config;
    private final Random random;

    private final AtomicLong ordersSubmitted = new AtomicLong(0);
    private final AtomicLong tradesExecuted = new AtomicLong(0);
    private final AtomicLong executedVolume = new AtomicLong(0);
    private final AtomicLong errorsCount = new AtomicLong(0);
    private final java.util.concurrent.ConcurrentLinkedQueue<Long> latenciesMicros = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final int MAX_LATENCY_SAMPLES = 10_000;
    private volatile long startTimeMs = System.currentTimeMillis();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);

    private ScheduledExecutorService scheduler;

    public SimulatedTraderService(ExchangeService exchangeService, SimulationConfig config) {
        this.exchangeService = Objects.requireNonNull(exchangeService);
        this.config = Objects.requireNonNull(config);
        this.random = new Random(config.seed());
    }

    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            paused.set(false);
            scheduler = Executors.newScheduledThreadPool(Math.min(4, config.traderCount()));

            long periodMicros = Math.max(1000L, 1_000_000L / config.orderRatePerSec());
            scheduler.scheduleAtFixedRate(this::submitRandomOrder, 0, periodMicros, TimeUnit.MICROSECONDS);
        }
    }

    public void pause() {
        if (running.get()) {
            paused.set(true);
        }
    }

    public void resume() {
        if (running.get()) {
            paused.set(false);
        }
    }

    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            paused.set(false);
            if (scheduler != null) {
                scheduler.shutdownNow();
                try {
                    scheduler.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    /**
     * Executes a single simulated order submission step.
     */
    public void submitRandomOrder() {
        if (!running.get() || paused.get()) return;

        try {
            String symbol = config.symbols().get(random.nextInt(config.symbols().size()));
            long userId = 900000L + (random.nextInt(config.traderCount()) + 1);

            boolean isBuy = random.nextDouble() < config.buyRatio();
            OrderSide side = isBuy ? OrderSide.BUY : OrderSide.SELL;

            long qty = config.minQuantity() + (long) (random.nextDouble() * (config.maxQuantity() - config.minQuantity() + 1));
            double offset = (random.nextDouble() * 2.0 - 1.0) * (config.priceSpreadPercent() / 100.0);
            BigDecimal price = config.basePrice().multiply(BigDecimal.valueOf(1.0 + offset)).setScale(2, java.math.RoundingMode.HALF_UP);
            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                price = new BigDecimal("1.00");
            }

            // Provision simulated account balance in memory if not present
            Account acc = exchangeService.getAccountService().getOrCreateAccount(userId, new BigDecimal("10000000.00"));
            acc.depositShares(symbol, 100000L);

            String clientOrderId = "sim-" + UUID.randomUUID();
            ordersSubmitted.incrementAndGet();

            long t0 = System.nanoTime();
            List<Trade> trades = exchangeService.submitOrder(clientOrderId, userId, symbol, side, OrderType.LIMIT, price, qty);
            long elapsedMicros = (System.nanoTime() - t0) / 1000L;

            latenciesMicros.add(elapsedMicros);
            while (latenciesMicros.size() > MAX_LATENCY_SAMPLES) {
                latenciesMicros.poll();
            }

            if (!trades.isEmpty()) {
                tradesExecuted.addAndGet(trades.size());
                long vol = trades.stream().mapToLong(Trade::getQuantity).sum();
                executedVolume.addAndGet(vol);
            }
        } catch (Exception e) {
            long n = errorsCount.incrementAndGet();
            // Surface why orders are rejected: first 20 errors, then every 200th
            if (n <= 20 || n % 200 == 0) {
                System.err.println("[Simulator] order rejected (#" + n + "): " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    public SimulationMetrics getMetrics() {
        long submitted = ordersSubmitted.get();
        long elapsedSec = Math.max(1, (System.currentTimeMillis() - startTimeMs) / 1000);
        double ops = (double) submitted / (double) elapsedSec;

        List<Long> samples = new java.util.ArrayList<>(latenciesMicros);
        java.util.Collections.sort(samples);

        double p50 = 0.0, p95 = 0.0, p99 = 0.0;
        if (!samples.isEmpty()) {
            p50 = samples.get((int) (samples.size() * 0.50));
            p95 = samples.get((int) (samples.size() * 0.95));
            p99 = samples.get((int) (samples.size() * 0.99));
        }

        return new SimulationMetrics(
                submitted,
                tradesExecuted.get(),
                executedVolume.get(),
                errorsCount.get(),
                Math.round(ops * 100.0) / 100.0,
                p50,
                p95,
                p99
        );
    }

    public boolean isRunning() { return running.get(); }
    public boolean isPaused() { return paused.get(); }
}