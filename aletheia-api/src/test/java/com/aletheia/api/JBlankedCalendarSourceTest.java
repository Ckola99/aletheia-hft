package com.aletheia.api;

import com.aletheia.core.EconomicEvent;
import com.aletheia.core.ImpactLevel;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

class JBlankedCalendarSourceTest {

	private String sampleJson() {
		return """
				[
				  {"Name":"Core CPI m/m","Currency":"USD","Impact":"High","Date":"2026.02.08 08:30:00"},
				  {"Name":"ECB Rate Decision","Currency":"EUR","Impact":"High","Date":"2026.02.09 08:15:00"},
				  {"Name":"Some Survey","Currency":"USD","Impact":"Low","Date":"2026.02.08 10:00:00"}
				]
				""";
	}

	private JBlankedCalendarSource sourceReturning(String json) {
		Function<String, Optional<String>> fetch = url -> Optional.ofNullable(json);
		return new JBlankedCalendarSource("https://x", ZoneId.of("America/New_York"), fetch);
	}

	@Test
	void parses_events_from_json() throws Exception {
		List<EconomicEvent> events = sourceReturning(sampleJson())
				.fetch(java.time.LocalDate.of(2026, 2, 1), java.time.LocalDate.of(2026, 2, 28));

		assertThat(events).hasSize(3);
		assertThat(events).anyMatch(e -> e.eventName().equals("Core CPI m/m")
				&& e.currency().equals("USD") && e.impact() == ImpactLevel.HIGH);
		assertThat(events).anyMatch(e -> e.currency().equals("EUR")
				&& e.impact() == ImpactLevel.HIGH);
	}

	@Test
	void maps_impact_levels() throws Exception {
		List<EconomicEvent> events = sourceReturning(sampleJson())
				.fetch(java.time.LocalDate.of(2026, 2, 1), java.time.LocalDate.of(2026, 2, 28));

		long high = events.stream().filter(e -> e.impact() == ImpactLevel.HIGH).count();
		long low = events.stream().filter(e -> e.impact() == ImpactLevel.LOW).count();
		assertThat(high).isEqualTo(2);
		assertThat(low).isEqualTo(1);
	}

	@Test
	void skips_malformed_rows_without_failing() throws Exception {
		String json = """
				[
				  {"Name":"Good","Currency":"USD","Impact":"High","Date":"2026.02.08 08:30:00"},
				  {"Name":"","Currency":"USD","Impact":"High","Date":"2026.02.08 09:00:00"},
				  {"Name":"BadDate","Currency":"USD","Impact":"High","Date":"not-a-date"}
				]
				""";
		List<EconomicEvent> events = sourceReturning(json)
				.fetch(java.time.LocalDate.of(2026, 2, 1), java.time.LocalDate.of(2026, 2, 28));

		assertThat(events).hasSize(1);
		assertThat(events.get(0).eventName()).isEqualTo("Good");
	}

	@Test
	void empty_fetch_throws_fetch_exception() {
		JBlankedCalendarSource src = new JBlankedCalendarSource(
				"https://x", ZoneId.of("America/New_York"), url -> Optional.empty());

		assertThatThrownBy(() -> src.fetch(java.time.LocalDate.of(2026, 2, 1),
				java.time.LocalDate.of(2026, 2, 28)))
				.isInstanceOf(com.aletheia.calendar.CalendarFetchException.class);
	}
}
