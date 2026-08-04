package com.aletheia.api;

import com.aletheia.calendar.CalendarDataSource;
import com.aletheia.calendar.CsvCalendarLoader;
import com.aletheia.calendar.EconomicCalendarService;
import com.aletheia.calendar.HttpCalendarSource;
import com.aletheia.core.EconomicEvent;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Loads economic-calendar events into the shared EconomicCalendarService so the
 * live news-blackout pillar has data to check against.
 *
 * SOURCE CHAIN (first non-empty wins; a failure never clears existing data):
 * 1. PRIMARY — the standalone Aletheia Calendar Service, over HTTP.
 * 2. FALLBACK — a local CSV, for when the calendar service is unreachable.
 *
 * Runs at startup and daily. Warns loudly if the cache ends up empty, so it is
 * always visible whether news protection is actually active.
 */
@Component
public class CalendarLoaderService {

	private final EconomicCalendarService calendarService;
	private final CalendarDataSource primarySource; // HTTP -> calendar service
	private final CalendarDataSource fallbackSource; // local CSV
	private final int lookaheadDays;

	public CalendarLoaderService(
			EconomicCalendarService calendarService,
			@Value("${calendar.service.url:http://localhost:8090}") String serviceUrl,
			@Value("${trading.calendar.csv-path:data/calendar_current.csv}") String csvPath,
			@Value("${trading.calendar.lookahead-days:21}") int lookaheadDays) {
		this.calendarService = calendarService;
		this.primarySource = new HttpCalendarSource(serviceUrl);
		this.fallbackSource = new CsvCalendarLoader(Path.of(csvPath));
		this.lookaheadDays = lookaheadDays;
	}

	private static final int MAX_STARTUP_RETRIES = 6; // ~30 min of retries
	private static final long RETRY_DELAY_MS = 5 * 60 * 1000; // 5 minutes
	private final java.util.concurrent.atomic.AtomicInteger startupRetries = new java.util.concurrent.atomic.AtomicInteger(
			0);

	@PostConstruct
	public void init() {
		load();
		// If the first load got nothing (e.g. Render service still cold),
		// schedule retries so we don't run with an empty calendar until the
		// next daily refresh.
		if (calendarService.cacheSize() == 0) {
			System.out.println("[CalendarLoaderService] Calendar empty at startup "
					+ "(service may be warming up). Will retry in 5 minutes.");
		}
	}

	/**
	 * Retries the initial load every 5 minutes if the calendar is still empty,
	 * up to a cap. This covers the cold-start case: the Render calendar service
	 * spins down when idle and takes ~30-60s to wake, so the engine's first
	 * load can miss it. Once events load, retries stop and the normal daily
	 * schedule takes over.
	 */
	@Scheduled(fixedDelay = RETRY_DELAY_MS, initialDelay = RETRY_DELAY_MS)
	public void retryUntilLoaded() {
		if (calendarService.cacheSize() > 0) {
			return; // already have data — nothing to do
		}
		int attempt = startupRetries.incrementAndGet();
		if (attempt > MAX_STARTUP_RETRIES) {
			return; // gave up on startup retries; daily refresh still runs
		}
		System.out.println("[CalendarLoaderService] Startup retry " + attempt
				+ "/" + MAX_STARTUP_RETRIES + " — attempting calendar load...");
		load();
	}

	@Scheduled(cron = "${trading.calendar.refresh-cron:0 0 6 * * *}")
	public void scheduledRefresh() {
		load();
	}

	public void load() {
		LocalDate from = LocalDate.now(ZoneOffset.UTC).minusDays(1);
		LocalDate to = LocalDate.now(ZoneOffset.UTC).plusDays(lookaheadDays);

		List<EconomicEvent> events = tryFetch("calendar service", primarySource, from, to);
		if (events.isEmpty()) {
			events = tryFetch("CSV fallback", fallbackSource, from, to);
		}

		if (events.isEmpty()) {
			System.err.println("=====================================================");
			System.err.println("  WARNING: economic calendar is EMPTY.");
			System.err.println("  News-blackout protection is NOT active.");
			System.err.println("  Calendar service unreachable and no CSV fallback.");
			System.err.println("=====================================================");
			return; // keep whatever was previously cached
		}

		calendarService.loadEvents(events);
		System.out.println("[CalendarLoaderService] Loaded " + events.size()
				+ " events. Cache size now " + calendarService.cacheSize() + ".");
	}

	private List<EconomicEvent> tryFetch(String label, CalendarDataSource source,
			LocalDate from, LocalDate to) {
		try {
			List<EconomicEvent> events = source.fetch(from, to);
			if (!events.isEmpty()) {
				System.out.println("[CalendarLoaderService] Loaded "
						+ events.size() + " events from " + label + ".");
			}
			return events;
		} catch (Exception e) {
			System.out.println("[CalendarLoaderService] " + label
					+ " failed: " + e.getMessage());
			return List.of();
		}
	}
}
