package com.aletheia.strategy;

import com.aletheia.core.MarketBias;

import java.util.List;
import java.util.Optional;

/**
 * The five-pillar gatekeeper. Evaluates a MarketContext and returns
 * a TradeSignal only if ALL pillars pass.
 *
 * THE FIVE PILLARS:
 * 1. USDX Bias — must be directional with tradeable confidence
 * 2. Killzone — must be inside London Open or NY Open
 * 3. News Clearance — must NOT be in a news blackout window
 * 4. HTF PD Array — must find an FVG or OB on the 15min chart
 * 5. Judas Swing — must detect a sweep + displacement + FVG on LTF
 *
 * Each pillar returns a rejection reason if it fails. This is logged
 * so you can debug why a setup was rejected — "was it the bias?
 * the killzone? the news?" Without rejection reasons, debugging
 * missed signals is extremely difficult.
 *
 * DESIGN PRINCIPLE:
 * The aggregator does NOT fetch any data. It receives a fully populated
 * MarketContext and makes a decision. This makes it deterministic and
 * testable — the same context always produces the same result.
 */
public class SignalAggregator {

	private final FairValueGapDetector fvgDetector;
	private final OrderBlockDetector obDetector;
	private final JudasSwingDetector judasDetector;

	public SignalAggregator() {
		this.fvgDetector = new FairValueGapDetector();
		this.obDetector = new OrderBlockDetector();
		this.judasDetector = new JudasSwingDetector();
	}

	/**
	 * Constructor for custom detector settings (useful in tests).
	 */
	public SignalAggregator(FairValueGapDetector fvgDetector,
			OrderBlockDetector obDetector,
			JudasSwingDetector judasDetector) {
		this.fvgDetector = fvgDetector;
		this.obDetector = obDetector;
		this.judasDetector = judasDetector;
	}

	/**
	 * Evaluates the market context against all five pillars.
	 *
	 * @param ctx the current market snapshot
	 * @return a TradeSignal if all pillars pass, empty if any fails
	 */
	public Optional<TradeSignal> evaluate(MarketContext ctx) {

		// ── PILLAR 1: USDX Bias ────────────────────────────────────
		if (!ctx.usdxBias().isTradeable()) {
			logRejection(ctx, "Pillar 1 FAIL: USDX bias not tradeable — "
					+ ctx.usdxBias().direction() + " / " + ctx.usdxBias().confidence());
			return Optional.empty();
		}

		// Get the bias for this specific pair
		MarketBias pairBias = ctx.usdxBias().biasForPair(ctx.instrument());
		if (!pairBias.isDirectional()) {
			logRejection(ctx, "Pillar 1 FAIL: pair bias is NEUTRAL for " + ctx.instrument());
			return Optional.empty();
		}

		// ── PILLAR 2: Killzone ─────────────────────────────────────
		if (!ctx.killzone().isActive()) {
			logRejection(ctx, "Pillar 2 FAIL: outside killzone — "
					+ ctx.killzone().displayName());
			return Optional.empty();
		}

		// ── PILLAR 3: News Clearance ───────────────────────────────
		if (ctx.newsBlackout()) {
			logRejection(ctx, "Pillar 3 FAIL: news blackout active");
			return Optional.empty();
		}

		// ── PILLAR 4: HTF PD Array ─────────────────────────────────
		// Price must be near a PD Array on the 15min chart.
		// "Near" means: at least one FVG or OB exists in recent candles
		// that matches our directional bias.
		List<FairValueGap> htfFvgs = fvgDetector.detect(ctx.htfCandles());
		List<OrderBlock> htfObs = obDetector.detect(ctx.htfCandles());

		PdArray.Bias requiredBias = (pairBias == MarketBias.BULLISH)
				? PdArray.Bias.BULLISH
				: PdArray.Bias.BEARISH;

		boolean hasFvg = htfFvgs.stream()
				.anyMatch(f -> f.bias() == requiredBias);
		boolean hasOb = htfObs.stream()
				.anyMatch(o -> o.bias() == requiredBias);

		if (!hasFvg && !hasOb) {
			logRejection(ctx, "Pillar 4 FAIL: no " + requiredBias
					+ " PD Array on HTF (" + htfFvgs.size() + " FVGs, "
					+ htfObs.size() + " OBs found, none matching bias)");
			return Optional.empty();
		}

		// ── PILLAR 5: Judas Swing ──────────────────────────────────
		Optional<JudasSwingSignal> judas = judasDetector.detect(
				pairBias, ctx.killzone(), ctx.ltfCandles());

		if (judas.isEmpty()) {
			logRejection(ctx, "Pillar 5 FAIL: no Judas Swing detected on LTF");
			return Optional.empty();
		}

		// ── ALL FIVE PILLARS PASSED ────────────────────────────────
		JudasSwingSignal j = judas.get();

		TradeSignal signal = new TradeSignal(
				pairBias,
				ctx.instrument(),
				j.entryZone(),
				j.idealEntry(),
				j.sweepPrice(),
				ctx.killzone(),
				j.grade(), // A for now — SMT upgrades to A+ in M3
				ctx.usdxBias(),
				j,
				ctx.now());

		logSignal(signal);
		return Optional.of(signal);
	}

	private void logRejection(MarketContext ctx, String reason) {
		System.out.println("[SignalAggregator] " + ctx.instrument()
				+ " @ " + ctx.killzone().displayName() + " — " + reason);
	}

	private void logSignal(TradeSignal signal) {
		System.out.println("═══════════════════════════════════════════════════");
		System.out.println("  ✅ TRADE SIGNAL GENERATED");
		System.out.println("  Instrument:  " + signal.instrument());
		System.out.println("  Direction:   " + signal.bias());
		System.out.println("  Entry zone:  " + signal.entryZone().lower()
				+ " to " + signal.entryZone().upper());
		System.out.println("  Ideal entry: " + signal.idealEntry());
		System.out.println("  Sweep price: " + signal.sweepPrice());
		System.out.println("  Killzone:    " + signal.killzone().displayName());
		System.out.println("  Grade:       " + signal.grade());
		System.out.println("  USDX:        " + signal.usdxBias().direction()
				+ " (" + signal.usdxBias().confidence() + ")");
		System.out.println("═══════════════════════════════════════════════════");
	}
}
