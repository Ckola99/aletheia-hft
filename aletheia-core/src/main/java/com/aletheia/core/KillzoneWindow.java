package com.aletheia.core;

/**
 * ICT Killzone time windows.
 * All times are in EST (America/New_York timezone).
 *
 * ICT THEORY:
 * The Interbank Price Delivery Algorithm (IPDA) is most active during
 * specific sessions when institutional liquidity is highest.
 * Outside these windows, price movement is mostly noise — retail
 * traders chasing each other's stops with no institutional backing.
 *
 * Aletheia ONLY generates signals inside these windows:
 *
 * LONDON_OPEN 02:00 – 05:00 EST
 * → The Judas Swing most often occurs here.
 * → Price creates a false move, traps retail, then reverses.
 * → Sets up the "low of day" or "high of day".
 *
 * NEW_YORK_OPEN 07:00 – 10:00 EST
 * → Highest volume session. True directional expansion.
 * → London's Judas Swing is confirmed here.
 * → Best R:R opportunities of the day.
 *
 * LONDON_CLOSE 10:00 – 12:00 EST
 * → Counter-trend scalps only. Advanced — not used in early Aletheia.
 *
 * NONE All other hours → no signal generation
 */

public enum KillzoneWindow {

	LONDON_OPEN,
	NEW_YORK_OPEN,
	LONDON_CLOSE,
	NONE;

	/**
	 * Returns true if we are inside an active trading window.
	 * Used by PreTradeGuard as the first filter before any signal evaluation.
	 */

	public boolean isActive() {
		return this != NONE;
	}

	/**
	 * Returns a human-readable description for logging and dashboards.
	 */
	
	public String displayName() {
		return switch (this) {
			case LONDON_OPEN -> "London Open (02:00-05:00 EST)";
			case NEW_YORK_OPEN -> "New York Open (07:00-10:00 EST)";
			case LONDON_CLOSE -> "London Close (10:00-12:00 EST)";
			case NONE -> "Outside Killzone";
		};
	}
}
