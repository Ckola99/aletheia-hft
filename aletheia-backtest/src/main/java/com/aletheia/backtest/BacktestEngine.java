package com.aletheia.backtest;

import com.aletheia.calendar.EconomicCalendarService;
import com.aletheia.core.Candle;
import com.aletheia.core.KillzoneWindow;
import com.aletheia.core.MarketBias;
import com.aletheia.core.Timeframe;
import com.aletheia.strategy.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Event-driven backtesting engine with spread simulation and SMT support.
 */
public class BacktestEngine {

	private final SignalAggregator aggregator;
	private final KillzoneService killzoneService;
	private final EconomicCalendarService calendarService;
	private final UsdxBiasEngine usdxBiasEngine;

	private final double riskRewardRatio;
	private final long slBufferScaled;
	private final int maxOpenTrades;
	private final long spreadScaled;

	// Trade-management parameters — mirror live ManagedOrder rules.
	private static final double TP1_RISK_MULTIPLE = 2.0; // TP1 at 2R
	private static final double TP2_RISK_MULTIPLE = 3.0; // TP2 at 3R
	private static final double TP1_CLOSE_FRACTION = 0.70; // close 70% at TP1

	/**
	 * @param riskRewardRatio target R:R (e.g. 3.0) — retained for the banner;
	 *                        actual targets now use TP1/TP2 multiples above
	 * @param slBufferScaled  extra buffer beyond sweep price for SL
	 * @param maxOpenTrades   max simultaneous positions
	 * @param spreadScaled    simulated spread in scaled units
	 *                        EUR/USD typical spread = 1.5 pips = 15 scaled units
	 *                        Use 0 in unit tests to test mechanics without spread
	 */
	public BacktestEngine(double riskRewardRatio, long slBufferScaled,
			int maxOpenTrades, long spreadScaled) {
		this.aggregator = new SignalAggregator(
				new FairValueGapDetector(),
				new OrderBlockDetector(14, 2.0),
				new JudasSwingDetector(3, 20, 2.5));
		this.killzoneService = new KillzoneService();
		this.calendarService = new EconomicCalendarService();
		this.usdxBiasEngine = new UsdxBiasEngine(3);
		this.riskRewardRatio = riskRewardRatio;
		this.slBufferScaled = slBufferScaled;
		this.maxOpenTrades = maxOpenTrades;
		this.spreadScaled = spreadScaled;
	}

	public EconomicCalendarService calendarService() {
		return calendarService;
	}

	/**
	 * Run with PRECOMPUTED USDX bias, no SMT. Used in unit tests.
	 */
	public BacktestResult run(String instrument,
			List<Candle> htfCandles,
			List<Candle> ltfCandles,
			UsdxBias usdxBias) {
		return runInternal(instrument, htfCandles, ltfCandles,
				null, null, null, usdxBias, Optional.empty());
	}

	/**
	 * Run with DYNAMIC USDX bias, no SMT.
	 */
	public BacktestResult run(String instrument,
			List<Candle> htfCandles,
			List<Candle> ltfCandles,
			List<Candle> usdxMonthly,
			List<Candle> usdxWeekly,
			List<Candle> usdxDaily) {
		return runInternal(instrument, htfCandles, ltfCandles,
				usdxMonthly, usdxWeekly, usdxDaily, null, Optional.empty());
	}

	/**
	 * Run with DYNAMIC USDX bias AND SMT divergence signal.
	 */
	public BacktestResult run(String instrument,
			List<Candle> htfCandles,
			List<Candle> ltfCandles,
			List<Candle> usdxMonthly,
			List<Candle> usdxWeekly,
			List<Candle> usdxDaily,
			Optional<SmtDivergenceSignal> smtSignal) {
		return runInternal(instrument, htfCandles, ltfCandles,
				usdxMonthly, usdxWeekly, usdxDaily, null, smtSignal);
	}

	/**
	 * Run with DYNAMIC USDX bias AND per-candle SMT divergence.
	 *
	 * Unlike the older overload that takes a single precomputed
	 * Optional<SmtDivergenceSignal> (frozen for the whole run), this version
	 * receives the raw MIN_15 candle series for this instrument and its SMT
	 * partner, and computes divergence fresh on every candle inside the loop —
	 * exactly mirroring LiveSignalService.detectSmt(), with no look-ahead.
	 *
	 * @param smtInstrument   the correlated partner (e.g. "GBP_USD"), or null
	 * @param selfMin15       this instrument's full MIN_15 series
	 * @param smtPartnerMin15 the partner's full MIN_15 series
	 */
	public BacktestResult runWithLiveSmt(String instrument,
			List<Candle> htfCandles,
			List<Candle> ltfCandles,
			List<Candle> usdxMonthly,
			List<Candle> usdxWeekly,
			List<Candle> usdxDaily,
			String smtInstrument,
			List<Candle> selfMin15,
			List<Candle> smtPartnerMin15) {
		return runInternalLiveSmt(instrument, htfCandles, ltfCandles,
				usdxMonthly, usdxWeekly, usdxDaily,
				smtInstrument, selfMin15, smtPartnerMin15);
	}

	/**
	 * Internal backtest loop with PER-CANDLE SMT divergence.
	 *
	 * Identical to runInternal(), except SMT is computed fresh each candle from
	 * the visible (no-look-ahead) window, mirroring LiveSignalService.detectSmt().
	 */
	private BacktestResult runInternalLiveSmt(String instrument,
			List<Candle> htfCandles,
			List<Candle> ltfCandles,
			List<Candle> usdxMonthly,
			List<Candle> usdxWeekly,
			List<Candle> usdxDaily,
			String smtInstrument,
			List<Candle> selfMin15,
			List<Candle> smtPartnerMin15) {

		// SMT machinery — one registry/detector reused across the loop,
		// exactly like LiveSignalService's shared smtRegistry.
		boolean smtEnabled = (smtInstrument != null
				&& selfMin15 != null && smtPartnerMin15 != null);
		SwingPointRegistry smtRegistry = new SwingPointRegistry(3, 50);
		SmtDivergenceDetector smtDetector = new SmtDivergenceDetector();
		final Timeframe SMT_TF = Timeframe.MIN_15;
		final int SMT_WINDOW = 300; // mirror LiveSignalService MIN_15 buffer limit

		List<SimulatedTrade> allTrades = new ArrayList<>();
		List<SimulatedTrade> openTrades = new ArrayList<>();
		int signalsGenerated = 0;
		int signalsRejected = 0;

		System.out.println("=====================================================");
		System.out.println("  BACKTEST STARTING (per-candle SMT)");
		System.out.println("  Instrument:    " + instrument);
		System.out.println("  HTF candles:   " + htfCandles.size());
		System.out.println("  LTF candles:   " + ltfCandles.size());
		System.out.println("  USDX bias:     DYNAMIC");
		System.out.println("  TP1/TP2:       " + TP1_RISK_MULTIPLE + "R / "
				+ TP2_RISK_MULTIPLE + "R (" + (int) (TP1_CLOSE_FRACTION * 100)
				+ "% at TP1)");
		System.out.println("  Spread:        " + spreadScaled + " scaled units ("
				+ (spreadScaled / 10.0) + " pips)");
		System.out.println("  SMT:           " + (smtEnabled ? smtInstrument : "disabled"));
		System.out.println("  Max positions: " + maxOpenTrades);
		System.out.println("=====================================================");

		int warmupPeriod = 30;
		int cooldownBars = 0;
		int cooldownPeriod = 24;

		for (int i = warmupPeriod; i < ltfCandles.size(); i++) {
			Candle currentCandle = ltfCandles.get(i);
			Instant now = currentCandle.time();

			// Check open trades against this candle
			List<SimulatedTrade> toRemove = new ArrayList<>();
			for (SimulatedTrade trade : openTrades) {
				if (trade.checkExit(currentCandle.high(), currentCandle.low(),
						currentCandle.time())) {
					toRemove.add(trade);
				}
			}
			openTrades.removeAll(toRemove);

			if (openTrades.size() >= maxOpenTrades)
				continue;

			if (cooldownBars > 0) {
				cooldownBars--;
				continue;
			}

			List<Candle> visibleHtf = htfCandles.stream()
					.filter(c -> !c.time().isAfter(now))
					.toList();
			if (visibleHtf.size() < 10)
				continue;

			int ltfStart = Math.max(0, i - 50);
			List<Candle> visibleLtf = ltfCandles.subList(ltfStart, i + 1);

			// Dynamic USDX bias (no look-ahead)
			List<Candle> visibleMonthly = usdxMonthly.stream()
					.filter(c -> !c.time().isAfter(now)).toList();
			List<Candle> visibleWeekly = usdxWeekly.stream()
					.filter(c -> !c.time().isAfter(now)).toList();
			List<Candle> visibleDaily = usdxDaily.stream()
					.filter(c -> !c.time().isAfter(now)).toList();
			UsdxBias currentBias = usdxBiasEngine.compute(
					visibleMonthly, visibleWeekly, visibleDaily);

			KillzoneWindow killzone = killzoneService.classify(now);
			boolean newsBlackout = calendarService.isNewsBlackout(now, instrument);

			// ── SMT computed PER CANDLE — mirrors LiveSignalService.detectSmt()
			Optional<SmtDivergenceSignal> smt = Optional.empty();
			if (smtEnabled) {
				List<Candle> selfVisible = lastVisible(selfMin15, now, SMT_WINDOW);
				List<Candle> partnerVisible = lastVisible(smtPartnerMin15, now, SMT_WINDOW);

				if (!selfVisible.isEmpty() && !partnerVisible.isEmpty()) {
					smtRegistry.update(instrument, SMT_TF, selfVisible);
					smtRegistry.update(smtInstrument, SMT_TF, partnerVisible);
					smt = smtDetector.detect(
							new SmtPair(instrument, smtInstrument),
							SMT_TF, smtRegistry, killzone);
				}
			}

			MarketContext ctx = new MarketContext(
					now, instrument, killzone, currentBias,
					visibleHtf, visibleLtf, newsBlackout, smt);

			Optional<TradeSignal> signal = aggregator.evaluate(ctx);

			if (signal.isEmpty()) {
				signalsRejected++;
				continue;
			}

			TradeSignal s = signal.get();
			signalsGenerated++;

			long sl, tp1, tp2;
			if (s.bias() == MarketBias.BULLISH) {
				long effectiveEntry = s.idealEntry() + (spreadScaled / 2);
				sl = s.sweepPrice() - slBufferScaled;
				long risk = effectiveEntry - sl;
				tp1 = effectiveEntry + (long) (risk * TP1_RISK_MULTIPLE);
				tp2 = effectiveEntry + (long) (risk * TP2_RISK_MULTIPLE);
			} else {
				long effectiveEntry = s.idealEntry() - (spreadScaled / 2);
				sl = s.sweepPrice() + slBufferScaled;
				long risk = sl - effectiveEntry;
				tp1 = effectiveEntry - (long) (risk * TP1_RISK_MULTIPLE);
				tp2 = effectiveEntry - (long) (risk * TP2_RISK_MULTIPLE);
			}

			SimulatedTrade trade = new SimulatedTrade(s, sl, tp1, tp2, TP1_CLOSE_FRACTION);
			openTrades.add(trade);
			allTrades.add(trade);

			cooldownBars = cooldownPeriod;
		}

		if (!ltfCandles.isEmpty()) {
			Candle lastCandle = ltfCandles.get(ltfCandles.size() - 1);
			for (SimulatedTrade trade : openTrades) {
				trade.checkExit(lastCandle.high(), lastCandle.low(), lastCandle.time());
			}
		}

		System.out.println("  Signals generated: " + signalsGenerated);
		System.out.println("  Contexts rejected: " + signalsRejected);
		System.out.println("  Total trades:      " + allTrades.size());

		return new BacktestResult(allTrades, signalsGenerated, signalsRejected);
	}

	private BacktestResult runInternal(String instrument,
			List<Candle> htfCandles,
			List<Candle> ltfCandles,
			List<Candle> usdxMonthly,
			List<Candle> usdxWeekly,
			List<Candle> usdxDaily,
			UsdxBias fixedBias,
			Optional<SmtDivergenceSignal> smtSignal) {

		boolean dynamicBias = (fixedBias == null);
		Optional<SmtDivergenceSignal> smt = (smtSignal != null) ? smtSignal : Optional.empty();

		List<SimulatedTrade> allTrades = new ArrayList<>();
		List<SimulatedTrade> openTrades = new ArrayList<>();
		List<PendingSignal> pendingSignals = new ArrayList<>();
		int signalsGenerated = 0;
		int signalsRejected = 0;
		int signalsExpiredUnfilled = 0;

		System.out.println("=====================================================");
		System.out.println("  BACKTEST STARTING");
		System.out.println("  Instrument:    " + instrument);
		System.out.println("  HTF candles:   " + htfCandles.size());
		System.out.println("  LTF candles:   " + ltfCandles.size());
		System.out.println("  USDX bias:     " + (dynamicBias ? "DYNAMIC" : "FIXED " + fixedBias.direction()));
		System.out.println("  TP1/TP2:       " + TP1_RISK_MULTIPLE + "R / "
				+ TP2_RISK_MULTIPLE + "R (" + (int) (TP1_CLOSE_FRACTION * 100)
				+ "% at TP1)");
		System.out.println("  Spread:        " + spreadScaled + " scaled units ("
				+ (spreadScaled / 10.0) + " pips)");
		System.out.println("  SMT signal:    " + (smt.isPresent() ? smt.get().type() : "none"));
		System.out.println("  Max positions: " + maxOpenTrades);
		System.out.println("=====================================================");

		int warmupPeriod = 30;
		int cooldownBars = 0; // bars remaining before next trade allowed
		int cooldownPeriod = 24; // wait N LTF candles after opening a trade

		for (int i = warmupPeriod; i < ltfCandles.size(); i++) {
			Candle currentCandle = ltfCandles.get(i);

			// Check open trades against this candle
			List<SimulatedTrade> toRemove = new ArrayList<>();
			for (SimulatedTrade trade : openTrades) {
				if (trade.checkExit(currentCandle.high(), currentCandle.low(),
						currentCandle.time())) {
					toRemove.add(trade);
				}
			}
			openTrades.removeAll(toRemove);

			// Check pending limit orders for a fill or expiry, exactly like
			// OrderExpiryService does live: a resting order only stays valid
			// for the killzone it was raised in (plus a 3-hour safety cutoff).
			// This runs regardless of maxOpenTrades/cooldown, same as live
			// where expiry isn't gated on how many positions are open.
			List<PendingSignal> stillPending = new ArrayList<>();
			for (PendingSignal p : pendingSignals) {
				KillzoneWindow nowZone = killzoneService.classify(currentCandle.time());
				long ageMs = currentCandle.time().toEpochMilli() - p.generatedAt().toEpochMilli();
				boolean expired = isPendingExpired(nowZone, p.killzone(), ageMs);
				boolean touched = isLimitTouched(p.signal().idealEntry(),
						currentCandle.low(), currentCandle.high());

				if (touched && openTrades.size() < maxOpenTrades) {
					SimulatedTrade trade = new SimulatedTrade(
							p.signal(), p.effectiveEntry(), p.sl(), p.tp());
					openTrades.add(trade);
					allTrades.add(trade);
				} else if (expired) {
					signalsExpiredUnfilled++;
				} else {
					// Still waiting -- either untouched, or touched but at
					// capacity (mirrors OrderManager only opening once a
					// slot is free; the order itself stays live at OANDA).
					stillPending.add(p);
				}
			}
			pendingSignals = stillPending;

			if (openTrades.size() >= maxOpenTrades)
				continue;

			// Cooldown: skip signal evaluation after recently opening a trade
			if (cooldownBars > 0) {
				cooldownBars--;
				continue;
			}

			// HTF candles up to current time (no look-ahead)
			List<Candle> visibleHtf = htfCandles.stream()
					.filter(c -> !c.time().isAfter(currentCandle.time()))
					.toList();
			if (visibleHtf.size() < 10)
				continue;

			// LTF candles: last 50 up to current
			int ltfStart = Math.max(0, i - 50);
			List<Candle> visibleLtf = ltfCandles.subList(ltfStart, i + 1);

			// Compute USDX bias
			UsdxBias currentBias;
			if (dynamicBias) {
				Instant now = currentCandle.time();
				List<Candle> visibleMonthly = usdxMonthly.stream()
						.filter(c -> !c.time().isAfter(now)).toList();
				List<Candle> visibleWeekly = usdxWeekly.stream()
						.filter(c -> !c.time().isAfter(now)).toList();
				List<Candle> visibleDaily = usdxDaily.stream()
						.filter(c -> !c.time().isAfter(now)).toList();
				currentBias = usdxBiasEngine.compute(
						visibleMonthly, visibleWeekly, visibleDaily);
			} else {
				currentBias = fixedBias;
			}

			KillzoneWindow killzone = killzoneService.classify(currentCandle.time());
			boolean newsBlackout = calendarService.isNewsBlackout(
					currentCandle.time(), instrument);

			// Build context WITH SMT signal
			MarketContext ctx = new MarketContext(
					currentCandle.time(),
					instrument,
					killzone,
					currentBias,
					visibleHtf,
					visibleLtf,
					newsBlackout,
					smt);

			Optional<TradeSignal> signal = aggregator.evaluate(ctx);

			if (signal.isEmpty()) {
				signalsRejected++;
				continue;
			}

			TradeSignal s = signal.get();
			signalsGenerated++;

			// Calculate SL, TP1, TP2 with spread applied
			long sl, tp1, tp2;
			if (s.bias() == MarketBias.BULLISH) {
				// Long: enter at ask (ideal entry + half spread)
				effectiveEntry = s.idealEntry() + (spreadScaled / 2);
				sl = s.sweepPrice() - slBufferScaled;
				long risk = effectiveEntry - sl;
				tp1 = effectiveEntry + (long) (risk * TP1_RISK_MULTIPLE);
				tp2 = effectiveEntry + (long) (risk * TP2_RISK_MULTIPLE);
			} else {
				// Short: enter at bid (ideal entry - half spread)
				effectiveEntry = s.idealEntry() - (spreadScaled / 2);
				sl = s.sweepPrice() + slBufferScaled;
				long risk = sl - effectiveEntry;
				tp1 = effectiveEntry - (long) (risk * TP1_RISK_MULTIPLE);
				tp2 = effectiveEntry - (long) (risk * TP2_RISK_MULTIPLE);
			}

			SimulatedTrade trade = new SimulatedTrade(s, sl, tp1, tp2, TP1_CLOSE_FRACTION);
			openTrades.add(trade);
			allTrades.add(trade);

			// Start cooldown — don't open another trade for N candles
			cooldownBars = cooldownPeriod;
		}

		// Force-close remaining open trades
		if (!ltfCandles.isEmpty()) {
			Candle lastCandle = ltfCandles.get(ltfCandles.size() - 1);
			for (SimulatedTrade trade : openTrades) {
				trade.checkExit(lastCandle.high(), lastCandle.low(), lastCandle.time());
			}
		}

		System.out.println("  Signals generated:  " + signalsGenerated);
		System.out.println("  Contexts rejected:  " + signalsRejected);
		System.out.println("  Expired unfilled:   " + signalsExpiredUnfilled);
		System.out.println("  Still pending:      " + pendingSignals.size());
		System.out.println("  Total trades:       " + allTrades.size());

		return new BacktestResult(allTrades, signalsGenerated, signalsRejected, signalsExpiredUnfilled);
	}

	/**
	 * Returns the candles at or before 'now', capped to the last 'window' of
	 * them. This gives the SMT detector the same bounded, no-look-ahead view
	 * that LiveSignalService's rolling buffer provides live.
	 *
	 * Assumes 'all' is in chronological order (oldest first), which the
	 * aggregated candle lists always are.
	 */
	private static List<Candle> lastVisible(List<Candle> all, Instant now, int window) {
		List<Candle> visible = new ArrayList<>();
		for (Candle c : all) {
			if (!c.time().isAfter(now)) {
				visible.add(c);
			} else {
				break; // chronological — nothing after this point is visible yet
			}
		}
		int from = Math.max(0, visible.size() - window);
		return visible.subList(from, visible.size());
	}
}
