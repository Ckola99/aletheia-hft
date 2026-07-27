package com.aletheia.execution;

import com.aletheia.core.MarketBias;
import com.aletheia.core.PriceScale;

/**
 * Calculates position size based on fixed-percentage risk management.
 *
 * ICT RISK MANAGEMENT:
 * You never risk more than a fixed percentage of your account on any
 * single trade. The position SIZE changes to keep the dollar risk constant.
 *
 * Example:
 * Account balance: $10,000
 * Risk per trade: 1% = $100
 * Entry price: 1.08300
 * Stop loss: 1.08050
 * SL distance: 25 pips
 *
 * Position size = $100 / (25 pips × $10 per pip per standard lot)
 * = $100 / $250
 * = 0.4 lots = 40,000 units
 *
 * If the SL distance is larger, the position is SMALLER (less units).
 * If the SL distance is smaller, the position is LARGER (more units).
 * Either way, you risk exactly $100.
 */
public class RiskManager {

	private final double riskPercentage; // e.g. 0.01 for 1%

	/**
	 * @param riskPercentage the fraction of account to risk per trade
	 *                       (0.01 = 1%, 0.02 = 2%)
	 */
	public RiskManager(double riskPercentage) {
		if (riskPercentage <= 0 || riskPercentage > 0.10) {
			throw new IllegalArgumentException(
					"Risk percentage must be between 0 and 10%, got: "
							+ (riskPercentage * 100) + "%");
		}
		this.riskPercentage = riskPercentage;
	}

	/**
	 * Calculates the number of units to trade.
	 *
	 * @param accountBalance current account balance in account currency (e.g. GBP)
	 * @param entryPrice     the planned entry price (scaled long)
	 * @param stopLoss       the stop loss price (scaled long)
	 * @param instrument     the instrument being traded
	 * @param direction      BULLISH (long) or BEARISH (short)
	 * @return number of units to trade (e.g. 40000 for 0.4 standard lots)
	 */
	public long calculatePositionSize(double accountBalance,
			long entryPrice,
			long stopLoss,
			String instrument,
			MarketBias direction) {

		// Dollar amount we're willing to risk
		double riskAmount = accountBalance * riskPercentage;

		// SL distance in scaled units
		long slDistanceScaled = Math.abs(entryPrice - stopLoss);

		if (slDistanceScaled == 0) {
			return 0; // can't calculate — SL equals entry
		}

		// Convert SL distance to actual price distance (e.g. 0.00250)
		double slDistancePrice = (double) slDistanceScaled / PriceScale.scaleFor(instrument);

		// For forex pairs where USD is the quote currency (EUR/USD, GBP/USD):
		// 1 standard lot = 100,000 units
		// Pip value = lot size × 0.0001 (for 4dp pairs) or 0.00001 (for 5dp)
		// For 1 standard lot of EUR/USD: pip value = $10
		//
		// Position size in units = riskAmount / (slDistance × pipValuePerUnit)
		//
		// For USD quote pairs: pip value per unit = 0.00001 (one unit = $0.00001 per
		// pip)
		// So: units = riskAmount / slDistancePrice
		//
		// This simplification works because for EUR/USD:
		// riskAmount / slDistancePrice = $100 / 0.00250 = 40,000 units
		// P&L at SL: 40,000 × 0.00250 = $100 ✓

		long units = Math.round(riskAmount / slDistancePrice);

		// Clamp to reasonable bounds
		// Min: 1,000 units (0.01 lots / micro lot)
		// Max: 10,000,000 units (100 lots)
		units = Math.max(units, 1_000);
		units = Math.min(units, 10_000_000);

		return units;
	}

	/**
	 * Returns the dollar amount at risk for a given position.
	 * Used for verification and logging.
	 */
	public double calculateRiskAmount(double accountBalance) {
		return accountBalance * riskPercentage;
	}

	/**
	 * Returns the risk percentage (for logging).
	 */
	public double riskPercentage() {
		return riskPercentage;
	}
}
