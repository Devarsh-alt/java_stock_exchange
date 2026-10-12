package exchange.api;

import exchange.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExchangeServerIntegrationTest {

    private static ExchangeServer server;
    private static int port = 18080;
    private static ExchangeService exchangeService;
    private static SimulatedTraderService simService;

    @BeforeAll
    static void setUp() throws Exception {
        MatchingEngine engine = new MatchingEngine();
        exchangeService = new ExchangeService(engine);

        // Pre-fund test account 999901
        Account acc = exchangeService.getAccountService().getOrCreateAccount(999901L, new BigDecimal("500000.00"));
        acc.depositShares("AAPL", 1000L);

        SimulatedTraderService.SimulationConfig simConfig = new SimulatedTraderService.SimulationConfig(
                List.of("AAPL"), 2, 10, 0.5, 1, 10, new BigDecimal("150.00"), 1.0, 123L
        );
        simService = new SimulatedTraderService(exchangeService, simConfig);

        server = new ExchangeServer(exchangeService, simService, port);
        server.start();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (simService != null) {
            simService.stop();
        }
        if (server != null) {
            server.stop();
        }
    }

    private static String getBaseUrl() {
        return "http://localhost:" + port;
    }

    private HttpResponse sendRequest(String method, String path, String body) throws Exception {
        URL url = URI.create(getBaseUrl() + path).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        String responseBody = is != null ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";
        return new HttpResponse(code, responseBody);
    }

    record HttpResponse(int statusCode, String body) {}

    @Test
    @DisplayName("GET /api/v1/health returns 200 ok")
    void testHealthEndpoint() throws Exception {
        HttpResponse res = sendRequest("GET", "/api/v1/health", null);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("\"status\":\"ok\""));
    }

    @Test
    @DisplayName("GET /api/v1/accounts/{userId} retrieves account balance and positions")
    void testGetAccount() throws Exception {
        HttpResponse res = sendRequest("GET", "/api/v1/accounts/999901", null);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("\"userId\":999901"));
        assertTrue(res.body().contains("\"totalCash\":\"500000.00\""));
        assertTrue(res.body().contains("\"AAPL\":1000"));
    }

    @Test
    @DisplayName("POST /api/v1/orders places order and updates orderbook")
    void testSubmitOrderAndOrderBook() throws Exception {
        Account acc = exchangeService.getAccountService().getOrCreateAccount(999911L, new BigDecimal("500000.00"));
        acc.depositShares("TEST_ORD", 1000L);

        String orderJson = "{" +
                "\"clientOrderId\":\"test-cli-1\"," +
                "\"userId\":999911," +
                "\"symbol\":\"TEST_ORD\"," +
                "\"side\":\"SELL\"," +
                "\"type\":\"LIMIT\"," +
                "\"price\":\"155.00\"," +
                "\"quantity\":50" +
                "}";

        HttpResponse orderRes = sendRequest("POST", "/api/v1/orders", orderJson);
        assertEquals(200, orderRes.statusCode());
        assertTrue(orderRes.body().contains("\"trades\":[]"));

        HttpResponse bookRes = sendRequest("GET", "/api/v1/orderbook/TEST_ORD", null);
        assertEquals(200, bookRes.statusCode());
        assertTrue(bookRes.body().contains("\"symbol\":\"TEST_ORD\""));
        assertTrue(bookRes.body().contains("\"price\":\"155.00\""));
        assertTrue(bookRes.body().contains("\"quantity\":50"));
    }

    @Test
    @DisplayName("Simulator lifecycle API start/status/stop endpoints")
    void testSimulatorEndpoints() throws Exception {
        HttpResponse startRes = sendRequest("POST", "/api/v1/simulator/start", "");
        assertEquals(200, startRes.statusCode());
        assertTrue(startRes.body().contains("\"running\":true"));

        HttpResponse statusRes = sendRequest("GET", "/api/v1/simulator/status", null);
        assertEquals(200, statusRes.statusCode());
        assertTrue(statusRes.body().contains("\"running\":true"));

        HttpResponse metricsRes = sendRequest("GET", "/api/v1/simulator/metrics", null);
        assertEquals(200, metricsRes.statusCode());
        assertTrue(metricsRes.body().contains("\"ordersSubmitted\""));

        HttpResponse stopRes = sendRequest("POST", "/api/v1/simulator/stop", "");
        assertEquals(200, stopRes.statusCode());
        assertTrue(stopRes.body().contains("\"running\":false"));
    }

    @Test
    @DisplayName("GET /api/v1/ready returns readiness and operational mode")
    void testReadinessProbe() throws Exception {
        HttpResponse res = sendRequest("GET", "/api/v1/ready", null);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("\"status\":\"ok\""));
        assertTrue(res.body().contains("\"mode\":\"in-memory\""));
    }

    @Test
    @DisplayName("GET /api/v1/trades returns committed trade tape after matching")
    void testTradeTapeEndpoint() throws Exception {
        Account seller = exchangeService.getAccountService().getOrCreateAccount(999921L, new BigDecimal("100000.00"));
        seller.depositShares("TEST_TAPE", 100L);

        Account buyer = exchangeService.getAccountService().getOrCreateAccount(999922L, new BigDecimal("100000.00"));

        // 1. Submit resting sell
        String sellOrder = "{" +
                "\"clientOrderId\":\"tape-sell-1\"," +
                "\"userId\":999921," +
                "\"symbol\":\"TEST_TAPE\"," +
                "\"side\":\"SELL\"," +
                "\"type\":\"LIMIT\"," +
                "\"price\":\"200.00\"," +
                "\"quantity\":10" +
                "}";
        HttpResponse sellRes = sendRequest("POST", "/api/v1/orders", sellOrder);
        assertEquals(200, sellRes.statusCode());

        // 2. Submit matching buy order
        String buyOrder = "{" +
                "\"clientOrderId\":\"tape-buy-1\"," +
                "\"userId\":999922," +
                "\"symbol\":\"TEST_TAPE\"," +
                "\"side\":\"BUY\"," +
                "\"type\":\"LIMIT\"," +
                "\"price\":\"200.00\"," +
                "\"quantity\":10" +
                "}";

        HttpResponse buyRes = sendRequest("POST", "/api/v1/orders", buyOrder);
        assertEquals(200, buyRes.statusCode());
        assertTrue(buyRes.body().contains("\"trades\":["));

        // 3. Verify Trade Tape endpoint returns the executed trade
        HttpResponse tapeRes = sendRequest("GET", "/api/v1/trades?symbol=TEST_TAPE&limit=10", null);
        assertEquals(200, tapeRes.statusCode());
        assertTrue(tapeRes.body().contains("\"symbol\":\"TEST_TAPE\""));
        assertTrue(tapeRes.body().contains("\"quantity\":10"));
    }

    @Test
    @DisplayName("GET /api/v1/simulator/metrics returns throughput and p50/p95/p99 latencies")
    void testMetricsPercentiles() throws Exception {
        HttpResponse res = sendRequest("GET", "/api/v1/simulator/metrics", null);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("\"latencyP50Micros\""));
        assertTrue(res.body().contains("\"latencyP95Micros\""));
        assertTrue(res.body().contains("\"latencyP99Micros\""));
        assertTrue(res.body().contains("\"ordersPerSecond\""));
    }
}
