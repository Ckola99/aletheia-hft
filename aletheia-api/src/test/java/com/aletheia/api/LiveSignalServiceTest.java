package com.aletheia.api;

import com.aletheia.calendar.EconomicCalendarService;
import com.aletheia.core.Candle;
import com.aletheia.core.Timeframe;
import com.aletheia.data.CandleAggregator;
import com.aletheia.data.CandleRepository;
import com.aletheia.execution.KillSwitch;
import com.aletheia.execution.ManagedOrder;
import com.aletheia.execution.OandaOrderExecutor;
import com.aletheia.execution.OrderManager;
import com.aletheia.execution.RiskManager;
import com.aletheia.strategy.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Verifies the LiveSignalService plumbing and gating.
 *
 * Strategy correctness itself is covered by the backtest suite — here we only
 * prove the wiring: that evaluation triggers on the right candle events,
 * respects the kill switch and killzone gates, and that buffers accumulate.
 *
 * A synchronous executor (Runnable::run) is injected so evaluations run inline
 * and deterministically, with no background thread.
 */
class LiveSignalServiceTest {

	// A CandleRepository whose warmup queries return nothing (fresh DB).
	// We pass null for the JdbcTemplate and override findRecent so warmup()
	// never touches a real database.
	private final CandleRepository emptyRepo = new CandleRepository(null) {
		@Override
		public List<Candle> findRecent(String instrument, Timeframe tf, int limit) {
			return List.of();
		}
	};

	/** Records executor calls without touching OANDA. */
	static class RecordingExecutor extends OandaOrderExecutor {
		int balanceCalls = 0;
		int placeCalls = 0;

		RecordingExecutor() {
			super("k", "a", "http://localhost:9999");
		}

		@Override
		public Optional<Double> getAccountBalance() {
			balanceCalls++;
			return Optional.of(100_000.0);
		}

		@Override
		public Optional<String> placeLimitOrder(ManagedOrder o) {
			placeCalls++;
			return Optional.of("order-1");
		}
	}

	private LiveSignalService build(KillSwitch killSwitch, RecordingExecutor exec) {
		RiskManager rm = new RiskManager(0.01);
		OrderManager om = new OrderManager(rm, 4, 2.0, 3.0, 20L);
		return new LiveSignalService(
				new CandleAggregator(),
				emptyRepo,
				new SignalAggregator(),
				new UsdxBiasEngine(3),
				new KillzoneService(),
				new EconomicCalendarService(),
				new SwingPointRegistry(3, 50),
				new SmtDivergenceDetector(),
				om,
				exec,
				killSwitch,
				null, // DxyFeedService — not needed for these tests
				new String[] { "EUR_USD", "GBP_USD" },
				"HOUR_1", "MIN_5", "EUR_USD", 100_000,
				new String[] { "EUR_USD:GBP_USD", "GBP_USD:EUR_USD" }, // smt-pairs
				new String[] {}, // smt-partners (none)
				Runnable::run // synchronous — evaluate inline
		);
	}

	private OandaOrderExecutor noopExecutor() {
		return new RecordingExecutor();
	}

	private KillSwitch freshKillSwitch() {
		return new KillSwitch(
				new OrderManager(new RiskManager(0.01), 4, 2.0, 3.0, 20L),
				noopExecutor());
	}

	// 07:00 UTC in July = 03:00 EDT = London Open (active killzone)
	private Instant londonOpen() {
		return ZonedDateTime.of(2026, 7, 22, 7, 0, 0, 0, ZoneId.of("UTC")).toInstant();
	}

	// 00:00 UTC = 20:00 EDT = no killzone
	private Instant deadHours() {
		return ZonedDateTime.of(2026, 7, 22, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant();
	}

	private Candle candle(String instrument, Timeframe tf, Instant time) {
		return new Candle(time, instrument, tf, 108_200L, 108_250L, 108_150L, 108_220L, 50L);
	}

	// ── Trigger gating ──────────────────────────────────────────────

	@Test
	void min5_traded_candle_in_killzone_triggers_evaluation() {
		RecordingExecutor exec = new RecordingExecutor();
		LiveSignalService svc = build(freshKillSwitch(), exec);

		svc.onCandleClosed(candle("EUR_USD", Timeframe.MIN_5, londonOpen()));

		// Evaluation was triggered (LTF close + traded instrument + not killed)
		assertThat(svc.evaluationsTriggered()).isEqualTo(1);
		// But no signal/order — buffers are empty on a fresh service
		assertThat(svc.ordersPlaced()).isZero();
		assertThat(exec.placeCalls).isZero();
	}

	@Test
	void non_ltf_candle_does_not_trigger_evaluation() {
		RecordingExecutor exec = new RecordingExecutor();
		LiveSignalService svc = build(freshKillSwitch(), exec);

		// MIN_1 is not the configured LTF (MIN_5)
		svc.onCandleClosed(candle("EUR_USD", Timeframe.MIN_1, londonOpen()));

		assertThat(svc.evaluationsTriggered()).isZero();
	}

	@Test
	void htf_candle_does_not_trigger_evaluation() {
		RecordingExecutor exec = new RecordingExecutor();
		LiveSignalService svc = build(freshKillSwitch(), exec);

		// HOUR_1 is the HTF, not the trigger timeframe
		svc.onCandleClosed(candle("EUR_USD", Timeframe.HOUR_1, londonOpen()));

		assertThat(svc.evaluationsTriggered()).isZero();
	}

	@Test
	void untraded_instrument_does_not_trigger_evaluation() {
		RecordingExecutor exec = new RecordingExecutor();
		LiveSignalService svc = build(freshKillSwitch(), exec);

		// XAU_USD is not in the configured instruments list
		svc.onCandleClosed(candle("XAU_USD", Timeframe.MIN_5, londonOpen()));

		assertThat(svc.evaluationsTriggered()).isZero();
	}

	// ── Safety gates ────────────────────────────────────────────────

	@Test
	void killswitch_active_blocks_evaluation() {
		RecordingExecutor exec = new RecordingExecutor();
		KillSwitch ks = freshKillSwitch();
		ks.activate("test");

		LiveSignalService svc = build(ks, exec);

		svc.onCandleClosed(candle("EUR_USD", Timeframe.MIN_5, londonOpen()));

		assertThat(svc.evaluationsTriggered()).isZero();
	}

	@Test
	void outside_killzone_does_not_reach_context_evaluation() {
		RecordingExecutor exec = new RecordingExecutor();
		LiveSignalService svc = build(freshKillSwitch(), exec);

		svc.onCandleClosed(candle("EUR_USD", Timeframe.MIN_5, deadHours()));

		// Triggered, but bailed at the killzone gate before evaluating a context
		assertThat(svc.evaluationsTriggered()).isEqualTo(1);
		assertThat(svc.contextsEvaluated()).isZero();
	}

	// ── Buffer management ───────────────────────────────────────────

	@Test
	void buffers_accumulate_on_candle_close() {
		RecordingExecutor exec = new RecordingExecutor();
		LiveSignalService svc = build(freshKillSwitch(), exec);

		Instant t = londonOpen();
		for (int i = 0; i < 5; i++) {
			svc.onCandleClosed(candle("EUR_USD", Timeframe.HOUR_1, t.plusSeconds(i * 3600L)));
		}

		assertThat(svc.bufferSize("EUR_USD", Timeframe.HOUR_1)).isEqualTo(5);
	}

	@Test
	void buffers_are_kept_separate_per_instrument_and_timeframe() {
		RecordingExecutor exec = new RecordingExecutor();
		LiveSignalService svc = build(freshKillSwitch(), exec);

		Instant t = londonOpen();
		svc.onCandleClosed(candle("EUR_USD", Timeframe.HOUR_1, t));
		svc.onCandleClosed(candle("EUR_USD", Timeframe.MIN_5, t));
		svc.onCandleClosed(candle("GBP_USD", Timeframe.HOUR_1, t));

		assertThat(svc.bufferSize("EUR_USD", Timeframe.HOUR_1)).isEqualTo(1);
		assertThat(svc.bufferSize("EUR_USD", Timeframe.MIN_5)).isEqualTo(1);
		assertThat(svc.bufferSize("GBP_USD", Timeframe.HOUR_1)).isEqualTo(1);
		// A timeframe that never received a candle is empty
		assertThat(svc.bufferSize("GBP_USD", Timeframe.MIN_5)).isZero();
	}

	@Test
	void untraded_instrument_candles_are_not_buffered() {
		RecordingExecutor exec = new RecordingExecutor();
		LiveSignalService svc = build(freshKillSwitch(), exec);

		svc.onCandleClosed(candle("XAU_USD", Timeframe.HOUR_1, londonOpen()));

		assertThat(svc.bufferSize("XAU_USD", Timeframe.HOUR_1)).isZero();
	}

	@Test
	void candle_events_counter_tracks_all_closes() {
		RecordingExecutor exec = new RecordingExecutor();
		LiveSignalService svc = build(freshKillSwitch(), exec);

		Instant t = londonOpen();
		svc.onCandleClosed(candle("EUR_USD", Timeframe.MIN_5, t));
		svc.onCandleClosed(candle("EUR_USD", Timeframe.HOUR_1, t));
		svc.onCandleClosed(candle("XAU_USD", Timeframe.MIN_5, t)); // untraded, still counted

		assertThat(svc.candleEvents()).isEqualTo(3);
	}
}
