package com.aletheia.backtest;

import com.aletheia.core.Candle;
import com.aletheia.core.Timeframe;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for SyntheticUsdxBuilder.
 */
class SyntheticUsdxBuilderTest {

	@Test
	void inverts_eurusd_to_usdx() {
		// EUR/USD at 1.08500 → USDX ≈ 1/1.085 ≈ 0.92166
		Candle eurCandle = new Candle(
				Instant.now(), "EUR_USD", Timeframe.DAILY,
				108_500L, 109_000L, 108_000L, 108_700L, 1000L);

		List<Candle> usdx = SyntheticUsdxBuilder.fromEurUsd(List.of(eurCandle));

		assertThat(usdx).hasSize(1);

		Candle u = usdx.get(0);
		assertThat(u.instrument()).isEqualTo("USDX");
		assertThat(u.timeframe()).isEqualTo(Timeframe.DAILY);

		// USDX high should come from EUR low (lowest EUR = strongest USD)
		// USDX low should come from EUR high (highest EUR = weakest USD)
		assertThat(u.high()).isGreaterThan(u.low());

		// When EUR/USD goes up (bullish), USDX should go down (bearish)
		// EUR close > EUR open → USDX close < USDX open
		assertThat(u.close()).isLessThan(u.open());
	}

	@Test
	void preserves_candle_count() {
		List<Candle> eurCandles = List.of(
				new Candle(Instant.now(), "EUR_USD", Timeframe.DAILY,
						108_500L, 109_000L, 108_000L, 108_700L, 100L),
				new Candle(Instant.now(), "EUR_USD", Timeframe.DAILY,
						108_700L, 109_200L, 108_500L, 109_000L, 100L),
				new Candle(Instant.now(), "EUR_USD", Timeframe.DAILY,
						109_000L, 109_500L, 108_800L, 109_300L, 100L));

		List<Candle> usdx = SyntheticUsdxBuilder.fromEurUsd(eurCandles);

		assertThat(usdx).hasSize(3);
	}

	@Test
	void handles_empty_list() {
		assertThat(SyntheticUsdxBuilder.fromEurUsd(List.of())).isEmpty();
	}
}
