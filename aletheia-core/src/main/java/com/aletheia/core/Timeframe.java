package com.aletheia.core;

/**
 * Every timeframe Aletheia operates on, from 5 seconds to monthly.
 *
 * DESIGN PRINCIPLE — Timeframe is a parameter, not a class hierarchy.
 * The same FVG detector, the same Order Block detector, the same
 * Market Structure analyser all work on any Timeframe. You pass
 * the timeframe in — you don't create a subclass for each one.
 *
 * This is the code expression of ICT's fractal principle:
 * the pattern is the same at every scale.
 *
 * USAGE IN ALETHEIA:
 * HTF (Higher Timeframe) = MIN_15 → establishes bias, finds PD Arrays
 * LTF (Lower Timeframe) = SECONDS_5 → detects Judas Swing, finds entry FVG
 */

public enum Timeframe {

	// ── Lower timeframes (LTF) — execution layer ───
	SECONDS_5,
	SECONDS_15,
	SECONDS_30,

	// ── Mid timeframes — context layer ───
	MIN_1,
	MIN_5,
	MIN_15, // ← PRIMARY HTF in Aletheia
	MIN_30,

	// ── Higher timeframes — bias layer ───
	HOUR_1,
	HOUR_4,

	// ── Macro timeframes — USDX bias layer ───
	DAILY,
	WEEKLY,
	MONTHLY;

	/**
	 * Returns the duration of this timeframe in seconds.
	 *
	 * Used by the CandleAggregator to know when a candle period has
	 * expired and a new one should open.
	 *
	 * This is a Java 14+ switch expression — cleaner than if/else chains.
	 * Every case must return a value. The compiler enforces that all
	 * enum values are handled (no default needed — missing case = compile error).
	 */
	
	public long toSeconds() {
		return switch (this) {
			case SECONDS_5 -> 5;
			case SECONDS_15 -> 15;
			case SECONDS_30 -> 30;
			case MIN_1 -> 60;
			case MIN_5 -> 300;
			case MIN_15 -> 900;
			case MIN_30 -> 1_800;
			case HOUR_1 -> 3_600;
			case HOUR_4 -> 14_400;
			case DAILY -> 86_400;
			case WEEKLY -> 604_800;
			case MONTHLY -> 2_592_000; // approximation: 30 days
		};
	}

	/**
	 * Returns true if this is a macro timeframe used for USDX bias analysis.
	 * The UsdxBiasEngine only looks at DAILY, WEEKLY, and MONTHLY candles.
	 */
	public boolean isMacro() {
		return this == DAILY || this == WEEKLY || this == MONTHLY;
	}

	/**
	 * Returns true if this is the primary HTF used for PD Array detection.
	 */
	public boolean isPrimaryHtf() {
		return this == MIN_15;
	}

	/**
	 * Returns true if this is a LTF used for entry execution.
	 */
	public boolean isExecutionTf() {
		return this == SECONDS_5 || this == SECONDS_15 || this == SECONDS_30;
	}
}
