package com.aletheia.api;

import com.aletheia.data.PricingStream;
import com.aletheia.execution.KillSwitch;
import com.aletheia.execution.OrderManager;
import com.aletheia.observability.MetricsService;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically samples the live state of core components and pushes it into
 * MetricsService, so gauges (stream connected, kill-switch, open positions,
 * ticks) stay current for Prometheus.
 *
 * WHY POLL FROM HERE INSTEAD OF INSIDE THE COMPONENTS?
 * Observability sits at the EDGE. Rather than making aletheia-data or
 * aletheia-execution depend on aletheia-observability, this API-layer
 * component reads the state those modules already expose publicly
 * (isRunning(), tickCount(), isActive()) and reports it. The core stays
 * unaware it is being watched — the right dependency direction.
 */
@Component
@Profile("!test")
public class MetricsUpdater {

	private final PricingStream pricingStream;
	private final KillSwitch killSwitch;
	private final OrderManager orderManager;
	private final MetricsService metrics;

	private long lastTickCount = 0;

	public MetricsUpdater(PricingStream pricingStream,
			KillSwitch killSwitch,
			OrderManager orderManager,
			MetricsService metrics) {
		this.pricingStream = pricingStream;
		this.killSwitch = killSwitch;
		this.orderManager = orderManager;
		this.metrics = metrics;
	}

	/**
	 * Sample core state every 5 seconds and update gauges.
	 */
	@Scheduled(fixedDelay = 5_000)
	public void sample() {
		// Stream connectivity
		metrics.setStreamConnected(pricingStream.isRunning());

		// Kill switch state
		metrics.setKillSwitchActive(killSwitch.isActive());

		// Open positions
		metrics.setOpenPositions(orderManager.openPositionCount());

		metrics.setTickCount(pricingStream.tickCount());
	}
}
