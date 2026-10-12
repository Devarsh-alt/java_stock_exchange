package exchange;

/**
 * Type of an order.
 *   LIMIT  – execute at specified price or better; rest on book if unfilled.
 *   MARKET – execute immediately at best price; cancel any unfilled remainder.
 */
public enum OrderType { LIMIT, MARKET }
