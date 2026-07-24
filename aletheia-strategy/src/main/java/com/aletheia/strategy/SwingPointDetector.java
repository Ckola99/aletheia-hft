package com.aletheia.strategy;

import com.aletheia.core.Candle;
import com.aletheia.core.SwingPoint;
import com.aletheia.core.SwingType;
import com.aletheia.core.Timeframe;

import java.util.ArrayList;
import java.util.List;

/**
 * Identifies swing highs and swing lows in a sequence of candles.
 *
 * WHAT IS A SWING HIGH?
 * A candle is a swing high if its HIGH is the highest of all candles
 * within N candles on BOTH sides. It is a local peak — price went up
 * to this point and then came back down.
 *
 * Example with lookback N=3:
 * To confirm candle[5] is a swing high, we check:
 * - candle[5].high > candle[2].high (3 candles to the left)
 * - candle[5].high > candle[3].high
 * - candle[5].high > candle[4].high
 * - candle[5].high > candle[6].high (3 candles to the right)
 * - candle[5].high > candle[7].high
 * - candle[5].high > candle[8].high
 * All must be true for it to qualify as a swing high.
 *
 * WHAT IS A SWING LOW?
 * Same concept but inverted — a candle whose LOW is the lowest of
 * all candles within N candles on both sides.
 *
 * WHY IS LOOKBACK CONFIGURABLE?
 * A larger lookback (N=5) produces fewer, more significant swings.
 * These are the "major" structure points — weekly or daily swing levels.
 *
 * A smaller lookback (N=2 or N=3) produces more swings, including
 * minor ones. These are useful for LTF execution — finding the exact
 * swing low that the Judas Swing will sweep.
 *
 * In Aletheia:
 * HTF (15min) uses lookback=5 for major structure
 * LTF (seconds) uses lookback=3 for entry precision
 */
public class SwingPointDetector {

	private final int lookback;

	/**
	 * @param lookback how many candles on each side must be lower/higher
	 *                 for a swing to qualify. Minimum 1, typically 3-5.
	 */
	public SwingPointDetector(int lookback) {
		if (lookback < 1) {
			throw new IllegalArgumentException("Lookback must be at least 1, got: " + lookback);
		}
		this.lookback = lookback;
	}

	/**
	 * Creates a detector with the default lookback of 5.
	 */
	public SwingPointDetector() {
		this(5);
	}

	/**
	 * Scans candles and returns all swing highs and swing lows found.
	 *
	 * NOTE: The first N and last N candles can never be swing points
	 * because there aren't enough candles on one side to confirm them.
	 * This is inherent to the algorithm, not a limitation.
	 *
	 * @param candles candles in chronological order (oldest first)
	 * @return list of swing points in chronological order
	 */
	public List<SwingPoint> detect(List<Candle> candles) {
		List<SwingPoint> swings = new ArrayList<>();

		if (candles == null || candles.size() < (2 * lookback + 1)) {
			return swings; // not enough candles
		}

		// Check each candle from index 'lookback' to 'size - lookback - 1'
		// (need N candles on each side)
		for (int i = lookback; i < candles.size() - lookback; i++) {
			Candle current = candles.get(i);

			boolean isSwingHigh = true;
			boolean isSwingLow = true;

			// Compare against N candles on each side
			for (int j = 1; j <= lookback; j++) {
				Candle left = candles.get(i - j);
				Candle right = candles.get(i + j);

				// For swing HIGH: current's high must be STRICTLY greater
				// than both neighbours' highs
				if (current.high() <= left.high() || current.high() <= right.high()) {
					isSwingHigh = false;
				}

				// For swing LOW: current's low must be STRICTLY less
				// than both neighbours' lows
				if (current.low() >= left.low() || current.low() >= right.low()) {
					isSwingLow = false;
				}

				// Early exit: if neither is possible, skip remaining checks
				if (!isSwingHigh && !isSwingLow) {
					break;
				}
			}

			if (isSwingHigh) {
				swings.add(new SwingPoint(
						current.time(),
						current.instrument(),
						SwingType.HIGH,
						current.high(),
						current.timeframe()));
			}

			if (isSwingLow) {
				swings.add(new SwingPoint(
						current.time(),
						current.instrument(),
						SwingType.LOW,
						current.low(),
						current.timeframe()));
			}
		}

		return swings;
	}
}
