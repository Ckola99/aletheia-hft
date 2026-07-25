package com.aletheia.strategy;

/**
 * How confident we are in the USDX directional bias.
 *
 * HIGH: All three timeframes (Monthly, Weekly, Daily) agree.
 * This is the strongest signal — institutional intent
 * is consistent across all scales.
 *
 * MEDIUM: Two of three timeframes agree.
 * Usable but with caution — one timeframe is diverging.
 *
 * LOW: No agreement — mixed or neutral across timeframes.
 * No trade. Wait for clarity.
 */
public enum ConfidenceLevel {
	HIGH,
	MEDIUM,
	LOW;

	/**
	 * Returns true if confidence is high enough to trade.
	 * In Aletheia, we require at least MEDIUM confidence.
	 * HIGH is preferred — the A+ checklist wants HIGH.
	 */
	public boolean isTradeable() {
		return this == HIGH || this == MEDIUM;
	}
}
