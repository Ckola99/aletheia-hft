package com.aletheia.calendar;

import com.aletheia.core.EconomicEvent;
import com.aletheia.core.ImpactLevel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ForexFactoryHtmlParser.
 *
 * Uses a saved HTML fixture — no real HTTP calls.
 * This is the professional testing pattern for scrapers:
 * save a real HTML page once, test against it forever.
 * If the website changes its structure, save a new fixture
 * and update the parser.
 */
class ForexFactoryHtmlParserTest {

	private final ForexFactoryHtmlParser parser = new ForexFactoryHtmlParser();

	private String loadFixture() throws IOException {
		try (InputStream is = getClass().getResourceAsStream("/forex_factory_sample.html")) {
			if (is == null)
				throw new IOException("Fixture not found");
			return new String(is.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void parses_all_events_from_fixture() throws IOException {
		String html = loadFixture();
		List<EconomicEvent> events = parser.parse(html, LocalDate.of(2026, 7, 22));

		// The fixture has 5 rows
		assertThat(events).hasSize(5);
	}

	@Test
	void parses_high_impact_events_correctly() throws IOException {
		String html = loadFixture();
		List<EconomicEvent> events = parser.parse(html, LocalDate.of(2026, 7, 22));

		List<EconomicEvent> highImpact = events.stream()
				.filter(EconomicEvent::isHighImpact)
				.toList();

		// NFP (USD, red), BOE (GBP, red), ECB (EUR, red) = 3 high impact
		assertThat(highImpact).hasSize(3);
	}

	@Test
	void parses_currency_correctly() throws IOException {
		String html = loadFixture();
		List<EconomicEvent> events = parser.parse(html, LocalDate.of(2026, 7, 22));

		// First event should be NFP = USD
		assertThat(events.get(0).currency()).isEqualTo("USD");

		// BOE should be GBP
		List<EconomicEvent> gbpEvents = events.stream()
				.filter(e -> e.currency().equals("GBP"))
				.toList();
		assertThat(gbpEvents).hasSize(1);
		assertThat(gbpEvents.get(0).eventName()).isEqualTo("BOE Rate Decision");
	}

	@Test
	void parses_impact_levels_correctly() throws IOException {
		String html = loadFixture();
		List<EconomicEvent> events = parser.parse(html, LocalDate.of(2026, 7, 22));

		// ISM Manufacturing PMI = orange = MEDIUM
		EconomicEvent ism = events.stream()
				.filter(e -> e.eventName().contains("ISM"))
				.findFirst().orElseThrow();
		assertThat(ism.impact()).isEqualTo(ImpactLevel.MEDIUM);

		// JOLTS = yellow = LOW
		EconomicEvent jolts = events.stream()
				.filter(e -> e.eventName().contains("JOLTS"))
				.findFirst().orElseThrow();
		assertThat(jolts.impact()).isEqualTo(ImpactLevel.LOW);
	}

	@Test
	void handles_date_inheritance_across_rows() throws IOException {
		String html = loadFixture();
		List<EconomicEvent> events = parser.parse(html, LocalDate.of(2026, 7, 22));

		// NFP and ISM are both on Jul 22 (first row has date, second inherits)
		// BOE and ECB are on Jul 23 (third row has new date, fourth inherits)
		EconomicEvent nfp = events.get(0); // Jul 22
		EconomicEvent ism = events.get(1); // Jul 22 (inherited)
		EconomicEvent boe = events.get(2); // Jul 23

		// NFP and ISM should be same day
		assertThat(nfp.scheduledTime().toString()).contains("2026-07-22");
		assertThat(ism.scheduledTime().toString()).contains("2026-07-22");

		// BOE should be next day
		assertThat(boe.scheduledTime().toString()).contains("2026-07-23");
	}

	@Test
	void returns_empty_for_null_html() {
		assertThat(parser.parse(null, LocalDate.now())).isEmpty();
	}

	@Test
	void returns_empty_for_blank_html() {
		assertThat(parser.parse("", LocalDate.now())).isEmpty();
	}
}
