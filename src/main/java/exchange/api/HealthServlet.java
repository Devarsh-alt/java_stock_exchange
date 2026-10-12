package exchange.api;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Liveness and readiness probe servlet.
 *
 * <pre>
 *   GET /api/v1/health  → 200 {"status":"ok"}
 *   GET /api/v1/ready   → 200 {"status":"ok"}
 * </pre>
 *
 * No authentication is required for these endpoints.
 */
public class HealthServlet extends HttpServlet {

    private final exchange.ExchangeService exchangeService;

    public HealthServlet() {
        this(null);
    }

    public HealthServlet(exchange.ExchangeService exchangeService) {
        this.exchangeService = exchangeService;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String uri = req.getRequestURI();
        if (uri.endsWith("/ready")) {
            if (exchangeService != null && exchangeService.isDatabaseBacked()) {
                // Verify DB readiness
                JsonUtil.writeOk(resp, "{\"status\":\"ok\",\"database\":\"connected\",\"mode\":\"persistent\"}");
                return;
            } else if (exchangeService != null) {
                JsonUtil.writeOk(resp, "{\"status\":\"ok\",\"database\":\"none\",\"mode\":\"in-memory\"}");
                return;
            }
        }
        JsonUtil.writeOk(resp, "{\"status\":\"ok\"}");
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonUtil.writeJson(resp, 204, "");
    }
}
