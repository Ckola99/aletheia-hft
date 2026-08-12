package com.aletheia.execution;

import com.aletheia.core.KillzoneWindow;
import com.aletheia.strategy.KillzoneService;

import java.time.Instant;
import java.util.List;

/**
 * Expires (cancels) pending orders when the killzone ends.
 *
 * ICT PRINCIPLE:
 * A Judas Swing setup is only valid within the killzone it formed in.
 * If price didn't reach your entry during London Open (02:00-05:00 EST),
 * the setup is dead. Leaving the order open is dangerous because:
 *
 * 1. The setup logic relied on institutional activity during the killzone.
 * Outside the killzone, price movement is retail noise.
 * 2. A stale limit order might fill during Asian session thin liquidity,
 * where spreads are wide and the setup context no longer applies.
 * 3. By the next killzone, market structure may have changed entirely.
 *
 * This service runs on a schedule (every 60 seconds) and cancels any
 * pending order that was created during a killzone that has now ended.
 *
 * USAGE:
 * Called by a scheduled task every minute:
 * expiryService.checkAndExpire(Instant.now());
 */
public class OrderExpiryService {

	private final OrderManager orderManager;
	private final BrokerExecutor executor;
	private final KillzoneService killzoneService;

	public OrderExpiryService(OrderManager orderManager,
			BrokerExecutor executor,
			KillzoneService killzoneService) {
		this.orderManager = orderManager;
		this.executor = executor;
		this.killzoneService = killzoneService;
	}

	/**
	 * Checks all pending orders and cancels any whose killzone has ended.
	 *
	 * @param now the current time
	 * @return number of orders cancelled
	 */
	public int checkAndExpire(Instant now) {
		// What killzone are we in right now?
		KillzoneWindow currentKillzone = killzoneService.classify(now);

		List<ManagedOrder> pending = orderManager.pendingOrders();
		int cancelled = 0;

		for (ManagedOrder order : pending) {
			// If the order was placed during a killzone that is no longer active,
			// cancel it. Two cases:
			//
			// Case 1: We're now outside ALL killzones (NONE)
			// → Every pending order from any killzone should be cancelled
			//
			// Case 2: We've moved to a DIFFERENT killzone
			// → Order from London Open should be cancelled during NY Open
			// → It was valid for London's context, not NY's

			boolean shouldExpire = false;

			if (currentKillzone == KillzoneWindow.NONE) {
				// Outside all killzones — cancel everything pending
				shouldExpire = true;
			} else if (currentKillzone != order.killzone()) {
				// Different killzone than when the order was placed
				shouldExpire = true;
			}

			// Also expire orders older than 3 hours regardless
			// (safety net for edge cases)
			long ageMs = now.toEpochMilli() - order.signalTime().toEpochMilli();
			if (ageMs > 3 * 60 * 60 * 1000) { // 3 hours
				shouldExpire = true;
			}

			if (shouldExpire) {
				System.out.println("[OrderExpiryService] Expiring order "
						+ order.id().substring(0, 8)
						+ " -- killzone ended (was " + order.killzone().displayName()
						+ ", now " + currentKillzone.displayName() + ")");

				// Cancel on OANDA if the order was placed
				if (order.oandaOrderId() != null) {
					executor.cancelOrder(order.oandaOrderId());
				}
				order.onCancelled();
				cancelled++;
			}
		}

		if (cancelled > 0) {
			System.out.println("[OrderExpiryService] Cancelled " + cancelled
					+ " expired pending orders");
		}

		return cancelled;
	}
}
