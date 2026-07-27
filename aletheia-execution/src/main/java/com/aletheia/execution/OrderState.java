package com.aletheia.execution;

/**
 * The lifecycle state of a managed order.
 *
 * PENDING → order sent to OANDA, waiting for fill
 * FILLED → order filled, position is open
 * PARTIAL → TP1 hit, 70% closed, 30% runner still open, SL moved to breakeven
 * CLOSED → fully closed (TP2 hit, SL hit, or manually closed)
 * CANCELLED → order was cancelled before filling
 * FAILED → order rejected by OANDA (insufficient margin, invalid price, etc.)
 */
public enum OrderState {
	PENDING,
	FILLED,
	PARTIAL,
	CLOSED,
	CANCELLED,
	FAILED
}
