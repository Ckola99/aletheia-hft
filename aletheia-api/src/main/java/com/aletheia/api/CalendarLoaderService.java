package com.aletheia.api;

import com.aletheia.calendar.CalendarDataSource;
import com.aletheia.calendar.CsvCalendarLoader;
import com.aletheia.calendar.EconomicCalendarService;
import com.aletheia.calendar.ForexFactoryHtmlParser;
import com.aletheia.core.EconomicEvent;

import jakarta.annotation.PostConstruct;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Loads economic-calendar events into the shared EconomicCalendarService so the
 * live news-blackout pillar actually protects trading.
 *
 * SOURCE CHAIN (first non-empty wins; a failure never clears existing data):
 * 1. PRIMARY — JBlanked Forex Factory API (proper JSON, if an API key is set)
 * 2. HTML — best-effort Forex Factory HTML fetch + parse (usually blocked)
 * 3. CSV — reliable local fallback
 *
 * Runs once at startup and on a schedule. Warns loudly if the cache ends up
 * empty, so you always know whether news protection is actually active.
 */
@Component
public class CalendarLoaderService {

	private final EconomicCalendarService calendarService;
	private final ForexFactoryHtmlParser parser = new ForexFactoryHtmlParser();

	private final CalendarDataSource primarySource; // JBlanked (nullable)
	private final boolean enabled;
	private final String csvPath;
	private final String calendarUrl;
	private final int lookaheadDays;
	private final Function<String, Optional<String>> htmlFetcher;

	@Autowired
	public CalendarLoaderService(
			EconomicCalendarService calendarService,
			@Value("${trading.calendar.enabled:true}") boolean enabled,
			@Value("${trading.calendar.csv-path:data/calendar_current.csv}") String csvPath,
			@Value("${trading.calendar.url:}") String calendarUrl,
			@Value("${trading.calendar.lookahead-days:21}") int lookaheadDays,
			@Value("${trading.calendar.jblanked.enabled:true}") boolean jbEnabled,
			@Value("${trading.calendar.jblanked.api-key:}") String jbApiKey,
			@Value("${trading.calendar.jblanked.base-url:https://www.jblanked.com}") String jbBaseUrl,
			@Value("${trading.calendar.jblanked.timezone:America/New_York}") String jbZone) {

		this(calendarService, enabled, csvPath, calendarUrl, lookaheadDays,
				(jbEnabled && jbApiKey != null && !jbApiKey.isBlank())
						? new JBlankedCalendarSource(jbApiKey, jbBaseUrl, ZoneId.of(jbZone))
						: null,
				CalendarLoaderService::fetchHtmlOverHttp);
	}

	/**
	 * Full constructor — tests inject a stub primary source and/or html fetcher.
	 */
	CalendarLoaderService(
			EconomicCalendarService calendarService,
			boolean enabled,
			String csvPath,
			String calendarUrl,
			int lookaheadDays,
			CalendarDataSource primarySource,
			Function<String, Optional<String>> htmlFetcher) {
		this.calendarService = calendarService;
		this.enabled = enabled;
		this.csvPath = csvPath;
		this.calendarUrl = calendarUrl;
		this.lookaheadDays = lookaheadDays;
		this.primarySource = primarySource;
		this.htmlFetcher = htmlFetcher;
	}

	@PostConstruct
	public void init() {
		if (!enabled) {
			System.out.println("[CalendarLoaderService] Disabled — NEWS BLACKOUT PROTECTION IS OFF.");
			return;
		}
		load();
	}

	/**
	 * Refresh (daily by default — respects JBlanked's ~1 request/day free tier).
	 */
	@Scheduled(cron = "${trading.calendar.refresh-cron:0 0 6 * * *}")
	public void scheduledRefresh() {
		if (enabled)
			load();
	}

	public void load() {
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		LocalDate from = today.minusDays(1);
		LocalDate to = today.plusDays(lookaheadDays);

		List<EconomicEvent> events = tryPrimary(from, to);
		String source = "JBlanked";

		if (events.isEmpty()) {
			events = tryFetchAndParse(today);
			source = "HTML";
		}
		if (events.isEmpty()) {
			events = tryCsv(from, to);
			source = "CSV";
		}

		if (events.isEmpty()) {
			System.err.println("=====================================================");
			System.err.println("  WARNING: economic calendar is EMPTY.");
			System.err.println("  News-blackout protection is NOT active.");
			System.err.println("  Set a JBlanked API key or provide a current CSV at: " + csvPath);
			System.err.println("=====================================================");
			return; // keep whatever was previously loaded
		}

		calendarService.loadEvents(events);
		System.out.println("[CalendarLoaderService] Loaded " + events.size()
				+ " events from " + source + ". Cache size now "
				+ calendarService.cacheSize() + ".");
	}

	private List<EconomicEvent> tryPrimary(LocalDate from, LocalDate to) {
		if (primarySource == null)
			return List.of();
		try {
			return primarySource.fetch(from, to);
		} catch (Exception e) {
			System.out.println("[CalendarLoaderService] Primary source failed: " + e.getMessage());
			return List.of();
		}
	}

	private List<EconomicEvent> tryFetchAndParse(LocalDate referenceDate) {
		if (calendarUrl == null || calendarUrl.isBlank())
			return List.of();
		try {
			Optional<String> html = htmlFetcher.apply(calendarUrl);
			if (html.isEmpty())
				return List.of();
			return parser.parse(html.get(), referenceDate);
		} catch (Exception e) {
			System.out.println("[CalendarLoaderService] HTML fetch/parse failed: " + e.getMessage());
			return List.of();
		}
	}

	private List<EconomicEvent> tryCsv(LocalDate from, LocalDate to) {
		try {
			return new CsvCalendarLoader(Path.of(csvPath)).fetch(from, to);
		} catch (Exception e) {
			System.out.println("[CalendarLoaderService] CSV load failed (" + csvPath
					+ "): " + e.getMessage());
			return List.of();
		}
	}

	private static Optional<String> fetchHtmlOverHttp(String url) {
		OkHttpClient client = new OkHttpClient.Builder()
				.callTimeout(Duration.ofSeconds(15)).build();
		Request request = new Request.Builder()
				.url(url)
				.header("User-Agent",
						"Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
								+ "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36")
				.get().build();
		try (Response response = client.newCall(request).execute()) {
			if (!response.isSuccessful() || response.body() == null)
				return Optional.empty();
			return Optional.of(response.body().string());
		} catch (Exception e) {
			return Optional.empty();
		}
	}
}
