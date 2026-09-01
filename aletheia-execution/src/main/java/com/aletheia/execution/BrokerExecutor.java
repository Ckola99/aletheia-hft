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

	/**
	 * Fetches all currently-open trades from the broker.
	 *
	 * Each returned trade carries the clientExtensions ID we set at placement
	 * (our ManagedOrder id), so the caller can match a broker trade back to the
	 * ManagedOrder that created it — no guessing.
	 *
	 * This is a READ-ONLY call: it never modifies any position.
	 *
	 * @return list of open-trade snapshots; empty list if none are open or the
	 *         call fails (never null).
	 */
	java.util.List<BrokerTrade> getOpenTrades();

	/**
	 * A snapshot of an open trade as the broker currently reports it.
	 *
	 * @param brokerTradeId the broker's own trade ID — needed for close/modify
	 *                      calls
	 * @param clientId      the clientExtensions ID we set at placement (our
	 *                      ManagedOrder id),
	 *                      or null if the trade has none
	 * @param instrument    e.g. "EUR_USD"
	 * @param currentUnits  signed units still open (shrinks after a partial close;
	 *                      positive = long, negative = short)
	 * @param openPrice     the fill price, as a scaled long
	 */
	record BrokerTrade(
			String brokerTradeId,
			String clientId,
			String instrument,
			long currentUnits,
			long openPrice) {
	}

	/**
	 * Fetches the current market price for an instrument.
	 *
	 * READ-ONLY. Returns the mid-price as a scaled long, or empty on failure.
	 *
	 * @param instrument e.g. "EUR_USD"
	 */
	java.util.Optional<Long> getCurrentPrice(String instrument);
}
