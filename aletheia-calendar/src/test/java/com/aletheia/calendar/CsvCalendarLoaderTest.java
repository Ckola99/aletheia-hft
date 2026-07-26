package com.aletheia.calendar;

import com.aletheia.core.EconomicEvent;
import com.aletheia.core.ImpactLevel;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for CsvCalendarLoader.
 *
 * Uses the test_calendar.csv fixture file.
 */
class CsvCalendarLoaderTest {

	private Path fixturePath() throws URISyntaxException {
		return Paths.get(getClass().getResource("/test_calendar.csv").toURI());
	}

	@Test
	void loads_all_events_from_csv() throws Exception {
		CsvCalendarLoader loader = new CsvCalendarLoader(fixturePath());

		List<EconomicEvent> events = loader.fetch(
				LocalDate.of(2023, 1, 1),
				LocalDate.of(2023, 12, 31));

		// The CSV has 11 events
		assertThat(events).hasSize(11);
	}

	@Test
	void filters_by_date_range() throws Exception {
		CsvCalendarLoader loader = new CsvCalendarLoader(fixturePath());

		// Only January 2023
		List<EconomicEvent> janEvents = loader.fetch(
				LocalDate.of(2023, 1, 1),
				LocalDate.of(2023, 1, 31));

		// Jan has: NFP (6th), CPI (12th), New Home Sales (25th) = 3 events
		assertThat(janEvents).hasSize(3);
	}

	@Test
	void parses_impact_levels() throws Exception {
		CsvCalendarLoader loader = new CsvCalendarLoader(fixturePath());

		List<EconomicEvent> events = loader.fetch(
				LocalDate.of(2023, 1, 1),
				LocalDate.of(2023, 1, 31));

		// New Home Sales is MEDIUM
		EconomicEvent nhs = events.stream()
				.filter(e -> e.eventName().contains("New Home Sales"))
				.findFirst().orElseThrow();
		assertThat(nhs.impact()).isEqualTo(ImpactLevel.MEDIUM);

		// NFP is HIGH
		EconomicEvent nfp = events.stream()
				.filter(e -> e.eventName().contains("Non-Farm"))
				.findFirst().orElseThrow();
		assertThat(nfp.impact()).isEqualTo(ImpactLevel.HIGH);
	}

	@Test
	void parses_currencies() throws Exception {
		CsvCalendarLoader loader = new CsvCalendarLoader(fixturePath());

		List<EconomicEvent> allEvents = loader.fetch(
				LocalDate.of(2023, 1, 1),
				LocalDate.of(2023, 12, 31));

		// Should have USD, GBP, and EUR events
		assertThat(allEvents.stream().map(EconomicEvent::currency).distinct().toList())
				.containsExactlyInAnyOrder("USD", "GBP", "EUR");
	}

	@Test
	void empty_result_for_range_with_no_events() throws Exception {
		CsvCalendarLoader loader = new CsvCalendarLoader(fixturePath());

		// No events in December 2023 (our CSV only has Jan-Mar)
		List<EconomicEvent> events = loader.fetch(
				LocalDate.of(2023, 12, 1),
				LocalDate.of(2023, 12, 31));

		assertThat(events).isEmpty();
	}
}
