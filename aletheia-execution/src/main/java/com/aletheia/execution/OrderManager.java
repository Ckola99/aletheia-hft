package com.aletheia.execution;

import com.aletheia.core.MarketBias;
import com.aletheia.core.PriceScale;
import com.aletheia.strategy.TradeSignal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Manages the lifecycle of all active orders and positions.
 *
 * Responsibilities:
 * - Enforce maximum concurrent positions
 * - Calculate TP1 and TP2 from signal data
 * - Track which orders are pending, filled, partial, closed
 * - Provide the current list of open positions for monitoring
 *
 * This class does NOT communicate with OANDA directly — it manages
 * the internal state. The OandaOrderExecutor handles the actual
 * API calls and reports back to this manager via callbacks.
 */
public class OrderManager {

	private final RiskManager riskManager;
	private final int maxOpenPositions;
	private final double tp1RiskMultiple; // e.g. 2.0 means TP1 at 2R
	private final double tp2RiskMultiple; // e.g. 3.0 means TP2 at 3R
	private final long slBufferScaled;

	private final List<ManagedOrder> allOrders = new ArrayList<>();

	/**
	 * @param riskManager      calculates position sizing
	 * @param maxOpenPositions max concurrent open positions (e.g. 2)
	 * @param tp1RiskMultiple  TP1 at this many R (e.g. 2.0)
	 * @param tp2RiskMultiple  TP2 at this many R (e.g. 3.0)
	 * @param slBufferScaled   extra buffer beyond sweep price for SL
	 */
	public OrderManager(RiskManager riskManager, int maxOpenPositions,
			double tp1RiskMultiple, double tp2RiskMultiple,
			long slBufferScaled) {
		this.riskManager = riskManager;
		this.maxOpenPositions = maxOpenPositions;
		this.tp1RiskMultiple = tp1RiskMultiple;
		this.tp2RiskMultiple = tp2RiskMultiple;
		this.slBufferScaled = slBufferScaled;
	}

	/**
	 * Attempts to create a new order from a trade signal.
	 *
	 * Returns empty if max positions are already open.
	 * Otherwise creates a ManagedOrder with calculated SL, TP1, TP2,
	 * and position size.
	 *
	 * @param signal         the validated trade signal
	 * @param accountBalance current account balance for position sizing
	 * @return the new order if created, empty if at capacity
	 */
	public Optional<ManagedOrder> createOrder(TradeSignal signal,
			double accountBalance) {

		if (openPositionCount() >= maxOpenPositions) {
			System.out.println("[OrderManager] Max positions reached ("
					+ maxOpenPositions + "). Skipping signal.");
			return Optional.empty();
		}

		// Calculate SL
		long sl;
		if (signal.bias() == MarketBias.BULLISH) {
			sl = signal.sweepPrice() - slBufferScaled;
		} else {
			sl = signal.sweepPrice() + slBufferScaled;
		}

		// Calculate risk distance (entry to SL)
		long riskDistance = Math.abs(signal.idealEntry() - sl);

		// Calculate TP1 and TP2
		long tp1, tp2;
		if (signal.bias() == MarketBias.BULLISH) {
			tp1 = signal.idealEntry() + (long) (riskDistance * tp1RiskMultiple);
			tp2 = signal.idealEntry() + (long) (riskDistance * tp2RiskMultiple);
		} else {
			tp1 = signal.idealEntry() - (long) (riskDistance * tp1RiskMultiple);
			tp2 = signal.idealEntry() - (long) (riskDistance * tp2RiskMultiple);
		}

		// Calculate position size
		long units = riskManager.calculatePositionSize(
				accountBalance,
				signal.idealEntry(),
				sl,
				signal.instrument(),
				signal.bias());

		ManagedOrder order = new ManagedOrder(signal, sl, tp1, tp2, units);
		allOrders.add(order);

		System.out.println("[OrderManager] Created order " + order.id().substring(0, 8));
		System.out.println("  Direction: " + order.direction());
		System.out.println("  Entry:     " + order.entryPrice());
		System.out.println("  SL:        " + sl);
		System.out.println("  TP1:       " + tp1 + " (" + tp1RiskMultiple + "R)");
		System.out.println("  TP2:       " + tp2 + " (" + tp2RiskMultiple + "R)");
		System.out.println("  Units:     " + units);
		System.out.println("  Grade:     " + order.grade());

		return Optional.of(order);
	}

	/**
	 * Returns all currently open positions (FILLED or PARTIAL).
	 */
	public List<ManagedOrder> openPositions() {
		return allOrders.stream()
				.filter(ManagedOrder::isOpen)
				.toList();
	}

	/**
	 * Returns all pending orders (not yet filled).
	 */
	public List<ManagedOrder> pendingOrders() {
		return allOrders.stream()
				.filter(ManagedOrder::isPending)
				.toList();
	}

	/**
	 * Number of currently open positions.
	 */
	public int openPositionCount() {
		return (int) allOrders.stream().filter(ManagedOrder::isOpen).count();
	}

	/**
	 * Returns all orders (for logging and persistence).
	 */
	public List<ManagedOrder> allOrders() {
		return List.copyOf(allOrders);
	}

	/**
	 * Finds an order by its internal ID.
	 */
	public Optional<ManagedOrder> findById(String orderId) {
		return allOrders.stream()
				.filter(o -> o.id().equals(orderId))
				.findFirst();
	}

	/**
	 * Finds an order by its OANDA trade ID.
	 */
	public Optional<ManagedOrder> findByOandaTradeId(String tradeId) {
		return allOrders.stream()
				.filter(o -> tradeId.equals(o.oandaTradeId()))
				.findFirst();
	}
}
