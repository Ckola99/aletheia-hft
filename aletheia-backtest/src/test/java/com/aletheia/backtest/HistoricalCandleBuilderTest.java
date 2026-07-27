package com.aletheia.backtest;

import com.aletheia.core.Candle;
import com.aletheia.core.Tick;
import com.aletheia.core.Timeframe;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for HistoricalCandleBuilder.
 */
class HistoricalCandleBuilderTest {

	@Test
	void builds_candles_from_ticks() {
		// Create 10 minutes of ticks (one per second)
		List<Tick> ticks = new ArrayList<>();
		Instant start = Instant.parse("2023-06-15T09:00:00Z");

		for (int i = 0; i < 600; i++) { // 600 seconds = 10 minutes
			long price = 108_200L + (i % 50); // oscillating price
			ticks.add(new Tick(
					start.plusSeconds(i),
					"EUR_USD",
					price - 5, // bid
					price + 5 // ask
			));
		}

		HistoricalCandleBuilder builder = new HistoricalCandleBuilder();
		builder.buildFrom(ticks);

		// Should have candles at multiple timeframes
		assertThat(builder.totalCandles()).isGreaterThan(0);

		// Should have MIN_1 candles (10 minutes = at least 9 closed 1-min candles)
		List<Candle> min1 = builder.getCandles("EUR_USD", Timeframe.MIN_1);
		assertThat(min1).hasSizeGreaterThanOrEqualTo(9);

		// Should have MIN_5 candles (10 minutes = at least 1 closed 5-min candle)
		List<Candle> min5 = builder.getCandles("EUR_USD", Timeframe.MIN_5);
		assertThat(min5).hasSizeGreaterThanOrEqualTo(1);

		// Candles should be in chronological order
		for (int i = 1; i < min1.size(); i++) {
			assertThat(min1.get(i).time()).isAfter(min1.get(i - 1).time());
		}

		// Print summary
		builder.summary().forEach((tf, count) -> System.out.println(tf + ": " + count));
	}

	@Test
	void handles_empty_ticks() {
		HistoricalCandleBuilder builder = new HistoricalCandleBuilder();
		builder.buildFrom(List.of());

		assertThat(builder.totalCandles()).isEqualTo(0);
	}

	@Test
	void separates_instruments() {
		List<Tick> ticks = new ArrayList<>();
		Instant start = Instant.parse("2023-06-15T09:00:00Z");

		// EUR/USD and GBP/USD ticks interleaved
		for (int i = 0; i < 600; i++) {
			ticks.add(new Tick(start.plusSeconds(i), "EUR_USD",
					108_200L, 108_210L));
			ticks.add(new Tick(start.plusSeconds(i), "GBP_USD",
					133_800L, 133_815L));
		}

		HistoricalCandleBuilder builder = new HistoricalCandleBuilder();
		builder.buildFrom(ticks);

		List<Candle> eurCandles = builder.getCandles("EUR_USD", Timeframe.MIN_1);
		List<Candle> gbpCandles = builder.getCandles("GBP_USD", Timeframe.MIN_1);

		// Both instruments should have candles
		assertThat(eurCandles).isNotEmpty();
		assertThat(gbpCandles).isNotEmpty();

		// EUR candles should only be EUR
		eurCandles.forEach(c -> assertThat(c.instrument()).isEqualTo("EUR_USD"));

		// GBP candles should only be GBP
		gbpCandles.forEach(c -> assertThat(c.instrument()).isEqualTo("GBP_USD"));
	}
}
