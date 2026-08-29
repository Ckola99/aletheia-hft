package com.aletheia.backtest;

import java.nio.file.Path;
import java.time.LocalDate;

public class RunBacktest {

	public static void main(String[] args) {

		LocalDate startDate = LocalDate.of(2023, 1, 1);
		LocalDate endDate = LocalDate.of(2023, 12, 30);

		double riskRewardRatio = 3.0;
		long slBuffer = 20L;
		int maxOpenTrades = 6;
		long spread = 15L;

		Path calendarCsv = Path.of("data/calendar_2023.csv");

		BacktestRunner runner = new BacktestRunner(
				riskRewardRatio, slBuffer, maxOpenTrades, spread);

		System.out.println("\n###################################################");
		System.out.println("###  EUR/USD (SMT: GBP/USD)                     ###");
		System.out.println("###################################################");
		BacktestResult eurResult = runner.run("EUR_USD", "GBP_USD", startDate, endDate, calendarCsv);
		printTradeLog(eurResult, "EUR/USD");

		System.out.println("\n\n###################################################");
		System.out.println("###  GBP/USD (SMT: EUR/USD)                     ###");
		System.out.println("###################################################");
		BacktestResult gbpResult = runner.run("GBP_USD", "EUR_USD", startDate, endDate, calendarCsv);
		printTradeLog(gbpResult, "GBP/USD");

		System.out.println("\n\n###################################################");
		System.out.println("###  AUD/USD (SMT: NZD_USD)                     ###");
		System.out.println("###################################################");
		BacktestResult audResult = runner.run("AUD_USD", "NZD_USD", startDate, endDate, calendarCsv);
		printTradeLog(audResult, "AUD/USD");

		System.out.println("\n\n###################################################");
		System.out.println("###  USD/JPY (no SMT partner)                   ###");
		System.out.println("###################################################");
		BacktestResult jpyResult = runner.run("USD_JPY", null, startDate, endDate, calendarCsv);
		printTradeLog(jpyResult, "USD/JPY");

		System.out.println("\n\n###################################################");
		System.out.println("###  COMBINED SUMMARY                           ###");
		System.out.println("###################################################");
		printSummaryLine("EUR/USD", eurResult);
		printSummaryLine("GBP/USD", gbpResult);
		printSummaryLine("AUD/USD", audResult);
		printSummaryLine("USD/JPY", jpyResult);

		double totalPips = eurResult.metrics().netPnlPips() + gbpResult.metrics().netPnlPips()
				+ audResult.metrics().netPnlPips() + jpyResult.metrics().netPnlPips();
		int totalTrades = eurResult.trades().size() + gbpResult.trades().size()
				+ audResult.trades().size() + jpyResult.trades().size();
		System.out.println("---------------------------------------------------");
		System.out.println("  TOTAL:   " + totalTrades + " trades, "
				+ String.format("%.1f", totalPips) + " pips combined");
		System.out.println("###################################################");
	}

	private static void printSummaryLine(String label, BacktestResult result) {
		System.out.println("  " + label + ": " + result.trades().size() + " trades, "
				+ String.format("%.1f", result.metrics().netPnlPips()) + " pips, "
				+ String.format("%.1f%%", result.metrics().winRate()) + " win rate");
	}

	private static void printTradeLog(BacktestResult result, String label) {
		System.out.println("\n-- TRADE LOG (" + label + ") --");
		if (result.trades().isEmpty()) {
			System.out.println("  No trades generated.");
			return;
		}
		int tradeNum = 1;
		for (SimulatedTrade trade : result.trades()) {
			if (trade.isClosed()) {
				System.out.printf("  #%d  %s %s %-14s entry=%d  exit=%d  P&L=%.1f pips  %s  [%s]%n",
						tradeNum++, trade.direction(), trade.instrument(), trade.killzone(),
						trade.entryPrice(), trade.exitPrice(), trade.pnlPips(),
						trade.isWin() ? "WIN" : "LOSS", trade.grade());
			}
		}
	}
}
