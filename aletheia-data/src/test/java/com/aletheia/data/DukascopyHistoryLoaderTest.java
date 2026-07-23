package com.aletheia.data;

import com.aletheia.core.Tick;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test for DukascopyHistoryLoader.
 *
 * Downloads real data from Dukascopy — only runs when
 * DUKASCOPY_TEST=true environment variable is set.
 * Skipped in CI.
 */
class DukascopyHistoryLoaderTest {

	@Test
	@EnabledIfEnvironmentVariable(named = "DUKASCOPY_TEST", matches = "true")
	void downloads_and_parses_one_day_of_eurusd() {

		// Collect ticks instead of writing to database
		List<Tick> allTicks = new ArrayList<>();

		// Create a TickRepository that just collects ticks (no database)
		TickRepository collector = new TickRepository(null, 10000) {
			@Override
			public synchronized void flush() {
				// don't write to DB — ticks stay in memory for assertions
			}

			@Override
			public void onTick(Tick tick) {
				allTicks.add(tick);
			}
		};

		DukascopyHistoryLoader loader = new DukascopyHistoryLoader(collector);

		// Download just ONE day — January 3, 2023 (Tuesday)
		// This is enough to verify the download, decompress, and parse works
		loader.load("EUR_USD", LocalDate.of(2023, 1, 3), LocalDate.of(2023, 1, 3));

		System.out.println("Downloaded " + allTicks.size() + " ticks");

		// A typical trading day has 30,000-80,000 ticks for EUR/USD
		assertThat(allTicks).hasSizeGreaterThan(1000);

		// Verify ticks have sensible data
		Tick first = allTicks.get(0);
		assertThat(first.instrument()).isEqualTo("EUR_USD");
		assertThat(first.bid()).isGreaterThan(100_000L); // EUR/USD > 1.00000
		assertThat(first.ask()).isGreaterThan(first.bid()); // ask > bid always

		// Verify timestamps are in January 2023
		assertThat(first.time().toString()).startsWith("2023-01-03");

		System.out.println("First tick: " + first.instrument()
				+ " bid=" + first.bid()
				+ " ask=" + first.ask()
				+ " time=" + first.time());
	}
}
