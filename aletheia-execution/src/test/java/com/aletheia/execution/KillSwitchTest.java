package com.aletheia.execution;

import com.aletheia.core.KillzoneWindow;
import com.aletheia.core.MarketBias;
import com.aletheia.core.SwingPoint;
import com.aletheia.core.SwingType;
import com.aletheia.core.Timeframe;
import com.aletheia.strategy.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class KillSwitchTest {

	private long signalCounter = 0;

	private TradeSignal buildSignal() {
		signalCounter++;
		long entryBase = 108_200L + (signalCounter * 200);
		long sweepBase = 107_950L + (signalCounter * 200);

		return new TradeSignal(
				MarketBias.BULLISH, "EUR_USD",
				new FairValueGap(PdArray.Bias.BULLISH,
						entryBase + 50, entryBase - 50,
						Instant.now(), Timeframe.SECONDS_5),
				entryBase, sweepBase,
				KillzoneWindow.LONDON_OPEN,
				SignalGrade.A,
				new UsdxBias(MarketBias.BEARISH, ConfidenceLevel.HIGH,
						MarketBias.BEARISH, MarketBias.BEARISH, MarketBias.BEARISH),
				new JudasSwingSignal(MarketBias.BULLISH, "EUR_USD",
						new FairValueGap(PdArray.Bias.BULLISH,
								entryBase + 50, entryBase - 50,
								Instant.now(), Timeframe.SECONDS_5),
						new SwingPoint(Instant.now(), "EUR_USD",
								SwingType.LOW, sweepBase + 50,
								Timeframe.SECONDS_5),
						sweepBase, KillzoneWindow.LONDON_OPEN, SignalGrade.A),
				Optional.empty(),
				Instant.now());
	}

	@Test
	void kill_switch_cancels_pending_and_closes_open() {
		RiskManager rm = new RiskManager(0.01);
		OrderManager om = new OrderManager(rm, 5, 2.0, 3.0, 20L);
		OandaOrderExecutor fakeExecutor = new OandaOrderExecutor(
				"fake-key", "fake-account", "http://localhost:9999");
		KillSwitch killSwitch = new KillSwitch(om, fakeExecutor);

		// Create a pending order
		ManagedOrder pending = om.createOrder(buildSignal(), 10_000.0).get();

		// Create and fill another order
		ManagedOrder filled = om.createOrder(buildSignal(), 10_000.0).get();
		filled.onFilled("trade-1", 108_400L, Instant.now());

		assertThat(om.pendingOrders()).hasSize(1);
		assertThat(om.openPositions()).hasSize(1);

		// Activate kill switch
		boolean activated = killSwitch.activate("Test emergency");

		assertThat(activated).isTrue();
		assertThat(killSwitch.isActive()).isTrue();
		assertThat(killSwitch.reason()).isEqualTo("Test emergency");

		// Pending order cancelled
		assertThat(pending.state()).isEqualTo(OrderState.CANCELLED);

		// Open position closed
		assertThat(filled.state()).isEqualTo(OrderState.CLOSED);

		// Nothing open
		assertThat(om.openPositions()).isEmpty();
		assertThat(om.pendingOrders()).isEmpty();
	}

	@Test
	void kill_switch_only_activates_once() {
		RiskManager rm = new RiskManager(0.01);
		OrderManager om = new OrderManager(rm, 5, 2.0, 3.0, 20L);
		OandaOrderExecutor fakeExecutor = new OandaOrderExecutor(
				"fake-key", "fake-account", "http://localhost:9999");
		KillSwitch killSwitch = new KillSwitch(om, fakeExecutor);

		boolean first = killSwitch.activate("First activation");
		boolean second = killSwitch.activate("Second activation");

		assertThat(first).isTrue();
		assertThat(second).isFalse();
		assertThat(killSwitch.reason()).isEqualTo("First activation");
	}

	@Test
	void kill_switch_starts_inactive() {
		RiskManager rm = new RiskManager(0.01);
		OrderManager om = new OrderManager(rm, 5, 2.0, 3.0, 20L);
		OandaOrderExecutor fakeExecutor = new OandaOrderExecutor(
				"fake-key", "fake-account", "http://localhost:9999");
		KillSwitch killSwitch = new KillSwitch(om, fakeExecutor);

		assertThat(killSwitch.isActive()).isFalse();
		assertThat(killSwitch.activatedAt()).isNull();
		assertThat(killSwitch.reason()).isNull();
	}
}
