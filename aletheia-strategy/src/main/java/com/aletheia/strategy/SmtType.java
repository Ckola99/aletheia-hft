package com.aletheia.strategy;

/**
 * The direction of an SMT Divergence signal.
 *
 * BULLISH: instrumentB made a lower low, instrumentA held higher.
 * → Trade instrumentA LONG (the defended pair).
 *
 * BEARISH: instrumentB made a higher high, instrumentA held lower.
 * → Trade instrumentA SHORT (the distributed pair).
 */
public enum SmtType {
	BULLISH,
	BEARISH
}
