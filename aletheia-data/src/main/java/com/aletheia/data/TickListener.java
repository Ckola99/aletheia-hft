package com.aletheia.data;

import com.aletheia.core.Tick;

/**
 * Callback interface for receiving parsed ticks from the OANDA stream.
 *
 * WHY AN INTERFACE AND NOT A DIRECT METHOD CALL?
 * Different consumers need different things from the same tick:
 * - CandleAggregator updates open candles
 * - TickRepository persists to TimescaleDB
 * - A logger prints tick info for debugging
 * - A test asserts the tick was parsed correctly
 *
 * By using an interface, OandaPricingStream doesn't know or care
 * what happens after it parses a tick. It just calls onTick().
 * This is the Dependency Inversion Principle from SOLID:
 * the high-level module (stream) depends on an abstraction (interface),
 * not on a concrete implementation.
 *
 * In Java, this is also a @FunctionalInterface — it has exactly one
 * abstract method, so you can use a lambda expression:
 * stream.addListener(tick -> System.out.println(tick));
 */

@FunctionalInterface
public interface TickListener {

	/**
	 * Called every time a new price tick is received and parsed.
	 *
	 * IMPORTANT: This method is called from the streaming thread.
	 * Implementations must be fast and non-blocking.
	 * If you need to do slow work (database writes), queue the tick
	 * and process it on a separate thread.
	 *
	 * @param tick the parsed, immutable Tick with scaled long prices
	 */
	
	void onTick(Tick tick);
}
