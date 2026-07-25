package com.aletheia.strategy;

import com.aletheia.core.Candle;
import com.aletheia.core.MarketBias;

import java.util.List;

/**
 * Determines the directional bias of the US Dollar Index (USDX)
 * by analysing market structure across three timeframes.
 *
 * ICT THEORY:
 * Before looking at any individual pair, you must know what the
 * dollar is doing. The dollar is the common denominator:
 * - EUR/USD = Euro priced IN dollars
 * - GBP/USD = Pound priced IN dollars
 * - XAU/USD = Gold priced IN dollars
 *
 * If the dollar is getting stronger (USDX bullish), all these pairs
 * go DOWN. If the dollar is getting weaker (USDX bearish), they go UP.
 *
 * The engine requires structure agreement across Monthly, Weekly,
 * and Daily candles for maximum confidence.
 *
 * USAGE:
 * UsdxBias bias = engine.compute(monthlyCandles, weeklyCandles, dailyCandles);
 * if (bias.isTradeable()) {
 * MarketBias eurBias = bias.biasForPair("EUR_USD");
 * // eurBias is the direction to look for trades on EUR/USD
 * }
 */
public class UsdxBiasEngine {

	private final MarketStructureAnalyser analyser;

	/**
	 * @param lookback swing point lookback for the structure analyser.
	 *                 Macro timeframes typically use a larger lookback (5)
	 *                 because each swing is more significant.
	 */
	public UsdxBiasEngine(int lookback) {
		this.analyser = new MarketStructureAnalyser(lookback);
	}

	/**
	 * Creates an engine with default lookback of 5.
	 */
	public UsdxBiasEngine() {
		this(5);
	}

	/**
	 * Computes the USDX bias from three timeframes of candle data.
	 *
	 * @param monthlyCandles USDX monthly candles (oldest first, at least 30)
	 * @param weeklyCandles  USDX weekly candles (oldest first, at least 30)
	 * @param dailyCandles   USDX daily candles (oldest first, at least 30)
	 * @return the computed USDX bias with confidence level
	 */
	public UsdxBias compute(List<Candle> monthlyCandles,
			List<Candle> weeklyCandles,
			List<Candle> dailyCandles) {

		// Analyse structure on each timeframe independently
		MarketBias monthly = analyser.analyse(monthlyCandles).bias();
		MarketBias weekly = analyser.analyse(weeklyCandles).bias();
		MarketBias daily = analyser.analyse(dailyCandles).bias();

		// Determine consensus
		MarketBias direction;
		ConfidenceLevel confidence;

		if (monthly == weekly && weekly == daily && monthly.isDirectional()) {
			// All three agree on a directional bias
			direction = daily;
			confidence = ConfidenceLevel.HIGH;

		} else if (monthly == weekly && monthly.isDirectional()) {
			// Monthly and weekly agree — daily diverges
			direction = monthly;
			confidence = ConfidenceLevel.MEDIUM;

		} else if (weekly == daily && weekly.isDirectional()) {
			// Weekly and daily agree — monthly diverges
			direction = weekly;
			confidence = ConfidenceLevel.MEDIUM;

		} else if (monthly == daily && monthly.isDirectional()) {
			// Monthly and daily agree — weekly diverges
			direction = monthly;
			confidence = ConfidenceLevel.MEDIUM;

		} else {
			// No two directional timeframes agree
			direction = MarketBias.NEUTRAL;
			confidence = ConfidenceLevel.LOW;
		}

		return new UsdxBias(direction, confidence, monthly, weekly, daily);
	}
}
