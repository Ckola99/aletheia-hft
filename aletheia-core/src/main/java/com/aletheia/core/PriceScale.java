package com.aletheia.core;

/**
 * Converts between human-readable double prices and scaled long integers.
 *
 * THE FLOATING-POINT PROBLEM:
 * double a = 1.0825 + 0.0001;
 * // result: 1.0826000000000002 ← NOT 1.0826
 *
 * THE SOLUTION — scaled integers:
 * EUR/USD has 5 decimal places → scale = 100,000
 * 1.08250 stored as 108250L
 * 1.08260 stored as 108260L
 * 108260 - 108250 = 10 ← exactly 1 pip, no rounding error
 *
 * SCALE FACTORS BY INSTRUMENT:
 * EUR/USD, GBP/USD, AUD/USD → 5 decimal places → scale 100,000
 * XAU/USD (Gold) → 2 decimal places → scale 100
 * US30, NAS100 → 1 decimal place → scale 10
 *
 * All prices stored in TimescaleDB are BIGINT (scaled longs).
 * All prices in Java are long.
 * The only place a double appears is at the API boundary
 * (when OANDA sends us JSON with a double price) — we immediately
 * convert to scaled long and never use the double again.
 */

public final class PriceScale {

	// Private constructor — this is a utility class, no instances needed
	private PriceScale() {
	}

	/**
	 * Returns the scale factor for a given instrument.
	 * The scale factor is 10^(decimal places).
	 */

	public static long scaleFor(String instrument) {
		return switch (instrument) {
			case "EUR_USD", "GBP_USD", "AUD_USD",
					"USD_JPY", "USD_CHF", "USD_CAD" ->
				100_000L;
			case "XAU_USD" -> 100L;
			case "DOLLAR_IDX" -> 1_000L;
			case "US30_USD", "NAS100_USD",
					"SPX500_USD" ->
				10L;
			default -> 100_000L;
		};
	}

	/**
	 * Converts a double price to a scaled long.
	 *
	 * Called ONCE when we receive a price from OANDA's API.
	 * After this, we never use the double again.
	 *
	 * Math.round() handles floating-point imprecision in the input:
	 * 1.08250 * 100000 might compute as 108249.99999... due to float issues
	 * Math.round() gives us 108250 as intended.
	 */

	public static long toScaled(double price, String instrument) {
		return Math.round(price * scaleFor(instrument));
	}

	/**
	 * Converts a scaled long back to a double for display purposes only.
	 * Never use this result in arithmetic — convert back to scaled long first.
	 */

	public static double toDouble(long scaled, String instrument) {
		return (double) scaled / scaleFor(instrument);
	}

	/**
	 * Returns the value of 1 pip in scaled units for a given instrument.
	 *
	 * EUR/USD: 1 pip = 0.00010 = 10 in 100,000 scale
	 * XAU/USD: 1 pip = $0.10 = 10 in 100 scale
	 *
	 * Used by RiskManager to calculate stop loss distance in pips.
	 */

	public static long onePip(String instrument) {
		return switch (instrument) {
			case "EUR_USD", "GBP_USD", "AUD_USD" -> 10L;
			case "XAU_USD" -> 10L;
			case "US30_USD", "NAS100_USD" -> 10L;
			default -> 10L;
		};
	}
}
