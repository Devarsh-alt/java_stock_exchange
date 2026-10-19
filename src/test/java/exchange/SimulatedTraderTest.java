
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

        SimulatedTraderService.SimulationConfig config =
                new SimulatedTraderService.SimulationConfig(
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

        SimulatedTraderService sim =
                new SimulatedTraderService(service, config);

        assertFalse(sim.isRunning());

        sim.start();
        assertTrue(sim.isRunning());

        // Allow the simulator time to submit orders.
        Thread.sleep(300);

        sim.pause();
        assertTrue(sim.isPaused());

        /*
         * Allow any order-generation iteration already in progress
         * to finish before recording the paused baseline.
         */
        Thread.sleep(150);

        long submittedAtPause = sim.getMetrics().ordersSubmitted();

        // Once paused and settled, the order count should remain stable.
        Thread.sleep(200);

        assertEquals(
                submittedAtPause,
                sim.getMetrics().ordersSubmitted(),
                "Orders should not increase while paused"
        );

        sim.resume();
        assertFalse(sim.isPaused());

        Thread.sleep(200);

        sim.stop();
        assertFalse(sim.isRunning());

        SimulatedTraderService.SimulationMetrics metrics = sim.getMetrics();

        assertTrue(
                metrics.ordersSubmitted() > 0,
                "Orders should have been submitted"
        );

        assertEquals(
                0,
                metrics.errorsCount(),
                "No simulated orders should fail"
        );
    }
}
