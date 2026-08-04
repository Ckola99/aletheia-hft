package com.aletheia.calendar;

import com.aletheia.core.EconomicEvent;
import com.aletheia.core.ImpactLevel;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Manages the economic calendar and provides the news blackout check.
 *
 * ARCHITECTURE:
 * This service holds an in-memory list of upcoming high-impact events.
 * The isNewsBlackout() method scans this list — it never touches the
 * database or makes network calls. This is critical because isNewsBlackout()
 * is called on every signal evaluation — potentially thousands of times
 * per second. It must be pure in-memory.
 *
 * The list is refreshed by calling loadEvents() — which is done either:
 * - By a scheduled job that reads from the database every 10 minutes
 * - By the backtest engine that loads historical events for a date range
 * - By tests that inject known events directly
 *
 * THREAD SAFETY:
 * The events field is 'volatile'. This guarantees that when the scheduler
 * thread writes a new list, the trading thread immediately sees it.
 * Without volatile, the trading thread might read a stale cached copy
 * from CPU cache and miss a newly added event.
 *
 * The list itself is immutable (List.copyOf) — once created, it cannot
 * be modified. Readers iterate safely without synchronisation.
 *
 * BLACKOUT WINDOW:
 * ±15 minutes around each HIGH impact event.
 * If NFP is at 13:30 UTC, trading is blocked from 13:15 to 13:45.
 */
public class EconomicCalendarService {

	// The blackout window: 15 minutes before and after the event
	private static final Duration BLACKOUT_BEFORE = Duration.ofMinutes(15);
	private static final Duration BLACKOUT_AFTER = Duration.ofMinutes(15);

	// Volatile: guarantees cross-thread visibility
	// When the scheduler writes a new list, the trading thread sees it immediately
	private volatile List<EconomicEvent> events = List.of();

	/**
	 * Loads events into the in-memory cache.
	 *
	 * Called by:
	 * - The scheduled job every 10 minutes (production)
	 * - The backtest engine with historical events
	 * - Tests with fabricated events
	 *
	 * @param newEvents the events to cache (will be copied to an immutable list)
	 */
	public void loadEvents(List<EconomicEvent> newEvents) {
		// List.copyOf creates an immutable copy — the original list
		// can be modified later without affecting our cache
		this.events = newEvents.stream()
				.sorted((a, b) -> a.scheduledTime().compareTo(b.scheduledTime()))
				.toList();
	}

	/**
	 * THE HOT-PATH METHOD — called on every signal evaluation.
	 *
	 * Returns true if trading should be BLOCKED for the given instrument
	 * at the given time. A high-impact event affecting this instrument's
	 * currencies is within ±15 minutes.
	 *
	 * This method is pure in-memory — no database, no network, no I/O.
	 * It scans a small list (typically 5-20 events) and does simple
	 * time comparisons. Execution time: microseconds.
	 *
	 * @param now        the current time (or simulated time in backtest)
	 * @param instrument which instrument we want to trade e.g. "EUR_USD"
	 * @return true if trading is blocked, false if the coast is clear
	 */
	public boolean isNewsBlackout(Instant now, String instrument) {
		Set<String> affected = getAffectedCurrencies(instrument);

		return events.stream()
				// Only HIGH impact events block trading
				.filter(EconomicEvent::isHighImpact)
				// Only events that affect this instrument's currencies
				.filter(event -> affected.contains(event.currency()))
				// Check if 'now' falls within the ±15 minute window
				.anyMatch(event -> {
					Instant windowStart = event.scheduledTime().minus(BLACKOUT_BEFORE);
					Instant windowEnd = event.scheduledTime().plus(BLACKOUT_AFTER);
					// now >= windowStart AND now <= windowEnd
					return !now.isBefore(windowStart) && !now.isAfter(windowEnd);
				});
	}

	/**
	 * Returns the next upcoming high-impact event for the given instrument.
	 *
	 * Used by Grafana dashboards to show "Next news: NFP in 47 minutes"
	 * as a countdown annotation on the price chart.
	 *
	 * @param now        current time
	 * @param instrument which instrument
	 * @return the next event, or empty if nothing upcoming
	 */
	public Optional<EconomicEvent> nextHighImpactEvent(Instant now, String instrument) {
		Set<String> affected = getAffectedCurrencies(instrument);

		return events.stream()
				.filter(EconomicEvent::isHighImpact)
				.filter(event -> affected.contains(event.currency()))
				.filter(event -> event.scheduledTime().isAfter(now))
				.findFirst(); // events should be sorted by time
	}

	/**
	 * Returns how many events are currently in the cache.
	 * Useful for monitoring — if this is zero, the scraper may have failed.
	 */
	public int cacheSize() {
		return events.size();
	}

	/**
	 * Maps an instrument to the currencies it is affected by.
	 *
	 * EUR/USD is affected by BOTH EUR events AND USD events.
	 * If NFP (USD) is releasing, EUR/USD trading is blocked.
	 * If ECB Rate Decision (EUR) is releasing, EUR/USD is also blocked.
	 *
	 * XAU/USD (Gold) is affected by USD events because Gold is priced in dollars.
	 */
	private Set<String> getAffectedCurrencies(String instrument) {
		return switch (instrument) {
			case "EUR_USD" -> Set.of("EUR", "USD");
			case "GBP_USD" -> Set.of("GBP", "USD");
			case "XAU_USD" -> Set.of("USD");
			case "NAS100_USD", "US30_USD", "SPX500_USD" -> Set.of("USD");
			default -> Set.of("USD");
		};
	}

	/**
	 * Returns all cached events (immutable). For monitoring / debugging.
	 */
	public List<EconomicEvent> allEvents() {
		return events;
	}
}
