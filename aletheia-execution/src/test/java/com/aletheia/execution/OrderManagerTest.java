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
			riskManager, 2, 2.0, 3.0, 20L);

	private TradeSignal buildSignal(MarketBias bias) {
		return new TradeSignal(
				bias, "EUR_USD",
				new FairValueGap(PdArray.Bias.BULLISH,
						108_350L, 108_250L, Instant.now(), Timeframe.SECONDS_5),
				108_300L, 108_050L,
				KillzoneWindow.LONDON_OPEN,
				SignalGrade.A,
				new UsdxBias(MarketBias.BEARISH, ConfidenceLevel.HIGH,
						MarketBias.BEARISH, MarketBias.BEARISH, MarketBias.BEARISH),
				new JudasSwingSignal(bias, "EUR_USD",
						new FairValueGap(PdArray.Bias.BULLISH,
								108_350L, 108_250L, Instant.now(), Timeframe.SECONDS_5),
						new SwingPoint(Instant.now(), "EUR_USD",
								SwingType.LOW, 108_100L, Timeframe.SECONDS_5),
						108_050L, KillzoneWindow.LONDON_OPEN, SignalGrade.A),
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
		assertThat(o.entryPrice()).isEqualTo(108_300L);
		assertThat(o.state()).isEqualTo(OrderState.PENDING);
		assertThat(o.totalUnits()).isGreaterThan(0);

		// SL should be below sweep price (108050) minus buffer (20)
		assertThat(o.stopLoss()).isEqualTo(108_030L);

		// TP1 at 2R, TP2 at 3R
		long risk = Math.abs(o.entryPrice() - o.stopLoss());
		assertThat(o.tp1()).isEqualTo(o.entryPrice() + (long) (risk * 2.0));
		assertThat(o.tp2()).isEqualTo(o.entryPrice() + (long) (risk * 3.0));
	}

	@Test
	void enforces_max_open_positions() {
		TradeSignal signal = buildSignal(MarketBias.BULLISH);

		// Create and fill 2 orders (max)
		ManagedOrder order1 = orderManager.createOrder(signal, 10_000.0).get();
		order1.onFilled("trade-1", 108_300L, Instant.now());

		ManagedOrder order2 = orderManager.createOrder(signal, 10_000.0).get();
		order2.onFilled("trade-2", 108_300L, Instant.now());

		assertThat(orderManager.openPositionCount()).isEqualTo(2);

		// Third order should be rejected
		Optional<ManagedOrder> order3 = orderManager.createOrder(signal, 10_000.0);
		assertThat(order3).isEmpty();
	}

	@Test
	void partial_close_moves_sl_to_breakeven() {
		TradeSignal signal = buildSignal(MarketBias.BULLISH);
		ManagedOrder order = orderManager.createOrder(signal, 10_000.0).get();

		// Fill the order
		order.onFilled("trade-1", 108_300L, Instant.now());
		assertThat(order.state()).isEqualTo(OrderState.FILLED);

		// TP1 hit — partial close
		order.onPartialClose(order.tp1(), 150.0);
		assertThat(order.state()).isEqualTo(OrderState.PARTIAL);

		// SL should now be at breakeven (entry price)
		assertThat(order.currentSl()).isEqualTo(order.filledPrice());

		// 30% of position remains
		assertThat(order.remainingUnits()).isEqualTo(order.runnerUnits());
	}

	@Test
	void full_close_sets_final_state() {
		TradeSignal signal = buildSignal(MarketBias.BULLISH);
		ManagedOrder order = orderManager.createOrder(signal, 10_000.0).get();

		order.onFilled("trade-1", 108_300L, Instant.now());
		order.onPartialClose(order.tp1(), 150.0);
		order.onFullClose(order.tp2(), Instant.now(), 100.0);

		assertThat(order.state()).isEqualTo(OrderState.CLOSED);
		assertThat(order.remainingUnits()).isEqualTo(0);
		assertThat(order.realisedPnl()).isEqualTo(250.0); // 150 + 100
		assertThat(order.isClosed()).isTrue();
	}

	@Test
	void closed_position_frees_slot_for_new_order() {
		TradeSignal signal = buildSignal(MarketBias.BULLISH);

		// Fill 2 positions
		ManagedOrder o1 = orderManager.createOrder(signal, 10_000.0).get();
		o1.onFilled("t1", 108_300L, Instant.now());
		ManagedOrder o2 = orderManager.createOrder(signal, 10_000.0).get();
		o2.onFilled("t2", 108_300L, Instant.now());

		// At capacity
		assertThat(orderManager.createOrder(signal, 10_000.0)).isEmpty();

		// Close one position
		o1.onFullClose(108_500L, Instant.now(), 200.0);

		// Now there's room for a new order
		assertThat(orderManager.openPositionCount()).isEqualTo(1);
		assertThat(orderManager.createOrder(signal, 10_000.0)).isPresent();
	}
}
