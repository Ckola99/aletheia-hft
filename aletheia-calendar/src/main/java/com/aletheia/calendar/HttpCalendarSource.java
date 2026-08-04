package com.aletheia.calendar;

import com.aletheia.core.EconomicEvent;
import com.aletheia.core.ImpactLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Fetches economic events from the standalone Aletheia Calendar Service over
 * HTTP.
 *
 * This is the primary calendar source in production: the calendar service is a
 * separate app (default http://localhost:8090) that ingests events from
 * official
 * sources and Forex Factory, and serves them at GET
 * /calendar?from=&to=&impact=.
 *
 * FAIL-SAFE: if the service is unreachable or returns an error (cold start,
 * network blip, service down), this THROWS CalendarFetchException. It never
 * returns an empty list on failure — the caller (CalendarLoaderService) then
 * falls back to the CSV, and if that is also unavailable the news pillar treats
 * the calendar as stale and blocks trading. An unreachable calendar must never
 * be mistaken for "no events, safe to trade."
 *
 * The HTTP call is injected as a Function so this class is unit-testable
 * without a running calendar service.
 */
public class HttpCalendarSource implements CalendarDataSource {

	private final String baseUrl;
	private final ObjectMapper mapper = new ObjectMapper();

	/** url -> Optional-ish: returns the JSON body, or null on any failure. */
	private final Function<String, String> httpGet;

	/** Production constructor — real OkHttp call. */
	public HttpCalendarSource(String baseUrl) {
		this(baseUrl, HttpCalendarSource::defaultHttpGet);
	}

	/** Test constructor — inject a stub returning canned JSON (no network). */
	HttpCalendarSource(String baseUrl, Function<String, String> httpGet) {
		this.baseUrl = baseUrl;
		this.httpGet = httpGet;
	}

	@Override
	public List<EconomicEvent> fetch(LocalDate from, LocalDate to)
			throws CalendarFetchException {

		// We only care about HIGH-impact events for the news blackout.
		String url = baseUrl + "/calendar?from=" + from + "&to=" + to + "&impact=HIGH";

		String body = httpGet.apply(url);
		if (body == null) {
			throw new CalendarFetchException(
					"Calendar service unreachable or errored: " + url);
		}

		try {
			return parse(body);
		} catch (Exception e) {
			throw new CalendarFetchException(
					"Failed to parse calendar service response", e);
		}
	}

	/**
	 * Maps the calendar service's JSON array into the engine's EconomicEvent.
	 *
	 * Service JSON per element:
	 * { "scheduledTime":"2026-08-07T12:30:00Z", "name":"Non-Farm Employment
	 * Change", "currency":"USD", "impact":"HIGH", ... }
	 *
	 * Note the field-name difference: service "name" -> engine "eventName".
	 */
	List<EconomicEvent> parse(String body) throws Exception {
		JsonNode root = mapper.readTree(body);
		if (!root.isArray()) {
			throw new IllegalArgumentException("Expected a JSON array");
		}

		List<EconomicEvent> events = new ArrayList<>();
		for (JsonNode node : root) {
			try {
				Instant when = Instant.parse(node.path("scheduledTime").asText());
				String currency = node.path("currency").asText("").toUpperCase();
				String name = node.path("name").asText("");
				ImpactLevel impact = ImpactLevel.valueOf(node.path("impact").asText("").toUpperCase());

				if (name.isEmpty() || currency.isEmpty())
					continue;

				events.add(new EconomicEvent(when, currency, name, impact));
			} catch (Exception skip) {
				// Skip a malformed element rather than failing the whole fetch
			}
		}
		return events;
	}

	private static String defaultHttpGet(String url) {
		OkHttpClient client = new OkHttpClient.Builder()
				.callTimeout(Duration.ofSeconds(60))
				.build();
		Request request = new Request.Builder().url(url).get().build();
		try (Response response = client.newCall(request).execute()) {
			if (!response.isSuccessful() || response.body() == null) {
				System.err.println("[HttpCalendarSource] HTTP " + response.code()
						+ " from " + url);
				return null;
			}
			return response.body().string();
		} catch (Exception e) {
			System.err.println("[HttpCalendarSource] request failed: " + e.getMessage());
			return null;
		}
	}
}
