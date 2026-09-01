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
 * STAGE 3: RECONCILE + ACT.
 *
 * Each 10s cycle:
 * 1. RECONCILE fills — PENDING orders that appear as broker trades -> FILLED
 * 2. RECONCILE closes — open orders that vanished broker-side -> CLOSED
 * 3. MANAGE TP1 — FILLED orders whose price reached TP1:
 * a) close 70% of the position
 * b) move the stop to breakeven (entry)
 * After this the runner (30%) rides to TP2, which the
 * broker enforces (takeProfitOnFill), with the
 * breakeven stop protecting it on a reversal.
 *
 * SAFETY GUARDS:
 * - Only trades WE placed (clientId matches a ManagedOrder) are ever touched.
 * - TP1 management runs ONLY for orders in state FILLED (not PENDING, not
 * already PARTIAL, not CLOSED) — so the 70% close happens at most once.
 * - Every broker action is checked; on failure we log and leave state
 * unchanged so the next cycle retries rather than corrupting our view.
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
			if (!byClientId.containsKey(order.id())) {
				// Placeholder close price — refined later via OANDA realizedPL.
				order.onFullClose(order.currentSl(), Instant.now(), 0.0);
				System.out.println("[PositionMonitor] CLOSE (broker-side): order "
						+ shortId(order.id()) + " " + order.instrument()
						+ " -> " + order.state());
			}
		}
	}

	// -- 3. TP1 management: partial close + breakeven --------------------

	private void manageTp1(Map<String, BrokerTrade> byClientId) {
		for (ManagedOrder order : orderManager.openPositions()) {
			// Only manage TP1 for freshly FILLED orders — not ones already
			// partialled. isOpen() covers FILLED and PARTIAL, so gate on state.
			if (order.state() != OrderState.FILLED) {
				continue;
			}

			BrokerTrade t = byClientId.get(order.id());
			if (t == null) {
				continue; // not currently open on broker (will be close-reconciled)
			}

			Optional<Long> priceOpt = broker.getCurrentPrice(order.instrument());
			if (priceOpt.isEmpty()) {
				continue; // no price this cycle — try again next time
			}
			long price = priceOpt.get();

			if (!tp1Reached(order, price)) {
				continue; // target not hit yet
			}

			// TP1 reached — execute the two-step management action.
			executeTp1(order, t);
		}
	}

	/** True if the current price has reached the order's TP1 target. */
	private boolean tp1Reached(ManagedOrder order, long price) {
		if (order.direction() == MarketBias.BULLISH) {
			return price >= order.tp1();
		} else {
			return price <= order.tp1();
		}
	}

	/**
	 * Closes 70% of the position and moves the stop to breakeven. Order of
	 * operations matters: close the partial FIRST, then move the stop. If the
	 * partial close fails we abort and retry next cycle — we never move the stop
	 * on a position we failed to reduce.
	 */
	private void executeTp1(ManagedOrder order, BrokerTrade t) {
		String tradeId = t.brokerTradeId();

		// Units to close = 70% of the CURRENTLY OPEN units, unsigned.
		long openUnits = Math.abs(t.currentUnits());
		long unitsToClose = (long) (openUnits * TP1_CLOSE_FRACTION);
		if (unitsToClose <= 0) {
			return; // nothing meaningful to close (tiny position)
		}

		// Step 1: partial close (70%)
		boolean closed = broker.closeTrade(tradeId, unitsToClose);
		if (!closed) {
			System.err.println("[PositionMonitor] TP1 partial close FAILED for "
					+ shortId(order.id()) + " -- will retry next cycle");
			return; // abort: do NOT move stop if we didn't reduce the position
		}

		// Step 2: move stop to breakeven (entry / fill price)
		long breakeven = order.filledPrice();
		boolean moved = broker.modifyStopLoss(tradeId, breakeven, order.instrument());
		if (!moved) {
			System.err.println("[PositionMonitor] WARNING: TP1 partial closed but "
					+ "breakeven SL move FAILED for " + shortId(order.id())
					+ " (trade " + tradeId + "). Runner is unprotected at breakeven!");
		}

		// Update our internal state: 70% closed, SL now at breakeven.
		// pnl left 0.0 — real P&L accounting comes with the OANDA realizedPL pass.
		order.onPartialClose(order.tp1(), 0.0);

		System.out.println("[PositionMonitor] TP1 HIT: order " + shortId(order.id())
				+ " " + order.instrument() + " -- closed " + unitsToClose
				+ " units (70%), SL -> breakeven " + breakeven
				+ ", runner riding to TP2 " + order.tp2());
	}

	private static String shortId(String id) {
		return id != null && id.length() >= 8 ? id.substring(0, 8) : id;
	}
}
