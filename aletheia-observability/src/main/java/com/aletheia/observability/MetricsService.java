package com.aletheia.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central metrics facade for the Aletheia trading engine.
 *
 * WHY THIS CLASS EXISTS (dependency inversion):
 * The rest of the engine should not know or care that we use Micrometer.
 * It calls clean, domain-specific methods here — recordSignal(...),
 * setOpenPositions(...), setKillSwitchActive(...) — and THIS class is the
 * only place that touches the Micrometer MeterRegistry. Swap Micrometer for
 * something else later and only this file changes. Same discipline as the
 * BrokerExecutor interface hiding OANDA.
 *
 * HOW METRICS REACH GRAFANA:
 * engine code -> MetricsService -> MeterRegistry (Micrometer)
 * -> /actuator/prometheus (text endpoint, auto-exposed by Actuator)
 * -> Prometheus scrapes every 15s -> Grafana queries Prometheus
 *
 * METRIC TYPES USED:
 * - Counter: monotonic, only goes up (signals, orders, ticks). Prometheus
 * derives rates from these ("signals per hour").
 * - Gauge: goes up and down (open positions, kill-switch state). A live
 * snapshot of "right now". Implemented here by binding a Micrometer gauge
 * to an AtomicInteger/AtomicLong we mutate.
 *
 * TAGS (labels):
 * Counters are tagged with dimensions like {grade, instrument} so a single
 * metric name can be sliced in Grafana (e.g. signals by grade, per pair).
 * We create tagged counters lazily and cache nothing here — Micrometer's
 * registry already de-duplicates meters by name+tags, so repeated calls with
 * the same tags return the same underlying counter.
 */
@Component
public class MetricsService {

	private final MeterRegistry registry;

	// ── Gauge-backing state (Micrometer reads these live) ───────────────
	private final AtomicInteger openPositions = new AtomicInteger(0);
	private final AtomicInteger killSwitchActive = new AtomicInteger(0);
	private final AtomicInteger streamConnected = new AtomicInteger(0);
	private final AtomicLong lastPnlPips = new AtomicLong(0);
	private final AtomicLong tickCount = new AtomicLong(0);

	public MetricsService(MeterRegistry registry) {
		this.registry = registry;

		// Bind gauges once. Micrometer will call .get() on these atomics every
		// time Prometheus scrapes, so we just mutate the atomics elsewhere.
		registry.gauge("aletheia.open.positions", openPositions);
		registry.gauge("aletheia.kill.switch.active", killSwitchActive);
		registry.gauge("aletheia.stream.connected", streamConnected);
		registry.gauge("aletheia.pnl.pips", lastPnlPips);
		registry.gauge("aletheia.ticks.received", tickCount);
	}

	// ── Counters (tagged) ───────────────────────────────────────────────

	/**
	 * A trade signal was generated. Tagged by grade (A / A_PLUS) and instrument.
	 */
	public void recordSignal(String grade, String instrument) {
		Counter.builder("aletheia.signals")
				.description("Trade signals generated")
				.tags(Tags.of("grade", safe(grade), "instrument", safe(instrument)))
				.register(registry)
				.increment();
	}

	/** An order was placed with the broker. Tagged by instrument and direction. */
	public void recordOrderPlaced(String instrument, String direction) {
		Counter.builder("aletheia.orders.placed")
				.description("Orders placed with the broker")
				.tags(Tags.of("instrument", safe(instrument), "direction", safe(direction)))
				.register(registry)
				.increment();
	}

	/** A signal was suppressed because of a news blackout. */
	public void recordNewsBlackout(String instrument) {
		Counter.builder("aletheia.news.blackouts")
				.description("Signals suppressed by the economic-calendar news guard")
				.tags(Tags.of("instrument", safe(instrument)))
				.register(registry)
				.increment();
	}

	/**
	 * A closed candle was received/processed. Tagged by instrument and timeframe.
	 */
	public void recordCandle(String instrument, String timeframe) {
		Counter.builder("aletheia.candles")
				.description("Closed candles processed")
				.tags(Tags.of("instrument", safe(instrument), "timeframe", safe(timeframe)))
				.register(registry)
				.increment();
	}

	/** A price tick was received. Tagged by instrument. */
	public void recordTick(String instrument) {
		Counter.builder("aletheia.ticks")
				.description("Price ticks received from the stream")
				.tags(Tags.of("instrument", safe(instrument)))
				.register(registry)
				.increment();
	}

	// ── Gauges (set current value) ──────────────────────────────────────

	/** How many positions are currently open. */
	public void setOpenPositions(int n) {
		openPositions.set(n);
	}

	/** Kill switch state: true -> 1, false -> 0. */
	public void setKillSwitchActive(boolean active) {
		killSwitchActive.set(active ? 1 : 0);
	}

	/** Price stream connection state: connected -> 1, disconnected -> 0. */
	public void setStreamConnected(boolean connected) {
		streamConnected.set(connected ? 1 : 0);
	}

	/** Most recent realised P&L in pips (rounded). */
	public void setPnlPips(double pips) {
		lastPnlPips.set(Math.round(pips));
	}

	public void setTickCount(long count) {
		tickCount.set(count);
	}

	// ── Helpers ─────────────────────────────────────────────────────────

	/** Micrometer tag values must be non-null; normalise nulls/blanks. */
	private static String safe(String v) {
		return (v == null || v.isBlank()) ? "unknown" : v;
	}
}
