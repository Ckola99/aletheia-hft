package com.aletheia.core;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Candle.
 *
 * Candle helper methods (isBullish, bodySize, upperWick etc.)
 * are used throughout the strategy engine. We verify them here
 * so we can trust them everywhere else.
 */
class CandleTest {

	/** Helper: build a test candle quickly without repeating boilerplate */
	private Candle candle(long open, long high, long low, long close) {
		return new Candle(
				Instant.now(),
				"EUR_USD",
				Timeframe.MIN_15,
				open, high, low, close,
				1000L);
	}

	@Test
	void bullish_when_close_above_open() {
		Candle c = candle(108_200L, 108_500L, 108_100L, 108_450L);
		assertThat(c.isBullish()).isTrue();
		assertThat(c.isBearish()).isFalse();
		assertThat(c.isDoji()).isFalse();
	}

	@Test
	void bearish_when_close_below_open() {
		Candle c = candle(108_450L, 108_500L, 108_100L, 108_200L);
		assertThat(c.isBearish()).isTrue();
		assertThat(c.isBullish()).isFalse();
	}

	@Test
	void doji_when_open_equals_close() {
		Candle c = candle(108_300L, 108_500L, 108_100L, 108_300L);
		assertThat(c.isDoji()).isTrue();
	}

	@Test
	void body_size_is_absolute_difference_between_open_and_close() {
		// Bullish: close(450) - open(200) = 250
		Candle bull = candle(108_200L, 108_500L, 108_100L, 108_450L);
		assertThat(bull.bodySize()).isEqualTo(250L);

		// Bearish: |close(200) - open(450)| = 250 (same absolute value)
		Candle bear = candle(108_450L, 108_500L, 108_100L, 108_200L);
		assertThat(bear.bodySize()).isEqualTo(250L);
	}

	@Test
	void upper_wick_is_high_minus_top_of_body() {
		// Bullish candle: open=200, close=450, high=500
		// Top of body = max(open, close) = 450
		// Upper wick = high(500) - 450 = 50
		Candle c = candle(108_200L, 108_500L, 108_100L, 108_450L);
		assertThat(c.upperWick()).isEqualTo(50L);
	}

	@Test
	void lower_wick_is_bottom_of_body_minus_low() {
		// Bullish candle: open=200, close=450, low=100
		// Bottom of body = min(open, close) = 200
		// Lower wick = 200 - low(100) = 100
		Candle c = candle(108_200L, 108_500L, 108_100L, 108_450L);
		assertThat(c.lowerWick()).isEqualTo(100L);
	}

	@Test
	void total_range_is_high_minus_low() {
		Candle c = candle(108_200L, 108_500L, 108_100L, 108_450L);
		// high(500) - low(100) = 400
		assertThat(c.totalRange()).isEqualTo(400L);
	}

	@Test
	void market_bias_invert_reverses_direction() {
		assertThat(MarketBias.BULLISH.invert()).isEqualTo(MarketBias.BEARISH);
		assertThat(MarketBias.BEARISH.invert()).isEqualTo(MarketBias.BULLISH);
		assertThat(MarketBias.NEUTRAL.invert()).isEqualTo(MarketBias.NEUTRAL);
	}

	@Test
	void market_bias_isDirectional_returns_false_for_neutral() {
		assertThat(MarketBias.NEUTRAL.isDirectional()).isFalse();
		assertThat(MarketBias.BULLISH.isDirectional()).isTrue();
		assertThat(MarketBias.BEARISH.isDirectional()).isTrue();
	}
}
