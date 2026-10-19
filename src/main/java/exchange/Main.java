package exchange;

import exchange.api.ExchangeServer;

import java.math.BigDecimal;
import java.util.List;

/**
 * Main application entrypoint for the Stock Exchange server.
 *
 * <p>Bootstraps:
 * <ul>
 *   <li>MatchingEngine with default symbols</li>
 *   <li>AccountService</li>
 *   <li>Database connectivity via {@link DatabaseConfig} (if DB_URL provided; otherwise runs in-memory)</li>
 *   <li>ExchangeService with crash-recovery if DB is present</li>
 *   <li>SimulatedTraderService for background market activity (optional via SIMULATOR_ENABLED=true)</li>
 *   <li>Jetty 12 REST API Server bound to PORT (defaults to 8080)</li>
 * </ul>
 */
public class Main {
    private static final List<String> SYMBOLS = List.of("RELIANCE.BO", "TCS.BO", "HDFCBANK.BO", "ICICIBANK.BO", "INFY.BO", "ITC.BO", "LT.BO", "SBIN.BO", "BHARTIARTL.BO", "TATAMOTORS.BO", "M&M.BO", "HINDUNILVR.BO", "WIPRO.BO", "TATASTEEL.BO", "AXISBANK.BO");

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("  Java Stock Exchange Simulator - Server Starting ");
        System.out.println("==================================================");

        MatchingEngine engine = new MatchingEngine();
        AccountService accountService = new AccountService();

        ExchangeService exchangeService;
        ConnectionFactory connectionFactory = null;
        String simEnabled = System.getenv("SIMULATOR_ENABLED");
        boolean simulatorEnabled = "true".equalsIgnoreCase(simEnabled) || "1".equals(simEnabled);

        // 1. Try to initialize Database components if environment is configured
        String dbUrl = System.getenv("DB_URL");
        boolean inMemoryAllowed = "true".equalsIgnoreCase(System.getenv("IN_MEMORY_ALLOWED"));

        if (dbUrl != null && !dbUrl.isBlank()) {
            System.out.println("[Main] DB_URL detected. Configuring MySQL persistence...");
            DatabaseConfig dbConfig = DatabaseConfig.fromEnv();
            connectionFactory = new ConnectionFactory(dbConfig);

            if (!connectionFactory.testConnection()) {
                System.err.println("[Main] FATAL: Database is configured via DB_URL but connection verification failed!");
                System.err.println("[Main] Production safety violation: Refusing to silently fall back to in-memory mode.");
                System.exit(1);
            }

            System.out.println("[Main] Connected to MySQL successfully. Initializing schema if needed...");
            try {
                connectionFactory.initializeSchema("/schema.sql");
                System.out.println("[Main] Schema verified/initialized.");
            } catch (Exception ex) {
                System.out.println("[Main] Notice during schema init: " + ex.getMessage());
            }

            TransactionManager txManager = new TransactionManager(connectionFactory);
            JdbcAccountRepository jdbcAccountRepo = new JdbcAccountRepository();
            JdbcOrderRepository jdbcOrderRepo = new JdbcOrderRepository();
            JdbcTradeRepository jdbcTradeRepo = new JdbcTradeRepository();
            IdempotencyRepository idempRepo = new IdempotencyRepository();

            exchangeService = new ExchangeService(
                    engine, accountService, txManager, jdbcAccountRepo, jdbcOrderRepo, jdbcTradeRepo, idempRepo
            );

            if (simulatorEnabled) {
                // The simulator uses dedicated account IDs. Cancel their prior-run
                // simulation orders before recovery so legacy .NS orders cannot leave
                // demo cash/shares reserved while only .BO books are loaded.
                SimulatorSeeder.cancelOutstandingSimulatorOrders(connectionFactory, 900001L, 4);
            }

            System.out.println("[Main] Recovering active state for configured symbols...");
            for (String symbol : SYMBOLS) {
                try {
                    exchangeService.recoverSymbolFromDatabase(symbol);
                } catch (Exception recEx) {
                    System.err.println("[Main] Warning: Could not recover " + symbol + ": " + recEx.getMessage());
                }
            }
            System.out.println("[Main] Database-backed ExchangeService initialized successfully.");
        } else if (inMemoryAllowed) {
            System.out.println("[Main] DB_URL not set and IN_MEMORY_ALLOWED=true. Running in fast in-memory mode.");
            exchangeService = new ExchangeService(engine, accountService);
        } else {
            System.err.println("[Main] FATAL: DB_URL is not set and IN_MEMORY_ALLOWED is not true.");
            System.err.println("[Main] Production safety violation: Provide DB_URL, DB_USER, DB_PASSWORD or explicitly set IN_MEMORY_ALLOWED=true for development.");
            System.exit(1);
            return;
        }

        // 2. Pre-seed default demo accounts in memory if not already present
        Account demo1 = accountService.getOrCreateAccount(1001L, new BigDecimal("1000000.00"));
        Account demo2 = accountService.getOrCreateAccount(1002L, new BigDecimal("1000000.00"));
        // 3. Optional simulated trader background service
        SimulatedTraderService simService = null;
        if (simulatorEnabled) {
            System.out.println("[Main] SIMULATOR_ENABLED=true. Starting automated market simulation...");
            SimulatedTraderService.SimulationConfig simConfig = new SimulatedTraderService.SimulationConfig(
                    SYMBOLS,
                    4,
                    5,
                    0.5,
                    10L,
                    50L,
                    new BigDecimal("2500.00"),
                    2.0,
                    System.currentTimeMillis()
            );
            if (connectionFactory != null) {
                // DB mode: simulator users must exist in MySQL (user ids 900001..900004), not just in memory
                SimulatorSeeder.seed(connectionFactory, 900001L, simConfig.traderCount(),
                        simConfig.symbols(), new BigDecimal("10000000.00"), 100000L);
            }
            simService = new SimulatedTraderService(exchangeService, simConfig);
            simService.start();
        }

        // 4. Start Jetty HTTP API server
        int port = 8080;
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.isBlank()) {
            try {
                port = Integer.parseInt(portEnv.trim());
            } catch (NumberFormatException ignored) {}
        }

        ExchangeServer server = new ExchangeServer(exchangeService, simService, port);
        try {
            server.start();
            System.out.println("[Main] Stock Exchange server ready at http://0.0.0.0:" + port);

            // Shutdown hook for graceful termination
            SimulatedTraderService finalSimService = simService;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("[Main] Shutting down server...");
                if (finalSimService != null) finalSimService.stop();
                try {
                    server.stop();
                } catch (Exception ignored) {}
                System.out.println("[Main] Server stopped.");
            }));

            server.join();
        } catch (Exception e) {
            System.err.println("[Main] Fatal server error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}