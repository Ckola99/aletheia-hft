package com.aletheia.backtest;

import java.util.List;

/**
 * The output of a backtest run.
 *
 * Contains all trades and pre-computed performance metrics.
 * Can be serialised to JSON for storage or display on Grafana.
 */
public record BacktestResult(
		List<SimulatedTrade> trades,
		int signalsGenerated,
		int signalsRejected,
		int signalsExpiredUnfilled) {

	public BacktestResult(List<SimulatedTrade> trades, int signalsGenerated, int signalsRejected) {
		this(trades, signalsGenerated, signalsRejected, 0);
	}

	/**
	 * Returns the performance metrics for all trades.
	 */
	public PerformanceMetrics metrics() {
		return new PerformanceMetrics(trades);
	}

	/**
	 * Percentage of five-pillar-validated signals that actually got a limit
	 * fill before their pending order expired (killzone ended / went stale).
	 * Signals that failed the five-pillar gate are not counted here.
	 */
	public double fillRate() {
		int passedGate = signalsGenerated;
		if (passedGate == 0)
			return 0;
		return (double) trades.size() / passedGate * 100;
	}

	/**
	 * Prints the full performance report.
	 */
	public void printReport() {
		System.out.printf("  Signal fill rate: %.1f%% (%d filled / %d expired unfilled)%n",
				fillRate(), trades.size(), signalsExpiredUnfilled);
		metrics().printReport();
	}
}
