package exchange.api;

import exchange.SimulatedTraderService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Simulator lifecycle and metrics endpoint.
 *
 * <pre>
 *   POST /api/v1/simulator/start    — Start simulated traders (idempotent)
 *   POST /api/v1/simulator/pause    — Pause order generation
 *   POST /api/v1/simulator/resume   — Resume order generation
 *   POST /api/v1/simulator/stop     — Stop simulated traders (idempotent)
 *   GET  /api/v1/simulator/metrics  — Current metrics snapshot
 *   GET  /api/v1/simulator/status   — Running/paused/stopped
 * </pre>
 *
 * Returns 503 if no {@link SimulatedTraderService} is configured.
 */
public class SimulatorServlet extends HttpServlet {

    private final SimulatedTraderService sim;

    public SimulatorServlet(SimulatedTraderService sim) {
        this.sim = sim; // may be null
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (sim == null) {
            JsonUtil.writeError(resp, 503, "Simulator not configured");
            return;
        }

        String pathInfo = req.getPathInfo();
        if (pathInfo == null) pathInfo = "/";

        try {
            switch (pathInfo) {
                case "/start"  -> { sim.start();  JsonUtil.writeOk(resp, "{\"action\":\"start\",\"running\":true}"); }
                case "/pause"  -> { sim.pause();  JsonUtil.writeOk(resp, "{\"action\":\"pause\",\"paused\":true}"); }
                case "/resume" -> { sim.resume(); JsonUtil.writeOk(resp, "{\"action\":\"resume\",\"paused\":false}"); }
                case "/stop"   -> { sim.stop();   JsonUtil.writeOk(resp, "{\"action\":\"stop\",\"running\":false}"); }
                default -> JsonUtil.writeError(resp, 400, "Unknown action: " + pathInfo);
            }
        } catch (Exception e) {
            JsonUtil.writeError(resp, 500, "Simulator error: " + e.getMessage());
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (sim == null) {
            JsonUtil.writeError(resp, 503, "Simulator not configured");
            return;
        }

        String pathInfo = req.getPathInfo();
        if (pathInfo == null) pathInfo = "/metrics";

        switch (pathInfo) {
            case "/metrics" -> {
                SimulatedTraderService.SimulationMetrics m = sim.getMetrics();
                JsonUtil.writeOk(resp, "{" +
                        "\"ordersSubmitted\":"   + m.ordersSubmitted()   + "," +
                        "\"tradesExecuted\":"    + m.tradesExecuted()    + "," +
                        "\"executedVolume\":"    + m.executedVolume()    + "," +
                        "\"errorsCount\":"       + m.errorsCount()       + "," +
                        "\"ordersPerSecond\":"   + m.ordersPerSecond()   + "," +
                        "\"latencyP50Micros\":"  + m.latencyP50Micros()  + "," +
                        "\"latencyP95Micros\":"  + m.latencyP95Micros()  + "," +
                        "\"latencyP99Micros\":"  + m.latencyP99Micros()  + "}");
            }
            case "/status" -> {
                JsonUtil.writeOk(resp, "{" +
                        "\"running\":" + sim.isRunning() + "," +
                        "\"paused\":"  + sim.isPaused()  + "}");
            }
            default -> JsonUtil.writeError(resp, 400, "Unknown path: " + pathInfo);
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonUtil.writeJson(resp, 204, "");
    }
}
