package com.aletheia.execution;

import com.aletheia.core.KillzoneWindow;
import com.aletheia.core.MarketBias;
import com.aletheia.core.SwingPoint;
import com.aletheia.core.SwingType;
import com.aletheia.core.Timeframe;
import com.aletheia.strategy.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class OrderExpiryServiceTest {

	private final RiskManager rm = new RiskManager(0.01);
	private final OrderManager om = new OrderManager(rm, 5, 2.0, 3.0, 20L);
	private final OandaOrderExecutor fakeExecutor = new OandaOrderExecutor(
			"fake", "fake", "http://localhost:9999");
	private final KillzoneService killzoneService = new KillzoneService();
	private final OrderExpiryService expiryService = new OrderExpiryService(
			om, fakeExecutor, killzoneService);

	private TradeSignal buildSignal() {
		return new TradeSignal(
				MarketBias.BULLISH, "EUR_USD",
				new FairValueGap(PdArray.Bias.BULLISH,
						108_350L, 108_250L, Instant.now(), Timeframe.SECONDS_5),
				108_300L, 108_050L,
				KillzoneWindow.LONDON_OPEN,
				SignalGrade.A,
				new UsdxBias(MarketBias.BEARISH, ConfidenceLevel.HIGH,
						MarketBias.BEARISH, MarketBias.BEARISH, MarketBias.BEARISH),
				new JudasSwingSignal(MarketBias.BULLISH, "EUR_USD",
						new FairValueGap(PdArray.Bias.BULLISH,
								108_350L, 108_250L, Instant.now(), Timeframe.SECONDS_5),
						new SwingPoint(Instant.now(), "EUR_USD",
								SwingType.LOW, 108_100L, Timeframe.SECONDS_5),
						108_050L, KillzoneWindow.LONDON_OPEN, SignalGrade.A),
				Optional.empty(),
				Instant.now());
	}

	@Test
	void expires_pending_order_when_killzone_ends() {
		// Create a pending order during London Open
		ManagedOrder order = om.createOrder(buildSignal(), 10_000.0).get();
		assertThat(order.state()).isEqualTo(OrderState.PENDING);
		assertThat(order.killzone()).isEqualTo(KillzoneWindow.LONDON_OPEN);

		// Simulate time moving to outside all killzones
		// 15:00 EST = 19:00 UTC (no killzone active)
		// In July, EST = EDT = UTC-4, so 19:00 UTC = 15:00 EDT = NONE
		ZonedDateTime outsideKz = ZonedDateTime.of(
				2026, 7, 22, 19, 0, 0, 0, ZoneId.of("UTC"));

		int cancelled = expiryService.checkAndExpire(outsideKz.toInstant());

		assertThat(cancelled).isEqualTo(1);
		assertThat(order.state()).isEqualTo(OrderState.CANCELLED);
	}

	@Test
	void does_not_expire_order_during_same_killzone() {
		ManagedOrder order = om.createOrder(buildSignal(), 10_000.0).get();

		// Still during London Open: 03:00 EDT = 07:00 UTC
		ZonedDateTime duringLondon = ZonedDateTime.of(
				2026, 7, 22, 7, 0, 0, 0, ZoneId.of("UTC"));

		int cancelled = expiryService.checkAndExpire(duringLondon.toInstant());

		assertThat(cancelled).isEqualTo(0);
		assertThat(order.state()).isEqualTo(OrderState.PENDING);
	}

	@Test
	void does_not_expire_filled_orders() {
		ManagedOrder order = om.createOrder(buildSignal(), 10_000.0).get();
		order.onFilled("trade-1", 108_300L, Instant.now());

		// Outside killzone
		ZonedDateTime outsideKz = ZonedDateTime.of(
				2026, 7, 22, 19, 0, 0, 0, ZoneId.of("UTC"));

		int cancelled = expiryService.checkAndExpire(outsideKz.toInstant());

		// Filled orders are NOT pending — they should not be cancelled
		assertThat(cancelled).isEqualTo(0);
		assertThat(order.state()).isEqualTo(OrderState.FILLED);
	}

	@Test
	void expires_order_when_killzone_changes() {
		ManagedOrder order = om.createOrder(buildSignal(), 10_000.0).get();
		// Order was placed during LONDON_OPEN

		// Now we're in NY Open: 08:00 EDT = 12:00 UTC
		ZonedDateTime nyOpen = ZonedDateTime.of(
				2026, 7, 22, 12, 0, 0, 0, ZoneId.of("UTC"));

		int cancelled = expiryService.checkAndExpire(nyOpen.toInstant());

		// Different killzone → order should be cancelled
		assertThat(cancelled).isEqualTo(1);
		assertThat(order.state()).isEqualTo(OrderState.CANCELLED);
	}
}
