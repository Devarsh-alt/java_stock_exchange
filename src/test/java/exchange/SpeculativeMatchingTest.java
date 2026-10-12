package exchange;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Increment 3.3.1 tests: Validates pure non-mutating speculative matching,
 * immutable StagedMatch and FillDelta generation, and applyCommittedMatch behavior.
 */
class SpeculativeMatchingTest {

    static final String SYM = "RELIANCE.NS";
    static final long USER_BUYER = 101L;
    static final long USER_SELLER = 202L;

    MatchingEngine engine;
    OrderBook book;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        book = new OrderBook(SYM);
    }

    static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }

    @Test
    @DisplayName("stageMatch does NOT mutate resting orders, incoming order, or order book")
    void stageMatchDoesNotMutateState() {
        // Place resting sell order
        Order restingSell = new Order("s1", USER_SELLER, SYM, OrderSide.SELL, OrderType.LIMIT, bd("100"), 10, 1L);
        restingSell.open();
        book.addLimitOrder(restingSell);

        // Prepare incoming buy order
        Order incomingBuy = new Order("b1", USER_BUYER, SYM, OrderSide.BUY, OrderType.LIMIT, bd("100"), 6, 2L);

        // Pre-check invariant
        assertEquals(10L, restingSell.getRemainingQty());
        assertEquals(0L, restingSell.getFilledQty());
        assertEquals(OrderStatus.OPEN, restingSell.getStatus());
        assertEquals(6L, incomingBuy.getRemainingQty());
        assertEquals(0L, incomingBuy.getFilledQty());
        assertEquals(OrderStatus.NEW, incomingBuy.getStatus());
        assertEquals(1, book.size());

        // Perform speculative match
        StagedMatch staged = engine.stageMatch(incomingBuy, book);

        // Verify proposed plan
        assertNotNull(staged);
        assertEquals(1, staged.trades().size());
        assertEquals(6L, staged.trades().get(0).getQuantity());
        assertEquals(bd("100"), staged.trades().get(0).getPrice());
        assertEquals(1, staged.restingFills().size());
        FillDelta restingDelta = staged.restingFills().get(0);
        assertEquals(4L, restingDelta.newRemainingQty());
        assertEquals(6L, restingDelta.newFilledQty());
        assertEquals(OrderStatus.PARTIALLY_FILLED, restingDelta.newStatus());
        assertFalse(restingDelta.evictFromBook());

        // CRITICAL: Verify live objects and book remain COMPLETELY UNMUTATED
        assertEquals(10L, restingSell.getRemainingQty(), "Resting order remainingQty must be untouched");
        assertEquals(0L, restingSell.getFilledQty(), "Resting order filledQty must be untouched");
        assertEquals(OrderStatus.OPEN, restingSell.getStatus(), "Resting order status must be untouched");

        assertEquals(6L, incomingBuy.getRemainingQty(), "Incoming order remainingQty must be untouched");
        assertEquals(0L, incomingBuy.getFilledQty(), "Incoming order filledQty must be untouched");
        assertEquals(OrderStatus.NEW, incomingBuy.getStatus(), "Incoming order status must be untouched");

        assertEquals(1, book.size(), "OrderBook size must remain 1");
        assertNotNull(book.findById(restingSell.getId()), "Resting order must still be indexed in book");
    }

    @Test
    @DisplayName("applyCommittedMatch correctly applies full fill and evicts resting maker")
    void applyCommittedMatchFullFill() {
        Order restingSell = new Order("s1", USER_SELLER, SYM, OrderSide.SELL, OrderType.LIMIT, bd("100"), 10, 1L);
        restingSell.open();
        book.addLimitOrder(restingSell);

        Order incomingBuy = new Order("b1", USER_BUYER, SYM, OrderSide.BUY, OrderType.LIMIT, bd("100"), 10, 2L);

        StagedMatch staged = engine.stageMatch(incomingBuy, book);
        assertEquals(1, staged.trades().size());
        assertTrue(staged.restingFills().get(0).evictFromBook());
        assertFalse(staged.restIncomingOnBook());

        // Apply committed match
        engine.applyCommittedMatch(staged, book);

        // Verify resting order is filled and evicted
        assertEquals(0L, restingSell.getRemainingQty());
        assertEquals(10L, restingSell.getFilledQty());
        assertEquals(OrderStatus.FILLED, restingSell.getStatus());
        assertNull(book.findById(restingSell.getId()), "Fully filled resting order must be evicted from book index");
        assertTrue(book.isEmpty(), "OrderBook should be completely empty");

        // Verify incoming order is filled and not resting
        assertEquals(0L, incomingBuy.getRemainingQty());
        assertEquals(10L, incomingBuy.getFilledQty());
        assertEquals(OrderStatus.FILLED, incomingBuy.getStatus());
    }

    @Test
    @DisplayName("applyCommittedMatch places unfilled limit order remainder on book")
    void applyCommittedMatchLimitRemainderRestsOnBook() {
        Order restingSell = new Order("s1", USER_SELLER, SYM, OrderSide.SELL, OrderType.LIMIT, bd("100"), 5, 1L);
        restingSell.open();
        book.addLimitOrder(restingSell);

        Order incomingBuy = new Order("b1", USER_BUYER, SYM, OrderSide.BUY, OrderType.LIMIT, bd("100"), 12, 2L);

        StagedMatch staged = engine.stageMatch(incomingBuy, book);
        assertTrue(staged.restIncomingOnBook());

        engine.applyCommittedMatch(staged, book);

        // Maker is filled and removed
        assertEquals(OrderStatus.FILLED, restingSell.getStatus());
        assertNull(book.findById(restingSell.getId()));

        // Taker is partially filled and rests on book with 7 remaining
        assertEquals(7L, incomingBuy.getRemainingQty());
        assertEquals(5L, incomingBuy.getFilledQty());
        assertEquals(OrderStatus.PARTIALLY_FILLED, incomingBuy.getStatus());
        assertEquals(1, book.size());
        assertEquals(incomingBuy, book.findById(incomingBuy.getId()));
        assertEquals(bd("100"), book.bestBid());
    }

    @Test
    @DisplayName("Self-trade protection skips maker order if buy and sell order ID match")
    void selfTradeProtectionInStagedMatch() {
        Order restingSell = new Order("ord-1", USER_SELLER, SYM, OrderSide.SELL, OrderType.LIMIT, bd("100"), 10, 1L);
        restingSell.open();
        book.addLimitOrder(restingSell);

        // Incoming order with identical ID
        Order incomingBuy = new Order("ord-1", USER_BUYER, SYM, OrderSide.BUY, OrderType.LIMIT, bd("100"), 10, 2L);

        StagedMatch staged = engine.stageMatch(incomingBuy, book);
        assertTrue(staged.trades().isEmpty(), "Must not trade against self");
        assertTrue(staged.restingFills().isEmpty());
        assertTrue(staged.restIncomingOnBook());
    }
}
