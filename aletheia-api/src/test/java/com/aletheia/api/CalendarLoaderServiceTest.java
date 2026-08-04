package com.aletheia.api;

import com.aletheia.calendar.EconomicCalendarService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the CalendarLoaderService's fallback behaviour.
 *
 * The loader's source chain is HTTP (calendar service) -> CSV fallback. When
 * both are unavailable, load() must degrade safely — empty cache, no crash —
 * rather than throwing. That is the fail-safe "leave the cache alone"
 * behaviour.
 *
 * The HTTP parsing itself is covered by HttpCalendarSourceTest; the real
 * end-to-end path is verified by running both services together.
 */
class CalendarLoaderServiceTest {

	@Test
	void load_does_not_crash_when_all_sources_unavailable() {
		EconomicCalendarService svc = new EconomicCalendarService();

		CalendarLoaderService loader = new CalendarLoaderService(
				svc,
				"http://localhost:1", // nothing listening
				"data/does_not_exist.csv", // no such file
				21);

		loader.load();

		assertThat(svc.cacheSize()).isZero();
	}
}
