package exchange;

import java.util.List;
import java.util.Objects;

/**
 * Immutable execution plan produced by non-mutating speculative matching.
 *
 * @param incomingOrder the incoming order being matched
 * @param trades proposed immutable trades
 * @param restingFills proposed fill deltas for resting maker orders
 * @param incomingFill proposed fill delta for the incoming taker order (null if 0 fills)
 * @param restIncomingOnBook true if LIMIT order with remaining quantity > 0
 * @param cancelIncomingRemainder true if MARKET order with remaining quantity > 0
 */
public record StagedMatch(
        Order incomingOrder,
        List<Trade> trades,
        List<FillDelta> restingFills,
        FillDelta incomingFill,
        boolean restIncomingOnBook,
        boolean cancelIncomingRemainder
) {
    public StagedMatch {
        Objects.requireNonNull(incomingOrder, "incomingOrder cannot be null");
        trades = List.copyOf(trades != null ? trades : List.of());
        restingFills = List.copyOf(restingFills != null ? restingFills : List.of());
    }
}
