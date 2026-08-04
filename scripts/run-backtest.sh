#!/usr/bin/env bash
#
# run-backtest.sh — runs the Aletheia backtest and saves output to a file.
#
# Usage:
#   ./scripts/run-backtest.sh              (uses default memory, auto-named log)
#   ./scripts/run-backtest.sh my-test      (custom log name: my-test.txt)

set -e

# Use the first argument as the log name, or default to a timestamp
LOG_NAME="${1:-backtest_$(date +%Y%m%d_%H%M%S)}"
LOG_FILE="${LOG_NAME}.txt"

echo "Building project (skipping tests for speed)..."
./mvnw clean install -DskipTests -q

echo "Running backtest — output will be saved to: ${LOG_FILE}"
echo "This can take a while. Tailing the summary at the end."
echo ""

# Run the backtest, show output on screen AND save to file
./mvnw exec:java -pl aletheia-backtest -Dexec.jvmArgs="-Xmx12g" 2>&1 | tee "${LOG_FILE}"

echo ""
echo "Backtest complete. Full log saved to: ${LOG_FILE}"
echo "Combined summary:"
grep -A 10 "COMBINED SUMMARY" "${LOG_FILE}" || echo "(no summary found)"
