package com.aletheia.api;

import com.aletheia.calendar.EconomicCalendarService;
import com.aletheia.core.Candle;
import com.aletheia.core.KillzoneWindow;
import com.aletheia.core.PriceScale;
import com.aletheia.core.Timeframe;
import com.aletheia.data.CandleAggregator;
import com.aletheia.data.CandleListener;
import com.aletheia.data.CandleRepository;
import com.aletheia.execution.KillSwitch;
import com.aletheia.execution.ManagedOrder;
import com.aletheia.execution.OrderManager;
import com.aletheia.execution.BrokerExecutor;
import com.aletheia.strategy.*;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The live signal-generation loop — the piece that makes the engine autonomous.
 *
 * It listens for closed candles from the CandleAggregator, maintains rolling
 * in-memory buffers of candles per (instrument x timeframe), and whenever a
 * MIN_5 candle closes for a traded instrument it runs exactly the same
 * evaluation the backtest does:
 *
 * 1. Build USDX bias (multi-timeframe structure)
 * 2. Check SMT divergence between the traded pair and its partner
 * 3. Determine killzone and news blackout
 * 4. SignalAggregator.evaluate(context)
 * 5. On a signal -> OrderManager.createOrder -> BrokerExecutor.placeLimitOrder
 *
 * DESIGN NOTES:
 *
 * - EVENT-DRIVEN, NOT POLLED. We act on candle-close events so the strategy
 * sees exactly the same "completed candles up to now" view the backtest used,
 * with no look-ahead.
 *
 * - OFF THE STREAM THREAD. onCandleClosed runs on the pricing-stream thread.
 * Doing REST calls there would stall tick processing, so evaluation is handed
 * to a single background thread. Single-threaded so evaluations never overlap.
 *
 * - IN-MEMORY BUFFERS + WARMUP. Rather than re-querying the DB on every candle
 * (and racing the persistence writer), we keep bounded rolling buffers and
 * warm them up once at startup from the CandleRepository. If the DB is empty
 * on a fresh install, the service simply won't trade until enough live candles
 * accrue -- which is safe.
 *
 * - BROKER-AGNOSTIC. This service depends only on BrokerExecutor, not any
 * concrete broker. Swapping OANDA for a cTrader FIX executor (or any future
 * broker) requires no change here — only the wiring in TradingEngineConfig.
 *
 * - USDX BIAS SOURCE. For now the dollar bias is derived synthetically from the
 * EUR/USD inverse (same method the early backtests used). This is a KNOWN
 * LIMITATION: real DXY produced far better results in tuning. Replacing this
 * with a streamed/fetched DOLLAR_IDX feed is the single highest-value upgrade
 * before this goes beyond paper trading. The bias computation is isolated in
 * buildUsdxBias() so it can be swapped without touching the rest of the loop.
 */
@Component
public class LiveSignalService implements CandleListener {

	// ── Timeframes (parsed from config, defaulting to the tuned settings) ──
	private final Timeframe htf; // HOUR_1 — PD arrays / structure
	private final Timeframe ltf; // MIN_5 — Judas Swing / trigger
	private static final Timeframe SMT_TF = Timeframe.MIN_15;
	private static final Timeframe USDX_MONTH = Timeframe.DAILY; // monthly proxy
	private static final Timeframe USDX_WEEK = Timeframe.HOUR_4; // weekly proxy
	private static final Timeframe USDX_DAY = Timeframe.HOUR_1; // daily proxy

	// How many candles to keep per (instrument x timeframe)
	private static final Map<Timeframe, Integer> BUFFER_LIMITS = Map.of(
			Timeframe.MIN_5, 300,
			Timeframe.MIN_15, 300,
			Timeframe.HOUR_1, 400,
			Timeframe.HOUR_4, 400,
			Timeframe.DAILY, 500);

	// ── Collaborators ──────────────────────────────────────────────────
	private final CandleAggregator aggregator;
	private final CandleRepository candleRepository;
	private final SignalAggregator signalAggregator;
	private final UsdxBiasEngine usdxBiasEngine;
	private final KillzoneService killzoneService;
	private final EconomicCalendarService calendarService;
	private final SwingPointRegistry smtRegistry;
	private final SmtDivergenceDetector smtDetector;
	private final OrderManager orderManager;
	private final BrokerExecutor executor;
	private final KillSwitch killSwitch;
	private final DxyFeedService dxyFeed;
	private final Executor evaluationExecutor;

	// ── Config ─────────────────────────────────────────────────────────
	private final String[] instruments;
	private final String usdxSource; // instrument used to derive USDX bias
	private final double defaultBalance; // fallback if OANDA balance unavailable

	// ── Rolling candle buffers, keyed "INSTRUMENT:TIMEFRAME" ────────────
	private final Map<String, Deque<Candle>> buffers = new ConcurrentHashMap<>();
	private final Object bufferLock = new Object();

	// ── Observability counters (also used by tests) ─────────────────────
	private final AtomicLong candleEvents = new AtomicLong();
	private final AtomicLong evaluationsTriggered = new AtomicLong();
	private final AtomicLong contextsEvaluated = new AtomicLong();
	private final AtomicLong ordersPlaced = new AtomicLong();

	@Autowired
	public LiveSignalService(
			CandleAggregator aggregator,
			CandleRepository candleRepository,
			SignalAggregator signalAggregator,
			UsdxBiasEngine usdxBiasEngine,
			KillzoneService killzoneService,
			EconomicCalendarService calendarService,
			SwingPointRegistry smtRegistry,
			SmtDivergenceDetector smtDetector,
			OrderManager orderManager,
			BrokerExecutor executor,
			KillSwitch killSwitch,
			DxyFeedService dxyFeed,
			@Value("${trading.instruments}") String[] instruments,
			@Value("${trading.htf-timeframe:HOUR_1}") String htfName,
			@Value("${trading.ltf-timeframe:MIN_5}") String ltfName,
			@Value("${trading.usdx-source:EUR_USD}") String usdxSource,
			@Value("${trading.default-balance:100000}") double defaultBalance) {

		this(aggregator, candleRepository, signalAggregator, usdxBiasEngine,
				killzoneService, calendarService, smtRegistry, smtDetector,
				orderManager, executor, killSwitch, dxyFeed, instruments,
				htfName, ltfName, usdxSource, defaultBalance,
				Executors.newSingleThreadExecutor(r -> {
					Thread t = new Thread(r, "live-signal-eval");
					t.setDaemon(true);
					return t;
				}));
	}

	LiveSignalService(
			CandleAggregator aggregator,
			CandleRepository candleRepository,
			SignalAggregator signalAggregator,
			UsdxBiasEngine usdxBiasEngine,
			KillzoneService killzoneService,
			EconomicCalendarService calendarService,
			SwingPointRegistry smtRegistry,
			SmtDivergenceDetector smtDetector,
			OrderManager orderManager,
			BrokerExecutor executor,
			KillSwitch killSwitch,
			DxyFeedService dxyFeed,
			String[] instruments,
			String htfName,
			String ltfName,
			String usdxSource,
			double defaultBalance,
			Executor evaluationExecutor) {

		this.aggregator = aggregator;
		this.candleRepository = candleRepository;
		this.signalAggregator = signalAggregator;
		this.usdxBiasEngine = usdxBiasEngine;
		this.killzoneService = killzoneService;
		this.calendarService = calendarService;
		this.smtRegistry = smtRegistry;
		this.smtDetector = smtDetector;
		this.orderManager = orderManager;
		this.executor = executor;
		this.killSwitch = killSwitch;
		this.dxyFeed = dxyFeed;
		this.instruments = instruments;
		this.usdxSource = usdxSource;
		this.defaultBalance = defaultBalance;
		this.htf = Timeframe.valueOf(htfName);
		this.ltf = Timeframe.valueOf(ltfName);
		this.evaluationExecutor = evaluationExecutor;
	}

	/**
	 * Registers with the aggregator and warms up buffers from the database.
	 */
	@PostConstruct
	public void init() {
		aggregator.addCandleListener(this);
		warmup();
		System.out.println("[LiveSignalService] Active. HTF=" + htf
				+ " LTF=" + ltf + " instruments=" + String.join(",", instruments)
				+ " (USDX bias source: " + usdxSource + ", synthetic)");
	}

	/**
	 * Backfills the rolling buffers from persisted candles so the strategy
	 * has history immediately at startup instead of waiting days for live
	 * candles to accrue.
	 */
	public void warmup() {
		for (String instrument : instrumentsToBuffer()) {
			for (Timeframe tf : BUFFER_LIMITS.keySet()) {
				try {
					List<Candle> recent = candleRepository.findRecent(
							instrument, tf, BUFFER_LIMITS.get(tf));
					// Ensure oldest-first for the detectors
					recent = new ArrayList<>(recent);
					recent.sort(Comparator.comparing(Candle::time));
					synchronized (bufferLock) {
						Deque<Candle> buf = buffers.computeIfAbsent(
								key(instrument, tf), k -> new ArrayDeque<>());
						buf.clear();
						recent.forEach(buf::addLast);
						trim(buf, tf);
					}
				} catch (Exception e) {
					// Empty DB / missing table on fresh install — safe to skip
					System.out.println("[LiveSignalService] warmup skip "
							+ instrument + ":" + tf + " (" + e.getMessage() + ")");
				}
			}
		}
	}

	// ── Candle-close entry point (runs on the pricing-stream thread) ─────

	@Override
	public void onCandleClosed(Candle candle) {
		candleEvents.incrementAndGet();

		// Buffer every timeframe we track, for every instrument we care about
		if (isBufferable(candle)) {
			synchronized (bufferLock) {
				Deque<Candle> buf = buffers.computeIfAbsent(
						key(candle.instrument(), candle.timeframe()),
						k -> new ArrayDeque<>());
				buf.addLast(candle);
				trim(buf, candle.timeframe());
			}
		}

		// Only a closed LTF candle on a TRADED instrument triggers evaluation
		if (candle.timeframe() != ltf)
			return;
		if (!isTradedInstrument(candle.instrument()))
			return;
		if (killSwitch.isActive())
			return;

		evaluationsTriggered.incrementAndGet();
		final String instrument = candle.instrument();
		final Instant now = candle.time();

		// Off the stream thread — never block tick processing
		evaluationExecutor.execute(() -> {
			try {
				evaluate(instrument, now);
			} catch (Exception e) {
				System.err.println("[LiveSignalService] evaluation error for "
						+ instrument + ": " + e.getMessage());
			}
		});
	}

	// ── The evaluation (runs on the background thread) ──────────────────

	private void evaluate(String instrument, Instant now) {
		// Cheap gate first: outside a killzone the aggregator rejects anyway
		KillzoneWindow killzone = killzoneService.classify(now);
		if (!killzone.isActive())
			return;

		// Snapshot the buffers we need (immutable copies, oldest-first)
		List<Candle> htfCandles = snapshot(instrument, htf);
		List<Candle> ltfCandles = snapshot(instrument, ltf);
		if (htfCandles.size() < 10 || ltfCandles.size() < 30)
			return;

		// Pillar 1: USDX bias (synthetic for now — see class notes)
		UsdxBias usdxBias = buildUsdxBias(now);

		// SMT divergence between this pair and its partner
		Optional<SmtDivergenceSignal> smt = detectSmt(instrument, killzone);

		// Pillar 3: news blackout
		boolean newsBlackout = calendarService.isNewsBlackout(now, instrument);

		MarketContext ctx = new MarketContext(
				now, instrument, killzone, usdxBias,
				htfCandles, ltfCandles, newsBlackout, smt);

		contextsEvaluated.incrementAndGet();

		Optional<TradeSignal> maybeSignal = signalAggregator.evaluate(ctx);
		if (maybeSignal.isEmpty())
			return;

		TradeSignal signal = maybeSignal.get();

		// Size the position off the live account balance (fallback if unavailable)
		double balance = executor.getAccountBalance().orElse(defaultBalance);

		Optional<ManagedOrder> maybeOrder = orderManager.createOrder(signal, balance);
		if (maybeOrder.isEmpty())
			return; // max positions / duplicate rejected

		ManagedOrder order = maybeOrder.get();
		Optional<String> brokerOrderId = executor.placeLimitOrder(order);
		brokerOrderId.ifPresent(order::setOandaOrderId);

		ordersPlaced.incrementAndGet();
		System.out.println("[LiveSignalService] Order placed for " + instrument
				+ " " + signal.bias() + " grade=" + signal.grade()
				+ " brokerId=" + brokerOrderId.orElse("FAILED"));
	}

	/**
	 * Builds the multi-timeframe USDX bias.
	 *
	 * Preferred path: REAL DXY from DxyFeedService (matches the tuned backtest).
	 * Until the feed has seeded, we return NEUTRAL (no trades) rather than fall
	 * back to the inferior synthetic proxy — flat is safer than wrong.
	 *
	 * Fallback path (only when the DXY feed is disabled): synthetic dollar index
	 * derived by inverting the EUR/USD candles.
	 */
	private UsdxBias buildUsdxBias(Instant now) {
		if (dxyFeed != null && dxyFeed.enabled()) {
			if (!dxyFeed.hasData()) {
				// Real feed not ready yet -> NEUTRAL (compute on empty lists)
				return usdxBiasEngine.compute(List.of(), List.of(), List.of());
			}
			return usdxBiasEngine.compute(
					dxyFeed.usdxMonthly(), dxyFeed.usdxWeekly(), dxyFeed.usdxDaily());
		}

		// Synthetic fallback (DXY feed disabled)
		List<Candle> monthly = invertToUsdx(snapshot(usdxSource, USDX_MONTH));
		List<Candle> weekly = invertToUsdx(snapshot(usdxSource, USDX_WEEK));
		List<Candle> daily = invertToUsdx(snapshot(usdxSource, USDX_DAY));
		return usdxBiasEngine.compute(monthly, weekly, daily);
	}

	/**
	 * Updates the SMT swing registry for this pair and its partner, then checks
	 * for divergence. Returns empty if there is no configured partner.
	 */
	private Optional<SmtDivergenceSignal> detectSmt(String instrument,
			KillzoneWindow killzone) {
		String partner = partnerFor(instrument);
		if (partner == null)
			return Optional.empty();

		List<Candle> selfSmt = snapshot(instrument, SMT_TF);
		List<Candle> partnerSmt = snapshot(partner, SMT_TF);
		if (selfSmt.isEmpty() || partnerSmt.isEmpty())
			return Optional.empty();

		smtRegistry.update(instrument, SMT_TF, selfSmt);
		smtRegistry.update(partner, SMT_TF, partnerSmt);

		return smtDetector.detect(
				new SmtPair(instrument, partner), SMT_TF, smtRegistry, killzone);
	}

	// ── Synthetic USDX inversion (mirrors SyntheticUsdxBuilder) ─────────

	private static final long USDX_SCALE = 100_000L;

	private static List<Candle> invertToUsdx(List<Candle> src) {
		List<Candle> out = new ArrayList<>(src.size());
		for (Candle c : src) {
			double open = PriceScale.toDouble(c.open(), "EUR_USD");
			double high = PriceScale.toDouble(c.high(), "EUR_USD");
			double low = PriceScale.toDouble(c.low(), "EUR_USD");
			double close = PriceScale.toDouble(c.close(), "EUR_USD");
			out.add(new Candle(
					c.time(), "USDX", c.timeframe(),
					Math.round((1.0 / open) * USDX_SCALE),
					Math.round((1.0 / low) * USDX_SCALE), // EUR low -> USDX high
					Math.round((1.0 / high) * USDX_SCALE), // EUR high -> USDX low
					Math.round((1.0 / close) * USDX_SCALE),
					c.volume()));
		}
		return out;
	}

	// ── Buffer helpers ──────────────────────────────────────────────────

	private List<Candle> snapshot(String instrument, Timeframe tf) {
		synchronized (bufferLock) {
			Deque<Candle> buf = buffers.get(key(instrument, tf));
			return buf == null ? List.of() : new ArrayList<>(buf);
		}
	}

	private void trim(Deque<Candle> buf, Timeframe tf) {
		int limit = BUFFER_LIMITS.getOrDefault(tf, 300);
		while (buf.size() > limit)
			buf.removeFirst();
	}

	private boolean isBufferable(Candle c) {
		return BUFFER_LIMITS.containsKey(c.timeframe())
				&& instrumentsToBuffer().contains(c.instrument());
	}

	private boolean isTradedInstrument(String instrument) {
		for (String i : instruments)
			if (i.equals(instrument))
				return true;
		return false;
	}

	/** Traded instruments plus the USDX source (usually already included). */
	private List<String> instrumentsToBuffer() {
		List<String> list = new ArrayList<>(List.of(instruments));
		if (!list.contains(usdxSource))
			list.add(usdxSource);
		return list;
	}

	/** The SMT partner is simply the other configured instrument, if any. */
	private String partnerFor(String instrument) {
		for (String i : instruments)
			if (!i.equals(instrument))
				return i;
		return null;
	}

	private static String key(String instrument, Timeframe tf) {
		return instrument + ":" + tf.name();
	}

	// ── Accessors for monitoring / tests ────────────────────────────────

	public long candleEvents() {
		return candleEvents.get();
	}

	public long evaluationsTriggered() {
		return evaluationsTriggered.get();
	}

	public long contextsEvaluated() {
		return contextsEvaluated.get();
	}

	public long ordersPlaced() {
		return ordersPlaced.get();
	}

	public int bufferSize(String instrument, Timeframe tf) {
		return snapshot(instrument, tf).size();
	}

	@PreDestroy
	public void shutdown() {
		if (evaluationExecutor instanceof java.util.concurrent.ExecutorService es) {
			es.shutdown();
		}
	}
}
