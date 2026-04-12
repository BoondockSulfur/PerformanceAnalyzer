# Changelog

All notable changes to PerformanceAnalyzer will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
