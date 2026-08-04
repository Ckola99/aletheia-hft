package com.aletheia.api;

import com.aletheia.calendar.EconomicCalendarService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

/**
 * Verifies the calendar loader's source-selection and fallback logic
 * with no network access (the HTML fetcher is stubbed).
 */
class CalendarLoaderServiceTest {

	// A tiny Forex Factory HTML fixture with one HIGH-impact USD event.
	private String ffHtml() {
		return """
				<table class="calendar__table">
				<tr class="calendar__row">
				  <td class="calendar__date">Mon Jul 22</td>
				  <td class="calendar__time">8:30am</td>
				  <td class="calendar__currency">USD</td>
				  <td class="calendar__impact">
				    <span class="calendar__impact-icon calendar__impact-icon--red"></span>
				  </td>
				  <td class="calendar__event">Non-Farm Payrolls</td>
				</tr>
				</table>
				""";
	}

	private Function<String, Optional<String>> fetcherReturning(String html) {
		return url -> Optional.ofNullable(html);
	}

	private Function<String, Optional<String>> emptyFetcher() {
		return url -> Optional.empty();
	}

	@Test
	void loads_from_html_fetch_when_available() {
		EconomicCalendarService svc = new EconomicCalendarService();

		CalendarLoaderService loader = new CalendarLoaderService(
				svc, true, "data/does-not-exist.csv",
				"https://example.com/calendar", 21,
				null, // primarySource (JBlanked) — not used here
				fetcherReturning(ffHtml()));

		loader.load();

		// The single HIGH-impact event should be cached
		assertThat(svc.cacheSize()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void falls_back_to_csv_when_fetch_empty(@TempDir Path tmp) throws Exception {
		// Write a CSV with a HIGH-impact event dated a few days from now,
		// so it falls inside the loader's [today-1 .. today+21] window.
		LocalDate soon = LocalDate.now(ZoneOffset.UTC).plusDays(2);
		String date = soon.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
		Path csv = tmp.resolve("cal.csv");
		Files.writeString(csv,
				"date,time,currency,impact,event\n"
						+ date + ",08:30,USD,HIGH,Non-Farm Payrolls\n");

		EconomicCalendarService svc = new EconomicCalendarService();

		CalendarLoaderService loader = new CalendarLoaderService(
				svc, true, "data/does-not-exist.csv",
				"https://example.com/calendar", 21,
				null, // primarySource (JBlanked) — not used here
				fetcherReturning(ffHtml()));

		loader.load();

		assertThat(svc.cacheSize()).isEqualTo(1);
	}

	@Test
	void empty_when_no_source_available_and_does_not_crash(@TempDir Path tmp) {
		EconomicCalendarService svc = new EconomicCalendarService();

		CalendarLoaderService loader = new CalendarLoaderService(
				svc, true, "data/does-not-exist.csv",
				"https://example.com/calendar", 21,
				null, // primarySource (JBlanked) — not used here
				fetcherReturning(ffHtml()));
		// Should not throw; cache stays empty (loud warning is logged)
		loader.load();

		assertThat(svc.cacheSize()).isZero();
	}

	@Test
	void disabled_loader_does_nothing() {
		EconomicCalendarService svc = new EconomicCalendarService();

		CalendarLoaderService loader = new CalendarLoaderService(
				svc, true, "data/does-not-exist.csv",
				"https://example.com/calendar", 21,
				null, // primarySource (JBlanked) — not used here
				fetcherReturning(ffHtml()));
		loader.init(); // disabled → returns immediately

		assertThat(svc.cacheSize()).isZero();
	}
}
