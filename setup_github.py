import requests
import json
import time
import sys

# ── CHANGE THESE THREE LINES ─────────────────────────────────────
REPO_OWNER   = "Ckola99"
REPO_NAME    = "aletheia-hft"
GITHUB_TOKEN = ""
# ──────────────────────────────────────────────────────────────────

BASE_URL = f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}"
HEADERS  = {
    "Authorization": f"Bearer {GITHUB_TOKEN}",
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
}

def post(endpoint, payload):
    """Send a POST request to GitHub API."""
    r = requests.post(f"{BASE_URL}{endpoint}", headers=HEADERS, json=payload)
    time.sleep(0.3)  # small delay to avoid rate limiting
    return r

def get_existing(endpoint):
    """Get all items from a paginated GitHub API endpoint."""
    results = []
    page = 1
    while True:
        r = requests.get(
            f"{BASE_URL}{endpoint}?per_page=100&page={page}&state=all",
            headers=HEADERS
        )
        data = r.json()
        if not data or not isinstance(data, list):
            break
        results.extend(data)
        page += 1
    return results

LABELS = [
    # Milestone labels — dark colours, one per milestone
    {"name": "M0: Foundation",      "color": "0d1117", "description": "Project setup, structure, domain models"},
    {"name": "M1: Data Ingestion",  "color": "1e3a5f", "description": "OANDA feed, candle aggregation, TimescaleDB"},
    {"name": "M2: Strategy Engine", "color": "1a4731", "description": "ICT detectors: FVG, OB, Judas Swing"},
    {"name": "M3: SMT Divergence",  "color": "4a1942", "description": "Multi-instrument concurrent swing detection"},
    {"name": "M4: Calendar Guard",  "color": "7d4e00", "description": "Forex Factory scraper, news blackout"},
    {"name": "M5: Backtesting",     "color": "003d4f", "description": "Historical replay, performance metrics"},
    {"name": "M6: Live Execution",  "color": "5c1b00", "description": "OMS, OANDA REST, circuit breaker"},
    {"name": "M7: Cloud & DevOps",  "color": "00264d", "description": "Terraform, ECS, CI/CD, Grafana"},
    {"name": "M8: Paper Trading",   "color": "1a3300", "description": "Validation against live practice account"},

    # Type labels — what kind of work
    {"name": "type: feature",  "color": "0075ca", "description": "New functionality"},
    {"name": "type: test",     "color": "e4e669", "description": "Unit or integration tests"},
    {"name": "type: infra",    "color": "f9d0c4", "description": "Docker, Terraform, CI/CD"},
    {"name": "type: docs",     "color": "d4edda", "description": "Documentation and wiki"},
    {"name": "type: research", "color": "e99695", "description": "Investigation or spike"},

    # Priority labels
    {"name": "priority: critical", "color": "b60205", "description": "Must be done first"},
    {"name": "priority: high",     "color": "e4312b", "description": "Do this week"},
    {"name": "priority: medium",   "color": "fbca04", "description": "Do this milestone"},
    {"name": "priority: low",      "color": "0e8a16", "description": "Nice to have"},
]

def create_labels():
    print("\n── Creating Labels ─────────────────────────────────────")
    existing = {l["name"] for l in get_existing("/labels")}
    for label in LABELS:
        if label["name"] in existing:
            print(f"  ⏭  Exists: {label['name']}")
            continue
        r = post("/labels", label)
        if r.status_code in (200, 201):
            print(f"  ✅ Created: {label['name']}")
        else:
            print(f"  ❌ Failed: {label['name']} — {r.text[:80]}")


MILESTONES = [
    {"title": "M0: Foundation",               "description": "Project structure, Maven, Docker Compose, CI, core domain models"},
    {"title": "M1: Market Data Ingestion",     "description": "OANDA streaming, candle aggregation, TimescaleDB, historical data loader"},
    {"title": "M2: Core Strategy Engine",      "description": "FVG, Order Block, Market Structure, USDX Bias, Judas Swing — all TDD"},
    {"title": "M3: SMT Divergence Detection",  "description": "Multi-instrument concurrent swing registry, divergence detector, A+ grading"},
    {"title": "M4: Economic Calendar Guard",   "description": "Forex Factory scraper, news blackout service, backtest integration"},
    {"title": "M5: Backtesting Engine",        "description": "Event-driven replay, simulated execution, Sharpe/drawdown metrics"},
    {"title": "M6: Live Execution Engine",     "description": "OMS, OANDA REST, circuit breaker, kill switch"},
    {"title": "M7: Cloud & DevOps",            "description": "Dockerfiles, Terraform, full CI/CD, Grafana dashboards"},
    {"title": "M8: Paper Trading & Validation","description": "OANDA practice account, live vs backtest comparison"},
]

def create_milestones():
    print("\n── Creating Milestones ─────────────────────────────────")
    existing = {m["title"]: m["number"] for m in get_existing("/milestones")}
    ms_numbers = {}
    for ms in MILESTONES:
        if ms["title"] in existing:
            ms_numbers[ms["title"]] = existing[ms["title"]]
            print(f"  ⏭  Exists: {ms['title']}")
            continue
        r = post("/milestones", ms)
        if r.status_code in (200, 201):
            num = r.json()["number"]
            ms_numbers[ms["title"]] = num
            print(f"  ✅ Created: {ms['title']} (#{num})")
        else:
            print(f"  ❌ Failed: {ms['title']} — {r.text[:80]}")
    return ms_numbers


def create_issues(ms_numbers):
    print("\n── Creating Issues ─────────────────────────────────────")
    existing_titles = {i["title"] for i in get_existing("/issues")}

    def ms(title):
        return ms_numbers.get(title)

    issues = [
        # ── M0 ────────────────────────────────────────────────────
        {
            "title": "[M0] Set up Maven multi-module project structure",
            "body": "Create root parent POM with 8 child modules.\n\n**Done when:** `./mvnw clean test` compiles all modules and runs core tests green.",
            "labels": ["M0: Foundation", "type: feature", "priority: critical"],
            "milestone": ms("M0: Foundation")
        },
        {
            "title": "[M0] Implement core domain models in aletheia-core",
            "body": "Create immutable Java Records and Enums:\n- Tick (scaled long bid/ask)\n- Candle (OHLCV with isBullish, bodySize, upperWick helpers)\n- PriceScale (toScaled/toDouble conversion utility)\n- Timeframe, MarketBias, SwingType, SwingPoint, KillzoneWindow, ImpactLevel\n\n**Done when:** All classes compile, all unit tests pass.",
            "labels": ["M0: Foundation", "type: feature", "priority: critical"],
            "milestone": ms("M0: Foundation")
        },
        {
            "title": "[M0] Write unit tests for PriceScale and Candle",
            "body": "**PriceScaleTest:** scaling, round-trip, floating-point bug demo\n**CandleTest:** isBullish/bearish, bodySize, wicks, MarketBias.invert()\n\n**Done when:** `./mvnw test -pl aletheia-core` all green.",
            "labels": ["M0: Foundation", "type: test", "priority: critical"],
            "milestone": ms("M0: Foundation")
        },
        {
            "title": "[M0] Set up Docker Compose local dev environment",
            "body": "Create `docker/docker-compose.dev.yml` with:\n- TimescaleDB (port 5433)\n- Redis (port 6379)\n- Prometheus (port 9090)\n- Grafana (port 3000)\n- Adminer (port 8081)\n\nAlso create `sql/init/01_schema.sql`, `prometheus/prometheus.yml`, Grafana datasource provisioning.\n\n**Done when:** `docker compose up -d` starts all 5 containers healthy, tables visible in Adminer.",
            "labels": ["M0: Foundation", "type: infra", "priority: high"],
            "milestone": ms("M0: Foundation")
        },
        {
            "title": "[M0] Create GitHub Actions CI pipeline",
            "body": "Create `.github/workflows/ci.yml`:\n1. Checkout code\n2. Set up Java 21 (Temurin) with Maven cache\n3. Run `./mvnw clean verify -Dspring.profiles.active=test`\n4. Upload JaCoCo coverage report\n\n**Done when:** Push to main shows green checkmark on GitHub Actions tab.",
            "labels": ["M0: Foundation", "type: infra", "priority: high"],
            "milestone": ms("M0: Foundation")
        },
        {
            "title": "[M0] Create Spring Boot application entry point",
            "body": "Create `AletheiaApplication.java` in aletheia-api with `@SpringBootApplication` and `@EnableScheduling`.\nCreate `application.properties` and `application-test.properties`.\n\n**Done when:** `./mvnw clean verify` passes CI without repackage error.",
            "labels": ["M0: Foundation", "type: feature", "priority: high"],
            "milestone": ms("M0: Foundation")
        },

        # ── M1 ────────────────────────────────────────────────────
        {
            "title": "[M1] Create OANDA practice account and test API connectivity",
            "body": "Sign up at https://www.oanda.com/register/#/sign-up/demo\nGenerate API key, store in `.env` (gitignored).\n\n**Done when:** `curl` with the API key returns account details JSON.",
            "labels": ["M1: Data Ingestion", "type: research", "priority: critical"],
            "milestone": ms("M1: Market Data Ingestion")
        },
        {
            "title": "[M1] Implement OandaMultiFeedClient — persistent SSE streaming",
            "body": "Build persistent HTTP connection to OANDA streaming endpoint.\n- Subscribe to EUR_USD, GBP_USD, XAU_USD on same connection\n- Deserialise JSON ticks into Tick records\n- Exponential backoff reconnection (10s→20s→40s, cap 5min)\n- Emit FeedHealthEvent every 30s\n\n**Done when:** Integration test receives 100 ticks and converts all correctly.",
            "labels": ["M1: Data Ingestion", "type: feature", "priority: critical"],
            "milestone": ms("M1: Market Data Ingestion")
        },
        {
            "title": "[M1] Implement MultiTimeframeCandleAggregator",
            "body": "Maintain open candles for every (instrument × timeframe) pair simultaneously.\n- Candle boundaries align to calendar grid (15min closes at :00/:15/:30/:45)\n- On tick: update matching open candle OHLCV\n- On period expiry: publish CandleClosedEvent, open new candle\n- Thread-safe with ConcurrentHashMap\n\n**Done when:** Unit test with 200 ticks verifies correct candle close at boundary times.",
            "labels": ["M1: Data Ingestion", "type: feature", "priority: critical"],
            "milestone": ms("M1: Market Data Ingestion")
        },
        {
            "title": "[M1] Build Dukascopy historical tick data loader",
            "body": "Download and parse `.bi5` binary tick data from Dukascopy.\nConvert to Tick records, bulk-insert into TimescaleDB (batches of 10,000).\n\nNeed: EUR_USD, GBP_USD, XAU_USD for 2022-2024.\n\n**Done when:** `SELECT count(*) FROM ticks WHERE instrument='EUR_USD'` returns > 50M rows.",
            "labels": ["M1: Data Ingestion", "type: feature", "priority: high"],
            "milestone": ms("M1: Market Data Ingestion")
        },

        # ── M2 ────────────────────────────────────────────────────
        {
            "title": "[M2] Implement FairValueGapDetector with full TDD",
            "body": "**Rule:**\n- Bullish FVG: candle[i-1].high < candle[i+1].low\n- Bearish FVG: candle[i-1].low > candle[i+1].high\n\n**Tests FIRST:**\n- Bullish FVG detected\n- Bearish FVG detected\n- No FVG when candles exactly touch\n- Multiple FVGs in sequence\n- Fewer than 3 candles returns empty\n\n**Done when:** All tests green, works on any Timeframe.",
            "labels": ["M2: Strategy Engine", "type: feature", "type: test", "priority: critical"],
            "milestone": ms("M2: Core Strategy Engine")
        },
        {
            "title": "[M2] Implement OrderBlockDetector with full TDD",
            "body": "**Rule:**\n- Bullish OB: last bearish candle before bullish displacement\n- Bearish OB: last bullish candle before bearish displacement\n- Displacement = body > 1.5x 20-period ATR (configurable)\n\n**Done when:** All tests green, ATR multiplier is configurable.",
            "labels": ["M2: Strategy Engine", "type: feature", "type: test", "priority: critical"],
            "milestone": ms("M2: Core Strategy Engine")
        },
        {
            "title": "[M2] Implement MarketStructureAnalyser with full TDD",
            "body": "Identify swing highs/lows (N-candle lookback, configurable).\nClassify: HH+HL=BULLISH, LH+LL=BEARISH, mixed=NEUTRAL.\nDetect BOS and CHoCH.\n\n**Done when:** All structure scenarios tested and passing.",
            "labels": ["M2: Strategy Engine", "type: feature", "type: test", "priority: critical"],
            "milestone": ms("M2: Core Strategy Engine")
        },
        {
            "title": "[M2] Implement KillzoneService",
            "body": "Classify ZonedDateTime into KillzoneWindow.\n- London Open: 02:00-05:00 EST\n- NY Open: 07:00-10:00 EST\n- London Close: 10:00-12:00 EST\n- Handle daylight saving with ZoneId(\"America/New_York\")\n\n**Done when:** Tests cover each window plus DST transition.",
            "labels": ["M2: Strategy Engine", "type: feature", "type: test", "priority: high"],
            "milestone": ms("M2: Core Strategy Engine")
        },
        {
            "title": "[M2] Implement UsdxBiasEngine",
            "body": "Run MarketStructureAnalyser on Monthly/Weekly/Daily USDX candles.\n- All agree → HIGH confidence\n- Two agree → MEDIUM\n- None → NEUTRAL\n\nInvert for pairs: USDX BULLISH → EUR/USD BEARISH.\n\n**Done when:** All confidence scenarios tested.",
            "labels": ["M2: Strategy Engine", "type: feature", "type: test", "priority: high"],
            "milestone": ms("M2: Core Strategy Engine")
        },
        {
            "title": "[M2] Implement JudasSwingDetector with integration test",
            "body": "Full pre-condition chain:\n1. Inside Killzone\n2. HTF bias directional\n3. No news blackout\n4. Price near HTF PD Array\n5. LTF liquidity sweep against bias\n6. Displacement candle in true direction\n7. LTF FVG created = entry zone\n\n**Done when:** Full scenario integration test passes. Failure scenarios tested.",
            "labels": ["M2: Strategy Engine", "type: feature", "type: test", "priority: critical"],
            "milestone": ms("M2: Core Strategy Engine")
        },
        {
            "title": "[M2] Implement SignalAggregator — the A+ checklist",
            "body": "Combines all detectors. Returns Optional.empty() if ANY pillar fails:\n1. USDX bias HIGH confidence\n2. Killzone active\n3. News clear\n4. HTF PD Array present\n5. Judas Swing confirmed\n\n**Done when:** Each pillar independently rejects. Full pass generates TradeSignal.",
            "labels": ["M2: Strategy Engine", "type: feature", "type: test", "priority: critical"],
            "milestone": ms("M2: Core Strategy Engine")
        },

        # ── M3 ────────────────────────────────────────────────────
        {
            "title": "[M3] Build SwingPointRegistry — thread-safe concurrent state",
            "body": "Track last N swings per (instrument × timeframe) using ConcurrentHashMap with immutable SwingSnapshot records.\n\nAtomic put: readers always see a complete snapshot, never partial update.\n\n**Done when:** Concurrency test with two writer threads and one reader passes.",
            "labels": ["M3: SMT Divergence", "type: feature", "priority: critical"],
            "milestone": ms("M3: SMT Divergence Detection")
        },
        {
            "title": "[M3] Implement SmtDivergenceDetector with full TDD",
            "body": "**Bullish SMT:** GBP/USD makes Lower Low, EUR/USD fails to follow.\n**Bearish SMT:** GBP/USD makes Higher High, EUR/USD fails to follow.\n**Temporal alignment:** swings must be within 30 minutes of each other.\n\n**Tests:**\n- Bullish SMT detected\n- Bearish SMT detected\n- No signal when both make new extreme\n- No signal when swings >30min apart\n- No signal outside killzone\n\n**Done when:** All 5 tests pass.",
            "labels": ["M3: SMT Divergence", "type: feature", "type: test", "priority: critical"],
            "milestone": ms("M3: SMT Divergence Detection")
        },
        {
            "title": "[M3] Integrate SMT into JudasSwingDetector for A+ grading",
            "body": "SMT present → SignalGrade.A_PLUS\nSMT absent → SignalGrade.A\nPersist grade in trades table.\n\n**Done when:** Integration test produces A_PLUS signal with SMT confirmation.",
            "labels": ["M3: SMT Divergence", "type: feature", "priority: high"],
            "milestone": ms("M3: SMT Divergence Detection")
        },

        # ── M4 ────────────────────────────────────────────────────
        {
            "title": "[M4] Implement ForexFactoryCalendarScraper using Jsoup",
            "body": "Scrape https://www.forexfactory.com/calendar\nParse rows into EconomicEvent records.\nRun on @Scheduled cron (06:00 + 18:00 UTC).\nUPSERT to database (idempotent).\nFail gracefully on scrape errors.\n\nTest with saved HTML fixture (no real HTTP in tests).\n\n**Done when:** Unit test parses fixture and extracts ≥5 HIGH impact events.",
            "labels": ["M4: Calendar Guard", "type: feature", "type: test", "priority: critical"],
            "milestone": ms("M4: Economic Calendar Guard")
        },
        {
            "title": "[M4] Implement EconomicCalendarService with in-memory cache",
            "body": "volatile List cache refreshed every 10 minutes.\nisNewsBlackout(now, instrument) — pure in-memory, called on every tick.\n±15 min blackout around HIGH impact events.\nCurrency-to-instrument mapping.\n\n**Done when:** 4 test scenarios pass (future event, past event, clear, currency filter).",
            "labels": ["M4: Calendar Guard", "type: feature", "type: test", "priority: critical"],
            "milestone": ms("M4: Economic Calendar Guard")
        },

        # ── M5 ────────────────────────────────────────────────────
        {
            "title": "[M5] Build event-driven BacktestEngine",
            "body": "Replay historical ticks through the same strategy pipeline used in live trading.\nStrategy must NOT know it is in backtest.\nAlso replay historical economic_events for news guard.\n\n**Done when:** Backtest runs for 2023 EUR_USD and prints a trade summary.",
            "labels": ["M5: Backtesting", "type: feature", "priority: critical"],
            "milestone": ms("M5: Backtesting Engine")
        },
        {
            "title": "[M5] Implement performance metrics report",
            "body": "After backtest: compute Net P&L, Win Rate, Profit Factor, Max Drawdown, Sharpe Ratio, Avg R:R.\nSplit by signal grade (A+ vs A).\nOutput as JSON.\n\n**Done when:** JSON report generated with all metrics.",
            "labels": ["M5: Backtesting", "type: feature", "priority: high"],
            "milestone": ms("M5: Backtesting Engine")
        },

        # ── M6 ────────────────────────────────────────────────────
        {
            "title": "[M6] Build Order Management System (OMS)",
            "body": "Track lifecycle: PENDING→FILLED→PARTIAL→CLOSED.\nPartial TP: close 70% at TP1, 30% runner.\nSL to breakeven after TP1.\nMax 2 concurrent positions.\n\n**Done when:** Unit test simulates full trade lifecycle.",
            "labels": ["M6: Live Execution", "type: feature", "priority: critical"],
            "milestone": ms("M6: Live Execution Engine")
        },
        {
            "title": "[M6] Implement OandaOrderExecutor with circuit breaker",
            "body": "OANDA REST API: createLimitOrder, createMarketOrder, modifyOrder, closePosition.\nResilience4j circuit breaker: 3 failures in 60s → open circuit.\n\n**Done when:** Integration test verifies circuit breaker opens after 3 failures.",
            "labels": ["M6: Live Execution", "type: feature", "priority: critical"],
            "milestone": ms("M6: Live Execution Engine")
        },
        {
            "title": "[M6] Implement kill switch",
            "body": "On activation: cancel pending orders → close all positions → stop engine → log event.\nExpose via POST /admin/kill-switch (secured JWT).\n\n**Done when:** Call endpoint → all positions closed within 5 seconds.",
            "labels": ["M6: Live Execution", "type: feature", "priority: high"],
            "milestone": ms("M6: Live Execution Engine")
        },

        # ── M7 ────────────────────────────────────────────────────
        {
            "title": "[M7] Write production Dockerfiles (multi-stage builds)",
            "body": "docker/Dockerfile.engine: build stage (JDK) → runtime stage (JRE, non-root user, ZGC).\ndocker/Dockerfile.calendar: lightweight run-and-exit container.\n\n**Done when:** `docker build` succeeds, container responds on /health.",
            "labels": ["M7: Cloud & DevOps", "type: infra", "priority: critical"],
            "milestone": ms("M7: Cloud & DevOps")
        },
        {
            "title": "[M7] Write Terraform modules for AWS infrastructure",
            "body": "Provision: VPC, ECS Fargate, RDS TimescaleDB, ElastiCache Redis, Secrets Manager, ECR, ALB, EventBridge (calendar cron), IAM.\n\n**Done when:** `terraform plan` shows clean plan with no errors.",
            "labels": ["M7: Cloud & DevOps", "type: infra", "priority: critical"],
            "milestone": ms("M7: Cloud & DevOps")
        },
        {
            "title": "[M7] Build full GitHub Actions CI/CD pipeline",
            "body": "PR: lint→test→coverage gate (≥70%)→docker build\nMain: +push to ECR→terraform apply→ECS deploy→smoke test\n\n**Done when:** Push to main deploys to ECS with green smoke test.",
            "labels": ["M7: Cloud & DevOps", "type: infra", "priority: critical"],
            "milestone": ms("M7: Cloud & DevOps")
        },
        {
            "title": "[M7] Build four Grafana dashboards",
            "body": "1. System Health (latency, throughput, GC, heap)\n2. Trading Performance (equity curve, win rate by grade, drawdown)\n3. Market Intelligence (prices, SMT annotations, killzone bands)\n4. Calendar & News Guard (events timeline, suppressed signals)\n\n**Done when:** All 4 load in Grafana with real data.",
            "labels": ["M7: Cloud & DevOps", "type: infra", "priority: high"],
            "milestone": ms("M7: Cloud & DevOps")
        },

        # ── M8 ────────────────────────────────────────────────────
        {
            "title": "[M8] Run paper trading on OANDA practice account for 4 weeks",
            "body": "Deploy full system to AWS connected to practice account.\nMonitor: signal timing, SMT accuracy, news guard, slippage.\nKeep trade journal in docs/PAPER_TRADING_JOURNAL.md.\n\n**Done when:** 4 weeks of data collected, journal complete.",
            "labels": ["M8: Paper Trading", "type: research", "priority: high"],
            "milestone": ms("M8: Paper Trading & Validation")
        },
        {
            "title": "[M8] Compare paper trading vs backtest — validation report",
            "body": "Compare: live win rate vs backtested, live profit factor vs backtested, actual slippage vs assumed.\nSave as docs/VALIDATION_REPORT.md.\n\n**Done when:** Report written with all comparisons.",
            "labels": ["M8: Paper Trading", "type: docs", "priority: high"],
            "milestone": ms("M8: Paper Trading & Validation")
        },
    ]

    for issue in issues:
        if issue["title"] in existing_titles:
            print(f"  ⏭  Exists: {issue['title'][:60]}")
            continue
        payload = {
            "title": issue["title"],
            "body": issue.get("body", ""),
            "labels": issue.get("labels", []),
        }
        if issue.get("milestone"):
            payload["milestone"] = issue["milestone"]
        r = post("/issues", payload)
        if r.status_code in (200, 201):
            num = r.json()["number"]
            print(f"  ✅ #{num}: {issue['title'][:65]}")
        else:
            print(f"  ❌ Failed: {issue['title'][:55]} — {r.text[:80]}")


if __name__ == "__main__":
    if GITHUB_TOKEN == "paste_your_token_here":
        print("Set your GITHUB_TOKEN before running.")
        sys.exit(1)

    print("=" * 60)
    print("  Aletheia GitHub Project Setup")
    print("=" * 60)

    # Check connection
    r = requests.get(f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}", headers=HEADERS)
    if r.status_code != 200:
        print(f"Cannot access repo: {r.status_code}")
        sys.exit(1)
    print(f"Connected to {REPO_OWNER}/{REPO_NAME}")

    create_labels()
    ms_numbers = create_milestones()
    create_issues(ms_numbers)

    print("\n" + "=" * 60)
    print("  Done!")
    print(f"  Issues:     https://github.com/{REPO_OWNER}/{REPO_NAME}/issues")
    print(f"  Milestones: https://github.com/{REPO_OWNER}/{REPO_NAME}/milestones")
    print("=" * 60)
