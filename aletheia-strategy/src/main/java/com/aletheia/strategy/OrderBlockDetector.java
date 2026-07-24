package com.aletheia.strategy;

import com.aletheia.core.Candle;
import com.aletheia.core.Timeframe;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects Order Blocks (OBs) in a sequence of candles.
 *
 * ICT THEORY:
 * An Order Block is the last opposing candle before a displacement move.
 * Institutional traders place large orders at these levels. When price
 * returns to an Order Block, those orders are still resting there —
 * making it a high-probability reversal zone.
 *
 * BULLISH ORDER BLOCK:
 * The last BEARISH candle (red) before a strong BULLISH displacement.
 * Institutions were accumulating longs during that bearish candle.
 * When price returns to this zone, those buy orders absorb selling.
 *
 * candle[i] is BEARISH (the Order Block)
 * candle[i+1] is BULLISH with body > 1.5× ATR (the displacement)
 *
 * BEARISH ORDER BLOCK:
 * The last BULLISH candle (green) before a strong BEARISH displacement.
 * Institutions were distributing shorts during that bullish candle.
 *
 * candle[i] is BULLISH (the Order Block)
 * candle[i+1] is BEARISH with body > 1.5× ATR (the displacement)
 *
 * CONFIGURABLE PARAMETERS:
 * - atrPeriod: how many candles to average for ATR (default 20)
 * - displacementMultiplier: how many times ATR the body must exceed (default
 * 1.5)
 */

public class OrderBlockDetector {

	private final int atrPeriod;
	private final double displacementMultiplier;

	/**
	 * Creates a detector with default settings.
	 * ATR period 20, displacement multiplier 1.5.
	 */
	public OrderBlockDetector() {
		this(20, 1.5);
	}

	/**
	 * Creates a detector with custom settings.
	 *
	 * @param atrPeriod              how many candles for ATR calculation
	 * @param displacementMultiplier body must be > ATR × this value
	 */
	public OrderBlockDetector(int atrPeriod, double displacementMultiplier) {
		this.atrPeriod = atrPeriod;
		this.displacementMultiplier = displacementMultiplier;
	}

	/**
	 * Scans a list of candles for Order Blocks.
	 *
	 * @param candles candles in chronological order (oldest first).
	 *                Must have at least atrPeriod + 2 candles.
	 * @return list of detected Order Blocks, may be empty
	 */
	public List<OrderBlock> detect(List<Candle> candles) {
		List<OrderBlock> orderBlocks = new ArrayList<>();

		if (candles == null || candles.size() < atrPeriod + 2) {
			return orderBlocks;
		}

		// Start scanning after enough candles exist for ATR calculation
		// We need atrPeriod candles BEFORE the candidate to calculate ATR
		for (int i = atrPeriod; i < candles.size() - 1; i++) {
			Candle candidate = candles.get(i); // potential Order Block
			Candle displacement = candles.get(i + 1); // the candle after it

			// Calculate ATR up to the candidate candle
			long atr = AtrCalculator.calculate(candles, i, atrPeriod);

			if (atr == 0) {
				continue; // not enough data for ATR — skip
			}

			// Is the displacement candle "big enough"?
			// displacement threshold = ATR × multiplier
			// We use integer arithmetic to avoid floating-point:
			// body > atr × 1.5 is equivalent to body × 10 > atr × 15
			// (multiply both sides by 10 to eliminate the decimal)
			long bodySize = displacement.bodySize();
			long threshold = (long) (atr * displacementMultiplier);

			if (bodySize <= threshold) {
				continue; // displacement not strong enough — skip
			}

			// ── BULLISH ORDER BLOCK ─────────────────────────────────
			// Candidate is BEARISH (red), displacement is BULLISH (green)
			// The bearish candle before the big bullish move = bullish OB
			if (candidate.isBearish() && displacement.isBullish()) {
				orderBlocks.add(new OrderBlock(
						PdArray.Bias.BULLISH,
						candidate.high(), // upper boundary = OB candle's high
						candidate.low(), // lower boundary = OB candle's low
						candidate.time(),
						candidate.timeframe()));
			}

			// ── BEARISH ORDER BLOCK ─────────────────────────────────
			// Candidate is BULLISH (green), displacement is BEARISH (red)
			// The bullish candle before the big bearish move = bearish OB
			if (candidate.isBullish() && displacement.isBearish()) {
				orderBlocks.add(new OrderBlock(
						PdArray.Bias.BEARISH,
						candidate.high(),
						candidate.low(),
						candidate.time(),
						candidate.timeframe()));
			}
		}

		return orderBlocks;
	}
}
