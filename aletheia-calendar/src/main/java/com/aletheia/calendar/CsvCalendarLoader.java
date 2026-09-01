package com.aletheia.calendar;

import com.aletheia.core.EconomicEvent;
import com.aletheia.core.ImpactLevel;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads economic calendar events from a CSV file.
 *
 * WHY CSV?
 * 1. Forex Factory can block automated scraping (Cloudflare)
 * 2. Historical events for backtesting need a reliable offline source
 * 3. You can download calendar data manually, save as CSV, and import it
 * 4. CSV is simple, portable, and easy to validate
 *
 * SUPPORTED CSV SCHEMAS (auto-detected from the header row):
 *
 * SCHEMA A — combined UTC timestamp (what the calendar service emits):
 * scheduled_time_utc,name,currency,impact,source
 * 2026-08-07T12:30:00Z,Non-Farm Employment Change,USD,HIGH,FOREX_FACTORY
 *
 * SCHEMA B — split date + EST-local time (hand-authored historical files):
 * date,time,currency,impact,event
 * 2024-01-05,08:30,USD,HIGH,Non-Farm Payrolls
 * - date: YYYY-MM-DD
 * - time: HH:mm in America/New_York (EST/EDT) local time
 *
 * Both resolve to the same absolute Instant internally. The header line is
 * inspected once to decide which schema is in play; if it can't be recognised,
 * we default to Schema B for backward compatibility.
 */
public class CsvCalendarLoader implements CalendarDataSource {

	private static final ZoneId EST = ZoneId.of("America/New_York");
	private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

	/** Which column layout a given CSV uses. */
	private enum Schema {
		UTC_TIMESTAMP, // scheduled_time_utc,name,currency,impact,source
		DATE_TIME_EST // date,time,currency,impact,event
	}

	private final Path csvPath;

	public CsvCalendarLoader(Path csvPath) {
		this.csvPath = csvPath;
	}

	@Override
	public List<EconomicEvent> fetch(LocalDate from, LocalDate to) throws CalendarFetchException {
		try {
			List<EconomicEvent> allEvents = loadAll();

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

	private List<EconomicEvent> loadAll() throws IOException {
		List<EconomicEvent> events = new ArrayList<>();

		try (BufferedReader reader = Files.newBufferedReader(csvPath)) {
			String header = reader.readLine();
			Schema schema = detectSchema(header);

			String line;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty())
					continue;

				EconomicEvent event = parseLine(line, schema);
				if (event != null) {
					events.add(event);
				}
			}
		}

		return events;
	}

	/**
	 * Inspects the header row to decide which schema the file uses. Falls back
	 * to the historical DATE_TIME_EST layout if the header is missing or
	 * unrecognised, preserving old behaviour.
	 */
	private Schema detectSchema(String header) {
		if (header == null) {
			return Schema.DATE_TIME_EST;
		}
		String h = header.toLowerCase();
		if (h.contains("scheduled_time_utc")) {
			return Schema.UTC_TIMESTAMP;
		}
		return Schema.DATE_TIME_EST;
	}

	private EconomicEvent parseLine(String line, Schema schema) {
		return switch (schema) {
			case UTC_TIMESTAMP -> parseUtcTimestampLine(line);
			case DATE_TIME_EST -> parseDateTimeEstLine(line);
		};
	}

	/**
	 * SCHEMA A: scheduled_time_utc,name,currency,impact,source
	 * e.g. 2026-08-07T12:30:00Z,Non-Farm Employment Change,USD,HIGH,FOREX_FACTORY
	 */
	private EconomicEvent parseUtcTimestampLine(String line) {
		try {
			String[] parts = line.split(",", 5);
			if (parts.length < 4)
				return null;

			Instant scheduledTime = Instant.parse(parts[0].trim());
			String eventName = parts[1].trim();
			String currency = parts[2].trim().toUpperCase();
			ImpactLevel impact = parseImpact(parts[3].trim());

			return new EconomicEvent(scheduledTime, currency, eventName, impact);

		} catch (Exception e) {
			System.err.println("[CsvCalendarLoader] Skipping line (utc schema): " + line
					+ " — " + e.getMessage());
			return null;
		}
	}

	/**
	 * SCHEMA B: date,time,currency,impact,event
	 * e.g. 2024-01-05,08:30,USD,HIGH,Non-Farm Payrolls (time is EST/EDT local)
	 */
	private EconomicEvent parseDateTimeEstLine(String line) {
		try {
			String[] parts = line.split(",", 5);
			if (parts.length < 5)
				return null;

			LocalDate date = LocalDate.parse(parts[0].trim(), DATE_FMT);
			String timeStr = parts[1].trim();
			String currency = parts[2].trim().toUpperCase();
			ImpactLevel impact = parseImpact(parts[3].trim());
			String eventName = parts[4].trim();

			LocalTime time = LocalTime.parse(timeStr, TIME_FMT);
			ZonedDateTime eventTime = ZonedDateTime.of(date, time, EST);
			Instant scheduledTime = eventTime.toInstant();

			return new EconomicEvent(scheduledTime, currency, eventName, impact);

		} catch (Exception e) {
			System.err.println("[CsvCalendarLoader] Skipping line (est schema): " + line
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
