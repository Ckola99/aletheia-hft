package com.aletheia.strategy;

/**
 * The quality grade of a trade signal.
 *
 * A_PLUS: Judas Swing confirmed by SMT Divergence.
 * Highest confidence — institutional fingerprint on two pairs.
 *
 * A: Judas Swing without SMT confirmation.
 * Good setup but missing the cross-pair validation.
 *
 * The grade is stored with every trade in the journal so we can
 * compare A+ vs A performance in backtest reports.
 */
public enum SignalGrade {
	A_PLUS,
	A
}
