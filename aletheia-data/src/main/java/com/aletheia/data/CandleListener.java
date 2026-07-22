package com.aletheia.data;

import com.aletheia.core.Candle;

/**
 * Callback interface for receiving closed (completed) candles.
 *
 * Called by CandleAggregator when a candle period ends and a new one begins.
 * The Candle passed to this method is immutable — safe to store, pass to
 * another thread, or use in strategy calculations.
 *
 * Consumers:
 * - Strategy engine: analyses completed candles for FVGs, Order Blocks etc.
 * - CandleRepository: persists completed candles to TimescaleDB
 * - SmtDivergenceDetector: updates swing point registry on candle close
 */
@FunctionalInterface
public interface CandleListener {
	void onCandleClosed(Candle candle);
}
