package com.aletheia.core;

import java.time.Instant;

/**
 * A single economic calendar event — e.g. NFP, FOMC, CPI.
 *
 * Scraped from Forex Factory and stored in the economic_events table.
 * The EconomicCalendarService reads these to determine whether
 * trading should be blocked.
 *
 * EXAMPLES OF HIGH IMPACT EVENTS:
 * NFP (Non-Farm Payrolls) — USD — first Friday of every month
 * FOMC Rate Decision — USD — 8 times per year
 * CPI (Consumer Price Index) — USD/EUR/GBP — monthly
 * GDP (Gross Domestic Product) — USD/EUR/GBP — quarterly
 * Central Bank Rate Decision — EUR (ECB), GBP (BOE)
 *
 * @param scheduledTime when the event is scheduled to be released (UTC)
 * @param currency      which currency is affected: "USD", "EUR", "GBP"
 * @param eventName     human-readable name: "Non-Farm Payrolls"
 * @param impact        HIGH, MEDIUM, or LOW
 */
public record EconomicEvent(
		Instant scheduledTime,
		String currency,
		String eventName,
		ImpactLevel impact) {

	/**
	 * Returns true if this is a market-moving event that should block trading.
	 */
	public boolean isHighImpact() {
		return impact == ImpactLevel.HIGH;
	}
}
