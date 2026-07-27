package com.aletheia.data;

import com.aletheia.core.Candle;
import com.aletheia.core.Tick;
import com.aletheia.core.Timeframe;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aggregates raw ticks into OHLCV candles at multiple timeframes
 * simultaneously.
 *
 * HOW IT WORKS:
 * For every (instrument × timeframe) combination, we maintain one "open" candle
 * that is being actively updated. When a tick arrives:
 *
 * 1. Calculate which time period this tick belongs to (e.g. the 09:15 period
 * for a MIN_15 candle if the tick arrived at 09:17:33)
 * 2. If the open candle is for the SAME period → update it (adjust
 * high/low/close)
 * 3. If the open candle is for a PREVIOUS period → close the old candle,
 * open a new one with this tick as the first data point
 *
 * CALENDAR ALIGNMENT:
 * Candle boundaries align to clock time, not to when the first tick arrived.
 * MIN_15 boundaries: :00, :15, :30, :45
 * HOUR_1 boundaries: every hour on the hour
 * MIN_5 boundaries: :00, :05, :10, :15, :20, :25, :30, :35, :40, :45, :50, :55
 *
 * A tick at 09:17:33 belongs to the 09:15 candle (the period that STARTED at
 * 09:15).
 * That candle closes when a tick arrives with a time ≥ 09:30:00.
 *
 * THREAD SAFETY:
 * Uses ConcurrentHashMap so the streaming thread can update candles while
 * another thread reads them. Each open candle is a mutable object, but
 * only one thread (the streaming thread) ever writes to it.
 */
public class CandleAggregator implements TickListener {

	/**
	 * Which timeframes to build candles for.
	 * We don't build every possible timeframe — only the ones Aletheia uses.
	 */
	private static final Timeframe[] ACTIVE_TIMEFRAMES = {
			Timeframe.SECONDS_5,
			Timeframe.MIN_1,
			Timeframe.MIN_5,
			Timeframe.MIN_15, // Primary HTF for strategy
			Timeframe.HOUR_1,
			Timeframe.HOUR_4,
			Timeframe.DAILY,
	};

	// Cached ZoneId to avoid creating new objects on every tick
	private static final java.time.ZoneId UTC = java.time.ZoneId.of("UTC");

	/**
	 * The key for looking up an open candle.
	 * Each unique (instrument + timeframe) has exactly one open candle.
	 *
	 * Example keys:
	 * "EUR_USD:MIN_15"
	 * "GBP_USD:HOUR_1"
	 * "EUR_USD:SECONDS_5"
	 */

	private record CandleKey(String instrument, Timeframe timeframe) {}

	/**
	 * A candle that is still being updated with new ticks.
	 * Once the period ends, we "close" it by converting to an immutable Candle
	 * record.
	 *
	 * WHY MUTABLE?
	 * While a candle is open (the time period hasn't ended), every tick potentially
	 * changes the high, low, close, and volume. A new immutable Candle record per
	 * tick would create enormous garbage collection pressure — thousands of
	 * short-lived
	 * objects per second. A mutable builder avoids this.
	 *
	 * Once closed, we convert to an immutable Candle record and never touch it
	 * again.
	 */
	private static class OpenCandle {
		final Instant periodStart; // when this candle period began (e.g. 09:15:00)
		final String instrument;
		final Timeframe timeframe;
		long open;
		long high;
		long low;
		long close;
		long volume;

		OpenCandle(Instant periodStart, String instrument, Timeframe timeframe, long firstPrice) {
			this.periodStart = periodStart;
			this.instrument = instrument;
			this.timeframe = timeframe;
			this.open = firstPrice;
			this.high = firstPrice;
			this.low = firstPrice;
			this.close = firstPrice;
			this.volume = 1;
		}

		/**
		 * Update this candle with a new tick's mid-price.
		 * Adjusts high, low, close, and increments volume.
		 */
		void update(long price) {
			if (price > high)
				high = price;
			if (price < low)
				low = price;
			close = price;
			volume++;
		}

		/**
		 * Convert this mutable open candle into an immutable Candle record.
		 * Called when the candle period ends.
		 */
		Candle toCandle() {
			return new Candle(periodStart, instrument, timeframe,
					open, high, low, close, volume);
		}
	}

	// One open candle per (instrument × timeframe)
	private final Map<CandleKey, OpenCandle> openCandles = new ConcurrentHashMap<>();

	// Listeners notified when a candle closes
	private final List<CandleListener> candleListeners = new ArrayList<>();

	/**
	 * Register a listener that is called when any candle closes.
	 * The closed candle is immutable — safe to store, cache, or pass to another
	 * thread.
	 */
	public void addCandleListener(CandleListener listener) {
		candleListeners.add(listener);
	}

	/**
	 * Called by OandaPricingStream on every tick.
	 * Updates open candles at ALL active timeframes for this tick's instrument.
	 */
	@Override
	public void onTick(Tick tick) {
		long price = tick.mid(); // use mid-price for candle construction

		for (Timeframe tf : ACTIVE_TIMEFRAMES) {
			CandleKey key = new CandleKey(tick.instrument(), tf);

			// Calculate which period this tick belongs to
			Instant periodStart = calculatePeriodStart(tick.time(), tf);

			// Get or create the open candle for this key
			OpenCandle existing = openCandles.get(key);

			if (existing == null) {
				// First tick ever for this instrument+timeframe — open a new candle
				openCandles.put(key, new OpenCandle(periodStart, tick.instrument(), tf, price));

			} else if (existing.periodStart.equals(periodStart)) {
				// Same period — update the existing open candle
				existing.update(price);

			} else {
				// New period — the old candle is now closed
				Candle closedCandle = existing.toCandle();

				// Notify all listeners about the closed candle
				for (CandleListener listener : candleListeners) {
					try {
						listener.onCandleClosed(closedCandle);
					} catch (Exception e) {
						System.err.println(
								"[CandleAggregator] Listener error: " + e.getMessage());
					}
				}

				// Open a new candle for the new period
				openCandles.put(key, new OpenCandle(periodStart, tick.instrument(), tf, price));
			}
		}
	}

	/**
	 * Returns the currently open (incomplete) candle for the given instrument
	 * and timeframe, or null if no ticks have arrived yet.
	 *
	 * Useful for debugging and for the strategy engine to see "where is price
	 * right now within the current candle."
	 */
	public Candle getOpenCandle(String instrument, Timeframe timeframe) {
		OpenCandle open = openCandles.get(new CandleKey(instrument, timeframe));
		return open != null ? open.toCandle() : null;
	}

	/**
	 * Calculates the START time of the period a given instant falls into.
	 *
	 * EXAMPLES (for MIN_15):
	 * 09:00:00 → 09:00:00 (exactly on boundary — belongs to the 09:00 candle)
	 * 09:07:33 → 09:00:00 (mid-period — belongs to the 09:00 candle)
	 * 09:15:00 → 09:15:00 (new boundary — belongs to the 09:15 candle)
	 * 09:29:59 → 09:15:00 (end of period — still the 09:15 candle)
	 *
	 * EXAMPLES (for HOUR_1):
	 * 09:00:00 → 09:00:00
	 * 09:45:22 → 09:00:00
	 * 10:00:00 → 10:00:00
	 *
	 * EXAMPLES (for SECONDS_5):
	 * 09:00:00.000 → 09:00:00.000
	 * 09:00:02.500 → 09:00:00.000
	 * 09:00:05.000 → 09:00:05.000
	 *
	 * The formula: floor the timestamp to the nearest multiple of the timeframe
	 * duration.
	 */
	static Instant calculatePeriodStart(Instant time, Timeframe tf) {
		if (tf == Timeframe.DAILY) {
			// Daily candles align to midnight UTC
			ZonedDateTime zdt = time.atZone(UTC);
			return zdt.toLocalDate().atStartOfDay(UTC).toInstant();
		}

		if (tf == Timeframe.HOUR_4) {
			// 4-hour candles align to 00:00, 04:00, 08:00, 12:00, 16:00, 20:00 UTC
			ZonedDateTime zdt = time.atZone(UTC);
			int hour = zdt.getHour();
			int alignedHour = (hour / 4) * 4; // e.g. hour 9 → 8, hour 15 → 12
			return zdt.withHour(alignedHour).withMinute(0).withSecond(0).withNano(0).toInstant();
		}

		// For all other timeframes: floor to nearest multiple of duration
		long periodSeconds = tf.toSeconds();
		long epochSeconds = time.getEpochSecond();
		long alignedEpoch = (epochSeconds / periodSeconds) * periodSeconds;

		return Instant.ofEpochSecond(alignedEpoch);
	}
}
