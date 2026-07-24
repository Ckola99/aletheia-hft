package com.aletheia.strategy;

import com.aletheia.core.Timeframe;

import java.time.Instant;

/**
 * A Fair Value Gap (FVG) — a 3-candle pattern where the wicks of
 * candle 1 and candle 3 do not overlap, leaving a "gap" of price
 * that was never traded.
 *
 * ICT THEORY:
 * FVGs represent imbalance — price moved so fast in one direction
 * that not all orders were filled. The IPDA (Interbank Price Delivery
 * Algorithm) will typically bring price back to "fill" this gap later.
 *
 * An unfilled FVG acts as a magnet. Price is drawn back to it.
 * Once price trades through the FVG, it is considered "filled" and
 * loses its magnetic quality.
 *
 * BULLISH FVG (price moved up too fast):
 * candle[0].high < candle[2].low
 * The gap is between candle[0].high (bottom) and candle[2].low (top)
 * Price should return DOWN to fill this gap — but the bias is BULLISH
 * because the original move was up, and the gap is a buying zone
 *
 * Candle 0: ─┤ high ←── bottom of gap
 * Candle 1: ────┤ (the impulse candle)
 * Candle 2: ├── low ←── top of gap
 * GAP ↕
 *
 * BEARISH FVG (price moved down too fast):
 * candle[0].low > candle[2].high
 * The gap is between candle[2].high (bottom) and candle[0].low (top)
 *
 * Candle 0: ├── low ←── top of gap
 * Candle 1: ────┤ (the impulse candle)
 * Candle 2: ─┤ high ←── bottom of gap
 * GAP ↕
 *
 * @param bias      BULLISH or BEARISH
 * @param upper     top of the gap zone (scaled long)
 * @param lower     bottom of the gap zone (scaled long)
 * @param time      when the impulse candle (candle 1) occurred
 * @param timeframe which timeframe this FVG was detected on
 */
public record FairValueGap(
		Bias bias,
		long upper,
		long lower,
		Instant time,
		Timeframe timeframe) implements PdArray {

	/**
	 * The size of the gap in scaled price units.
	 * Larger gaps indicate stronger displacement — more institutional force.
	 */
	public long gapSize() {
		return upper - lower;
	}
}
