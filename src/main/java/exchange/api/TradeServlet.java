package exchange.api;

import exchange.*;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Trade history endpoint (stub for in-memory path; DB-backed path requires DB connection).
 *
 * <pre>
 *   GET /api/v1/trades?symbol={symbol}&userId={userId}
 *   → 200 { "trades": [] }   (in-memory path; DB-backed path requires env DB_URL)
 * </pre>
 */
public class TradeServlet extends HttpServlet {

    private final ExchangeService exchangeService;

    public TradeServlet(ExchangeService exchangeService) {
        this.exchangeService = exchangeService;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // In-memory path: no trade store is persisted in memory between requests.
        // Return an empty list with a note. DB-backed path will be added when DB is wired.
        String symbol = req.getParameter("symbol");
        String userId = req.getParameter("userId");

        StringBuilder sb = new StringBuilder("{");
        if (symbol != null) sb.append("\"symbol\":").append(JsonUtil.escapeString(symbol)).append(",");
        if (userId != null) sb.append("\"userId\":").append(JsonUtil.escapeString(userId)).append(",");
        sb.append("\"trades\":[]}");

        JsonUtil.writeOk(resp, sb.toString());
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonUtil.writeJson(resp, 204, "");
    }
}
