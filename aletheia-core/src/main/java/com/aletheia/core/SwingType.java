package com.aletheia.core;

/**
 * Whether a swing point is a high or a low.
 *
 * A SWING HIGH is a candle whose HIGH is higher than the N candles
 * to its left AND the N candles to its right. It is a local peak.
 *
 * A SWING LOW is a candle whose LOW is lower than the N candles
 * to its left AND the N candles to its right. It is a local trough.
 *
 * N (the lookback) is configurable in MarketStructureAnalyser.
 * A larger N means fewer, more significant swings.
 * A smaller N means more swings but more noise.
 *
 * Used by:
 * - MarketStructureAnalyser (to classify HH/HL/LH/LL)
 * - SmtDivergenceDetector (to compare swings across EUR/USD and GBP/USD)
 * - JudasSwingDetector (to identify the liquidity sweep target)
 */

public enum SwingType {
	HIGH,
	LOW
}
