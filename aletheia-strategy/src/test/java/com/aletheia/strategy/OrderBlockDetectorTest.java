package com.aletheia.strategy;

import com.aletheia.core.Candle;
import com.aletheia.core.Timeframe;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for OrderBlockDetector.
 *
 * We create sequences of candles with a known ATR, then add
 * a displacement candle that exceeds the threshold, and verify
 * the detector finds the correct Order Block.
 */
class OrderBlockDetectorTest {

	// Use a small ATR period for testing — easier to construct test data
	private final OrderBlockDetector detector = new OrderBlockDetector(5, 1.5);

	private long timeCounter = 1_000_000_000L;

	/**
	 * Helper: create a candle.
	 * For a bullish candle: make close > open.
	 * For a bearish candle: make close < open.
	 */
	private Candle candle(long open, long high, long low, long close) {
		timeCounter += 900;
		return new Candle(
				Instant.ofEpochSecond(timeCounter),
				"EUR_USD",
				Timeframe.MIN_15,
				open, high, low, close,
				100L);
	}

	/**
	 * Helper: create N "normal" candles with a consistent range.
	 * These establish the ATR baseline.
	 * Each candle has a range of 'range' (high - low).
	 */
	private List<Candle> normalCandles(int count, long basePrice, long range) {
		List<Candle> candles = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			// Alternating bullish/bearish to make it realistic
			long open = basePrice;
			long high = basePrice + range;
			long low = basePrice - range / 4;
			long close = (i % 2 == 0)
					? basePrice + range / 2 // bullish
					: basePrice - range / 4; // bearish
			candles.add(candle(open, high, low, close));
		}
		return candles;
	}

	@Test
	void detects_bullish_order_block() {
		// Build 5 normal candles to establish ATR
		// Each has range ≈ 100 (high - low), so ATR ≈ 100
		List<Candle> candles = new ArrayList<>(normalCandles(5, 108_200L, 100L));

		// Add a BEARISH candle (the Order Block candidate)
		// open > close → bearish
		candles.add(candle(108_250L, 108_280L, 108_200L, 108_210L));

		// Add a BULLISH displacement candle with body > 1.5 × ATR
		// ATR ≈ 100, threshold = 150, so body must be > 150
		// body = close - open = 108_500 - 108_220 = 280 > 150 ✓
		candles.add(candle(108_220L, 108_520L, 108_210L, 108_500L));

		List<OrderBlock> obs = detector.detect(candles);

		assertThat(obs).hasSize(1);
		OrderBlock ob = obs.get(0);
		assertThat(ob.bias()).isEqualTo(PdArray.Bias.BULLISH);
		// The OB zone is the bearish candle's range
		assertThat(ob.upper()).isEqualTo(108_280L); // candidate's high
		assertThat(ob.lower()).isEqualTo(108_200L); // candidate's low
	}

	@Test
	void detects_bearish_order_block() {
		List<Candle> candles = new ArrayList<>(normalCandles(5, 108_200L, 100L));

		// BULLISH candle (the Order Block candidate)
		// open < close → bullish
		candles.add(candle(108_200L, 108_280L, 108_190L, 108_270L));

		// BEARISH displacement: body = open - close = 108_260 - 107_950 = 310 > 150 ✓
		candles.add(candle(108_260L, 108_270L, 107_940L, 107_950L));

		List<OrderBlock> obs = detector.detect(candles);

		assertThat(obs).hasSize(1);
		OrderBlock ob = obs.get(0);
		assertThat(ob.bias()).isEqualTo(PdArray.Bias.BEARISH);
	}

	@Test
	void no_order_block_when_displacement_too_small() {
		List<Candle> candles = new ArrayList<>(normalCandles(5, 108_200L, 100L));

		// Bearish candidate
		candles.add(candle(108_250L, 108_280L, 108_200L, 108_210L));

		// "Displacement" that is NOT big enough: body = 50 < 150 threshold
		candles.add(candle(108_220L, 108_290L, 108_210L, 108_270L));

		List<OrderBlock> obs = detector.detect(candles);

		assertThat(obs).isEmpty();
	}

	@Test
	void no_order_block_when_candidate_same_direction_as_displacement() {
		List<Candle> candles = new ArrayList<>(normalCandles(5, 108_200L, 100L));

		// BULLISH candidate
		candles.add(candle(108_200L, 108_280L, 108_190L, 108_270L));

		// BULLISH displacement (same direction — NOT an order block)
		// OB requires the candidate to be the OPPOSITE direction
		candles.add(candle(108_270L, 108_600L, 108_260L, 108_580L));

		List<OrderBlock> obs = detector.detect(candles);

		assertThat(obs).isEmpty();
	}

	@Test
	void returns_empty_for_insufficient_data() {
		// Only 3 candles — need at least atrPeriod(5) + 2 = 7
		List<Candle> candles = List.of(
				candle(108_100L, 108_200L, 108_050L, 108_180L),
				candle(108_180L, 108_300L, 108_170L, 108_280L),
				candle(108_280L, 108_400L, 108_270L, 108_380L));

		assertThat(detector.detect(candles)).isEmpty();
	}

	@Test
	void custom_displacement_multiplier_changes_sensitivity() {
		List<Candle> candles = new ArrayList<>(normalCandles(5, 108_200L, 100L));

		candles.add(candle(108_250L, 108_280L, 108_200L, 108_210L)); // bearish candidate

		// Displacement body = 200
		candles.add(candle(108_220L, 108_440L, 108_210L, 108_420L));

		// With 1.5× multiplier: threshold = 100 × 1.5 = 150. body 200 > 150 → detected
		OrderBlockDetector sensitive = new OrderBlockDetector(5, 1.5);
		assertThat(sensitive.detect(candles)).hasSize(1);

		// With 3.0× multiplier: threshold = 100 × 3.0 = 300. body 200 < 300 → NOT
		// detected
		OrderBlockDetector strict = new OrderBlockDetector(5, 3.0);
		assertThat(strict.detect(candles)).isEmpty();
	}

	@Test
	void order_block_contains_method_works() {
		List<Candle> candles = new ArrayList<>(normalCandles(5, 108_200L, 100L));
		candles.add(candle(108_250L, 108_280L, 108_200L, 108_210L));
		candles.add(candle(108_220L, 108_520L, 108_210L, 108_500L));

		OrderBlock ob = detector.detect(candles).get(0);

		// Price inside the OB zone
		assertThat(ob.contains(108_240L)).isTrue();

		// Price outside the OB zone
		assertThat(ob.contains(108_100L)).isFalse();
		assertThat(ob.contains(108_400L)).isFalse();
	}
}
