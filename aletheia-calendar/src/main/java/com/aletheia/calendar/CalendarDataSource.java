package com.aletheia.calendar;

import com.aletheia.core.EconomicEvent;

import java.time.LocalDate;
import java.util.List;

/**
 * Interface for fetching economic calendar events.
 *
 * WHY AN INTERFACE?
 * Forex Factory may block automated requests (Cloudflare protection).
 * By defining an interface, we can swap implementations:
 * - ForexFactoryHtmlParser: parses FF calendar HTML
 * - CsvCalendarLoader: imports from a CSV file (reliable fallback)
 * - Any future API source (TradingEconomics, FMP, etc.)
 *
 * The EconomicCalendarService doesn't care WHERE events come from —
 * it just receives a List<EconomicEvent> and manages the cache.
 */
public interface CalendarDataSource {

	/**
	 * Fetches economic events for the given date range.
	 *
	 * @param from start date (inclusive)
	 * @param to   end date (inclusive)
	 * @return list of events, may be empty if no data available
	 * @throws CalendarFetchException if the fetch fails
	 */
	List<EconomicEvent> fetch(LocalDate from, LocalDate to) throws CalendarFetchException;
}
