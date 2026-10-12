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

    private static final String BODY = "{\"status\":\"ok\"}";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonUtil.writeOk(resp, BODY);
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonUtil.writeJson(resp, 204, "");
    }
}
