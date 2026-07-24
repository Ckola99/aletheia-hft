package com.aletheia.strategy;

import com.aletheia.core.Candle;
import com.aletheia.core.Timeframe;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects Fair Value Gaps (FVGs) in a sequence of candles.
 *
 * THE RULE IS SIMPLE:
 * Look at every group of 3 consecutive candles [prev, impulse, next].
 *
 * Bullish FVG: prev.high < next.low
 * → There is a gap between prev's high and next's low
 * → Price moved up so fast it left unfilled space
 *
 * Bearish FVG: prev.low > next.high
 * → There is a gap between next's high and prev's low
 * → Price moved down so fast it left unfilled space
 *
 * TIMEFRAME AGNOSTIC:
 * This detector works identically on MONTHLY candles and SECONDS_5 candles.
 * You pass in a list of candles and a timeframe — the logic is the same.
 * This is the fractal principle in code.
 *
 * USAGE:
 * List<Candle> candles = candleRepo.findRecent("EUR_USD", Timeframe.MIN_15,
 * 100);
 * List<FairValueGap> fvgs = detector.detect(candles);
 * // fvgs contains every FVG found in the last 100 fifteen-minute candles
 */
public class FairValueGapDetector {

	/**
	 * Scans a list of candles for Fair Value Gaps.
	 *
	 * @param candles candles in chronological order (oldest first).
	 *                Must have at least 3 candles to detect any FVG.
	 * @return list of detected FVGs, may be empty
	 */
	public List<FairValueGap> detect(List<Candle> candles) {
		List<FairValueGap> fvgs = new ArrayList<>();

		// Need at least 3 candles — the pattern requires [prev, impulse, next]
		if (candles == null || candles.size() < 3) {
			return fvgs;
		}

		// Slide a 3-candle window across the list
		// i is the index of the IMPULSE candle (the middle one)
		for (int i = 1; i < candles.size() - 1; i++) {
			Candle prev = candles.get(i - 1); // candle before the impulse
			Candle impulse = candles.get(i); // the fast-moving candle
			Candle next = candles.get(i + 1); // candle after the impulse

			// All three candles must be the same instrument
			// (safety check — shouldn't happen with properly filtered data)
			if (!prev.instrument().equals(next.instrument())) {
				continue;
			}

			Timeframe tf = impulse.timeframe();

			// ── BULLISH FVG ──────────────────────────────────────────
			// prev candle's HIGH is below next candle's LOW
			// → a gap exists between them where price was never traded
			//
			// prev: ─┤ high (108200) ←── bottom of gap
			// impulse: ────────┤ (big green candle)
			// next: ├── low (108300) ←── top of gap
			// GAP ↕ (108200 to 108300)
			//
			if (prev.high() < next.low()) {
				fvgs.add(new FairValueGap(
						PdArray.Bias.BULLISH,
						next.low(), // upper boundary of gap
						prev.high(), // lower boundary of gap
						impulse.time(), // when it formed
						tf));
			}

			// ── BEARISH FVG ──────────────────────────────────────────
			// prev candle's LOW is above next candle's HIGH
			// → a gap exists between them
			//
			// prev: ├── low (108300) ←── top of gap
			// impulse: ────────┤ (big red candle)
			// next: ─┤ high (108200) ←── bottom of gap
			// GAP ↕ (108200 to 108300)
			//
			if (prev.low() > next.high()) {
				fvgs.add(new FairValueGap(
						PdArray.Bias.BEARISH,
						prev.low(), // upper boundary of gap
						next.high(), // lower boundary of gap
						impulse.time(), // when it formed
						tf));
			}
		}

		return fvgs;
	}
}
