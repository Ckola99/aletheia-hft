package com.aletheia.core;

import java.time.Instant;

/**
 * An OHLCV (Open, High, Low, Close, Volume) candle at a specific timeframe.
 *
 * WHAT IS A CANDLE?
 * The CandleAggregator groups ticks into time periods.
 * For a MIN_15 candle covering 09:00–09:15:
 * open = the bid price of the FIRST tick in that period
 * high = the highest bid price seen during that period
 * low = the lowest bid price seen during that period
 * close = the bid price of the LAST tick before the period ends
 * volume = count of ticks received (OANDA doesn't provide real volume)
 *
 * WHY CANDLES MATTER IN ICT:
 * Every ICT concept — FVGs, Order Blocks, Market Structure — is
 * defined in terms of candle relationships. The FVG detector looks
 * at three consecutive candles. The Order Block detector looks at
 * the last candle before a displacement. All of this requires
 * properly formed OHLCV candles.
 *
 * All prices are scaled longs — see PriceScale.java and Tick.java.
 */

public record Candle(
		Instant time, // The candle OPEN time (when this period started)
		String instrument,
		Timeframe timeframe,
		long open,
		long high,
		long low,
		long close,
		long volume) {

	// ── Candle direction ──

	/**
	 * Bullish candle: close is above open (green candle).
	 * In ICT: bullish candles are candidates for Bullish Order Blocks
	 * before a bearish displacement.
	 */

	public boolean isBullish() {
		return close > open;
	}

	/**
	 * Bearish candle: close is below open (red candle).
	 * In ICT: bearish candles are candidates for Bearish Order Blocks
	 * before a bullish displacement.
	 */

	public boolean isBearish() {
		return close < open;
	}

	/** Doji: open equals close — indecision candle. */
	public boolean isDoji() {
		return close == open;
	}

	// ── Candle measurements (all in scaled long units) ────

	/**
	 * Body size: the absolute distance between open and close.
	 * Used by OrderBlockDetector to measure displacement.
	 * A large body relative to the ATR indicates institutional movement.
	 */

	public long bodySize() {
		return Math.abs(close - open);
	}

	/**
	 * Total range from low to high.
	 * Used to calculate ATR (Average True Range).
	 */

	public long totalRange() {
		return high - low;
	}

	/**
	 * Upper wick: the distance from the body top to the high.
	 * For a bullish candle: high - close
	 * For a bearish candle: high - open
	 * In ICT: a long upper wick on a bearish candle = Rejection Block
	 */

	public long upperWick() {
		return high - Math.max(open, close);
	}

	/**
	 * Lower wick: the distance from the body bottom to the low.
	 * For a bullish candle: open - low
	 * For a bearish candle: close - low
	 * In ICT: a long lower wick on a bullish candle = Rejection Block
	 */

	public long lowerWick() {
		return Math.min(open, close) - low;
	}

	/**
	 * Body-to-range ratio: how much of the total range is the body.
	 * A ratio close to 1.0 means almost no wicks — strong directional move.
	 * A ratio close to 0.0 means mostly wicks — indecision.
	 * Used to qualify displacement candles in OrderBlockDetector.
	 */

	public double bodyRatio() {
		if (totalRange() == 0)
			return 0.0;
		return (double) bodySize() / totalRange();
	}
}
