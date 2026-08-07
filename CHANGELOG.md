# Changelog

All notable changes to PerformanceAnalyzer will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [3.1.0] - 2026-08-07

Complete rewrite. PerformanceAnalyzer is now a pure performance plugin with an
honest measurement core, an incident-based root-cause engine and a real REST
API. The AntiCheat module moved into its own plugin, **BS-AntiCheat**.

### Changed
- **MSPT now means real tick work time (BREAKING for dashboards).** v2.x
  measured the *tick interval* — the time between scheduler runs, which is
  pinned to ~50 ms on any healthy server. That was the root cause of the
  permanent false alerts. v3 measures the actual per-tick work duration via
  Paper's `ServerTickEndEvent`: a healthy server now shows single-digit MSPT,
  and 50 ms is the deadline at which 20 TPS can no longer be held. Exported
  MSPT values will drop dramatically after upgrading — the new values are the
  correct ones.
- **Statistics over fixed time windows** (10 s / 60 s) with p50/p95/p99/max
  percentiles instead of fixed tick counts; a single spike is caught, not
  averaged away. TPS is counted exactly on Paper and estimated from the worst
  region on Folia.
- **Fixed alert thresholds replaced by a documented severity model**
  (`analysis/SeverityModel.java`): OK/NOTICE/WARNING/CRITICAL/EMERGENCY plus
  a transparent 0–100 score (45 pts sustained load vs. the 50 ms deadline,
  30 pts p95 tail latency, 25 pts GC pressure). `thresholds.mspt`,
  `thresholds.tps_drop` and `thresholds.heap_usage` are gone; only
  `thresholds.spike_tick_ms` and `thresholds.packet_flood_per_tick` remain.
- **One-shot "performance drops" became incidents** with an
  open → escalate → resolve lifecycle, duration tracking and a resolved
  notification. `/perfincidents` (aliases `pi`, `perfdrops`, `incidents`)
  replaces `/perfdrops`.
- **Root-cause attribution is ordered and confidence-labeled**: findings are
  marked MEASURED or HEURISTIC and checked GC → plugin listener timings →
  hot chunks → chunk generation/churn → entity hotspots, so a GC-caused
  spike is never blamed on redstone.
- **Plugin analysis measures real cost**: `timing/ListenerTimings.java` wraps
  other plugins' event handlers and accumulates actual milliseconds per
  plugin, replacing the `listeners*2 + tasks*3` registration-count risk score
  that reliably blamed the wrong plugin.
- **Chunk analysis counts activity, not existence** (`ActivityCounters`):
  per-chunk redstone firings, piston movements, hopper item moves, spawns and
  chunk loads/gens per second in rolling 10 s windows replace the static
  redstone/block estimator (idle contraptions cost nothing; a 10 Hz clock
  costs real time).
- **Detection decoupled from database logging**: the evaluation loop runs
  every second regardless of DB state (v2 detection ran once per minute as a
  side effect of DB logging and stopped when the DB was down).
- `/perfhistory` now queries asynchronously and clamps its range; its
  permission (`performance.history`) and `performance.gui` default to op.
- Alerts are severity-based with escalation and a resolved message,
  dispatched thread-safely to players with `performance.alerts`; Discord
  embeds are colored by severity and use new `discord.alert_types` keys:
  `incident_opened`, `incident_escalated`, `incident_resolved`,
  `packet_flood`, `performance`.
- Config migrated automatically to `config_version: 7` — obsolete keys are
  deleted, new keys added with safe defaults.

### Added
- **Adaptive baseline** (`analysis/Baseline.java`): EWMA over ~10 minutes of
  healthy samples (120-sample warmup); the severity model alerts on deviation
  from the server's *own* normal in addition to the hard 50 ms deadline. The
  baseline is only fed while the server is healthy, so an ongoing incident
  cannot poison it.
- **GC monitoring** (`metrics/GcSampler.java`): GC pause-time deltas, old-gen
  occupancy *after* the last collection (the only honest memory-pressure
  signal — 90 % live heap right before a young GC is normal) and metaspace.
- **Single-tick spike trigger**: one tick above `thresholds.spike_tick_ms`
  (default 100 ms) starts incident analysis immediately, rate-limited.
- **Real REST API** (`api/MetricsAPI.java`): `GET /api/health` (no auth),
  `/api/metrics`, `/api/incidents`, `/api/worlds` and Grafana-ready
  `/api/metrics/prometheus` (text exposition v0.0.4). Constant-time
  `Authorization: Bearer` check, `api.bind` defaults to `127.0.0.1`, and the
  server refuses to start while `api.key` is empty or `"changeme"`.
- **Folia support**: `folia-supported: true`; all tasks go through the
  region-aware schedulers (`platform/Scheduling.java`). Tick samples come
  from every region thread; TPS is a worst-region estimate on Folia.
- Fallback file logging when the database is unavailable
  (`database.fallback_file_logging`, `database.fallback_log_file`).
- JUnit 5 unit tests for the pure logic (Baseline, SeverityModel, TickStats)
  wired into the Maven build via Surefire.
- Anonymous [bStats](https://bstats.org/plugin/bukkit/PerformanceAnalyzer/32115)
  usage metrics (shaded & relocated; opt-out via `plugins/bStats/config.yml`).

### Fixed
- **The analyzer no longer freezes the server during lag**: the v2 analyzer
  took chunk snapshots and iterated worlds on the main thread mid-incident,
  blocking for seconds. The v3 engine attributes causes exclusively from
  already-collected data — no chunk snapshots, no main-thread blocking, no
  Bukkit access from HTTP or monitor threads.
- **Fixes from live verification on Paper 26.1.2, Paper 1.21.4 and Folia 26.2
  (2026-08-05 … 2026-08-07):**
  - Plugin crashed on enable when ProtocolLib was absent
    (`NoClassDefFoundError` — the presence check now runs before the first
    hook-class reference).
  - Folia fires no `ServerTickEndEvent`; the interval fallback recorded the
    ~50 ms tick *period* as work time and produced a permanent false WARNING.
    Healthy fallback samples now use a nominal value and `/perfstatus` labels
    the estimate mode.
  - Scheduled restarts raised a bogus CRITICAL/EMERGENCY on every shutdown
    (counted TPS collapses while worlds save) — evaluation is now suspended
    once `Bukkit.isStopping()` and during the new startup grace period
    (`performance.startup_grace_seconds`, default 60 s; metrics still run).
  - The baseline learned during NOTICE, letting sustained load ratchet the
    "normal" value upward until active incidents self-resolved; it now learns
    only while the severity is OK.
  - Entity-hotspot findings multiplied chunk coordinates by 16 twice
    (reported "-256, 0" instead of "-16, 0").
  - TPS windows are clamped to the sampler uptime (the first minute after
    boot divided by a window that had not filled yet and reported bogus low
    60 s TPS).
  - Incident open/escalate/resolve messages are always written to the console
    (WARNING-level incidents were chat-only and invisible on empty servers).
  - The shutdown config save runs only when the plugin itself changed the
    config — the unconditional save wiped `config.yml` edits admins made
    while the server was running.
  - Obsolete-key cleanup is now version-independent and idempotent: configs
    stamped with a matching `config_version` by older builds kept dead
    sections (the `anticheat:` block survived a 6→7 migration), and the
    removal list used a wrong key name
    (`thresholds.heap_usage` vs. `heap_usage_percent`).
  - `/perfhistory` results are additionally written to the server log for
    console/RCON senders (async replies never reach the RCON response).
  - Severity assessment reasons are localized (were hardcoded German).
- GUI refresh-task leak fixed; all GUIs now share a single registered
  listener (`GuiManager`) instead of per-GUI listeners and throwaway
  instances. GUI texts fully localized (en/de).
- Permanent false "high MSPT" alerts on idle servers (see the measurement
  change above).
- CI artifact upload glob matched nothing (`PerformanceAnalyzer-*.jar` vs.
  actual `performance-analyzer-*.jar`).

### Removed
- **AntiCheat module** — now the separate plugin **BS-AntiCheat**. Gone with
  it: `/acwhitelist`, `/xrayalerts`, `/xrayores`, `/movealerts`, the
  `performance.anticheat.*` permissions and the `anticheat.*` config section
  (deleted by migration).
- **Auto Entity Cleaner** (`entity_cleaner.*` config section).
- Static redstone estimator, `PerformanceDropAnalyzer` and the
  plugin "risk score" (replaced as described above).
- Fixed `thresholds.mspt` / `thresholds.tps_drop` / `thresholds.heap_usage`
  and the `messages.*` config section.
- `discord.alert_types.anticheat` (deleted by migration); the old
  `high_mspt`/`tps_drop`/`high_heap` Discord alert keys are no longer
  consulted (replaced by the incident-based keys).
- `docs/grafana-dashboard.json` — use the Prometheus endpoint with your own
  Grafana dashboards instead.

---

## [3.0.0] - 2026-06-17

Published on Modrinth (Paper/Purpur/Spigot, MC 1.21–1.21.3). Contained a
first portion of the v3 changes (REST API scaffolding, `config_version: 6`).
The source of this release was never committed to the repository — this
entry exists for the record; the full rework shipped as 3.1.0.

---

## [2.3.4] - 2026-05-07

### Fixed
- **Redstone False Positives in Performance Drop Analyzer**: Removed 26 non-performance-impacting blocks (buttons, pressure plates, lecterns, daylight detectors, tripwire, levers, targets) from `isRedstoneComponent()`. These blocks appear in naturally generated structures (villages, temples) and caused massive false positives through sampling extrapolation
- **Redstone Sampling Extrapolation**: Added minimum threshold (≥3 components found) before extrapolating redstone counts. Previously, a single naturally-generated button was multiplied by ×64, producing false "problematic redstone" reports
- **Wrong Coordinates in Problematic Chunk Reports**: Chunk coordinates are now displayed alongside block coordinates (`[Chunk X,Z | Blocks ~X,Z]`) for easier in-game navigation
- **NullPointerException in PerformanceDropAnalyzer**: `worldInfo.get("name").equals(...)` replaced with `Objects.equals()` to prevent NPE when world name is null
- **Swallowed Exceptions in Chunk Analysis**: ChunkSnapshot and synchronous chunk analysis errors are now logged at `FINE` level instead of being silently ignored
- **Unbounded Database Queue (OOM Risk)**: `DatabaseManager.logAsync()` now enforces a maximum queue size of 10,000 entries. Prevents `OutOfMemoryError` when the database is unavailable for extended periods
- **Excessive Block Checks in MovementChecker**: `isNearLiquid()` reduced from 27 block checks (3×3×3 cube) to 7 checks (current block + 6 adjacent faces), reducing CPU load per `PlayerMoveEvent` by ~74%
- **Duplicated Alert Cooldown Constants**: `AlertManager` and `MovementAlertManager` now use the centralized `Constants.ALERT_COOLDOWN_MS` instead of defining their own identical values
- **Silent Config Parsing Failures**: Invalid alert categories in `AlertPreferenceManager` now log a warning instead of being silently skipped
- **Thread-Safety in WorldStatsManager**: Trend history lists now use `Collections.synchronizedList()` to prevent concurrent modification issues

### Added
- `Constants.DB_MAX_QUEUE_SIZE` — centralized maximum queue size for database log entries

---

## [2.3.3] - 2026-04-12

### Fixed
- **Sneaking/Swimming/Climbing False Positives**: These movement types are now skipped entirely by the speed checker.
- **Silk Touch Ores Not Recognized as Self-Placed**: The player-placed block check now applies to ALL worlds, not just restricted worlds. Previously, silk-touching an ore block, placing it in your base, and breaking it would count toward XRay detection
- **Y-Level Pattern Analysis Removed**: Completely removed the Y-Level analysis feature. Mining at optimal Y-levels is normal gameplay (anyone can Google "best Y level for diamonds") and produced false positives

### Removed
- `analyzeYLevelPattern()`, `trackYLevel()`, `getOptimalYRange()` methods from XRayDetector
- `playerOreYLevels` tracking map from XRayDetector
- `xray_ylevel_high/medium/low` config entries and validation
- `XRAY_YLEVEL` violation type

---

## [2.3.2] - 2026-04-11

### Fixed
- **Race Condition in AlertManager**: Cooldown check now uses atomic `compareAndSet()` instead of separate `get()`/`set()`, preventing duplicate alerts under concurrent access
- **Race Condition in TickSampler**: Tick sampling now uses `idx.getAndUpdate()` for atomic index read-modify-write, preventing data corruption in the nanos array
- **Connection Pool Leak in DatabaseManager**: `shutdown()` now uses try-finally to ensure `HikariDataSource.close()` is always called, even if `flushBatchSafe()` throws an exception
- **NullPointerException in GUIs**: Added null-checks for `getItemMeta()` in all GUI classes (PerformanceGUI, AntiCheatGUI, LagAnalysisGUI, PerformanceDropsGUI) — follows the safe pattern already used in ConfigGUI
- **Thread-Safety in ViolationTracker**: `resetViolations(UUID, ViolationType)` now uses `computeIfPresent()` to prevent race condition where the PlayerViolations object could be removed between `get()` and `counts.remove()`
- **MovementChecker Violation Reset Too Aggressive**: Consecutive violation counters now only reset when speed is significantly below threshold (70%), preventing a single valid move from immediately washing out violations
- **XRay Y-Level Thresholds Too Aggressive**: Default thresholds raised from 75%/65%/55% to 85%/75%/65% to reduce false positives from legitimate caving
- **UpdateChecker Missing Field Validation**: Now checks for `version_number` field existence in Modrinth API response before accessing it
- **Inconsistent Activity Weights**: `PlayerActivityTracker.getTotalActivity()` now uses centralized `Constants.ACTIVITY_WEIGHT_*` values instead of hardcoded numbers

### Added
- **Configurable XRay Y-Level Thresholds** (`config.yml`)
  - `anticheat.xray_ylevel_high` (default: 0.85) — percentage for maximum suspicion
  - `anticheat.xray_ylevel_medium` (default: 0.75) — percentage for moderate suspicion
  - `anticheat.xray_ylevel_low` (default: 0.65) — percentage for low suspicion
  - Config validation ensures values are 0.0-1.0 and properly ordered (low < medium < high)
  - Auto-migration adds defaults for existing configs

### Changed
- `XRayDetector.analyzeYLevelPattern()` reads thresholds from config instead of using hardcoded values

---

## [2.3.1] - 2026-04-10

### Added
- **Silent Mode / Streamer Mode** (`/perfsilent`)
  - Toggle all alerts on/off: `/perfsilent`
  - Toggle per category: `/perfsilent xray`, `/perfsilent movement`, `/perfsilent performance`
  - Reset all preferences at once: `/perfsilent reset`
  - View current status: `/perfsilent list`
  - Persistent across server restarts (saved in `config.yml` under `alerts.silent_players`)
  - Aliases: `/ps`, `/silent`
  - Full tab-completion support
  - Bilingual: German & English language strings
- **AntiCheat DB Cleanup Commands**
  - `/movealerts clear <player> --db` — deletes movement violation entries from the database
  - `/xrayalerts clear <player> --db` — deletes XRay detection entries from the database
  - Without `--db`: only clears in-memory alerts (as before), now shows a hint about the `--db` option
  - Tab-completion for `--db` flag
- **Teleport Immunity for Movement Checks**
  - New `PlayerTeleportEvent` listener prevents false positives from legitimate teleports (`/tp`, `/home`, ender pearls, etc.)
  - 1-second grace period after any teleport where movement checks are skipped
  - `lastLocations` reset to teleport destination to prevent follow-up false positives
  - Consecutive violation counters reset on teleport

### Fixed
- **Version Inconsistency**: pom.xml, plugin.yml, and main class now use the same version dynamically via `getDescription().getVersion()` instead of hardcoded strings
- **Race Condition in AsyncConfigSaver**: `pendingSave` changed from `volatile boolean` to `AtomicBoolean` with proper atomic check-and-set operations, preventing lost config saves under concurrent requests
- **NullPointerException in PerformanceDropAnalyzer**: Added null-checks for `Player.getWorld()` and `Player.getLocation()` during world unload scenarios
- **Memory Leak in AlertManager**: Added periodic cleanup task (every 5 minutes) for stale `lastAlertTimes` entries that were never removed
- **AntiCheat False Positives**: Improved lag compensation from linear to square-root scaling (200ms ping = +10%, 500ms = +20%, 1000ms = +30%), reducing false positives for high-ping players without allowing extreme speeds

### Changed
- All three alert systems (`AlertManager`, `XRayAlertManager`, `MovementAlertManager`) now respect per-player alert preferences before sending chat notifications
- Config auto-migration adds `alerts.silent_players` for new installations

---

## [2.3.0] - 2026-02-22

### Added
- **Lag Compensation for Movement Checks**
  - Player ping considered in speed calculations (+10% per 100ms above 100ms)
  - Reduces false-positives for high-ping players
- **Knockback/Damage Immunity Detection**
  - 2-second immunity window after explosions and entity attacks
  - Prevents false-positives from legitimate knockback
- **Y-Level Analysis for XRay Detection**
  - Tracks mining height for all ores
  - Optimal Y-level ranges based on Minecraft 1.21
  - New detection method: `XRAY_YLEVEL`
  - Detects suspicious pattern: 75%+ mining at optimal height
- **Async Chunk Analysis**
  - Uses `ChunkSnapshot` for thread-safe analysis
  - Parallel processing with `CompletableFuture`
  - **90% less main-thread load** during performance drop analysis
  - Timeout protection maintained
- **Auto-Cleanup for Database**
  - Automatic retention policy (default: 30 days)
  - Runs daily (1h after startup, then every 24h)
  - Configurable: `database.retention_days` (set to 0 to disable)
- **GUI Auto-Refresh System**
  - Performance data updates every 3 seconds
  - Only active for open GUIs (no overhead)
  - Automatic cleanup on GUI close
  - Configurable: `gui.auto_refresh`
- **Trend Analysis for World Stats**
  - Historical tracking (up to 288 snapshots = 24h @ 5min intervals)
  - Trend direction: `INCREASING`, `DECREASING`, `STABLE`
  - Change rate calculation (entities/hour)
  - Methods: `recordSnapshot()`, `analyzeTrend()`, `analyzeTrendsForAllWorlds()`
- **REST API for External Monitoring**
  - 4 JSON endpoints: `/api/health`, `/api/metrics`, `/api/worlds`, `/api/trends`
  - API key authentication (`Authorization: Bearer`)
  - Perfect for Grafana/Prometheus dashboards
  - Config: `api.enabled`, `api.port`, `api.key`
- **Automatic Entity Cleaner**
  - Smart entity management to prevent lag
  - Priority-based removal (items → projectiles → monsters → animals)
  - Protection for named entities, tamed animals, villagers
  - Boss mob blacklist (Ender Dragon, Wither, Warden)
  - Per-world and per-chunk limits
  - Dry-run mode for safe testing
  - Config: `entity_cleaner.*`

### Changed
- **Movement Checker** (`MovementChecker.java`)
  - Added environmental checks (slime blocks, bubble columns)
  - 2x vertical speed allowance near slime blocks/bubble columns
  - Enhanced cleanup method to include new tracking maps
- **XRay Detector** (`XRayDetector.java`)
  - Added Y-level tracking for pattern analysis
  - New method: `analyzeYLevelPattern()`
  - New method: `getOptimalYRange()` with Minecraft 1.21 spawn data
  - Enhanced `checkSuspiciousPattern()` with Y-level analysis
- **Performance Drop Analyzer** (`PerformanceDropAnalyzer.java`)
  - Replaced synchronous chunk analysis with async processing
  - Added `analyzeChunkAsync()` method using ChunkSnapshot
  - Legacy `analyzeChunk()` kept as fallback
  - Improved error handling for async operations
- **Database Manager** (`DatabaseManager.java`)
  - Added auto-cleanup task for retention management
  - Enhanced shutdown procedure to cancel cleanup task
- **World Stats Manager** (`WorldStatsManager.java`)
  - Added trend tracking infrastructure
  - New records: `WorldStatsSnapshot`, `TrendAnalysis`
  - Multiple helper methods for trend calculation
- **Performance GUI** (`PerformanceGUI.java`)
  - Added auto-refresh system with task management
  - New `InventoryCloseEvent` handler for cleanup
  - New method: `shutdown()` for proper cleanup
- **Plugin Config** (`PluginConfig.java`)
  - Added 10 new config methods for features
  - Auto-migration for all new config entries

### Fixed
- Memory leaks from incomplete cleanup (movement checker, XRay detector)
- False-positives in movement detection from knockback
- False-positives in movement detection from environmental effects
- Main-thread bottleneck in chunk analysis

### Performance
- **90% reduction** in main-thread load during performance drop analysis
- Reduced false-positive alerts (less CPU overhead)
- More efficient memory usage with automatic cleanup

### Security
- REST API authentication with API keys
- SSRF protection in Discord webhook validation (from v2.2.0)
- SQL injection prevention (from v2.2.0)

---

## [2.2.0] - 2024-12-15

### Added
- **Security Hardening**
  - SQL Injection Prevention (type-safe TimeUnit enum)
  - SSRF Protection (Discord webhook validation)
  - Memory Leak Prevention (size limits, cleanup routines)
- **Graceful Degradation**
  - FallbackLogger for automatic file logging when DB unavailable
  - Queue-based async file writing
  - CSV format with timestamps
- **Modular Architecture**
  - CommandRegistry for cleaner command management
  - ConfigMigrator for upgrade path
  - AsyncConfigSaver for non-blocking saves
- **Constants Class**
  - Centralized all magic numbers
  - Better code maintainability

### Changed
- Database operations now type-safe
- Discord webhooks validated (HTTPS-only, domain check)
- Config saves now async (prevents main thread freezes)
- Movement checks use event sampling (~90% CPU reduction)

### Performance
- Database indexing on `timestamp` and `type` columns
- Async config saves
- Event sampling for movement detection

---

## Upgrade Guide

### From 2.3.3 to 2.3.4
1. Replace plugin JAR
2. Restart server
3. Redstone detection in Performance Drop Analyzer is now much more accurate — false positives from villages/temples are eliminated

**No breaking changes** - fully backward compatible.

### From 2.3.2 to 2.3.3
1. Replace plugin JAR
2. Restart server
3. Old `xray_ylevel_*` config entries can be safely removed (ignored if present)

**No breaking changes** - fully backward compatible.

### From 2.3.1 to 2.3.2
1. Replace plugin JAR
2. Restart server
3. Config auto-migrates (adds `anticheat.xray_ylevel_*` thresholds)
4. Optionally adjust Y-Level thresholds in `config.yml` for your server

**No breaking changes** - fully backward compatible.

### From 2.3.0 to 2.3.1
1. Replace plugin JAR
2. Restart server
3. Config auto-migrates (adds `alerts.silent_players`)
4. Admins can use `/perfsilent` to mute alerts

**No breaking changes** - fully backward compatible.

### From 2.2.0 to 2.3.0
1. Replace plugin JAR
2. Restart server
3. Config auto-migrates (adds new entries)
4. Configure new features in `config.yml` if desired
5. **Set API key** if enabling REST API (`api.key`)

**No breaking changes** - fully backward compatible.

### From 2.1.0 to 2.2.0
1. Replace plugin JAR
2. Restart server
3. Config auto-migrates
4. Review fallback logging settings

**No breaking changes** - fully backward compatible.

---

## Links

- [GitHub Repository](https://github.com/BoondockSulfur/PerformanceAnalyzer)
- [Issue Tracker](https://github.com/BoondockSulfur/PerformanceAnalyzer/issues)
- [Discussions](https://github.com/BoondockSulfur/PerformanceAnalyzer/discussions)
