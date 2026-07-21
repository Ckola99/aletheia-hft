package com.aletheia.core;

import java.time.Instant;

/**
 * A single raw price tick received from the OANDA streaming feed.
 *
 * WHAT IS A TICK?
 * Every time the price of EUR/USD changes — even by 0.00001 —
 * OANDA sends us a new tick. At peak London/NY session hours,
 * this can be dozens of ticks per second per instrument.
 *
 * Ticks are the raw material. Everything else — candles, FVGs,
 * Order Blocks, signals — is derived from ticks.
 *
 * WHY JAVA RECORD?
 * A record is an immutable data carrier. Once created, a Tick
 * cannot be modified. This makes it:
 * 1. Thread-safe — multiple threads can read the same Tick safely
 * 2. Cache-safe — you can store it without worrying about mutation
 * 3. Simple — no getters/setters boilerplate, no defensive copies needed
 *
 * WHY long FOR bid AND ask?
 * See PriceScale.java for the full explanation.
 * Short version: double 1.0825 + 0.0001 = 1.0826000000000002 (wrong).
 * Long 108250 + 10 = 108260 (exactly correct).
 *
 * @param time       When this tick arrived (UTC)
 * @param instrument The traded instrument e.g. "EUR_USD", "XAU_USD"
 * @param bid        Best price a buyer will pay (scaled long)
 * @param ask        Best price a seller will accept (scaled long)
 */

public record Tick(
		Instant time,
		String instrument,
		long bid,
		long ask) {

	/**
	 * The mid-price: halfway between bid and ask.
	 * Used for candle construction and PD Array detection.
	 * Avoids taking either side of the spread.
	 */

	public long mid() {
		return (bid + ask) / 2;
	}

	/**
	 * The spread: difference between ask and bid.
	 * A wider spread = more expensive to trade.
	 * During news events, spreads can spike 10x normal.
	 * Used by the calendar guard to detect early news volatility.
	 */

	public long spread() {
		return ask - bid;
	}

	/**
	 * Convenience factory: create a Tick from double prices.
	 * Called once when we parse OANDA's JSON response.
	 * Immediately converts to scaled longs — no double used after this.
	 */

	public static Tick of(Instant time, String instrument,
			double bid, double ask) {
		return new Tick(
				time,
				instrument,
				PriceScale.toScaled(bid, instrument),
				PriceScale.toScaled(ask, instrument));
	}
}
