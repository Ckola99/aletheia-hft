package com.aletheia.core;

/**
 * The directional bias established by MarketStructureAnalyser
 * and UsdxBiasEngine.
 *
 * In ICT, you only trade in the direction of the higher timeframe bias:
 * BULLISH → only look for buy setups (Higher Highs, Higher Lows)
 * BEARISH → only look for sell setups (Lower Highs, Lower Lows)
 * NEUTRAL → structure is mixed — no trade, wait for clarity
 *
 * The invert() method exists because of the USDX correlation:
 * USDX BULLISH → EUR/USD is BEARISH (dollar strengthens, euro weakens)
 * USDX BEARISH → EUR/USD is BULLISH (dollar weakens, euro strengthens)
 *
 * So when UsdxBiasEngine returns BULLISH for the dollar,
 * we call invert() to get the correct bias for EUR/USD.
 */

public enum MarketBias {

	BULLISH,
	BEARISH,
	NEUTRAL;

	/**
	 * Returns the opposite bias.
	 *
	 * Used by UsdxBiasEngine:
	 * usdxBias.invert() → EUR/USD pair bias
	 *
	 * NEUTRAL inverted is still NEUTRAL — no directional flip
	 * makes sense when there is no clear direction.
	 */

	public MarketBias invert() {
		return switch (this) {
			case BULLISH -> BEARISH;
			case BEARISH -> BULLISH;
			case NEUTRAL -> NEUTRAL;
		};
	}

	/**
	 * Returns true if there is a clear direction (not NEUTRAL).
	 * Used by SignalAggregator to gate signal generation.
	 */
	
	public boolean isDirectional() {
		return this != NEUTRAL;
	}
}
