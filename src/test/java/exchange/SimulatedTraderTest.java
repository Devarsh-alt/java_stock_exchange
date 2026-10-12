package exchange;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SimulatedTraderTest {

    @Test
    @DisplayName("SimulatedTraderService lifecycle: start, submit, pause, resume, stop, and metrics tracking")
    void testSimulatedTraderLifecycle() throws InterruptedException {
        MatchingEngine engine = new MatchingEngine();
        ExchangeService service = new ExchangeService(engine);

        SimulatedTraderService.SimulationConfig config = new SimulatedTraderService.SimulationConfig(
                List.of("RELIANCE.NS"),
                5,
                100,
                0.5,
                10L,
                50L,
                new BigDecimal("2500.00"),
                2.0,
                42L
        );

        SimulatedTraderService sim = new SimulatedTraderService(service, config);

        assertFalse(sim.isRunning());
        sim.start();
        assertTrue(sim.isRunning());

        // Run for 300 ms to generate orders and trades
        Thread.sleep(300);

        sim.pause();
        assertTrue(sim.isPaused());
        long submittedAtPause = sim.getMetrics().ordersSubmitted();

        Thread.sleep(100);
        assertEquals(submittedAtPause, sim.getMetrics().ordersSubmitted(), "Orders should not increase while paused");

        sim.resume();
        assertFalse(sim.isPaused());
        Thread.sleep(200);

        sim.stop();
        assertFalse(sim.isRunning());

        SimulatedTraderService.SimulationMetrics metrics = sim.getMetrics();
        assertTrue(metrics.ordersSubmitted() > 0, "Orders should have been submitted");
        assertEquals(0, metrics.errorsCount(), "No simulated orders should fail");
    }
}
