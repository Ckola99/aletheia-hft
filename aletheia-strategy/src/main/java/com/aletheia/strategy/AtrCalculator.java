package com.aletheia.strategy;

import com.aletheia.core.Candle;

import java.util.List;

/**
 * Calculates the Average True Range (ATR) for a sequence of candles.
 *
 * WHAT IS ATR?
 * ATR measures the average "size" of recent candles — how much price
 * moved during each period. A 20-period ATR on 15-minute candles tells
 * you: "on average, the last twenty 15-minute candles had a range of X pips."
 *
 * WHY DO WE NEED IT?
 * The OrderBlockDetector needs to know whether a candle is "big" or "normal."
 * A 50-pip candle on EUR/USD might be huge during Asian session but normal
 * during NFP. ATR gives us a dynamic baseline — "big" means "bigger than
 * what's been normal recently."
 *
 * TRUE RANGE FOR A SINGLE CANDLE:
 * For simplicity, we use: high - low (the candle's total range).
 *
 * The formal True Range also considers the previous candle's close
 * (to account for gaps), but in forex, gaps are rare because the market
 * trades nearly 24 hours. high - low is sufficient for our purposes.
 *
 * ATR = average of the True Range over N candles.
 */
public class AtrCalculator {

	/**
	 * Calculates the ATR over the last N candles ending at the given index.
	 *
	 * @param candles  list of candles in chronological order
	 * @param endIndex the index of the last candle to include (inclusive)
	 * @param period   how many candles to average over (e.g. 20)
	 * @return the ATR as a scaled long, or 0 if not enough data
	 */
	
	public static long calculate(List<Candle> candles, int endIndex, int period) {
		// Need at least 'period' candles up to and including endIndex
		if (candles == null || endIndex < period - 1 || endIndex >= candles.size()) {
			return 0;
		}

		long sum = 0;
		int startIndex = endIndex - period + 1;

		for (int i = startIndex; i <= endIndex; i++) {
			Candle c = candles.get(i);
			sum += c.totalRange(); // high - low
		}

		return sum / period;
	}
}
