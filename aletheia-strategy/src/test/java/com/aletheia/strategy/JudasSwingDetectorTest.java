package com.aletheia.strategy;

import com.aletheia.core.Candle;
import com.aletheia.core.KillzoneWindow;
import com.aletheia.core.MarketBias;
import com.aletheia.core.Timeframe;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for JudasSwingDetector.
 *
 * Each test constructs a complete candle sequence representing
 * a specific ICT scenario and verifies the detector finds
 * (or correctly does not find) a Judas Swing signal.
 *
 * These are the most complex tests in the codebase because the
 * Judas Swing is a multi-step pattern requiring swing formation,
 * liquidity sweep, displacement, and FVG — all in one sequence.
 */
class JudasSwingDetectorTest {

	// Small ATR period for manageable test data
	// Swing lookback=2, ATR period=5, displacement=1.5×
	private final JudasSwingDetector detector = new JudasSwingDetector(2, 5, 1.5);

	private long timeCounter = 1_000_000_000L;

	private Candle candle(long open, long high, long low, long close) {
		timeCounter += 5; // 5-second candles
		return new Candle(
				Instant.ofEpochSecond(timeCounter),
				"EUR_USD",
				Timeframe.SECONDS_5,
				open, high, low, close,
				50L);
	}

	/**
	 * Builds the full candle sequence for a BULLISH Judas Swing.
	 *
	 * The sequence has:
	 * 1. Normal candles to establish ATR
	 * 2. A swing low forms (price dips and recovers)
	 * 3. Price dips AGAIN, below the swing low (the sweep)
	 * 4. A strong bullish candle forms (the displacement)
	 * 5. The displacement creates an FVG (gap in the candles)
	 */
	private List<Candle> buildBullishJudasSequence() {
		List<Candle> candles = new ArrayList<>();

		// Phase 1: Normal ranging candles to establish ATR
		// Each has range ~100 (high - low), so ATR ≈ 100
		candles.add(candle(108_200L, 108_280L, 108_180L, 108_250L));
		candles.add(candle(108_250L, 108_320L, 108_220L, 108_300L));
		candles.add(candle(108_300L, 108_370L, 108_270L, 108_350L));
		candles.add(candle(108_350L, 108_420L, 108_310L, 108_400L));
		candles.add(candle(108_400L, 108_470L, 108_360L, 108_440L));

		// Phase 2: Price dips down to form a SWING LOW at 108_100
		candles.add(candle(108_440L, 108_450L, 108_300L, 108_320L));
		candles.add(candle(108_320L, 108_330L, 108_150L, 108_180L));
		candles.add(candle(108_180L, 108_200L, 108_100L, 108_130L)); // swing low = 108100
		candles.add(candle(108_130L, 108_250L, 108_120L, 108_230L));
		candles.add(candle(108_230L, 108_350L, 108_200L, 108_320L));

		// Phase 3: Price recovers then dips AGAIN — BELOW the swing low
		// This is the SWEEP — price goes to 108_050, below 108_100
		candles.add(candle(108_320L, 108_340L, 108_200L, 108_220L));
		candles.add(candle(108_220L, 108_230L, 108_050L, 108_080L)); // SWEEP: low=108050 < 108100

		// Phase 4: DISPLACEMENT — strong bullish candle
		// Body must be > 1.5 × ATR. ATR ≈ 100, so body > 150
		// Body = 108_350 - 108_090 = 260 > 150 ✓
		//
		// This candle must also create an FVG with its neighbours.
		// prev candle high = 108_230
		candles.add(candle(108_090L, 108_360L, 108_080L, 108_350L)); // DISPLACEMENT

		// Phase 5: Next candle — its low must be ABOVE prev-prev candle's high
		// for an FVG to exist.
		// prev-prev (sweep candle) high = 108_230
		// This candle's low = 108_340 > 108_230 → FVG exists!
		// FVG zone: 108_230 (prev-prev high) to 108_340 (this candle's low)
		candles.add(candle(108_340L, 108_420L, 108_340L, 108_400L));

		// One more candle after for the detector to have room
		candles.add(candle(108_400L, 108_450L, 108_380L, 108_430L));

		return candles;
	}

	/**
	 * Builds the full candle sequence for a BEARISH Judas Swing.
	 * Mirror image of the bullish sequence.
	 */
	private List<Candle> buildBearishJudasSequence() {
		List<Candle> candles = new ArrayList<>();

		// Phase 1: Normal candles for ATR
		candles.add(candle(108_400L, 108_480L, 108_380L, 108_450L));
		candles.add(candle(108_450L, 108_520L, 108_420L, 108_500L));
		candles.add(candle(108_500L, 108_570L, 108_470L, 108_550L));
		candles.add(candle(108_550L, 108_620L, 108_510L, 108_600L));
		candles.add(candle(108_600L, 108_670L, 108_560L, 108_640L));

		// Phase 2: Price pushes up to form SWING HIGH at 108_900
		candles.add(candle(108_640L, 108_700L, 108_600L, 108_680L));
		candles.add(candle(108_680L, 108_800L, 108_660L, 108_780L));
		candles.add(candle(108_780L, 108_900L, 108_750L, 108_860L)); // swing high = 108900
		candles.add(candle(108_860L, 108_880L, 108_730L, 108_750L));
		candles.add(candle(108_750L, 108_770L, 108_650L, 108_680L));

		// Phase 3: Price recovers then pushes ABOVE swing high = SWEEP
		candles.add(candle(108_680L, 108_800L, 108_660L, 108_780L));
		candles.add(candle(108_780L, 108_950L, 108_770L, 108_920L)); // SWEEP: high=108950 > 108900

		// Phase 4: DISPLACEMENT — strong bearish candle
		// Body = 108_910 - 108_650 = 260 > 150 ✓
		// prev candle low = 108_770
		candles.add(candle(108_910L, 108_920L, 108_640L, 108_650L)); // DISPLACEMENT

		// Phase 5: Next candle — its high must be BELOW prev-prev candle's low
		// for a bearish FVG to exist.
		// prev-prev (sweep candle) low = 108_770
		// This candle's high = 108_660 < 108_770 → bearish FVG exists!
		candles.add(candle(108_660L, 108_660L, 108_580L, 108_600L));

		candles.add(candle(108_600L, 108_620L, 108_550L, 108_570L));

		return candles;
	}

	// ── BULLISH JUDAS SWING TESTS ───────────────────────────────────

	@Test
	void detects_bullish_judas_swing() {
		List<Candle> candles = buildBullishJudasSequence();

		Optional<JudasSwingSignal> signal = detector.detect(
				MarketBias.BULLISH,
				KillzoneWindow.LONDON_OPEN,
				candles);

		assertThat(signal).isPresent();

		JudasSwingSignal s = signal.get();
		assertThat(s.bias()).isEqualTo(MarketBias.BULLISH);
		assertThat(s.instrument()).isEqualTo("EUR_USD");
		assertThat(s.killzone()).isEqualTo(KillzoneWindow.LONDON_OPEN);
		assertThat(s.grade()).isEqualTo(SignalGrade.A);

		// The swept swing should be the swing low we created at 108_100
		assertThat(s.sweptSwing().price()).isEqualTo(108_100L);

		// The sweep price should be below the swing low
		assertThat(s.sweepPrice()).isLessThan(s.sweptSwing().price());

		// The entry zone (FVG) should exist
		assertThat(s.entryZone()).isNotNull();
		assertThat(s.entryZone().bias()).isEqualTo(PdArray.Bias.BULLISH);

		System.out.println("Bullish Judas Swing detected:");
		System.out.println("  Swept swing low at: " + s.sweptSwing().price());
		System.out.println("  Sweep price:        " + s.sweepPrice());
		System.out.println("  FVG entry zone:     " + s.entryZone().lower()
				+ " to " + s.entryZone().upper());
		System.out.println("  Ideal entry:        " + s.idealEntry());
	}

	// ── BEARISH JUDAS SWING TESTS ───────────────────────────────────

	@Test
	void detects_bearish_judas_swing() {
		List<Candle> candles = buildBearishJudasSequence();

		Optional<JudasSwingSignal> signal = detector.detect(
				MarketBias.BEARISH,
				KillzoneWindow.NEW_YORK_OPEN,
				candles);

		assertThat(signal).isPresent();

		JudasSwingSignal s = signal.get();
		assertThat(s.bias()).isEqualTo(MarketBias.BEARISH);
		assertThat(s.killzone()).isEqualTo(KillzoneWindow.NEW_YORK_OPEN);

		// Swept swing should be the swing high at 108_900
		assertThat(s.sweptSwing().price()).isEqualTo(108_900L);

		// Sweep price should be above the swing high
		assertThat(s.sweepPrice()).isGreaterThan(s.sweptSwing().price());

		// Entry FVG should be bearish
		assertThat(s.entryZone().bias()).isEqualTo(PdArray.Bias.BEARISH);

		System.out.println("Bearish Judas Swing detected:");
		System.out.println("  Swept swing high at: " + s.sweptSwing().price());
		System.out.println("  Sweep price:         " + s.sweepPrice());
		System.out.println("  FVG entry zone:      " + s.entryZone().lower()
				+ " to " + s.entryZone().upper());
	}

	// ── FAILURE CASES ───────────────────────────────────────────────

	@Test
	void no_signal_when_bias_is_neutral() {
		List<Candle> candles = buildBullishJudasSequence();

		Optional<JudasSwingSignal> signal = detector.detect(
				MarketBias.NEUTRAL,
				KillzoneWindow.LONDON_OPEN,
				candles);

		assertThat(signal).isEmpty();
	}

	@Test
	void no_signal_outside_killzone() {
		List<Candle> candles = buildBullishJudasSequence();

		Optional<JudasSwingSignal> signal = detector.detect(
				MarketBias.BULLISH,
				KillzoneWindow.NONE,
				candles);

		assertThat(signal).isEmpty();
	}

	@Test
	void no_signal_when_bias_direction_mismatches_pattern() {
		// Bullish candle pattern but BEARISH bias — should not detect
		// (Looking for a bearish Judas in bullish data won't find one)
		List<Candle> candles = buildBullishJudasSequence();

		Optional<JudasSwingSignal> signal = detector.detect(
				MarketBias.BEARISH, // wrong direction for this pattern
				KillzoneWindow.LONDON_OPEN,
				candles);

		assertThat(signal).isEmpty();
	}

	@Test
	void no_signal_with_insufficient_candles() {
		List<Candle> candles = List.of(
				candle(108_200L, 108_300L, 108_100L, 108_250L),
				candle(108_250L, 108_350L, 108_200L, 108_300L));

		Optional<JudasSwingSignal> signal = detector.detect(
				MarketBias.BULLISH,
				KillzoneWindow.LONDON_OPEN,
				candles);

		assertThat(signal).isEmpty();
	}

	@Test
	void no_signal_with_null_candles() {
		Optional<JudasSwingSignal> signal = detector.detect(
				MarketBias.BULLISH,
				KillzoneWindow.LONDON_OPEN,
				null);

		assertThat(signal).isEmpty();
	}
}
