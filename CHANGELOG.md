# Changelog

All notable changes to PerformanceAnalyzer will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
