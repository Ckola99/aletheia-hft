package com.aletheia.strategy;

import com.aletheia.core.Candle;
import com.aletheia.core.KillzoneWindow;
import com.aletheia.core.SwingPoint;
import com.aletheia.core.SwingType;
import com.aletheia.core.Timeframe;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for SmtDivergenceDetector and SwingPointRegistry.
 *
 * Each test populates the registry with known swing data for two
 * instruments and verifies the detector finds (or doesn't find)
 * the expected divergence.
 */
class SmtDivergenceDetectorTest {

	private final SmtDivergenceDetector detector = new SmtDivergenceDetector();
	private final SwingPointRegistry registry = new SwingPointRegistry(2, 20);

	/**
	 * Helper: build candles that produce specific swing points.
	 * We need to carefully construct candle sequences that the
	 * SwingPointDetector (lookback=2) will identify as swings.
	 */
	private long timeCounter = 1_000_000_000L;

	private Candle candle(String instrument, long high, long low) {
		timeCounter += 900;
		long mid = (high + low) / 2;
		return new Candle(
				Instant.ofEpochSecond(timeCounter),
				instrument,
				Timeframe.MIN_15,
				mid, high, low, mid,
				100L);
	}

	/**
	 * Builds candles for an instrument with TWO swing lows at specific prices.
	 *
	 * Pattern: high → LOW1 → high → LOW2 → high
	 * With lookback=2, each low needs 2 higher candles on each side.
	 */
	private List<Candle> candlesWithTwoSwingLows(String instrument,
			long low1, long low2) {
		// Reset time for each instrument so they're roughly simultaneous
		long savedTime = timeCounter;

		List<Candle> candles = List.of(
				// High before swing low 1
				candle(instrument, 108_500L, 108_400L),
				candle(instrument, 108_450L, 108_350L),
				// Swing low 1
				candle(instrument, 108_200L, low1),
				// Recovery
				candle(instrument, 108_450L, 108_350L),
				candle(instrument, 108_500L, 108_400L),
				// Transition
				candle(instrument, 108_480L, 108_380L),
				candle(instrument, 108_420L, 108_320L),
				// Swing low 2
				candle(instrument, 108_200L, low2),
				// Recovery
				candle(instrument, 108_450L, 108_350L),
				candle(instrument, 108_500L, 108_400L));

		return candles;
	}

	/**
	 * Builds candles for an instrument with TWO swing highs at specific prices.
	 */
	private List<Candle> candlesWithTwoSwingHighs(String instrument,
			long high1, long high2) {
		List<Candle> candles = List.of(
				// Low before swing high 1
				candle(instrument, 108_200L, 108_100L),
				candle(instrument, 108_250L, 108_150L),
				// Swing high 1
				candle(instrument, high1, 108_400L),
				// Pullback
				candle(instrument, 108_250L, 108_150L),
				candle(instrument, 108_200L, 108_100L),
				// Transition
				candle(instrument, 108_220L, 108_120L),
				candle(instrument, 108_280L, 108_180L),
				// Swing high 2
				candle(instrument, high2, 108_400L),
				// Pullback
				candle(instrument, 108_250L, 108_150L),
				candle(instrument, 108_200L, 108_100L));

		return candles;
	}

	// ── BULLISH SMT ─────────────────────────────────────────────────

	@Test
	void detects_bullish_smt_when_instrumentB_makes_lower_low() {
		// GBP/USD swing lows: 108_100 → 108_050 (LOWER LOW — the trap)
		// EUR/USD swing lows: 108_100 → 108_150 (HIGHER LOW — failed to follow)
		timeCounter = 1_000_000_000L;
		List<Candle> eurCandles = candlesWithTwoSwingLows("EUR_USD", 108_100L, 108_150L);
		timeCounter = 1_000_000_000L; // reset so times are close
		List<Candle> gbpCandles = candlesWithTwoSwingLows("GBP_USD", 108_100L, 108_050L);

		registry.update("EUR_USD", Timeframe.MIN_15, eurCandles);
		registry.update("GBP_USD", Timeframe.MIN_15, gbpCandles);

		Optional<SmtDivergenceSignal> signal = detector.detect(
				SmtPair.EUR_GBP,
				Timeframe.MIN_15,
				registry,
				KillzoneWindow.LONDON_OPEN);

		assertThat(signal).isPresent();

		SmtDivergenceSignal smt = signal.get();
		assertThat(smt.type()).isEqualTo(SmtType.BULLISH);
		assertThat(smt.instrumentToTrade()).isEqualTo("EUR_USD");
		assertThat(smt.judasInstrument()).isEqualTo("GBP_USD");
		assertThat(smt.killzone()).isEqualTo(KillzoneWindow.LONDON_OPEN);

		System.out.println("Bullish SMT detected:");
		System.out.println("  GBP/USD trap swing low at: " + smt.trapSwing().price());
		System.out.println("  EUR/USD confirming low at: " + smt.confirmingSwing().price());
	}

	// ── BEARISH SMT ─────────────────────────────────────────────────

	@Test
	void detects_bearish_smt_when_instrumentB_makes_higher_high() {
		// GBP/USD swing highs: 108_500 → 108_550 (HIGHER HIGH — the trap)
		// EUR/USD swing highs: 108_500 → 108_450 (LOWER HIGH — failed to follow)
		timeCounter = 1_000_000_000L;
		List<Candle> eurCandles = candlesWithTwoSwingHighs("EUR_USD", 108_500L, 108_450L);
		timeCounter = 1_000_000_000L;
		List<Candle> gbpCandles = candlesWithTwoSwingHighs("GBP_USD", 108_500L, 108_550L);

		registry.update("EUR_USD", Timeframe.MIN_15, eurCandles);
		registry.update("GBP_USD", Timeframe.MIN_15, gbpCandles);

		Optional<SmtDivergenceSignal> signal = detector.detect(
				SmtPair.EUR_GBP,
				Timeframe.MIN_15,
				registry,
				KillzoneWindow.NEW_YORK_OPEN);

		assertThat(signal).isPresent();

		SmtDivergenceSignal smt = signal.get();
		assertThat(smt.type()).isEqualTo(SmtType.BEARISH);
		assertThat(smt.instrumentToTrade()).isEqualTo("EUR_USD");
		assertThat(smt.judasInstrument()).isEqualTo("GBP_USD");
	}

	// ── NO DIVERGENCE CASES ─────────────────────────────────────────

	@Test
	void no_signal_when_both_make_lower_lows() {
		// Both make lower lows — they agree, no divergence
		// GBP/USD: 108_100 → 108_050 (LL)
		// EUR/USD: 108_100 → 108_060 (LL — also made a lower low)
		timeCounter = 1_000_000_000L;
		List<Candle> eurCandles = candlesWithTwoSwingLows("EUR_USD", 108_100L, 108_060L);
		timeCounter = 1_000_000_000L;
		List<Candle> gbpCandles = candlesWithTwoSwingLows("GBP_USD", 108_100L, 108_050L);

		registry.update("EUR_USD", Timeframe.MIN_15, eurCandles);
		registry.update("GBP_USD", Timeframe.MIN_15, gbpCandles);

		Optional<SmtDivergenceSignal> signal = detector.detect(
				SmtPair.EUR_GBP, Timeframe.MIN_15, registry,
				KillzoneWindow.LONDON_OPEN);

		assertThat(signal).isEmpty();
	}

	@Test
	void no_signal_outside_killzone() {
		timeCounter = 1_000_000_000L;
		List<Candle> eurCandles = candlesWithTwoSwingLows("EUR_USD", 108_100L, 108_150L);
		timeCounter = 1_000_000_000L;
		List<Candle> gbpCandles = candlesWithTwoSwingLows("GBP_USD", 108_100L, 108_050L);

		registry.update("EUR_USD", Timeframe.MIN_15, eurCandles);
		registry.update("GBP_USD", Timeframe.MIN_15, gbpCandles);

		Optional<SmtDivergenceSignal> signal = detector.detect(
				SmtPair.EUR_GBP, Timeframe.MIN_15, registry,
				KillzoneWindow.NONE // outside killzone
		);

		assertThat(signal).isEmpty();
	}

	@Test
	void no_signal_when_insufficient_swing_data() {
		// Only EUR/USD has data, GBP/USD is empty
		timeCounter = 1_000_000_000L;
		List<Candle> eurCandles = candlesWithTwoSwingLows("EUR_USD", 108_100L, 108_150L);
		registry.update("EUR_USD", Timeframe.MIN_15, eurCandles);
		// GBP_USD not updated — no data

		Optional<SmtDivergenceSignal> signal = detector.detect(
				SmtPair.EUR_GBP, Timeframe.MIN_15, registry,
				KillzoneWindow.LONDON_OPEN);

		assertThat(signal).isEmpty();
	}

	// ── REGISTRY TESTS ──────────────────────────────────────────────

	@Test
	void registry_stores_and_retrieves_swings() {
		timeCounter = 1_000_000_000L;
		List<Candle> candles = candlesWithTwoSwingLows("EUR_USD", 108_100L, 108_050L);

		registry.update("EUR_USD", Timeframe.MIN_15, candles);

		List<SwingPoint> swings = registry.getSwings("EUR_USD", Timeframe.MIN_15);
		assertThat(swings).isNotEmpty();

		// The list should be immutable
		assertThatThrownBy(() -> swings.add(null))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void registry_returns_empty_for_unknown_instrument() {
		List<SwingPoint> swings = registry.getSwings("UNKNOWN", Timeframe.MIN_15);
		assertThat(swings).isEmpty();
	}

	@Test
	void registry_keeps_instruments_separate() {
		timeCounter = 1_000_000_000L;
		List<Candle> eurCandles = candlesWithTwoSwingLows("EUR_USD", 108_100L, 108_050L);
		timeCounter = 2_000_000_000L;
		List<Candle> gbpCandles = candlesWithTwoSwingLows("GBP_USD", 133_800L, 133_750L);

		registry.update("EUR_USD", Timeframe.MIN_15, eurCandles);
		registry.update("GBP_USD", Timeframe.MIN_15, gbpCandles);

		List<SwingPoint> eurSwings = registry.getSwings("EUR_USD", Timeframe.MIN_15);
		List<SwingPoint> gbpSwings = registry.getSwings("GBP_USD", Timeframe.MIN_15);

		// Each instrument has its own swings
		assertThat(eurSwings).isNotEmpty();
		assertThat(gbpSwings).isNotEmpty();

		// EUR swings should all be for EUR_USD
		eurSwings.forEach(s -> assertThat(s.instrument()).isEqualTo("EUR_USD"));

		// GBP swings should all be for GBP_USD
		gbpSwings.forEach(s -> assertThat(s.instrument()).isEqualTo("GBP_USD"));
	}
}
