package exchange.api;

import exchange.*;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

/**
 * Order submission and cancellation endpoint.
 *
 * <pre>
 *   POST   /api/v1/orders
 *     Body: { "clientOrderId":"...", "userId":123, "symbol":"RELIANCE.NS",
 *             "side":"BUY", "type":"LIMIT", "price":"2500.00", "quantity":10 }
 *     → 200 { "trades": [...] }
 *
 *   DELETE /api/v1/orders/{orderId}?userId={userId}
 *     → 200 { "cancelled": true }
 *
 *   GET    /api/v1/orders/{orderId}/trades
 *     → 200 { "trades": [...] }   (DB-backed path; returns [] if no DB configured)
 * </pre>
 */
public class OrderServlet extends HttpServlet {

    private final ExchangeService exchangeService;

    public OrderServlet(ExchangeService exchangeService) {
        this.exchangeService = exchangeService;
    }

    // ── POST /api/v1/orders ────────────────────────────────────────────────────

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String body = JsonUtil.readBody(req);

        String clientOrderId = JsonUtil.extractString(body, "clientOrderId");
        String userIdStr     = JsonUtil.extractNumber(body, "userId");
        String symbol        = JsonUtil.extractString(body, "symbol");
        String sideStr       = JsonUtil.extractString(body, "side");
        String typeStr       = JsonUtil.extractString(body, "type");
        String priceStr      = JsonUtil.extractNumber(body, "price");
        String qtyStr        = JsonUtil.extractNumber(body, "quantity");

        // Validate required fields
        if (isBlank(clientOrderId) || isBlank(userIdStr) || isBlank(symbol)
                || isBlank(sideStr) || isBlank(typeStr) || isBlank(qtyStr)) {
            JsonUtil.writeError(resp, 400,
                    "Required fields: clientOrderId, userId, symbol, side, type, quantity");
            return;
        }

        long userId;
        BigDecimal price = null;
        long quantity;
        OrderSide side;
        OrderType type;

        try {
            userId   = Long.parseLong(userIdStr.strip());
            quantity = Long.parseLong(qtyStr.strip());
            side     = OrderSide.valueOf(sideStr.strip().toUpperCase());
            type     = OrderType.valueOf(typeStr.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            JsonUtil.writeError(resp, 400, "Invalid field value: " + e.getMessage());
            return;
        }

        if (type == OrderType.LIMIT) {
            if (isBlank(priceStr)) {
                JsonUtil.writeError(resp, 400, "price is required for LIMIT orders");
                return;
            }
            try {
                price = new BigDecimal(priceStr.strip());
            } catch (NumberFormatException e) {
                JsonUtil.writeError(resp, 400, "Invalid price: " + priceStr);
                return;
            }
        }

        if (quantity <= 0) {
            JsonUtil.writeError(resp, 400, "quantity must be positive");
            return;
        }

        try {
            List<Trade> trades = exchangeService.submitOrder(
                    clientOrderId, userId, symbol, side, type, price, quantity);
            JsonUtil.writeOk(resp, buildTradesJson(trades));
        } catch (ExchangeException e) {
            JsonUtil.writeError(resp, 422, e.getMessage());
        } catch (Exception e) {
            JsonUtil.writeError(resp, 500, "Internal error: " + e.getMessage());
        }
    }

    // ── DELETE /api/v1/orders/{orderId} ────────────────────────────────────────

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo(); // e.g. "/uuid-here" or "/uuid-here/trades"
        if (isBlank(pathInfo) || pathInfo.equals("/")) {
            JsonUtil.writeError(resp, 400, "orderId required in path");
            return;
        }

        String[] parts = pathInfo.split("/");
        String orderId = parts.length > 1 ? parts[1] : null;
        if (isBlank(orderId)) {
            JsonUtil.writeError(resp, 400, "orderId required in path");
            return;
        }

        String symbol = req.getParameter("symbol");
        if (isBlank(symbol)) {
            JsonUtil.writeError(resp, 400, "symbol query parameter required");
            return;
        }

        try {
            boolean cancelled = exchangeService.cancelOrder(symbol, orderId);
            JsonUtil.writeOk(resp, "{\"cancelled\":" + cancelled + "}");
        } catch (ExchangeException e) {
            JsonUtil.writeError(resp, 422, e.getMessage());
        } catch (Exception e) {
            JsonUtil.writeError(resp, 500, "Internal error: " + e.getMessage());
        }
    }

    // ── GET /api/v1/orders/{orderId}/trades ────────────────────────────────────

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if (isBlank(pathInfo)) {
            JsonUtil.writeError(resp, 400, "Path required: /orders/{orderId}/trades");
            return;
        }
        // Expect: /{orderId}/trades
        String[] parts = pathInfo.split("/");
        if (parts.length < 3 || !"trades".equals(parts[2])) {
            JsonUtil.writeError(resp, 400, "Path must be /orders/{orderId}/trades");
            return;
        }
        String orderId = parts[1];
        // Trade history requires DB; in-memory path returns empty list with a note
        JsonUtil.writeOk(resp, "{\"orderId\":" + JsonUtil.escapeString(orderId) + ",\"trades\":[]}");
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonUtil.writeJson(resp, 204, "");
    }

    // ── Serialisation ──────────────────────────────────────────────────────────

    static String buildTradesJson(List<Trade> trades) {
        StringBuilder sb = new StringBuilder("{\"trades\":[");
        for (int i = 0; i < trades.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(tradeToJson(trades.get(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    static String tradeToJson(Trade t) {
        return "{" +
                "\"id\":"          + JsonUtil.escapeString(t.getId()) + "," +
                "\"symbol\":"      + JsonUtil.escapeString(t.getSymbol()) + "," +
                "\"price\":"       + JsonUtil.escapeString(t.getPrice().toPlainString()) + "," +
                "\"quantity\":"    + t.getQuantity() + "," +
                "\"buyUserId\":"   + t.getBuyUserId() + "," +
                "\"sellUserId\":"  + t.getSellUserId() + "," +
                "\"buyOrderId\":"  + JsonUtil.escapeString(t.getBuyOrderId()) + "," +
                "\"sellOrderId\":" + JsonUtil.escapeString(t.getSellOrderId()) + "," +
                "\"executedAt\":"  + JsonUtil.escapeString(t.getExecutedAt().toString()) +
                "}";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
