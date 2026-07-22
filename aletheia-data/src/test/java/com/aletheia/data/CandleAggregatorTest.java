package com.aletheia.data;

import com.aletheia.core.Candle;
import com.aletheia.core.Tick;
import com.aletheia.core.Timeframe;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for CandleAggregator.
 *
 * These tests use fabricated ticks with known timestamps and prices
 * so we can verify the exact OHLCV values of the resulting candles.
 * No real OANDA connection needed.
 */
class CandleAggregatorTest {

	/**
	 * Helper: create a tick with a specific time and mid-price.
	 * We set bid = price - 5, ask = price + 5, so mid = price.
	 */
	private Tick tick(String time, String instrument, long midPrice) {
		return new Tick(
				Instant.parse(time),
				instrument,
				midPrice - 5, // bid
				midPrice + 5 // ask
		);
	}

	@Test
	void closes_candle_when_new_period_starts() {
		CandleAggregator aggregator = new CandleAggregator();
		List<Candle> closed = Collections.synchronizedList(new ArrayList<>());
		aggregator.addCandleListener(closed::add);

		// Feed ticks in the 09:00-09:05 period (MIN_5 candle)
		aggregator.onTick(tick("2026-07-22T09:00:01Z", "EUR_USD", 114100L)); // open
		aggregator.onTick(tick("2026-07-22T09:00:30Z", "EUR_USD", 114150L)); // new high
		aggregator.onTick(tick("2026-07-22T09:01:00Z", "EUR_USD", 114080L)); // new low
		aggregator.onTick(tick("2026-07-22T09:04:59Z", "EUR_USD", 114120L)); // close

		// No candle closed yet — we're still in the 09:00 period
		// Filter for MIN_5 candles only (other timeframes may have closed)
		List<Candle> min5Candles = closed.stream()
				.filter(c -> c.timeframe() == Timeframe.MIN_5)
				.toList();
		assertThat(min5Candles).isEmpty();

		// Now a tick arrives in the NEXT period (09:05)
		// This triggers the 09:00 candle to close
		aggregator.onTick(tick("2026-07-22T09:05:01Z", "EUR_USD", 114130L));

		min5Candles = closed.stream()
				.filter(c -> c.timeframe() == Timeframe.MIN_5)
				.toList();
		assertThat(min5Candles).hasSize(1);

		Candle candle = min5Candles.get(0);
		assertThat(candle.instrument()).isEqualTo("EUR_USD");
		assertThat(candle.timeframe()).isEqualTo(Timeframe.MIN_5);
		assertThat(candle.open()).isEqualTo(114100L);
		assertThat(candle.high()).isEqualTo(114150L);
		assertThat(candle.low()).isEqualTo(114080L);
		assertThat(candle.close()).isEqualTo(114120L);
		assertThat(candle.volume()).isEqualTo(4);
	}

	@Test
	void tracks_multiple_instruments_independently() {
		CandleAggregator aggregator = new CandleAggregator();
		List<Candle> closed = new ArrayList<>();
		aggregator.addCandleListener(closed::add);

		// EUR_USD and GBP_USD ticks in the same period
		aggregator.onTick(tick("2026-07-22T09:00:01Z", "EUR_USD", 114100L));
		aggregator.onTick(tick("2026-07-22T09:00:02Z", "GBP_USD", 133800L));
		aggregator.onTick(tick("2026-07-22T09:00:03Z", "EUR_USD", 114200L));
		aggregator.onTick(tick("2026-07-22T09:00:04Z", "GBP_USD", 133700L));

		// Check open candles — each instrument has its own
		Candle eurOpen = aggregator.getOpenCandle("EUR_USD", Timeframe.MIN_5);
		Candle gbpOpen = aggregator.getOpenCandle("GBP_USD", Timeframe.MIN_5);

		assertThat(eurOpen.open()).isEqualTo(114100L);
		assertThat(eurOpen.high()).isEqualTo(114200L); // EUR went UP
		assertThat(gbpOpen.open()).isEqualTo(133800L);
		assertThat(gbpOpen.low()).isEqualTo(133700L); // GBP went DOWN
	}

	@Test
	void builds_candles_at_multiple_timeframes_simultaneously() {
		CandleAggregator aggregator = new CandleAggregator();
		List<Candle> closed = new ArrayList<>();
		aggregator.addCandleListener(closed::add);

		// Feed ticks spanning 09:00 to 09:06
		// This should close a SECONDS_5 candle AND a MIN_1 candle
		// but NOT a MIN_5 candle (needs to reach 09:05)
		aggregator.onTick(tick("2026-07-22T09:00:01Z", "EUR_USD", 114100L));
		aggregator.onTick(tick("2026-07-22T09:00:04Z", "EUR_USD", 114110L));

		// Tick at 09:00:06 — closes the SECONDS_5 candle for 09:00:00-09:00:05
		aggregator.onTick(tick("2026-07-22T09:00:06Z", "EUR_USD", 114120L));

		boolean has5sClosed = closed.stream()
				.anyMatch(c -> c.timeframe() == Timeframe.SECONDS_5);
		assertThat(has5sClosed).isTrue();

		// Feed more ticks to cross the minute boundary
		aggregator.onTick(tick("2026-07-22T09:00:59Z", "EUR_USD", 114130L));
		aggregator.onTick(tick("2026-07-22T09:01:01Z", "EUR_USD", 114140L));

		boolean has1mClosed = closed.stream()
				.anyMatch(c -> c.timeframe() == Timeframe.MIN_1);
		assertThat(has1mClosed).isTrue();
	}

	@Test
	void calculates_period_start_correctly_for_15min() {
		// 09:00:00 → belongs to 09:00 candle
		assertThat(CandleAggregator.calculatePeriodStart(
				Instant.parse("2026-07-22T09:00:00Z"), Timeframe.MIN_15))
				.isEqualTo(Instant.parse("2026-07-22T09:00:00Z"));

		// 09:07:33 → belongs to 09:00 candle (not 09:15)
		assertThat(CandleAggregator.calculatePeriodStart(
				Instant.parse("2026-07-22T09:07:33Z"), Timeframe.MIN_15))
				.isEqualTo(Instant.parse("2026-07-22T09:00:00Z"));

		// 09:15:00 → belongs to 09:15 candle (new period)
		assertThat(CandleAggregator.calculatePeriodStart(
				Instant.parse("2026-07-22T09:15:00Z"), Timeframe.MIN_15))
				.isEqualTo(Instant.parse("2026-07-22T09:15:00Z"));

		// 09:29:59 → still the 09:15 candle
		assertThat(CandleAggregator.calculatePeriodStart(
				Instant.parse("2026-07-22T09:29:59Z"), Timeframe.MIN_15))
				.isEqualTo(Instant.parse("2026-07-22T09:15:00Z"));
	}

	@Test
	void calculates_period_start_correctly_for_4hour() {
		// 09:30:00 → belongs to 08:00 candle (4h blocks: 0,4,8,12,16,20)
		assertThat(CandleAggregator.calculatePeriodStart(
				Instant.parse("2026-07-22T09:30:00Z"), Timeframe.HOUR_4))
				.isEqualTo(Instant.parse("2026-07-22T08:00:00Z"));

		// 12:00:00 → belongs to 12:00 candle
		assertThat(CandleAggregator.calculatePeriodStart(
				Instant.parse("2026-07-22T12:00:00Z"), Timeframe.HOUR_4))
				.isEqualTo(Instant.parse("2026-07-22T12:00:00Z"));
	}

	@Test
	void candle_open_price_is_first_tick_close_is_last() {
		CandleAggregator aggregator = new CandleAggregator();

		// Feed 3 ticks: 100, 200, 150
		aggregator.onTick(tick("2026-07-22T09:00:01Z", "EUR_USD", 114100L));
		aggregator.onTick(tick("2026-07-22T09:00:02Z", "EUR_USD", 114200L));
		aggregator.onTick(tick("2026-07-22T09:00:03Z", "EUR_USD", 114150L));

		Candle open = aggregator.getOpenCandle("EUR_USD", Timeframe.MIN_5);

		assertThat(open.open()).isEqualTo(114100L); // first tick
		assertThat(open.close()).isEqualTo(114150L); // last tick
		assertThat(open.high()).isEqualTo(114200L); // highest
		assertThat(open.low()).isEqualTo(114100L); // lowest
		assertThat(open.volume()).isEqualTo(3);
	}
}
