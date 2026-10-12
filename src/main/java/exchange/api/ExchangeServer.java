package exchange.api;

import exchange.*;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import java.util.Objects;

/**
 * Jetty 12 HTTP server that exposes the ExchangeService via a RESTful JSON API.
 *
 * <p>Binds to the port specified by the {@code PORT} environment variable (default 8080).
 * All endpoints are mounted under {@code /api/v1/}.
 *
 * <h2>Endpoints</h2>
 * <pre>
 *   GET  /api/v1/health           — Liveness probe (no auth required)
 *   GET  /api/v1/ready            — Readiness probe (no auth required)
 *   POST /api/v1/orders           — Submit an order
 *   DELETE /api/v1/orders/{id}    — Cancel an order
 *   GET  /api/v1/orders/{id}/trades — Trade history for an order
 *   GET  /api/v1/orderbook/{symbol} — Current order-book snapshot
 *   GET  /api/v1/accounts/{userId}  — Account balance + positions
 *   POST /api/v1/accounts           — Create account (admin)
 *   POST /api/v1/simulator/start    — Start simulated traders
 *   POST /api/v1/simulator/stop     — Stop simulated traders
 *   GET  /api/v1/simulator/metrics  — Simulator metrics
 * </pre>
 */
public class ExchangeServer {

    private final Server server;
    private final int port;

    public ExchangeServer(ExchangeService exchangeService,
                          SimulatedTraderService simulatedTraderService,
                          int port) {
        Objects.requireNonNull(exchangeService, "exchangeService");
        this.port = port;

        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");

        // Auth filter applied to all /api/v1/* paths
        context.addFilter(ApiAuthFilter.class, "/api/v1/*", null);

        // Health / readiness — exempt from auth in ApiAuthFilter
        context.addServlet(new ServletHolder(new HealthServlet()), "/api/v1/health");
        context.addServlet(new ServletHolder(new HealthServlet()), "/api/v1/ready");

        // Core trading endpoints
        context.addServlet(new ServletHolder(new OrderServlet(exchangeService)),    "/api/v1/orders/*");
        context.addServlet(new ServletHolder(new OrderBookServlet(exchangeService)),"/api/v1/orderbook/*");
        context.addServlet(new ServletHolder(new AccountServlet(exchangeService)),  "/api/v1/accounts/*");
        context.addServlet(new ServletHolder(new TradeServlet(exchangeService)),    "/api/v1/trades/*");

        // Simulator controls
        context.addServlet(new ServletHolder(new SimulatorServlet(simulatedTraderService)), "/api/v1/simulator/*");

        server.setHandler(context);
    }

    /** Convenience constructor with {@code PORT} env-var resolution and no simulator. */
    public ExchangeServer(ExchangeService exchangeService) {
        this(exchangeService, null, resolvePort());
    }

    private static int resolvePort() {
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.isBlank()) {
            try {
                return Integer.parseInt(portEnv.trim());
            } catch (NumberFormatException e) {
                System.err.println("[ExchangeServer] Invalid PORT env var '" + portEnv + "', defaulting to 8080");
            }
        }
        return 8080;
    }

    public void start() throws Exception {
        server.start();
        System.out.println("[ExchangeServer] Listening on port " + port);
    }

    public void stop() throws Exception {
        server.stop();
    }

    public void join() throws InterruptedException {
        server.join();
    }

    public boolean isRunning() {
        return server.isRunning();
    }

    public int getPort() {
        return port;
    }
}
