package exchange.api;

import exchange.*;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Account management endpoint.
 *
 * <pre>
 *   GET  /api/v1/accounts/{userId}
 *   → 200 {
 *       "userId": 1001,
 *       "totalCash": "100000.00",
 *       "reservedCash": "5000.00",
 *       "availableCash": "95000.00",
 *       "positions": { "RELIANCE.NS": 500, "TCS.NS": 100 }
 *     }
 *
 *   POST /api/v1/accounts
 *   Body: { "userId": 1001, "initialCash": "100000.00" }
 *   → 200 { "userId": 1001, "created": true }
 *
 *   POST /api/v1/accounts/{userId}/deposit/cash
 *   Body: { "amount": "5000.00" }
 *   → 200 { "totalCash": "105000.00" }
 *
 *   POST /api/v1/accounts/{userId}/deposit/shares
 *   Body: { "symbol": "RELIANCE.NS", "quantity": 100 }
 *   → 200 { "owned": 600 }
 * </pre>
 */
public class AccountServlet extends HttpServlet {

    private final ExchangeService exchangeService;

    public AccountServlet(ExchangeService exchangeService) {
        this.exchangeService = exchangeService;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if (isBlank(pathInfo) || pathInfo.equals("/")) {
            JsonUtil.writeError(resp, 400, "userId required in path: /accounts/{userId}");
            return;
        }

        String[] parts = pathInfo.split("/");
        String userIdStr = parts.length > 1 ? parts[1] : null;
        if (isBlank(userIdStr)) {
            JsonUtil.writeError(resp, 400, "userId required in path");
            return;
        }

        long userId;
        try {
            userId = Long.parseLong(userIdStr.strip());
        } catch (NumberFormatException e) {
            JsonUtil.writeError(resp, 400, "Invalid userId");
            return;
        }

        AccountService accountService = exchangeService.getAccountService();
        Account account = accountService.getAccount(userId);
        if (account == null) {
            JsonUtil.writeError(resp, 404, "Account not found for userId: " + userId);
            return;
        }

        JsonUtil.writeOk(resp, accountToJson(account));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        String body = JsonUtil.readBody(req);

        // Route based on path: /accounts, /accounts/{userId}/deposit/cash, /accounts/{userId}/deposit/shares
        if (isBlank(pathInfo) || pathInfo.equals("/")) {
            handleCreateAccount(body, resp);
            return;
        }

        String[] parts = pathInfo.split("/");
        // /accounts/{userId}/deposit/cash  → parts: ["","userId","deposit","cash"]
        if (parts.length >= 4 && "deposit".equals(parts[2])) {
            String userIdStr = parts[1];
            String depositType = parts[3];
            long userId;
            try {
                userId = Long.parseLong(userIdStr.strip());
            } catch (NumberFormatException e) {
                JsonUtil.writeError(resp, 400, "Invalid userId");
                return;
            }

            if ("cash".equals(depositType)) {
                handleDepositCash(userId, body, resp);
            } else if ("shares".equals(depositType)) {
                handleDepositShares(userId, body, resp);
            } else {
                JsonUtil.writeError(resp, 400, "Unknown deposit type: " + depositType);
            }
            return;
        }

        JsonUtil.writeError(resp, 400, "Unrecognised path: " + pathInfo);
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonUtil.writeJson(resp, 204, "");
    }

    // ── Handlers ───────────────────────────────────────────────────────────────

    private void handleCreateAccount(String body, HttpServletResponse resp) throws IOException {
        String userIdStr     = JsonUtil.extractNumber(body, "userId");
        String initialCashStr = JsonUtil.extractNumber(body, "initialCash");

        if (isBlank(userIdStr)) {
            JsonUtil.writeError(resp, 400, "userId required");
            return;
        }

        long userId;
        BigDecimal initialCash;
        try {
            userId = Long.parseLong(userIdStr.strip());
            initialCash = initialCashStr != null ? new BigDecimal(initialCashStr.strip()) : BigDecimal.ZERO;
        } catch (NumberFormatException e) {
            JsonUtil.writeError(resp, 400, "Invalid numeric value: " + e.getMessage());
            return;
        }

        try {
            exchangeService.getAccountService().createAccount(userId, initialCash);
            JsonUtil.writeOk(resp, "{\"userId\":" + userId + ",\"created\":true}");
        } catch (ExchangeException e) {
            JsonUtil.writeError(resp, 409, e.getMessage());
        }
    }

    private void handleDepositCash(long userId, String body, HttpServletResponse resp) throws IOException {
        String amountStr = JsonUtil.extractNumber(body, "amount");
        if (isBlank(amountStr)) {
            JsonUtil.writeError(resp, 400, "amount required");
            return;
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(amountStr.strip());
        } catch (NumberFormatException e) {
            JsonUtil.writeError(resp, 400, "Invalid amount");
            return;
        }

        try {
            exchangeService.getAccountService().depositCash(userId, amount);
            Account acc = exchangeService.getAccountService().getAccount(userId);
            JsonUtil.writeOk(resp, "{\"totalCash\":" + JsonUtil.escapeString(acc.getTotalCash().toPlainString()) + "}");
        } catch (ExchangeException e) {
            JsonUtil.writeError(resp, 404, e.getMessage());
        }
    }

    private void handleDepositShares(long userId, String body, HttpServletResponse resp) throws IOException {
        String symbol = JsonUtil.extractString(body, "symbol");
        String qtyStr = JsonUtil.extractNumber(body, "quantity");

        if (isBlank(symbol) || isBlank(qtyStr)) {
            JsonUtil.writeError(resp, 400, "symbol and quantity required");
            return;
        }

        long qty;
        try {
            qty = Long.parseLong(qtyStr.strip());
        } catch (NumberFormatException e) {
            JsonUtil.writeError(resp, 400, "Invalid quantity");
            return;
        }

        try {
            exchangeService.getAccountService().depositShares(userId, symbol, qty);
            Account acc = exchangeService.getAccountService().getAccount(userId);
            long owned = acc == null ? 0 : acc.getOwnedShares(symbol);
            JsonUtil.writeOk(resp, "{\"symbol\":" + JsonUtil.escapeString(symbol) + ",\"owned\":" + owned + "}");
        } catch (ExchangeException e) {
            JsonUtil.writeError(resp, 404, e.getMessage());
        }
    }

    // ── Serialisation ──────────────────────────────────────────────────────────

    private static String accountToJson(Account acc) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"userId\":").append(acc.getUserId()).append(",");
        sb.append("\"totalCash\":").append(JsonUtil.escapeString(acc.getTotalCash().toPlainString())).append(",");
        sb.append("\"reservedCash\":").append(JsonUtil.escapeString(acc.getReservedCash().toPlainString())).append(",");
        sb.append("\"availableCash\":").append(JsonUtil.escapeString(acc.getAvailableCash().toPlainString())).append(",");
        sb.append("\"positions\":{");
        Map<String, Long> positions = acc.getAllOwnedShares();
        boolean first = true;
        for (Map.Entry<String, Long> e : positions.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(JsonUtil.escapeString(e.getKey())).append(':').append(e.getValue());
        }
        sb.append("}}");
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
