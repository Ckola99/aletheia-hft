package com.aletheia.execution;

import java.util.Optional;

/**
 * A broker-agnostic order execution interface.
 *
 * The engine (OrderManager, KillSwitch, OrderExpiryService) talks to this
 * interface, not to any specific broker. Concrete implementations wrap a
 * particular broker's API:
 *
 * OandaOrderExecutor — OANDA REST API (demo)
 * CTraderFixExecutor — cTrader FIX API (FxPro, live) [to build]
 *
 * Swapping brokers is a matter of wiring a different implementation, with no
 * change to the strategy or order-management logic.
 *
 * All methods return a value indicating success/failure rather than throwing,
 * so the caller can react (e.g. the KillSwitch continuing to close other
 * positions even if one close fails). Prices and units are scaled longs, as
 * used throughout the engine.
 */
public interface BrokerExecutor {

	/**
	 * Places a limit order for the given managed order.
	 *
	 * @return the broker's order ID if accepted, empty if it failed.
	 */
	Optional<String> placeLimitOrder(ManagedOrder order);

	/**
	 * Closes part of an open trade.
	 *
	 * @return true if the close was accepted.
	 */
	boolean closeTrade(String tradeId, long units);

	/**
	 * Closes an open trade fully.
	 *
	 * @return true if the close was accepted.
	 */
	boolean closeTradeAll(String tradeId);

	/**
	 * Modifies the stop-loss on an open trade.
	 *
	 * @return true if the modification was accepted.
	 */
	boolean modifyStopLoss(String tradeId, long newSlPrice, String instrument);

	/**
	 * Fetches the current account balance.
	 *
	 * @return the balance if reachable, empty on failure.
	 */
	Optional<Double> getAccountBalance();

	/**
	 * Cancels a pending order.
	 *
	 * @return true if the cancel was accepted.
	 */
	boolean cancelOrder(String orderId);
}
