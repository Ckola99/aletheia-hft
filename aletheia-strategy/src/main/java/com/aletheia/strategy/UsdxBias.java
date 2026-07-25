package com.aletheia.strategy;

import com.aletheia.core.MarketBias;

/**
 * The result of the USDX bias analysis.
 *
 * Contains the directional bias of the US Dollar and the confidence
 * level based on how many timeframes agree.
 *
 * @param direction   BULLISH (dollar strengthening), BEARISH (dollar
 *                    weakening),
 *                    or NEUTRAL (no clear direction)
 * @param confidence  HIGH (3 agree), MEDIUM (2 agree), LOW (none agree)
 * @param monthlyBias what the monthly structure says
 * @param weeklyBias  what the weekly structure says
 * @param dailyBias   what the daily structure says
 */
public record UsdxBias(
		MarketBias direction,
		ConfidenceLevel confidence,
		MarketBias monthlyBias,
		MarketBias weeklyBias,
		MarketBias dailyBias) {

	/**
	 * Returns the bias for a specific instrument based on USDX direction.
	 *
	 * EUR/USD, GBP/USD, and XAU/USD move INVERSELY to the dollar:
	 * USDX BULLISH → EUR/USD is BEARISH (dollar up, euro down)
	 * USDX BEARISH → EUR/USD is BULLISH (dollar down, euro up)
	 *
	 * US30 and NAS100 also tend to move inversely to the dollar
	 * but the correlation is weaker — we treat them the same way.
	 */
	public MarketBias biasForPair(String instrument) {
		if (direction == MarketBias.NEUTRAL) {
			return MarketBias.NEUTRAL;
		}
		// All our traded instruments are USD-inverse
		return direction.invert();
	}

	/**
	 * Returns true if the bias is strong enough to consider trading.
	 */
	public boolean isTradeable() {
		return direction.isDirectional() && confidence.isTradeable();
	}
}
