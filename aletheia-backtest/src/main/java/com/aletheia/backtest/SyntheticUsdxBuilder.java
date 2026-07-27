package com.aletheia.backtest;

import com.aletheia.core.Candle;
import com.aletheia.core.PriceScale;
import com.aletheia.core.Timeframe;

import java.util.List;

/**
 * Creates synthetic USDX candles by inverting EUR/USD candles.
 *
 * WHY SYNTHETIC?
 * The real USDX (US Dollar Index) is a basket of 6 currencies weighted:
 * EUR 57.6%, JPY 13.6%, GBP 11.9%, CAD 9.1%, SEK 4.2%, CHF 3.6%
 *
 * Since EUR is 57.6% of the weight, inverting EUR/USD gives a reasonable
 * proxy for dollar strength. When EUR/USD goes up (euro strengthens),
 * USDX goes down (dollar weakens). The structure patterns (HH/HL/LH/LL)
 * are what matter for bias, not the exact price level.
 *
 * For a more accurate USDX, you'd download DXY data from Dukascopy.
 * But for market structure analysis, the EUR/USD inverse is sufficient.
 *
 * HOW INVERSION WORKS:
 * EUR/USD = 1.0850 → USDX proxy = 1/1.0850 ≈ 0.9217
 *
 * For candle inversion:
 * EUR/USD open=1.0850, high=1.0900, low=1.0800, close=1.0870
 * USDX open=1/1.0850, high=1/1.0800, low=1/1.0900, close=1/1.0870
 *
 * NOTE: high and low are SWAPPED because inverting reverses the direction.
 * EUR/USD's highest price = the moment USDX was at its LOWEST.
 */
public class SyntheticUsdxBuilder {

	private static final long USDX_SCALE = 100_000L;

	/**
	 * Converts EUR/USD candles to synthetic USDX candles.
	 *
	 * @param eurUsdCandles EUR/USD candles at any timeframe
	 * @return synthetic USDX candles at the same timeframe
	 */
	public static List<Candle> fromEurUsd(List<Candle> eurUsdCandles) {
		return eurUsdCandles.stream()
				.map(SyntheticUsdxBuilder::invertCandle)
				.toList();
	}

	/**
	 * Inverts a single EUR/USD candle to a USDX proxy candle.
	 *
	 * EUR/USD open → USDX open = 1/open
	 * EUR/USD high → USDX LOW = 1/high (highest EUR = lowest USD)
	 * EUR/USD low → USDX HIGH = 1/low (lowest EUR = highest USD)
	 * EUR/USD close → USDX close = 1/close
	 */
	private static Candle invertCandle(Candle eur) {
		double scale = PriceScale.scaleFor("EUR_USD");

		// Convert scaled longs to doubles, invert, convert back
		double eurOpen = PriceScale.toDouble(eur.open(), "EUR_USD");
		double eurHigh = PriceScale.toDouble(eur.high(), "EUR_USD");
		double eurLow = PriceScale.toDouble(eur.low(), "EUR_USD");
		double eurClose = PriceScale.toDouble(eur.close(), "EUR_USD");

		// Invert and scale to USDX format
		// Note: EUR high becomes USDX low, EUR low becomes USDX high
		long usdxOpen = Math.round((1.0 / eurOpen) * USDX_SCALE);
		long usdxHigh = Math.round((1.0 / eurLow) * USDX_SCALE); // swapped!
		long usdxLow = Math.round((1.0 / eurHigh) * USDX_SCALE); // swapped!
		long usdxClose = Math.round((1.0 / eurClose) * USDX_SCALE);

		return new Candle(
				eur.time(),
				"USDX",
				eur.timeframe(),
				usdxOpen,
				usdxHigh,
				usdxLow,
				usdxClose,
				eur.volume());
	}
}
