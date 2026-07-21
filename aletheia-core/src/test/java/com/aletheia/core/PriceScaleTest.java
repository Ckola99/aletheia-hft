package com.aletheia.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * Tests for PriceScale.
 *
 * This is the most critical utility class in the entire codebase.
 * A bug here corrupts every price calculation downstream —
 * position sizing, stop loss placement, P&L calculation.
 *
 * We test it thoroughly and we test it first.
 */
class PriceScaleTest {

	@Test
	void eurusd_1_08250_scales_to_108250() {
		long result = PriceScale.toScaled(1.08250, "EUR_USD");
		assertThat(result).isEqualTo(108_250L);
	}

	@Test
	void eurusd_scaled_108250_converts_back_to_1_08250() {
		double result = PriceScale.toDouble(108_250L, "EUR_USD");
		assertThat(result).isEqualTo(1.08250);
	}

	@Test
	void gold_xauusd_uses_scale_100() {
		// Gold has 2 decimal places: 1920.50 → 192050
		long result = PriceScale.toScaled(1920.50, "XAU_USD");
		assertThat(result).isEqualTo(192_050L);
	}

	@Test
	void one_pip_eurusd_is_10_scaled_units() {
		// 1 pip = 0.00010
		// In 100,000 scale: 0.00010 × 100,000 = 10
		assertThat(PriceScale.onePip("EUR_USD")).isEqualTo(10L);
	}

	@Test
	void demonstrates_why_double_arithmetic_is_wrong() {
		// This test documents the exact problem we are solving.
		// Floating-point subtraction of two decimals that look simple
		// produces a value that is NOT exactly what we expect.

		// 108.260 - 108.250 should be exactly 0.010
		// But in double arithmetic it is not:
		double floatingResult = 108.260 - 108.250;
		assertThat(floatingResult).isNotEqualTo(0.010);
		// (on most machines this is 0.010000000000005116)

		// With scaled integers there is NO error, ever:
		long scaledResult = 108_260L - 108_250L;
		assertThat(scaledResult).isEqualTo(10L); // exactly 1 pip
	}

	@Test
	void tick_of_factory_method_converts_doubles_correctly() {
		// Test the Tick.of() factory method which is where
		// OANDA's JSON double prices enter the system
		java.time.Instant now = java.time.Instant.now();
		Tick tick = Tick.of(now, "EUR_USD", 1.08250, 1.08260);

		assertThat(tick.bid()).isEqualTo(108_250L);
		assertThat(tick.ask()).isEqualTo(108_260L);
		assertThat(tick.spread()).isEqualTo(10L); // exactly 1 pip
	}
}
