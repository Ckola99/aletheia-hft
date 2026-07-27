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
		int signalsRejected) {

	/**
	 * Returns the performance metrics for all trades.
	 */
	public PerformanceMetrics metrics() {
		return new PerformanceMetrics(trades);
	}

	/**
	 * Prints the full performance report.
	 */
	public void printReport() {
		metrics().printReport();
	}
}
