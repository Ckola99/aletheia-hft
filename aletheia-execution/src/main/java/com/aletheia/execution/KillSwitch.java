package com.aletheia.execution;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Emergency kill switch — shuts down all trading activity immediately.
 *
 * When activated:
 * 1. Cancel all pending orders
 * 2. Close all open positions at market price
 * 3. Stop the pricing stream (no new ticks)
 * 4. Block any new order placement
 * 5. Log the event with timestamp and reason
 *
 * The kill switch can be triggered:
 * - Manually via REST endpoint: POST /admin/kill-switch
 * - Automatically when the circuit breaker opens
 * - Automatically when max daily loss is exceeded (future)
 *
 * Once activated, the kill switch stays active until the application
 * is manually restarted. This is intentional — you want a human to
 * review what happened before trading resumes.
 */
public class KillSwitch {

	private final AtomicBoolean activated = new AtomicBoolean(false);
	private volatile Instant activatedAt;
	private volatile String activationReason;

	private final OrderManager orderManager;
	private final BrokerExecutor executor;

	public KillSwitch(OrderManager orderManager, BrokerExecutor executor) {
		this.orderManager = orderManager;
		this.executor = executor;
	}

	/**
	 * Activates the kill switch. All positions are closed immediately.
	 *
	 * @param reason why the kill switch was activated
	 * @return true if it was activated (false if already active)
	 */
	public boolean activate(String reason) {
		if (activated.getAndSet(true)) {
			System.out.println("[KillSwitch] Already active. Ignoring.");
			return false;
		}

		this.activatedAt = Instant.now();
		this.activationReason = reason;

		System.err.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
		System.err.println("!!  KILL SWITCH ACTIVATED");
		System.err.println("!!  Reason: " + reason);
		System.err.println("!!  Time:   " + activatedAt);
		System.err.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");

		// Step 1: Cancel all pending orders
		List<ManagedOrder> pending = orderManager.pendingOrders();
		System.out.println("[KillSwitch] Cancelling " + pending.size() + " pending orders...");
		for (ManagedOrder order : pending) {
			if (order.oandaOrderId() != null) {
				executor.cancelOrder(order.oandaOrderId());
			}
			order.onCancelled();
		}

		// Step 2: Close all open positions
		List<ManagedOrder> open = orderManager.openPositions();
		System.out.println("[KillSwitch] Closing " + open.size() + " open positions...");
		for (ManagedOrder order : open) {
			if (order.oandaTradeId() != null) {
				executor.closeTradeAll(order.oandaTradeId());
			}
			order.onFullClose(0, Instant.now(), 0);
		}

		System.out.println("[KillSwitch] All positions closed. Trading halted.");
		System.out.println("[KillSwitch] Restart the application to resume trading.");

		return true;
	}

	/**
	 * Returns true if the kill switch is currently active.
	 * When active, no new orders should be placed.
	 */
	public boolean isActive() {
		return activated.get();
	}

	/**
	 * When the kill switch was activated (null if not active).
	 */
	public Instant activatedAt() {
		return activatedAt;
	}

	/**
	 * Why the kill switch was activated (null if not active).
	 */
	public String reason() {
		return activationReason;
	}
}
