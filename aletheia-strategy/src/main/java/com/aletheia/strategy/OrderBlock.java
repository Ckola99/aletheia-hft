package com.aletheia.strategy;

import com.aletheia.core.Timeframe;

import java.time.Instant;

/**
 * An Order Block (OB) — the last opposing candle before a displacement move.
 *
 * Full implementation comes when we build OrderBlockDetector.
 * This record exists now so the sealed PdArray interface compiles.
 */
public record OrderBlock(
		Bias bias,
		long upper,
		long lower,
		Instant time,
		Timeframe timeframe) implements PdArray {
}
