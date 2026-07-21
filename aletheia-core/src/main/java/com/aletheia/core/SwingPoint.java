package com.aletheia.core;

import java.time.Instant;

/**
 * A significant swing high or swing low on a price chart.
 *
 * WHAT IS A SWING POINT?
 * A swing high is a candle whose HIGH is the highest of all candles
 * within N candles on either side of it. It is a local peak.
 *
 * A swing low is a candle whose LOW is the lowest of all candles
 * within N candles on either side of it. It is a local trough.
 *
 * WHERE SWING POINTS ARE USED:
 *
 * 1. MarketStructureAnalyser
 * Compares consecutive swing highs and lows to determine trend:
 * HH + HL = BULLISH, LH + LL = BEARISH
 *
 * 2. SmtDivergenceDetector
 * Compares the swing lows of EUR/USD and GBP/USD simultaneously.
 * If GBP/USD makes a new swing low but EUR/USD does not →
 * BULLISH SMT Divergence (institutional buying on EUR/USD).
 *
 * 3. JudasSwingDetector
 * The Judas Swing sweeps a prior swing low (in a bullish setup)
 * to trigger stop losses before reversing upward.
 * The swing low IS the liquidity target of the Judas Swing.
 *
 * @param time       When this swing point formed (timestamp of the pivot
 *                   candle)
 * @param instrument Which market this swing is on
 * @param type       HIGH or LOW
 * @param price      The extreme price (high for HIGH, low for LOW) — scaled
 *                   long
 * @param timeframe  Which timeframe this swing was detected on
 */

public record SwingPoint(
		Instant time,
		String instrument,
		SwingType type,
		long price,
		Timeframe timeframe) {

	/**
	 * True if this is a swing high.
	 */

	public boolean isHigh() {
		return type == SwingType.HIGH;
	}
	

	/**
	 * True if this is a swing low.
	 */

	public boolean isLow() {
		return type == SwingType.LOW;
	}
}
