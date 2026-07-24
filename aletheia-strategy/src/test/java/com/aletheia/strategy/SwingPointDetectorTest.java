package com.aletheia.strategy;

import com.aletheia.core.Candle;
import com.aletheia.core.SwingPoint;
import com.aletheia.core.SwingType;
import com.aletheia.core.Timeframe;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for SwingPointDetector.
 *
 * We construct candle sequences with known highs and lows
 * and verify the detector finds swings at the correct positions.
 */
class SwingPointDetectorTest {

	// Use lookback=2 for simpler test data (need fewer candles)
	private final SwingPointDetector detector = new SwingPointDetector(2);

	private long timeCounter = 1_000_000_000L;

	private Candle candle(long open, long high, long low, long close) {
		timeCounter += 900;
		return new Candle(
				Instant.ofEpochSecond(timeCounter),
				"EUR_USD",
				Timeframe.MIN_15,
				open, high, low, close,
				100L);
	}

	@Test
	void detects_swing_high() {
		// With lookback=2, candle[2] is a swing high if:
		// candle[2].high > candle[0].high (2 left)
		// candle[2].high > candle[1].high (1 left)
		// candle[2].high > candle[3].high (1 right)
		// candle[2].high > candle[4].high (2 right)
		//
		// Highs: 100, 200, 500, 300, 150
		// ^^^
		// swing high — highest of all 5
		//
		List<Candle> candles = List.of(
				candle(108_000L, 108_100L, 107_950L, 108_050L), // high=100
				candle(108_050L, 108_200L, 108_000L, 108_150L), // high=200
				candle(108_150L, 108_500L, 108_100L, 108_450L), // high=500 ← SWING HIGH
				candle(108_400L, 108_300L, 108_200L, 108_250L), // high=300
				candle(108_250L, 108_150L, 108_100L, 108_120L) // high=150
		);

		List<SwingPoint> swings = detector.detect(candles);

		List<SwingPoint> highs = swings.stream()
				.filter(SwingPoint::isHigh).toList();

		assertThat(highs).hasSize(1);
		assertThat(highs.get(0).price()).isEqualTo(108_500L);
	}

	@Test
	void detects_swing_low() {
		// Lows: 200, 150, 050, 100, 180
		// ^^^
		// swing low — lowest of all 5
		//
		List<Candle> candles = List.of(
				candle(108_300L, 108_400L, 108_200L, 108_350L), // low=200
				candle(108_250L, 108_300L, 108_150L, 108_200L), // low=150
				candle(108_100L, 108_200L, 108_050L, 108_150L), // low=050 ← SWING LOW
				candle(108_150L, 108_250L, 108_100L, 108_200L), // low=100
				candle(108_200L, 108_300L, 108_180L, 108_280L) // low=180
		);

		List<SwingPoint> swings = detector.detect(candles);

		List<SwingPoint> lows = swings.stream()
				.filter(SwingPoint::isLow).toList();

		assertThat(lows).hasSize(1);
		assertThat(lows.get(0).price()).isEqualTo(108_050L);
	}

	@Test
	void detects_both_swing_high_and_low_in_sequence() {
		// A realistic sequence: price goes up (swing high), then down (swing low)
		//
		// Highs: 100, 200, 400, 300, 150, 100, 050, 080, 120
		// ^^^
		// swing high
		//
		// Lows: 050, 080, 100, 090, 060, 020, 010, 030, 050
		// ^^^
		// swing low
		//
		List<Candle> candles = List.of(
				candle(108_050L, 108_100L, 108_050L, 108_080L),
				candle(108_080L, 108_200L, 108_080L, 108_180L),
				candle(108_180L, 108_400L, 108_100L, 108_350L), // swing high=400
				candle(108_350L, 108_300L, 108_090L, 108_100L),
				candle(108_100L, 108_150L, 108_060L, 108_080L),
				candle(108_080L, 108_100L, 108_020L, 108_040L),
				candle(108_040L, 108_050L, 108_010L, 108_030L), // swing low=010
				candle(108_030L, 108_080L, 108_030L, 108_070L),
				candle(108_070L, 108_120L, 108_050L, 108_100L));

		List<SwingPoint> swings = detector.detect(candles);

		List<SwingPoint> highs = swings.stream()
				.filter(SwingPoint::isHigh).toList();
		List<SwingPoint> lows = swings.stream()
				.filter(SwingPoint::isLow).toList();

		assertThat(highs).isNotEmpty();
		assertThat(lows).isNotEmpty();

		// The highest swing high should be 108_400
		long maxHigh = highs.stream()
				.mapToLong(SwingPoint::price).max().orElse(0);
		assertThat(maxHigh).isEqualTo(108_400L);

		// The lowest swing low should be 108_010
		long minLow = lows.stream()
				.mapToLong(SwingPoint::price).min().orElse(0);
		assertThat(minLow).isEqualTo(108_010L);
	}

	@Test
	void no_swing_when_candle_is_not_highest_on_both_sides() {
		// All highs are the same — no candle stands out
		List<Candle> candles = List.of(
				candle(108_000L, 108_200L, 108_000L, 108_100L),
				candle(108_000L, 108_200L, 108_000L, 108_100L),
				candle(108_000L, 108_200L, 108_000L, 108_100L),
				candle(108_000L, 108_200L, 108_000L, 108_100L),
				candle(108_000L, 108_200L, 108_000L, 108_100L));

		List<SwingPoint> swings = detector.detect(candles);

		// Equal highs don't qualify — must be STRICTLY greater
		List<SwingPoint> highs = swings.stream()
				.filter(SwingPoint::isHigh).toList();
		assertThat(highs).isEmpty();
	}

	@Test
	void returns_empty_for_insufficient_candles() {
		// Lookback=2 needs at least 5 candles (2 left + 1 center + 2 right)
		List<Candle> candles = List.of(
				candle(108_000L, 108_100L, 107_950L, 108_050L),
				candle(108_050L, 108_200L, 108_000L, 108_150L),
				candle(108_100L, 108_300L, 108_050L, 108_250L));

		assertThat(detector.detect(candles)).isEmpty();
	}

	@Test
	void returns_empty_for_null() {
		assertThat(detector.detect(null)).isEmpty();
	}
}
