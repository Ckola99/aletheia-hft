package com.aletheia.calendar;

/**
 * Thrown when fetching calendar data fails.
 * The caller should handle this gracefully — log the error and
 * continue with stale cache data rather than crashing.
 */
public class CalendarFetchException extends Exception {

	public CalendarFetchException(String message) {
		super(message);
	}

	public CalendarFetchException(String message, Throwable cause) {
		super(message, cause);
	}
}
