package com.aletheia.backtest;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Main entry point to run backtests from the command line.
 *
 * Runs BOTH EUR/USD and GBP/USD backtests sequentially,
 * each using the other as the SMT confirmation pair.
 *
 * USAGE:
 * ./mvnw clean install -DskipTests
 * ./mvnw exec:java -pl aletheia-backtest -Dexec.jvmArgs="-Xmx4g"
 */
public class RunBacktest {

	public static void main(String[] args) {

		// ── CONFIGURE YOUR BACKTEST HERE ─────────────────────────────
		LocalDate startDate = LocalDate.of(2023, 6, 2);
		LocalDate endDate = LocalDate.of(2023, 6, 30);

		// Risk settings
		double riskRewardRatio = 3.0;
		long slBuffer = 20L;
		int maxOpenTrades = 6;
		long spread = 15L; // 1.5 pips

		// Calendar CSV
		Path calendarCsv = Path.of("data/calendar_2023.csv");
		// ─────────────────────────────────────────────────────────────

		BacktestRunner runner = new BacktestRunner(
				riskRewardRatio, slBuffer, maxOpenTrades, spread);

		// ── BACKTEST 1: Trade EUR/USD, SMT from GBP/USD ──────────────
		System.out.println("\n");
		System.out.println("###################################################");
		System.out.println("###  BACKTEST 1: EUR/USD (SMT: GBP/USD)         ###");
		System.out.println("###################################################");

		BacktestResult eurResult = runner.run(
				"EUR_USD", "GBP_USD", startDate, endDate, calendarCsv);

		printTradeLog(eurResult, "EUR/USD");

		// ── BACKTEST 2: Trade GBP/USD, SMT from EUR/USD ──────────────
		System.out.println("\n\n");
		System.out.println("###################################################");
		System.out.println("###  BACKTEST 2: GBP/USD (SMT: EUR/USD)         ###");
		System.out.println("###################################################");

		BacktestResult gbpResult = runner.run(
				"GBP_USD", "EUR_USD", startDate, endDate, calendarCsv);

		printTradeLog(gbpResult, "GBP/USD");

		// ── COMBINED SUMMARY ─────────────────────────────────────────
		System.out.println("\n\n");
		System.out.println("###################################################");
		System.out.println("###  COMBINED SUMMARY                           ###");
		System.out.println("###################################################");
		System.out.println("  EUR/USD: " + eurResult.trades().size() + " trades, "
				+ String.format("%.1f", eurResult.metrics().netPnlPips()) + " pips, "
				+ String.format("%.1f%%", eurResult.metrics().winRate()) + " win rate");
		System.out.println("  GBP/USD: " + gbpResult.trades().size() + " trades, "
				+ String.format("%.1f", gbpResult.metrics().netPnlPips()) + " pips, "
				+ String.format("%.1f%%", gbpResult.metrics().winRate()) + " win rate");

		double totalPips = eurResult.metrics().netPnlPips() + gbpResult.metrics().netPnlPips();
		int totalTrades = eurResult.trades().size() + gbpResult.trades().size();
		System.out.println("---------------------------------------------------");
		System.out.println("  TOTAL:   " + totalTrades + " trades, "
				+ String.format("%.1f", totalPips) + " pips combined");
		System.out.println("###################################################");
	}

	private static void printTradeLog(BacktestResult result, String label) {
		System.out.println("\n-- TRADE LOG (" + label + ") --");
		if (result.trades().isEmpty()) {
			System.out.println("  No trades generated.");
		} else {
			int tradeNum = 1;
			for (SimulatedTrade trade : result.trades()) {
				if (trade.isClosed()) {
					System.out.printf("  #%d  %s %s  entry=%d  exit=%d  P&L=%.1f pips  %s  [%s]%n",
							tradeNum++,
							trade.direction(),
							trade.instrument(),
							trade.entryPrice(),
							trade.exitPrice(),
							trade.pnlPips(),
							trade.isWin() ? "WIN" : "LOSS",
							trade.grade());
				}
			}
		}
	}
}
