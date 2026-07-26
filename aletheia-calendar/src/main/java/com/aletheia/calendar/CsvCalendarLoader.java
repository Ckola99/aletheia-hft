package com.aletheia.calendar;

import com.aletheia.core.EconomicEvent;
import com.aletheia.core.ImpactLevel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads economic calendar events from a CSV file.
 *
 * WHY CSV?
 * 1. Forex Factory can block automated scraping (Cloudflare)
 * 2. Historical events for backtesting need a reliable offline source
 * 3. You can download calendar data manually, save as CSV, and import it
 * 4. CSV is simple, portable, and easy to validate
 *
 * EXPECTED CSV FORMAT:
 * date,time,currency,impact,event
 * 2023-01-06,08:30,USD,HIGH,Non-Farm Payrolls
 * 2023-01-12,08:30,USD,HIGH,CPI m/m
 * 2023-02-01,14:00,USD,HIGH,FOMC Statement
 *
 * - date: YYYY-MM-DD format
 * - time: HH:mm in EST (Eastern US timezone)
 * - currency: 3-letter code (USD, EUR, GBP)
 * - impact: HIGH, MEDIUM, or LOW
 * - event: the event name
 *
 * First line is a header and is skipped.
 */
public class CsvCalendarLoader implements CalendarDataSource {

	private static final ZoneId EST = ZoneId.of("America/New_York");
	private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

	private final Path csvPath;

	/**
	 * @param csvPath path to the CSV file
	 */
	public CsvCalendarLoader(Path csvPath) {
		this.csvPath = csvPath;
	}

	@Override
	public List<EconomicEvent> fetch(LocalDate from, LocalDate to) throws CalendarFetchException {
		try {
			List<EconomicEvent> allEvents = loadAll();

			// Filter to the requested date range
			Instant fromInstant = from.atStartOfDay(EST).toInstant();
			Instant toInstant = to.plusDays(1).atStartOfDay(EST).toInstant();

			return allEvents.stream()
					.filter(e -> !e.scheduledTime().isBefore(fromInstant))
					.filter(e -> e.scheduledTime().isBefore(toInstant))
					.toList();

		} catch (IOException e) {
			throw new CalendarFetchException("Failed to read CSV: " + csvPath, e);
		}
	}

	/**
	 * Loads all events from the CSV file.
	 */
	private List<EconomicEvent> loadAll() throws IOException {
		List<EconomicEvent> events = new ArrayList<>();

		try (BufferedReader reader = Files.newBufferedReader(csvPath)) {
			String header = reader.readLine(); // skip header line

			String line;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty())
					continue;

				EconomicEvent event = parseLine(line);
				if (event != null) {
					events.add(event);
				}
			}
		}

		return events;
	}

	/**
	 * Parses a single CSV line into an EconomicEvent.
	 *
	 * Expected format: date,time,currency,impact,event
	 * Example: 2023-01-06,08:30,USD,HIGH,Non-Farm Payrolls
	 */
	private EconomicEvent parseLine(String line) {
		try {
			String[] parts = line.split(",", 5);
			if (parts.length < 5)
				return null;

			LocalDate date = LocalDate.parse(parts[0].trim(), DATE_FMT);
			String timeStr = parts[1].trim();
			String currency = parts[2].trim().toUpperCase();
			ImpactLevel impact = parseImpact(parts[3].trim());
			String eventName = parts[4].trim();

			// Parse time and combine with date in EST
			var time = java.time.LocalTime.parse(timeStr, TIME_FMT);
			ZonedDateTime eventTime = ZonedDateTime.of(date, time, EST);
			Instant scheduledTime = eventTime.toInstant();

			return new EconomicEvent(scheduledTime, currency, eventName, impact);

		} catch (Exception e) {
			System.err.println("[CsvCalendarLoader] Skipping line: " + line
					+ " — " + e.getMessage());
			return null;
		}
	}

	private ImpactLevel parseImpact(String text) {
		return switch (text.toUpperCase()) {
			case "HIGH", "RED" -> ImpactLevel.HIGH;
			case "MEDIUM", "MED", "ORANGE" -> ImpactLevel.MEDIUM;
			default -> ImpactLevel.LOW;
		};
	}
}
