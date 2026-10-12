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
        String symbol = req.getParameter("symbol");
        String limitStr = req.getParameter("limit");
        int limit = 50;
        if (limitStr != null && !limitStr.isBlank()) {
            try {
                limit = Math.min(200, Math.max(1, Integer.parseInt(limitStr.strip())));
            } catch (NumberFormatException ignored) {}
        }

        java.util.List<Trade> trades = exchangeService.getTradeTape(symbol, limit);
        JsonUtil.writeOk(resp, OrderServlet.buildTradesJson(trades));
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonUtil.writeJson(resp, 204, "");
    }
}
