package com.aletheia.calendar;

import com.aletheia.core.EconomicEvent;
import com.aletheia.core.ImpactLevel;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses Forex Factory calendar HTML into EconomicEvent records.
 *
 * This class parses HTML content — it does NOT fetch it from the internet.
 * You can feed it:
 * - HTML saved from a browser (for testing — the fixture approach)
 * - HTML fetched by a separate HTTP client
 * - HTML from any other source
 *
 * This separation (parsing vs fetching) makes it testable without
 * network access and resilient to Cloudflare blocking.
 *
 * FOREX FACTORY HTML STRUCTURE:
 * <table class="calendar__table">
 * <tr class="calendar__row">
 * <td class="calendar__date">...</td>
 * <td class="calendar__time">8:30am</td>
 * <td class="calendar__currency">USD</td>
 * <td class="calendar__impact">
 * <span class="calendar__impact-icon calendar__impact-icon--red"></span>
 * </td>
 * <td class="calendar__event">Non-Farm Payrolls</td>
 * </tr>
 * </table>
 *
 * Impact levels are indicated by CSS class on the impact icon:
 * calendar__impact-icon--red = HIGH
 * calendar__impact-icon--orange = MEDIUM
 * calendar__impact-icon--yellow = LOW
 * calendar__impact-icon--gray = HOLIDAY (treat as LOW)
 */
public class ForexFactoryHtmlParser {

	// Forex Factory times are in EST (Eastern US)
	private static final ZoneId FF_TIMEZONE = ZoneId.of("America/New_York");

	/**
	 * Parses calendar HTML and returns all events found.
	 *
	 * @param html          the raw HTML content of the Forex Factory calendar page
	 * @param referenceDate the date this calendar page represents
	 *                      (used when rows don't have explicit dates)
	 * @return list of parsed events
	 */
	public List<EconomicEvent> parse(String html, LocalDate referenceDate) {
		List<EconomicEvent> events = new ArrayList<>();

		if (html == null || html.isBlank()) {
			return events;
		}

		Document doc = Jsoup.parse(html);

		// Select all calendar rows
		Elements rows = doc.select("tr.calendar__row");

		// Track the current date — FF only shows the date on the first row
		// of each day. Subsequent rows on the same day have empty date cells.
		LocalDate currentDate = referenceDate;

		for (Element row : rows) {
			try {
				// ── DATE ────────────────────────────────────────────
				Element dateCell = row.selectFirst("td.calendar__date");
				if (dateCell != null) {
					String dateText = dateCell.text().trim();
					if (!dateText.isEmpty()) {
						LocalDate parsed = parseDateCell(dateText, referenceDate.getYear());
						if (parsed != null) {
							currentDate = parsed;
						}
					}
				}

				// ── TIME ────────────────────────────────────────────
				Element timeCell = row.selectFirst("td.calendar__time");
				if (timeCell == null)
					continue;
				String timeText = timeCell.text().trim();

				// Skip rows with no time or "All Day" or "Tentative"
				if (timeText.isEmpty() || timeText.equalsIgnoreCase("All Day")
						|| timeText.equalsIgnoreCase("Tentative")) {
					continue;
				}

				LocalTime time = parseTimeCell(timeText);
				if (time == null)
					continue;

				// ── CURRENCY ────────────────────────────────────────
				Element currencyCell = row.selectFirst("td.calendar__currency");
				if (currencyCell == null)
					continue;
				String currency = currencyCell.text().trim().toUpperCase();
				if (currency.isEmpty())
					continue;

				// ── IMPACT ──────────────────────────────────────────
				Element impactCell = row.selectFirst("td.calendar__impact");
				ImpactLevel impact = parseImpact(impactCell);

				// ── EVENT NAME ──────────────────────────────────────
				Element eventCell = row.selectFirst("td.calendar__event");
				if (eventCell == null)
					continue;
				String eventName = eventCell.text().trim();
				if (eventName.isEmpty())
					continue;

				// ── BUILD THE EVENT ─────────────────────────────────
				ZonedDateTime eventTimeEst = ZonedDateTime.of(currentDate, time, FF_TIMEZONE);
				Instant scheduledTime = eventTimeEst.toInstant();

				events.add(new EconomicEvent(scheduledTime, currency, eventName, impact));

			} catch (Exception e) {
				// Skip unparseable rows — don't crash the entire parse
				// This is expected: some rows have unusual formatting
				System.err.println("[ForexFactoryHtmlParser] Skipping row: " + e.getMessage());
			}
		}

		return events;
	}

	/**
	 * Parses the date cell text.
	 *
	 * Forex Factory uses formats like "Mon Jul 22" or "Jul 22".
	 * We try several patterns.
	 */
	private LocalDate parseDateCell(String text, int year) {
		try {
			// Try "Mon Jul 22" format
			String cleaned = text.replaceAll("\\s+", " ").trim();

			// Remove day-of-week prefix if present (Mon, Tue, etc.)
			if (cleaned.length() > 4 && Character.isLetter(cleaned.charAt(0))) {
				// Check if it starts with a 3-letter day abbreviation
				String[] parts = cleaned.split("\\s+");
				if (parts.length >= 2) {
					// Try parsing just month + day
					String monthDay = parts.length >= 3
							? parts[1] + " " + parts[2]
							: parts[0] + " " + parts[1];

					DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
					var parsed = fmt.parse(monthDay);
					int month = parsed.get(java.time.temporal.ChronoField.MONTH_OF_YEAR);
					int day = parsed.get(java.time.temporal.ChronoField.DAY_OF_MONTH);
					return LocalDate.of(year, month, day);
				}
			}
		} catch (Exception e) {
			// Fall through
		}
		return null;
	}

	/**
	 * Parses time text like "8:30am" or "1:45pm" into LocalTime.
	 */
	private LocalTime parseTimeCell(String text) {
		try {
			String cleaned = text.trim().toUpperCase(Locale.ENGLISH);

			// Handle formats: "8:30AM", "12:00PM", "1:45PM"
			DateTimeFormatter fmt = DateTimeFormatter.ofPattern("h:mma", Locale.ENGLISH);
			return LocalTime.parse(cleaned, fmt);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Determines impact level from the impact cell's icon CSS class.
	 *
	 * red = HIGH impact (NFP, FOMC, CPI)
	 * orange = MEDIUM impact (Trade Balance, Retail Sales)
	 * yellow = LOW impact
	 * gray = Holiday
	 */
	private ImpactLevel parseImpact(Element impactCell) {
		if (impactCell == null)
			return ImpactLevel.LOW;

		String html = impactCell.html().toLowerCase();

		if (html.contains("red") || html.contains("high")) {
			return ImpactLevel.HIGH;
		}
		if (html.contains("orange") || html.contains("medium")) {
			return ImpactLevel.MEDIUM;
		}
		return ImpactLevel.LOW;
	}
}
