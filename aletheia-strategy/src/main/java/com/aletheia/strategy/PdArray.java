package com.aletheia.strategy;

import com.aletheia.core.Timeframe;
import java.time.Instant;

/**
 * A PD Array (Premium/Discount Array) — an institutional price zone
 * that acts as a magnet for price.
 *
 * In ICT theory, price does not move randomly. It is drawn toward
 * specific zones where institutional orders are resting. These zones
 * are collectively called PD Arrays. Each type has a different
 * formation pattern but they all share the same basic information:
 * - Where is the zone? (upper and lower boundary)
 * - When did it form? (timestamp)
 * - Is it a buying zone or selling zone? (bias)
 * - What timeframe was it detected on? (timeframe)
 *
 * WHY SEALED INTERFACE?
 * 'sealed' means: only the classes listed in 'permits' can implement
 * this interface. The compiler knows ALL possible types at compile time.
 * This lets you use exhaustive pattern matching:
 *
 * switch (pdArray) {
 * case FairValueGap fvg -> handleFvg(fvg);
 * case OrderBlock ob -> handleOb(ob);
 * // compiler enforces you handle ALL types — nothing missed
 * }
 *
 * If someone later adds a RejectionBlock, every switch statement
 * in the codebase that doesn't handle it will fail to compile.
 * That's safety — the compiler catches the oversight, not a runtime bug.
 */
public sealed interface PdArray permits FairValueGap, OrderBlock {

	/** The upper boundary of the zone (scaled long price). */
	long upper();

	/** The lower boundary of the zone (scaled long price). */
	long lower();

	/** When this PD Array formed. */
	Instant time();

	/** Which timeframe it was detected on. */
	Timeframe timeframe();

	/** Whether this is a bullish (discount) or bearish (premium) zone. */
	Bias bias();

	/**
	 * The midpoint of the zone.
	 * Used by RiskManager for take-profit targeting — price often
	 * reacts at the 50% level of a PD Array.
	 */
	default long midpoint() {
		return (upper() + lower()) / 2;
	}

	/**
	 * Returns true if the given price is inside this PD Array zone.
	 * Used by the strategy engine to check "is price currently at a PD Array?"
	 */
	default boolean contains(long price) {
		return price >= lower() && price <= upper();
	}

	/** Directional bias of a PD Array. */
	enum Bias {
		/** Discount zone — look for buys. Price should bounce UP from here. */
		BULLISH,
		/** Premium zone — look for sells. Price should reject DOWN from here. */
		BEARISH
	}
}
