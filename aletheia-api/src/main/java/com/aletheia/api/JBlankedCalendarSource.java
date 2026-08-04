package com.aletheia.api;

import com.aletheia.calendar.CalendarDataSource;
import com.aletheia.calendar.CalendarFetchException;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * CalendarDataSource backed by the JBlanked Calendar API, which serves
 * Forex Factory events as JSON — the same data our HTML parser targets, but
 * through a proper authenticated endpoint instead of fighting Cloudflare.
 *
 * ENDPOINT (Forex Factory, date range):
 * GET
 * {base}/news/api/forex-factory/calendar/range/?from=YYYY-MM-DD&to=YYYY-MM-DD
 * Header: Authorization: Api-Key YOUR_KEY
 *
 * RESPONSE (array of):
 * { "Name":"Core CPI m/m", "Currency":"USD", "Impact":"High",
 * "Date":"2026.02.08 15:30:00", ... }
 *
 * NOTES / LIMITATIONS:
 * - Free tier is ~1 request/day, so refresh at most daily. One wide-window
 * pull per day covers the rolling news guard.
 * - The "Date" timezone must be confirmed against your account settings.
 * We treat it as configurable (default America/New_York = EST, matching the
 * convention used by our CSV loader). A wrong zone would shift the ±15min
 * blackout window, so verify this once against a known release.
 *
 * The HTTP call is injected as a Function so this class is unit-testable
 * without network access.
 */
public class JBlankedCalendarSource implements CalendarDataSource {

	private static final DateTimeFormatter API_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss");

	private final String baseUrl;
	private final ZoneId sourceZone;
	private final ObjectMapper mapper = new ObjectMapper();

	/** url -> Optional<jsonBody>. Empty means the request failed. */
	private final Function<String, Optional<String>> httpGet;

	/** Production constructor — real OkHttp call with the API key header. */
	public JBlankedCalendarSource(String apiKey, String baseUrl, ZoneId sourceZone) {
		this(baseUrl, sourceZone, url -> httpGetWithKey(url, apiKey));
	}

	/** Test constructor — inject a stubbed fetcher (no network). */
	JBlankedCalendarSource(String baseUrl, ZoneId sourceZone,
			Function<String, Optional<String>> httpGet) {
		this.baseUrl = baseUrl;
		this.sourceZone = sourceZone;
		this.httpGet = httpGet;
	}

	@Override
	public List<EconomicEvent> fetch(LocalDate from, LocalDate to)
			throws CalendarFetchException {

		String url = baseUrl + "/news/api/forex-factory/calendar/range/?from="
				+ from + "&to=" + to;

		String body = httpGet.apply(url)
				.orElseThrow(() -> new CalendarFetchException(
						"JBlanked request failed or was blocked: " + url));

		try {
			return parse(body);
		} catch (Exception e) {
			throw new CalendarFetchException("Failed to parse JBlanked response", e);
		}
	}

	/** Parses the JSON array (or {"data":[...]}) into EconomicEvent records. */
	List<EconomicEvent> parse(String body) throws Exception {
		JsonNode root = mapper.readTree(body);
		JsonNode array = root.isArray() ? root
				: (root.has("data") && root.get("data").isArray() ? root.get("data") : null);

		List<EconomicEvent> events = new ArrayList<>();
		if (array == null)
			return events;

		for (JsonNode node : array) {
			try {
				String name = node.path("Name").asText("").trim();
				String currency = node.path("Currency").asText("").trim().toUpperCase();
				String dateStr = node.path("Date").asText("").trim();
				if (name.isEmpty() || currency.isEmpty() || dateStr.isEmpty())
					continue;

				ImpactLevel impact = mapImpact(node.path("Impact").asText(""));

				LocalDateTime ldt = LocalDateTime.parse(dateStr, API_DATE);
				Instant when = ldt.atZone(sourceZone).toInstant();

				events.add(new EconomicEvent(when, currency, name, impact));
			} catch (Exception skip) {
				// Skip malformed rows rather than failing the whole load
			}
		}
		return events;
	}

	private ImpactLevel mapImpact(String raw) {
		return switch (raw.trim().toUpperCase()) {
			case "HIGH", "RED" -> ImpactLevel.HIGH;
			case "MEDIUM", "MED", "ORANGE" -> ImpactLevel.MEDIUM;
			default -> ImpactLevel.LOW;
		};
	}

	private static Optional<String> httpGetWithKey(String url, String apiKey) {
		OkHttpClient client = new OkHttpClient.Builder()
				.callTimeout(Duration.ofSeconds(20))
				.build();
		Request request = new Request.Builder()
				.url(url)
				.header("Authorization", "Api-Key " + apiKey)
				.header("Content-Type", "application/json")
				.get()
				.build();
		try (Response response = client.newCall(request).execute()) {
			if (!response.isSuccessful() || response.body() == null) {
				System.out.println("[JBlanked] HTTP " + response.code());
				return Optional.empty();
			}
			return Optional.of(response.body().string());
		} catch (Exception e) {
			System.out.println("[JBlanked] request failed: " + e.getMessage());
			return Optional.empty();
		}
	}
}
