package com.aletheia.strategy;

import com.aletheia.core.Candle;
import com.aletheia.core.Timeframe;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for FairValueGapDetector.
 *
 * Each test fabricates a specific candle sequence and verifies the
 * detector finds (or correctly does not find) an FVG.
 *
 * The candle helper method lets us build candles with just OHLC values —
 * we control exactly what the detector sees so we know exactly what
 * it should produce.
 */
class FairValueGapDetectorTest {

	private final FairValueGapDetector detector = new FairValueGapDetector();

	// Time counter — each candle gets a unique timestamp
	private long timeCounter = 1_000_000_000L;

	/**
	 * Helper: create a candle with specific OHLC values.
	 * Each call advances the time counter so candles are chronological.
	 */
	private Candle candle(long open, long high, long low, long close) {
		timeCounter += 900; // 900 seconds = 15 minutes apart
		return new Candle(
				Instant.ofEpochSecond(timeCounter),
				"EUR_USD",
				Timeframe.MIN_15,
				open, high, low, close,
				100L);
	}

	// ── BULLISH FVG TESTS ───────────────────────────────────────────────

	@Test
	void detects_bullish_fvg_when_gap_exists() {
		// Setup: three candles where prev.high < next.low
		//
		// prev: open=108100 high=108200 low=108050 close=108180
		// impulse: open=108190 high=108500 low=108180 close=108480
		// next: open=108490 high=108600 low=108300 close=108550
		//
		// prev.high = 108200
		// next.low = 108300
		// 108200 < 108300 → BULLISH FVG exists!
		// Gap zone: 108200 (bottom) to 108300 (top)
		//
		List<Candle> candles = List.of(
				candle(108_100L, 108_200L, 108_050L, 108_180L), // prev
				candle(108_190L, 108_500L, 108_180L, 108_480L), // impulse
				candle(108_490L, 108_600L, 108_300L, 108_550L) // next
		);

		List<FairValueGap> fvgs = detector.detect(candles);

		assertThat(fvgs).hasSize(1);

		FairValueGap fvg = fvgs.get(0);
		assertThat(fvg.bias()).isEqualTo(PdArray.Bias.BULLISH);
		assertThat(fvg.lower()).isEqualTo(108_200L); // prev.high
		assertThat(fvg.upper()).isEqualTo(108_300L); // next.low
		assertThat(fvg.gapSize()).isEqualTo(100L); // 1 pip gap
	}

	@Test
	void bullish_fvg_contains_price_within_gap() {
		List<Candle> candles = List.of(
				candle(108_100L, 108_200L, 108_050L, 108_180L),
				candle(108_190L, 108_500L, 108_180L, 108_480L),
				candle(108_490L, 108_600L, 108_300L, 108_550L));

		FairValueGap fvg = detector.detect(candles).get(0);

		// Price inside the gap
		assertThat(fvg.contains(108_250L)).isTrue();

		// Price at the boundaries
		assertThat(fvg.contains(108_200L)).isTrue(); // lower boundary inclusive
		assertThat(fvg.contains(108_300L)).isTrue(); // upper boundary inclusive

		// Price outside the gap
		assertThat(fvg.contains(108_199L)).isFalse(); // just below
		assertThat(fvg.contains(108_301L)).isFalse(); // just above
	}

	// ── BEARISH FVG TESTS ───────────────────────────────────────────────

	@Test
	void detects_bearish_fvg_when_gap_exists() {
		// Setup: three candles where prev.low > next.high
		//
		// prev: open=108500 high=108550 low=108300 close=108350
		// impulse: open=108340 high=108350 low=108050 close=108080
		// next: open=108070 high=108200 low=108000 close=108050
		//
		// prev.low = 108300
		// next.high = 108200
		// 108300 > 108200 → BEARISH FVG exists!
		// Gap zone: 108200 (bottom) to 108300 (top)
		//
		List<Candle> candles = List.of(
				candle(108_500L, 108_550L, 108_300L, 108_350L), // prev
				candle(108_340L, 108_350L, 108_050L, 108_080L), // impulse
				candle(108_070L, 108_200L, 108_000L, 108_050L) // next
		);

		List<FairValueGap> fvgs = detector.detect(candles);

		assertThat(fvgs).hasSize(1);

		FairValueGap fvg = fvgs.get(0);
		assertThat(fvg.bias()).isEqualTo(PdArray.Bias.BEARISH);
		assertThat(fvg.upper()).isEqualTo(108_300L); // prev.low
		assertThat(fvg.lower()).isEqualTo(108_200L); // next.high
	}

	// ── NO FVG CASES ────────────────────────────────────────────────────

	@Test
	void no_fvg_when_candles_overlap() {
		// prev.high = 108300, next.low = 108250
		// 108300 > 108250 → candles overlap → NO bullish FVG
		//
		// prev.low = 108100, next.high = 108150
		// 108100 < 108150 → candles overlap → NO bearish FVG
		//
		List<Candle> candles = List.of(
				candle(108_150L, 108_300L, 108_100L, 108_280L),
				candle(108_280L, 108_400L, 108_270L, 108_380L),
				candle(108_370L, 108_450L, 108_250L, 108_420L));

		List<FairValueGap> fvgs = detector.detect(candles);

		assertThat(fvgs).isEmpty();
	}

	@Test
	void no_fvg_when_candles_exactly_touch() {
		// prev.high = 108300, next.low = 108300
		// 108300 < 108300 is FALSE (not strictly less than)
		// → no gap, no FVG
		//
		// "Exactly touching" is NOT a gap — there must be empty space
		//
		List<Candle> candles = List.of(
				candle(108_150L, 108_300L, 108_100L, 108_280L),
				candle(108_280L, 108_500L, 108_270L, 108_480L),
				candle(108_470L, 108_550L, 108_300L, 108_520L) // next.low = prev.high
		);

		List<FairValueGap> fvgs = detector.detect(candles);

		assertThat(fvgs).isEmpty();
	}

	// ── MULTIPLE FVGs ───────────────────────────────────────────────────

	@Test
	void detects_multiple_fvgs_in_long_sequence() {
		// 5 candles can contain up to 3 windows of [prev, impulse, next]
		// We construct a sequence where windows 1 and 3 have bullish FVGs
		// but window 2 does not
		//
		List<Candle> candles = List.of(
				candle(108_000L, 108_100L, 107_950L, 108_080L), // c0
				candle(108_080L, 108_400L, 108_070L, 108_380L), // c1 — impulse
				candle(108_370L, 108_500L, 108_200L, 108_480L), // c2 — c0.high(108100) < c2.low(108200)
										// → FVG!
				candle(108_480L, 108_800L, 108_470L, 108_780L), // c3 — impulse
				candle(108_770L, 108_900L, 108_600L, 108_880L) // c4 — c2.high(108500) < c4.low(108600)
										// → FVG!
		);

		List<FairValueGap> fvgs = detector.detect(candles);

		// Two bullish FVGs found
		long bullishCount = fvgs.stream()
				.filter(f -> f.bias() == PdArray.Bias.BULLISH)
				.count();
		assertThat(bullishCount).isGreaterThanOrEqualTo(2);
	}

	// ── EDGE CASES ──────────────────────────────────────────────────────

	@Test
	void returns_empty_for_fewer_than_three_candles() {
		// 2 candles — impossible to have a 3-candle pattern
		List<Candle> candles = List.of(
				candle(108_100L, 108_200L, 108_050L, 108_180L),
				candle(108_190L, 108_500L, 108_180L, 108_480L));

		assertThat(detector.detect(candles)).isEmpty();
	}

	@Test
	void returns_empty_for_null_input() {
		assertThat(detector.detect(null)).isEmpty();
	}

	@Test
	void returns_empty_for_empty_list() {
		assertThat(detector.detect(List.of())).isEmpty();
	}

	@Test
	void fvg_midpoint_is_center_of_gap() {
		List<Candle> candles = List.of(
				candle(108_100L, 108_200L, 108_050L, 108_180L),
				candle(108_190L, 108_500L, 108_180L, 108_480L),
				candle(108_490L, 108_600L, 108_400L, 108_550L));

		FairValueGap fvg = detector.detect(candles).get(0);
		// Gap: 108200 to 108400
		// Midpoint: (108200 + 108400) / 2 = 108300
		assertThat(fvg.midpoint()).isEqualTo((fvg.upper() + fvg.lower()) / 2);
	}

	@Test
	void works_on_any_timeframe() {
		// Create candles on SECONDS_5 instead of MIN_15
		// Same pattern should produce same FVG — timeframe is just a label
		Candle c0 = new Candle(Instant.ofEpochSecond(1000), "EUR_USD",
				Timeframe.SECONDS_5, 108_100L, 108_200L, 108_050L, 108_180L, 10L);
		Candle c1 = new Candle(Instant.ofEpochSecond(1005), "EUR_USD",
				Timeframe.SECONDS_5, 108_190L, 108_500L, 108_180L, 108_480L, 10L);
		Candle c2 = new Candle(Instant.ofEpochSecond(1010), "EUR_USD",
				Timeframe.SECONDS_5, 108_490L, 108_600L, 108_300L, 108_550L, 10L);

		List<FairValueGap> fvgs = detector.detect(List.of(c0, c1, c2));

		assertThat(fvgs).hasSize(1);
		assertThat(fvgs.get(0).timeframe()).isEqualTo(Timeframe.SECONDS_5);
	}
}
