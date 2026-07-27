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

class OrderManagerTest {

	private final RiskManager riskManager = new RiskManager(0.01);
	private final OrderManager orderManager = new OrderManager(
			riskManager, 4, 2.0, 3.0, 20L);

	private long signalCounter = 0;

	private TradeSignal buildSignal(MarketBias bias) {
		signalCounter++;
		long entryBase = 108_200L + (signalCounter * 100);
		long sweepBase = 107_950L + (signalCounter * 100);

		return new TradeSignal(
				bias, "EUR_USD",
				new FairValueGap(PdArray.Bias.BULLISH,
						entryBase + 50, entryBase - 50,
						Instant.now(), Timeframe.SECONDS_5),
				entryBase, sweepBase,
				KillzoneWindow.LONDON_OPEN,
				SignalGrade.A,
				new UsdxBias(MarketBias.BEARISH, ConfidenceLevel.HIGH,
						MarketBias.BEARISH, MarketBias.BEARISH, MarketBias.BEARISH),
				new JudasSwingSignal(bias, "EUR_USD",
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
	void creates_order_with_correct_sl_tp_and_units() {
		TradeSignal signal = buildSignal(MarketBias.BULLISH);
		Optional<ManagedOrder> order = orderManager.createOrder(signal, 10_000.0);

		assertThat(order).isPresent();

		ManagedOrder o = order.get();
		assertThat(o.direction()).isEqualTo(MarketBias.BULLISH);
		assertThat(o.entryPrice()).isEqualTo(signal.idealEntry());
		assertThat(o.state()).isEqualTo(OrderState.PENDING);
		assertThat(o.totalUnits()).isGreaterThan(0);

		// SL should be below sweep price minus buffer (20)
		assertThat(o.stopLoss()).isEqualTo(signal.sweepPrice() - 20L);

		// TP1 at 2R, TP2 at 3R
		long risk = Math.abs(o.entryPrice() - o.stopLoss());
		assertThat(o.tp1()).isEqualTo(o.entryPrice() + (long) (risk * 2.0));
		assertThat(o.tp2()).isEqualTo(o.entryPrice() + (long) (risk * 3.0));
	}

	@Test
	void enforces_max_open_positions() {
		ManagedOrder order1 = orderManager.createOrder(buildSignal(MarketBias.BULLISH), 10_000.0).get();
		order1.onFilled("trade-1", 108_300L, Instant.now());

		ManagedOrder order2 = orderManager.createOrder(buildSignal(MarketBias.BULLISH), 10_000.0).get();
		order2.onFilled("trade-2", 108_400L, Instant.now());

		ManagedOrder order3 = orderManager.createOrder(buildSignal(MarketBias.BULLISH), 10_000.0).get();
		order3.onFilled("trade-3", 108_500L, Instant.now());

		ManagedOrder order4 = orderManager.createOrder(buildSignal(MarketBias.BULLISH), 10_000.0).get();
		order4.onFilled("trade-4", 108_600L, Instant.now());

		assertThat(orderManager.openPositionCount()).isEqualTo(4);

		// Fifth order should be rejected
		Optional<ManagedOrder> order5 = orderManager.createOrder(
				buildSignal(MarketBias.BULLISH), 10_000.0);
		assertThat(order5).isEmpty();
	}

	@Test
	void partial_close_moves_sl_to_breakeven() {
		ManagedOrder order = orderManager.createOrder(
				buildSignal(MarketBias.BULLISH), 10_000.0).get();

		order.onFilled("trade-1", 108_300L, Instant.now());
		assertThat(order.state()).isEqualTo(OrderState.FILLED);

		order.onPartialClose(order.tp1(), 150.0);
		assertThat(order.state()).isEqualTo(OrderState.PARTIAL);

		// SL moved to breakeven (filled price)
		assertThat(order.currentSl()).isEqualTo(order.filledPrice());

		// 30% remains
		assertThat(order.remainingUnits()).isEqualTo(order.runnerUnits());
	}

	@Test
	void full_close_sets_final_state() {
		ManagedOrder order = orderManager.createOrder(
				buildSignal(MarketBias.BULLISH), 10_000.0).get();

		order.onFilled("trade-1", 108_300L, Instant.now());
		order.onPartialClose(order.tp1(), 150.0);
		order.onFullClose(order.tp2(), Instant.now(), 100.0);

		assertThat(order.state()).isEqualTo(OrderState.CLOSED);
		assertThat(order.remainingUnits()).isEqualTo(0);
		assertThat(order.realisedPnl()).isEqualTo(250.0);
		assertThat(order.isClosed()).isTrue();
	}

	@Test
	void closed_position_frees_slot_for_new_order() {
		// Fill all 4 slots
		ManagedOrder o1 = orderManager.createOrder(buildSignal(MarketBias.BULLISH), 10_000.0).get();
		o1.onFilled("t1", 108_300L, Instant.now());

		ManagedOrder o2 = orderManager.createOrder(buildSignal(MarketBias.BULLISH), 10_000.0).get();
		o2.onFilled("t2", 108_400L, Instant.now());

		ManagedOrder o3 = orderManager.createOrder(buildSignal(MarketBias.BULLISH), 10_000.0).get();
		o3.onFilled("t3", 108_500L, Instant.now());

		ManagedOrder o4 = orderManager.createOrder(buildSignal(MarketBias.BULLISH), 10_000.0).get();
		o4.onFilled("t4", 108_600L, Instant.now());

		// At capacity — 5th rejected
		assertThat(orderManager.createOrder(buildSignal(MarketBias.BULLISH), 10_000.0)).isEmpty();

		// Close one position
		o1.onFullClose(108_500L, Instant.now(), 200.0);

		// Now there's room
		assertThat(orderManager.openPositionCount()).isEqualTo(3);
		assertThat(orderManager.createOrder(buildSignal(MarketBias.BULLISH), 10_000.0)).isPresent();
	}

	@Test
	void rejects_duplicate_signals_at_same_price() {
		TradeSignal signal = buildSignal(MarketBias.BULLISH);

		// First signal accepted
		Optional<ManagedOrder> first = orderManager.createOrder(signal, 10_000.0);
		assertThat(first).isPresent();

		// Same signal object again — same prices — should be rejected
		Optional<ManagedOrder> duplicate = orderManager.createOrder(signal, 10_000.0);
		assertThat(duplicate).isEmpty();
	}
}
