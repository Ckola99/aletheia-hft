package com.aletheia.strategy;

/**
 * Defines a pair of instruments that can exhibit SMT Divergence.
 *
 * Not any two instruments can be compared — they must be correlated.
 * EUR/USD and GBP/USD are valid because both are USD pairs.
 * EUR/USD and XAU/USD could also work (both inverse-dollar).
 * EUR/USD and US30 would NOT work (different asset classes, weak correlation).
 *
 * instrumentA is the "primary" — the one we trade when SMT fires.
 * instrumentB is the "confirming" — the one that makes the trap move.
 *
 * For EUR/GBP SMT:
 * instrumentA = "EUR_USD" — this is what we trade
 * instrumentB = "GBP_USD" — this is the one that traps with a false extreme
 */
public record SmtPair(String instrumentA, String instrumentB) {

	/** The standard EUR/GBP pair used in Aletheia. */
	public static final SmtPair EUR_GBP = new SmtPair("EUR_USD", "GBP_USD");

	/** Optional: index pair for future use. */
	public static final SmtPair NAS_DOW = new SmtPair("NAS100_USD", "US30_USD");
}
