package com.aletheia.backtest;

import com.aletheia.core.KillzoneWindow;
import com.aletheia.strategy.SignalGrade;

import java.util.List;

/**
 * Calculates trading performance metrics from a list of completed trades.
 *
 * These metrics are the standard that every quantitative trader and
 * hedge fund uses to evaluate a strategy:
 *
 * Net P&L: Total profit minus total losses (in pips)
 * Win Rate: Percentage of trades that were profitable
 * Profit Factor: Gross profit ÷ gross loss (>1 means profitable)
 * Max Drawdown: Largest peak-to-trough decline in equity
 * Sharpe Ratio: Risk-adjusted return (>1 is good, >2 is excellent)
 * Average R:R: Average reward-to-risk ratio of winners
 */
public class PerformanceMetrics {

	private final List<SimulatedTrade> trades;

	public PerformanceMetrics(List<SimulatedTrade> trades) {
		this.trades = trades.stream().filter(SimulatedTrade::isClosed).toList();
	}

	/** Total number of completed trades. */
	public int totalTrades() {
		return trades.size();
	}

	/** Number of winning trades. */
	public int wins() {
		return (int) trades.stream().filter(SimulatedTrade::isWin).count();
	}

	/** Number of losing trades. */
	public int losses() {
		return (int) trades.stream().filter(SimulatedTrade::isLoss).count();
	}

	/**
	 * Win rate as a percentage (0-100).
	 * A win rate of 40-50% is typical for ICT strategies — they make
	 * up for it with high R:R ratios (small losses, large wins).
	 */
	public double winRate() {
		if (trades.isEmpty())
			return 0;
		return (double) wins() / trades.size() * 100;
	}

	/** Total P&L in pips across all trades. */
	public double netPnlPips() {
		return trades.stream().mapToDouble(SimulatedTrade::pnlPips).sum();
	}

	/** Sum of all winning trade P&L in pips. */
	public double grossProfit() {
		return trades.stream()
				.filter(SimulatedTrade::isWin)
				.mapToDouble(SimulatedTrade::pnlPips)
				.sum();
	}

	/** Sum of all losing trade P&L in pips (returned as positive number). */
	public double grossLoss() {
		return Math.abs(trades.stream()
				.filter(SimulatedTrade::isLoss)
				.mapToDouble(SimulatedTrade::pnlPips)
				.sum());
	}

	/**
	 * Profit Factor = Gross Profit ÷ Gross Loss.
	 *
	 * > 1.0 means the strategy is profitable overall.
	 * > 1.5 is good. > 2.0 is excellent. < 1.0 means losing money.
	 *
	 * A strategy with 40% win rate but 3:1 R:R has a profit factor
	 * of (0.40 × 3) / (0.60 × 1) = 2.0 — very profitable despite
	 * losing more often than winning.
	 */
	public double profitFactor() {
		double loss = grossLoss();
		if (loss == 0)
			return grossProfit() > 0 ? Double.POSITIVE_INFINITY : 0;
		return grossProfit() / loss;
	}

	/**
	 * Maximum drawdown in pips.
	 *
	 * Drawdown is the largest decline from a peak in the equity curve.
	 * If your equity goes: 0, +50, +100, +80, +60, +120
	 * The drawdown from 100 to 60 is 40 pips.
	 *
	 * This measures the worst losing streak. A strategy might be
	 * profitable overall but have a 200-pip drawdown that would
	 * blow a small account. Max drawdown tells you how much pain
	 * you need to survive to reach the profits.
	 */
	public double maxDrawdownPips() {
		if (trades.isEmpty())
			return 0;

		double peak = 0;
		double equity = 0;
		double maxDrawdown = 0;

		for (SimulatedTrade trade : trades) {
			equity += trade.pnlPips();
			if (equity > peak) {
				peak = equity;
			}
			double drawdown = peak - equity;
			if (drawdown > maxDrawdown) {
				maxDrawdown = drawdown;
			}
		}

		return maxDrawdown;
	}

	/**
	 * Sharpe Ratio — risk-adjusted return.
	 *
	 * Measures: "for each unit of risk (volatility), how much return
	 * did the strategy generate?"
	 *
	 * Sharpe = mean(returns) / stddev(returns)
	 *
	 * > 1.0 is acceptable. > 2.0 is very good. > 3.0 is excellent.
	 * < 0 means the strategy loses money.
	 *
	 * Uses per-trade P&L as the return series.
	 */
	public double sharpeRatio() {
		if (trades.size() < 2)
			return 0;

		double[] returns = trades.stream()
				.mapToDouble(SimulatedTrade::pnlPips)
				.toArray();

		double mean = 0;
		for (double r : returns)
			mean += r;
		mean /= returns.length;

		double variance = 0;
		for (double r : returns)
			variance += (r - mean) * (r - mean);
		variance /= (returns.length - 1); // sample variance

		double stddev = Math.sqrt(variance);
		if (stddev == 0)
			return 0;

		return mean / stddev;
	}

	/**
	 * Average reward-to-risk ratio of WINNING trades.
	 * Shows how much you make on average when you win,
	 * relative to how much you risked.
	 */
	public double averageWinRR() {
		return trades.stream()
				.filter(SimulatedTrade::isWin)
				.mapToDouble(SimulatedTrade::rewardRiskRatio)
				.average()
				.orElse(0);
	}

	/**
	 * Win rate for A+ grade signals only.
	 * Used to prove SMT Divergence adds edge.
	 */
	public double winRateAPlus() {
		List<SimulatedTrade> aPlus = trades.stream()
				.filter(t -> t.grade() == SignalGrade.A_PLUS)
				.toList();
		if (aPlus.isEmpty())
			return 0;
		long wins = aPlus.stream().filter(SimulatedTrade::isWin).count();
		return (double) wins / aPlus.size() * 100;
	}

	/**
	 * Win rate for A grade signals only.
	 * Compared with winRateAPlus to measure SMT's contribution.
	 */
	public double winRateA() {
		List<SimulatedTrade> aGrade = trades.stream()
				.filter(t -> t.grade() == SignalGrade.A)
				.toList();
		if (aGrade.isEmpty())
			return 0;
		long wins = aGrade.stream().filter(SimulatedTrade::isWin).count();
		return (double) wins / aGrade.size() * 100;
	}

	/**
	 * Prints a formatted summary report to stdout.
	 */
	public void printReport() {
		System.out.println("===================================================");
		System.out.println("  BACKTEST PERFORMANCE REPORT");
		System.out.println("===================================================");
		System.out.printf("  Total Trades:     %d (%d wins, %d losses)%n",
				totalTrades(), wins(), losses());
		System.out.printf("  Win Rate:         %.1f%%%n", winRate());
		System.out.printf("  Net P&L:          %.1f pips%n", netPnlPips());
		System.out.printf("  Gross Profit:     %.1f pips%n", grossProfit());
		System.out.printf("  Gross Loss:       %.1f pips%n", grossLoss());
		System.out.printf("  Profit Factor:    %.2f%n", profitFactor());
		System.out.printf("  Max Drawdown:     %.1f pips%n", maxDrawdownPips());
		System.out.printf("  Sharpe Ratio:     %.2f%n", sharpeRatio());
		System.out.printf("  Avg Winner R:R:   %.2f%n", averageWinRR());
		System.out.println("---------------------------------------------------");
		System.out.printf("  A+ Win Rate:      %.1f%% (%d trades)%n",
				winRateAPlus(),
				trades.stream().filter(t -> t.grade() == SignalGrade.A_PLUS).count());
		System.out.printf("  A  Win Rate:      %.1f%% (%d trades)%n",
				winRateA(),
				trades.stream().filter(t -> t.grade() == SignalGrade.A).count());
		System.out.println("---------------------------------------------------");
                System.out.println("  BY KILLZONE");
                printKillzoneBreakdown();
                System.out.println("===================================================");
        }

        /**
         * Prints win rate, trade count, and net pips for each killzone, so we
         * can see where the edge actually concentrates (London vs New York).
         */
        private void printKillzoneBreakdown() {
                java.util.Map<KillzoneWindow, List<SimulatedTrade>> byZone =
                                trades.stream().collect(
                                        java.util.stream.Collectors.groupingBy(
                                                SimulatedTrade::killzone));

                byZone.entrySet().stream()
                        .sorted(java.util.Map.Entry.comparingByKey())
                        .forEach(entry -> {
                                KillzoneWindow zone = entry.getKey();
                                List<SimulatedTrade> zoneTrades = entry.getValue();

                                long wins = zoneTrades.stream()
                                                .filter(SimulatedTrade::isWin).count();
                                int total = zoneTrades.size();
                                double winPct = total == 0 ? 0.0
                                                : (wins * 100.0) / total;
                                double netPips = zoneTrades.stream()
                                                .mapToDouble(SimulatedTrade::pnlPips).sum();

                                System.out.printf("  %-14s %2d trades, %.1f%% win, %+.1f pips%n",
                                                zone, total, winPct, netPips);
                        });
        }
}
