package com.aletheia.api;

import com.aletheia.core.MarketBias;
import com.aletheia.execution.BrokerExecutor;
import com.aletheia.execution.BrokerExecutor.BrokerTrade;
import com.aletheia.execution.ManagedOrder;
import com.aletheia.execution.OrderManager;
import com.aletheia.execution.OrderState;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Watches open positions and drives the breakeven / partial-TP trade-management
 * flow — the live counterpart of the backtest's SimulatedTrade logic.
 *
 * STAGE 4: RECONCILE + ACT + REAL P&L.
 *
 * Each 10s cycle:
 * 1. RECONCILE fills — PENDING orders that appear as broker trades -> FILLED
 * 2. RECONCILE closes — open orders that vanished broker-side -> CLOSED,
 * recording OANDA's authoritative realized P&L (USD)
 * 3. MANAGE TP1 — FILLED orders whose price reached TP1:
 * a) close 70% of the position
 * b) move the stop to breakeven (entry)
 * recording the partial's realized P&L (USD)
 *
 * P&L ACCOUNTING:
 * OANDA's realizedPL is CUMULATIVE per trade (it grows as the trade partially
 * then fully closes). ManagedOrder.realisedPnl ACCUMULATES the values we feed
 * it. So at each close point we feed only the INCREMENT since we last booked:
 * OANDA_total_realized - order.realisedPnl(). All figures are account home
 * currency (USD), not pips.
 *
 * SAFETY GUARDS:
 * - Only trades WE placed (clientId matches a ManagedOrder) are ever touched.
 * - TP1 management runs ONLY for orders in state FILLED (so the 70% close
 * happens at most once).
 * - Broker actions are checked; on failure we log and leave state unchanged so
 * the next cycle retries rather than corrupting our view.
 */
@Component
@Profile("!test")
public class PositionMonitor {

	private static final double TP1_CLOSE_FRACTION = 0.70;

	private final BrokerExecutor broker;
	private final OrderManager orderManager;

	public PositionMonitor(BrokerExecutor broker, OrderManager orderManager) {
		this.broker = broker;
		this.orderManager = orderManager;
	}

	@Scheduled(fixedDelay = 10_000)
	public void monitor() {
		List<BrokerTrade> brokerTrades = broker.getOpenTrades();
		Map<String, BrokerTrade> byClientId = new HashMap<>();
		for (BrokerTrade t : brokerTrades) {
			if (t.clientId() != null && !t.clientId().isBlank()) {
				byClientId.put(t.clientId(), t);
			}
		}

		reconcileFills(byClientId);
		reconcileCloses(byClientId);
		manageTp1(byClientId);
	}

	// -- 1. Fill detection -----------------------------------------------

	private void reconcileFills(Map<String, BrokerTrade> byClientId) {
		for (ManagedOrder order : orderManager.pendingOrders()) {
			BrokerTrade t = byClientId.get(order.id());
			if (t != null) {
				order.onFilled(t.brokerTradeId(), t.openPrice(), Instant.now());
				System.out.println("[PositionMonitor] FILL: order " + shortId(order.id())
						+ " -> trade " + t.brokerTradeId() + " " + order.instrument()
						+ " @ " + t.openPrice());
			}
		}
	}

	// -- 2. Broker-side close detection ----------------------------------

	private void reconcileCloses(Map<String, BrokerTrade> byClientId) {
		for (ManagedOrder order : orderManager.openPositions()) {
			if (byClientId.containsKey(order.id())) {
				continue; // still open
			}

			// Trade closed broker-side (SL or TP2 hit). Fetch OANDA's authoritative
			// cumulative realized P&L and book only the increment since last time.
			double increment = 0.0;
			String tradeId = order.oandaTradeId();
			if (tradeId != null) {
				Optional<Double> total = broker.getRealizedPnl(tradeId);
				if (total.isPresent()) {
					increment = total.get() - order.realisedPnl();
				}
			}

			order.onFullClose(order.currentSl(), Instant.now(), increment);
			System.out.println("[PositionMonitor] CLOSE (broker-side): order "
					+ shortId(order.id()) + " " + order.instrument()
					+ " -> " + order.state()
					+ " | realised P&L $" + String.format("%.2f", order.realisedPnl()));
		}
	}

	// -- 3. TP1 management: partial close + breakeven --------------------

	private void manageTp1(Map<String, BrokerTrade> byClientId) {
		for (ManagedOrder order : orderManager.openPositions()) {
			if (order.state() != OrderState.FILLED) {
				continue; // only freshly-filled orders; skip already-partialled
			}

			BrokerTrade t = byClientId.get(order.id());
			if (t == null) {
				continue;
			}

			Optional<Long> priceOpt = broker.getCurrentPrice(order.instrument());
			if (priceOpt.isEmpty()) {
				continue;
			}
			long price = priceOpt.get();

			if (!tp1Reached(order, price)) {
				continue;
			}

			executeTp1(order, t);
		}
	}

	private boolean tp1Reached(ManagedOrder order, long price) {
		if (order.direction() == MarketBias.BULLISH) {
			return price >= order.tp1();
		} else {
			return price <= order.tp1();
		}
	}

	/**
	 * Closes 70% of the position and moves the stop to breakeven. Close FIRST,
	 * then move the stop — if the partial close fails we abort and retry next
	 * cycle rather than moving the stop on an un-reduced position.
	 */
	private void executeTp1(ManagedOrder order, BrokerTrade t) {
		String tradeId = t.brokerTradeId();

		long openUnits = Math.abs(t.currentUnits());
		long unitsToClose = (long) (openUnits * TP1_CLOSE_FRACTION);
		if (unitsToClose <= 0) {
			return;
		}

		// Step 1: partial close (70%)
		boolean closed = broker.closeTrade(tradeId, unitsToClose);
		if (!closed) {
			System.err.println("[PositionMonitor] TP1 partial close FAILED for "
					+ shortId(order.id()) + " -- will retry next cycle");
			return;
		}

		// Step 2: move stop to breakeven (fill price)
		long breakeven = order.filledPrice();
		boolean moved = broker.modifyStopLoss(tradeId, breakeven, order.instrument());
		if (!moved) {
			System.err.println("[PositionMonitor] WARNING: TP1 partial closed but "
					+ "breakeven SL move FAILED for " + shortId(order.id())
					+ " (trade " + tradeId + "). Runner is unprotected at breakeven!");
		}

		// Book the partial's realized P&L (increment since last booked).
		double increment = 0.0;
		Optional<Double> total = broker.getRealizedPnl(tradeId);
		if (total.isPresent()) {
			increment = total.get() - order.realisedPnl();
		}

		order.onPartialClose(order.tp1(), increment);

		System.out.println("[PositionMonitor] TP1 HIT: order " + shortId(order.id())
				+ " " + order.instrument() + " -- closed " + unitsToClose
				+ " units (70%), SL -> breakeven " + breakeven
				+ ", runner -> TP2 " + order.tp2()
				+ " | partial P&L $" + String.format("%.2f", order.realisedPnl()));
	}

	private static String shortId(String id) {
		return id != null && id.length() >= 8 ? id.substring(0, 8) : id;
	}
}
