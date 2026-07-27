package com.aletheia.execution;

import com.aletheia.core.MarketBias;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class RiskManagerTest {

	private final RiskManager rm = new RiskManager(0.01); // 1% risk

	@Test
	void calculates_position_size_for_eurusd() {
		// Account: $10,000, Risk 1% = $100
		// Entry: 1.08300, SL: 1.08050 → 25 pips SL distance
		// Position = $100 / 0.00250 = 40,000 units

		long units = rm.calculatePositionSize(
				10_000.0,
				108_300L, // entry
				108_050L, // SL
				"EUR_USD",
				MarketBias.BULLISH);

		assertThat(units).isEqualTo(40_000L);
	}

	@Test
	void smaller_sl_means_larger_position() {
		// Tight SL: 10 pips → more units
		long tightSl = rm.calculatePositionSize(
				10_000.0, 108_300L, 108_200L, "EUR_USD", MarketBias.BULLISH);

		// Wide SL: 50 pips → fewer units
		long wideSl = rm.calculatePositionSize(
				10_000.0, 108_300L, 107_800L, "EUR_USD", MarketBias.BULLISH);

		assertThat(tightSl).isGreaterThan(wideSl);
	}

	@Test
	void position_size_scales_with_account_balance() {
		long smallAccount = rm.calculatePositionSize(
				5_000.0, 108_300L, 108_050L, "EUR_USD", MarketBias.BULLISH);

		long largeAccount = rm.calculatePositionSize(
				50_000.0, 108_300L, 108_050L, "EUR_USD", MarketBias.BULLISH);

		// 10× account = 10× position
		assertThat(largeAccount).isEqualTo(smallAccount * 10);
	}

	@Test
	void rejects_risk_percentage_above_10_percent() {
		assertThatThrownBy(() -> new RiskManager(0.15))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejects_zero_risk_percentage() {
		assertThatThrownBy(() -> new RiskManager(0))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void handles_short_trades_same_as_long() {
		long longUnits = rm.calculatePositionSize(
				10_000.0, 108_300L, 108_050L, "EUR_USD", MarketBias.BULLISH);

		long shortUnits = rm.calculatePositionSize(
				10_000.0, 108_300L, 108_550L, "EUR_USD", MarketBias.BEARISH);

		// Same SL distance (250 scaled units) = same position size
		assertThat(shortUnits).isEqualTo(longUnits);
	}

	@Test
	void returns_zero_when_sl_equals_entry() {
		long units = rm.calculatePositionSize(
				10_000.0, 108_300L, 108_300L, "EUR_USD", MarketBias.BULLISH);

		assertThat(units).isEqualTo(0);
	}
}
