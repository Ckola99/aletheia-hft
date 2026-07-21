package com.aletheia.core;

/**
 * The market impact level of an economic news event.
 *
 * This classification comes from Forex Factory's colour coding:
 * RED = HIGH — major market-moving events
 * ORANGE = MEDIUM — moderate impact
 * YELLOW = LOW — minor, usually ignored
 *
 * HIGH impact events in Aletheia trigger a trading blackout:
 * ±15 minutes around NFP, FOMC, CPI, GDP, Central Bank rate decisions.
 *
 * WHY block trading around news?
 * High-impact events cause:
 * 1. Extreme spread widening (your entry costs more)
 * 2. Stop hunting (price spikes past your SL then reverses)
 * 3. Slippage (your order fills at a worse price than requested)
 * These are not IPDA (Interbank Price Delivery Algorithm) moves —
 * they are unpredictable volatility. ICT explicitly says: do not trade news.
 */

public enum ImpactLevel {

	HIGH, // NFP, FOMC, CPI, GDP, Central Bank rate decisions → BLOCK trading
	MEDIUM, // Trade Balance, Retail Sales → warn but allow
	LOW // Minor indicators → no action
}
