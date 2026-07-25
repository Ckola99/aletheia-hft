package com.aletheia.strategy;

import com.aletheia.core.Candle;
import com.aletheia.core.SwingPoint;
import com.aletheia.core.Timeframe;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of recent swing points per instrument per timeframe.
 *
 * WHY THIS EXISTS:
 * SMT Divergence requires comparing the swing points of EUR/USD and GBP/USD.
 * These swings are detected from candle data that arrives on the streaming
 * thread. The SMT detector reads the swings on the signal evaluation thread.
 * We need a shared data structure that both threads can access safely.
 *
 * THREAD SAFETY APPROACH — Immutable Snapshots:
 * When new candles close and we detect new swings, we create a completely
 * new immutable List and put it in the map atomically.
 * ConcurrentHashMap.put() is atomic — readers always see either the old
 * complete list or the new complete list, never a partially-updated state.
 *
 * We do NOT use synchronized blocks or locks. The immutable snapshot
 * pattern is lock-free and faster for read-heavy workloads like ours
 * (many reads from the detector, infrequent writes on candle close).
 *
 * WHAT IS STORED:
 * For each (instrument × timeframe), the last N swing points.
 * N is bounded to prevent unbounded memory growth.
 *
 * Key format: "EUR_USD:MIN_15"
 * Value: immutable List of the most recent swing points
 */
public class SwingPointRegistry {

	private final SwingPointDetector swingDetector;
	private final int maxSwingsPerKey;

	// ConcurrentHashMap: safe for concurrent get/put from multiple threads
	// Values are immutable Lists — safe to read from any thread
	private final Map<String, List<SwingPoint>> snapshots = new ConcurrentHashMap<>();

	/**
	 * @param swingLookback   lookback for the swing point detector
	 * @param maxSwingsPerKey maximum swings stored per (instrument × timeframe)
	 */
	public SwingPointRegistry(int swingLookback, int maxSwingsPerKey) {
		this.swingDetector = new SwingPointDetector(swingLookback);
		this.maxSwingsPerKey = maxSwingsPerKey;
	}

	/**
	 * Creates a registry with default settings.
	 * Lookback=3 (LTF-appropriate), max 20 swings per key.
	 */
	public SwingPointRegistry() {
		this(3, 20);
	}

	/**
	 * Called when a new candle closes on ANY instrument.
	 * Re-detects swing points from recent candles and updates the snapshot.
	 *
	 * This method is called from the streaming/candle-aggregation thread.
	 * It creates a new immutable list and atomically replaces the old one.
	 *
	 * @param instrument    which instrument e.g. "EUR_USD"
	 * @param timeframe     which timeframe
	 * @param recentCandles the recent candles for this instrument+timeframe
	 *                      (should be at least 30-50 candles for reliable
	 *                      detection)
	 */
	public void update(String instrument, Timeframe timeframe,
			List<Candle> recentCandles) {
		String key = buildKey(instrument, timeframe);

		// Detect all swings in the recent candle window
		List<SwingPoint> allSwings = swingDetector.detect(recentCandles);

		// Keep only the most recent N swings
		List<SwingPoint> trimmed;
		if (allSwings.size() > maxSwingsPerKey) {
			trimmed = allSwings.subList(
					allSwings.size() - maxSwingsPerKey, allSwings.size());
		} else {
			trimmed = allSwings;
		}

		// Create an immutable copy and atomically replace in the map
		// Collections.unmodifiableList ensures nobody can modify this list
		// after it's stored — any reader gets a frozen snapshot
		snapshots.put(key, Collections.unmodifiableList(List.copyOf(trimmed)));
	}

	/**
	 * Returns the current swing points for an instrument+timeframe.
	 *
	 * The returned list is IMMUTABLE — safe to iterate, safe to pass
	 * to another thread, guaranteed not to change while you're reading it.
	 *
	 * Returns an empty list if no data exists for this key.
	 *
	 * @param instrument e.g. "EUR_USD"
	 * @param timeframe  e.g. Timeframe.MIN_15
	 * @return immutable list of recent swing points, may be empty
	 */
	public List<SwingPoint> getSwings(String instrument, Timeframe timeframe) {
		return snapshots.getOrDefault(buildKey(instrument, timeframe), List.of());
	}

	/**
	 * Builds the map key from instrument and timeframe.
	 * "EUR_USD" + MIN_15 → "EUR_USD:MIN_15"
	 */
	private String buildKey(String instrument, Timeframe timeframe) {
		return instrument + ":" + timeframe.name();
	}
}
