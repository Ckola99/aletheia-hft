package com.aletheia.strategy;

import com.aletheia.core.KillzoneWindow;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Determines which ICT Killzone the current time falls in.
 *
 * ICT THEORY:
 * The IPDA (Interbank Price Delivery Algorithm) is most active during
 * specific windows. Outside these windows, price movement is mostly
 * noise — retail traders chasing each other. Aletheia only generates
 * signals inside these windows.
 *
 * All windows are defined in EST (America/New_York timezone).
 * We use ZoneId, NOT a fixed UTC offset, because EST shifts between
 * UTC-5 (winter) and UTC-4 (summer / EDT). Java handles the daylight
 * saving transitions automatically when you use ZoneId.
 *
 * LONDON_OPEN 02:00 – 05:00 EST
 * NEW_YORK_OPEN 07:00 – 10:00 EST
 * LONDON_CLOSE 10:00 – 12:00 EST
 * NONE all other hours
 *
 * This service is STATELESS — no fields, no mutable state.
 * Safe to share across threads. Safe to call millions of times.
 */

public class KillzoneService {

	// EST timezone — handles daylight saving automatically
	private static final ZoneId EST = ZoneId.of("America/New_York");

	/**
	 * Returns which Killzone the given time falls in.
	 *
	 * @param time any ZonedDateTime (will be converted to EST internally)
	 * @return the active KillzoneWindow, or NONE if outside all windows
	 */
	public KillzoneWindow classify(ZonedDateTime time) {
		// Convert to EST regardless of what timezone was passed in
		ZonedDateTime est = time.withZoneSameInstant(EST);

		int hour = est.getHour();
		int minute = est.getMinute();

		// Convert to decimal hour for easier range checks
		// 02:30 → 2.5, 09:45 → 9.75
		double decimalHour = hour + (minute / 60.0);

		// London Open: 02:00 – 05:00 EST
		if (decimalHour >= 2.0 && decimalHour < 5.0) {
			return KillzoneWindow.LONDON_OPEN;
		}

		// New York Open: 07:00 – 10:00 EST
		if (decimalHour >= 7.0 && decimalHour < 10.0) {
			return KillzoneWindow.NEW_YORK_OPEN;
		}

		// London Close: 10:00 – 12:00 EST
		if (decimalHour >= 10.0 && decimalHour < 12.0) {
			return KillzoneWindow.LONDON_CLOSE;
		}

		return KillzoneWindow.NONE;
	}

	/**
	 * Convenience overload: classify from a java.time.Instant.
	 * Converts to ZonedDateTime in UTC first, then classifies.
	 *
	 * This is what most of the system will call, since ticks
	 * and candles store their timestamps as Instants.
	 */
	public KillzoneWindow classify(java.time.Instant instant) {
		return classify(instant.atZone(java.time.ZoneOffset.UTC));
	}

	/**
	 * Returns true if the given time is inside any active killzone.
	 */
	public boolean isInKillzone(java.time.Instant instant) {
		return classify(instant).isActive();
	}
}
