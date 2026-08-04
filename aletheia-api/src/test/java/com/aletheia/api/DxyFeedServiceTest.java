package com.aletheia.api;

import com.aletheia.core.Candle;
import com.aletheia.core.Timeframe;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests the pure, network-free helpers in DxyFeedService: the merge logic that
 * keeps the rolling DXY window fresh, and the filter/sort that extracts a
 * single
 * timeframe oldest-first.
 *
 * The live Dukascopy download is not unit-tested here (it requires network);
 * it reuses DukascopyHistoryLoader, which has its own integration test.
 */
class DxyFeedServiceTest {

	private Candle daily(String time) {
		Instant t = Instant.parse(time);
		return new Candle(t, "DOLLAR_IDX", Timeframe.DAILY,
				103_000L, 103_500L, 102_800L, 103_200L, 100L);
	}

	private Candle hour1(String time) {
		Instant t = Instant.parse(time);
		return new Candle(t, "DOLLAR_IDX", Timeframe.HOUR_1,
				103_000L, 103_100L, 102_900L, 103_050L, 10L);
	}

	// ── merge() ─────────────────────────────────────────────────────

	@Test
	void merge_replaces_overlapping_tail() {
		List<Candle> existing = List.of(
				daily("2023-06-01T00:00:00Z"),
				daily("2023-06-02T00:00:00Z"),
				daily("2023-06-03T00:00:00Z"));

		// Refresh covers Jun 3 onward: Jun 3 is replaced, Jun 4 is new
		List<Candle> refreshed = List.of(
				daily("2023-06-03T00:00:00Z"),
				daily("2023-06-04T00:00:00Z"));

		List<Candle> merged = DxyFeedService.merge(existing, refreshed);

		// Jun 1, Jun 2 kept + Jun 3, Jun 4 from refresh = 4, no duplicate Jun 3
		assertThat(merged).hasSize(4);
		assertThat(merged.get(0).time()).isEqualTo(Instant.parse("2023-06-01T00:00:00Z"));
		assertThat(merged.get(1).time()).isEqualTo(Instant.parse("2023-06-02T00:00:00Z"));
		assertThat(merged.get(2).time()).isEqualTo(Instant.parse("2023-06-03T00:00:00Z"));
		assertThat(merged.get(3).time()).isEqualTo(Instant.parse("2023-06-04T00:00:00Z"));
	}

	@Test
	void merge_drops_stale_tail_entirely_when_refresh_starts_earlier() {
		List<Candle> existing = List.of(
				daily("2023-06-05T00:00:00Z"),
				daily("2023-06-06T00:00:00Z"));

		// Refresh window begins BEFORE all existing candles → replaces them all
		List<Candle> refreshed = List.of(
				daily("2023-06-04T00:00:00Z"),
				daily("2023-06-05T00:00:00Z"),
				daily("2023-06-06T00:00:00Z"));

		List<Candle> merged = DxyFeedService.merge(existing, refreshed);

		assertThat(merged).hasSize(3);
		assertThat(merged.get(0).time()).isEqualTo(Instant.parse("2023-06-04T00:00:00Z"));
	}

	@Test
	void merge_appends_cleanly_when_no_overlap() {
		List<Candle> existing = List.of(
				daily("2023-06-01T00:00:00Z"),
				daily("2023-06-02T00:00:00Z"));

		List<Candle> refreshed = List.of(
				daily("2023-06-03T00:00:00Z"),
				daily("2023-06-04T00:00:00Z"));

		List<Candle> merged = DxyFeedService.merge(existing, refreshed);

		assertThat(merged).hasSize(4);
		assertThat(merged.get(0).time()).isEqualTo(Instant.parse("2023-06-01T00:00:00Z"));
		assertThat(merged.get(3).time()).isEqualTo(Instant.parse("2023-06-04T00:00:00Z"));
	}

	@Test
	void merge_returns_existing_when_refresh_empty() {
		List<Candle> existing = List.of(daily("2023-06-01T00:00:00Z"));
		assertThat(DxyFeedService.merge(existing, List.of())).isEqualTo(existing);
	}

	@Test
	void merge_returns_refreshed_when_existing_empty() {
		List<Candle> refreshed = List.of(daily("2023-06-04T00:00:00Z"));
		assertThat(DxyFeedService.merge(List.of(), refreshed)).isEqualTo(refreshed);
	}

	@Test
	void merge_of_two_empties_is_empty() {
		assertThat(DxyFeedService.merge(List.of(), List.of())).isEmpty();
	}

	// ── filterSort() ────────────────────────────────────────────────

	@Test
	void filterSort_keeps_only_timeframe_and_orders_oldest_first() {
		Candle d2 = daily("2023-06-02T00:00:00Z");
		Candle d1 = daily("2023-06-01T00:00:00Z");
		Candle h = hour1("2023-06-01T05:00:00Z");

		// Input deliberately out of order and mixed-timeframe
		List<Candle> result = DxyFeedService.filterSort(
				List.of(d2, h, d1), Timeframe.DAILY);

		// HOUR_1 filtered out; DAILY sorted ascending
		assertThat(result).containsExactly(d1, d2);
	}

	@Test
	void filterSort_returns_empty_when_no_candles_match() {
		List<Candle> result = DxyFeedService.filterSort(
				List.of(daily("2023-06-01T00:00:00Z")), Timeframe.HOUR_4);

		assertThat(result).isEmpty();
	}

	@Test
	void filterSort_extracts_hour1_independently() {
		Candle h2 = hour1("2023-06-01T06:00:00Z");
		Candle h1 = hour1("2023-06-01T05:00:00Z");
		Candle d = daily("2023-06-01T00:00:00Z");

		List<Candle> result = DxyFeedService.filterSort(
				List.of(h2, d, h1), Timeframe.HOUR_1);

		assertThat(result).containsExactly(h1, h2);
	}
}
