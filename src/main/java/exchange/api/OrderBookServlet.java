package exchange.api;

import exchange.*;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;


/**
 * Order-book snapshot endpoint.
 *
 * <pre>
 *   GET /api/v1/orderbook/{symbol}
 *   → 200 {
 *       "symbol": "RELIANCE.NS",
 *       "bids": [{"price":"2510.00","quantity":100}, ...],
 *       "asks": [{"price":"2511.00","quantity":50},  ...]
 *     }
 * </pre>
 *
 * Bids are returned in descending price order (highest first).
 * Asks are returned in ascending price order (lowest first).
 */
public class OrderBookServlet extends HttpServlet {

    private final ExchangeService exchangeService;

    public OrderBookServlet(ExchangeService exchangeService) {
        this.exchangeService = exchangeService;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/") || pathInfo.isBlank()) {
            JsonUtil.writeError(resp, 400, "symbol required in path: /orderbook/{symbol}");
            return;
        }

        String symbol = pathInfo.substring(1); // strip leading '/'
        if (symbol.isBlank()) {
            JsonUtil.writeError(resp, 400, "symbol cannot be blank");
            return;
        }

        try {
            OrderBookSnapshot snap = exchangeService.getSnapshot(symbol);
            JsonUtil.writeOk(resp, snapshotToJson(snap));
        } catch (ExchangeException e) {
            JsonUtil.writeError(resp, 422, e.getMessage());
        } catch (Exception e) {
            JsonUtil.writeError(resp, 500, "Internal error: " + e.getMessage());
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonUtil.writeJson(resp, 204, "");
    }

    // ── Serialisation ──────────────────────────────────────────────────────────

    private static String snapshotToJson(OrderBookSnapshot snap) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"symbol\":").append(JsonUtil.escapeString(snap.getSymbol()));
        sb.append(",\"bids\":");
        sb.append(sideToJson(snap.getBids()));
        sb.append(",\"asks\":");
        sb.append(sideToJson(snap.getAsks()));
        sb.append("}");
        return sb.toString();
    }

    /**
     * Aggregates adjacent orders at the same price into a single price level.
     * Input list is already sorted (bids: desc, asks: asc).
     */
    private static String sideToJson(java.util.List<Order> orders) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        java.math.BigDecimal currentPrice = null;
        long levelQty = 0;

        for (Order o : orders) {
            if (o.getRemainingQty() <= 0) continue;
            if (currentPrice == null || currentPrice.compareTo(o.getPrice()) != 0) {
                if (currentPrice != null && levelQty > 0) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"price\":").append(JsonUtil.escapeString(currentPrice.toPlainString()));
                    sb.append(",\"quantity\":").append(levelQty).append("}");
                }
                currentPrice = o.getPrice();
                levelQty = o.getRemainingQty();
            } else {
                levelQty += o.getRemainingQty();
            }
        }
        // Flush last level
        if (currentPrice != null && levelQty > 0) {
            if (!first) sb.append(',');
            sb.append("{\"price\":").append(JsonUtil.escapeString(currentPrice.toPlainString()));
            sb.append(",\"quantity\":").append(levelQty).append("}");
        }

        sb.append("]");
        return sb.toString();
    }
}

