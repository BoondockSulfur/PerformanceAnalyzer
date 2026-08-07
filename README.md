# PerformanceAnalyzer

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.x-green.svg)](https://www.minecraft.net/)
[![Paper](https://img.shields.io/badge/Paper-1.21.4%2B-blue.svg)](https://papermc.io/)
[![Folia](https://img.shields.io/badge/Folia-supported-blueviolet.svg)](https://papermc.io/software/folia)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)

**Performance monitoring and root-cause analysis plugin for Paper and Folia 1.21.x servers.**

PerformanceAnalyzer measures the real work your server does each tick, learns what "normal" looks like on *your* hardware, and — when things go wrong — opens an incident that tells you *why*: GC pressure, a slow plugin, a hot redstone/hopper chunk, chunk generation churn, or an entity hotspot.

> Looking for the AntiCheat that used to live here? It was split out into its own plugin, **BS-AntiCheat**, in v3.1.0. PerformanceAnalyzer now does one job: performance.

---

## ⚠️ MSPT means something different now (v3.1.0)

Up to v2.x the plugin measured the **tick interval** — the time *between* ticks, which is pinned to ~50 ms on any healthy server. That made an idle server look permanently "at the limit" and was the root cause of the constant false alerts.

Since v3.1.0 every MSPT value is the **real work time per tick**, taken from Paper's `ServerTickEndEvent`:

- A healthy server shows **single-digit** values (2–10 ms), not ~50 ms.
- **50 ms is the deadline**: at 50 ms of work per tick the server can no longer hold 20 TPS.
- Statistics are computed over fixed **10 s / 60 s time windows** with p50 / p95 / p99 / max percentiles — a single 200 ms spike is caught, not averaged away.

If you exported v2.x MSPT values to dashboards, expect the numbers to drop dramatically after upgrading. The new numbers are the correct ones.

---

## ✨ Key Features

- 📊 **Honest tick measurement** — real per-tick work duration via `ServerTickEndEvent`, fixed 10s/60s windows, p50/p95/p99/max percentiles, exact counted TPS on Paper
- 🧠 **Adaptive baseline** — the server learns its own normal tick time; a server that idles at 5 ms alerts far earlier than one that idles at 30 ms
- 🚦 **Documented severity model** — OK / NOTICE / WARNING / CRITICAL / EMERGENCY with a transparent 0–100 score (see below)
- 🔍 **Incident engine** — incidents open, escalate and resolve as a lifecycle (with duration and a "resolved" notification), instead of one-shot "drop" spam
- 🧾 **Findings with confidence** — every attributed cause is labeled **MEASURED** or **HEURISTIC**, checked in order: GC → plugin listeners → hot chunks → chunk generation/churn → entity hotspots
- ♻️ **GC monitoring** — GC pause deltas, old-gen occupancy *after* collection (the only honest memory-pressure signal), metaspace
- ⚡ **Single-tick spike detection** — one tick above `thresholds.spike_tick_ms` triggers immediate analysis
- 🔥 **Per-chunk activity rates** — redstone firings, piston movements, hopper item moves, spawns, chunk loads/gens per second in 10 s windows (what *fires*, not what merely exists)
- ⏱️ **Real per-plugin listener timing** — measures actual milliseconds spent in other plugins' event handlers (replaces the old registration-count "risk score")
- 🌐 **REST API** — Bearer-authenticated JSON endpoints plus a Prometheus exposition endpoint, Grafana-ready
- 🧵 **Folia support** — `folia-supported: true`, all scheduling through region-aware schedulers, no illegal cross-thread access
- 🔇 **Silent Mode** — per-player alert muting (persistent across restarts)
- 🎨 **Interactive GUIs** — localized (English/German), single shared listener, leak-free auto-refresh
- 💾 **Database logging** — SQLite/MySQL via HikariCP, automatic retention cleanup, file fallback when the DB is down
- 🔔 **Discord alerts** — severity-colored embeds for incident opened/escalated/resolved, rate-limited
- 🔄 **Update checker** — Modrinth integration, release-only, async

**The analyzer never makes lag worse.** The v2 analyzer took chunk snapshots on the main thread during lag and could freeze the server for seconds. The v3 engine attributes causes exclusively from data that is already collected — no chunk snapshots, no main-thread blocking, ever.

---

## 🚀 Quick Start

### Installation
1. Download the latest release
2. Place the JAR in your `plugins/` folder
3. Restart the server
4. Configure in `plugins/PerformanceAnalyzer/config.yml`

### Requirements
- **Server**: Paper or Folia 1.21.4+
- **Java**: 21 or higher

### Optional Dependencies
- [Spark](https://spark.lucko.me/) — profiler integration
- [ProtocolLib](https://www.spigotmc.org/resources/protocollib.1997/) — packet-flood detection
- **BS-AntiCheat** — the former AntiCheat module, now a separate plugin

### Metrics
PerformanceAnalyzer collects anonymous usage statistics via
[bStats](https://bstats.org/plugin/bukkit/PerformanceAnalyzer/32115)
(server count, MC/Java version, player count — no personal data, nothing
about your performance numbers). Opt out globally for all plugins in
`plugins/bStats/config.yml`.

---

## 📖 Commands & Permissions

| Command | Aliases | Description | Permission | Default |
|---------|---------|-------------|------------|---------|
| `/perfstatus` | – | Live severity, score, TPS/MSPT percentiles, GC, baseline | `performance.status` | everyone |
| `/perfhistory [minutes]` | – | Aggregated history (async, clamped range) | `performance.history` | op |
| `/perfgui` | – | Interactive GUI dashboard | `performance.gui` | op |
| `/perfincidents [n\|clear]` | `pi`, `perfdrops`, `incidents` | Recent incidents with causes and severity | `performance.admin` | op |
| `/worldstats [world]` | `ws` | Per-world statistics | `performance.admin` | op |
| `/entitystats [world\|hotspots]` | `es` | Entity analysis | `performance.admin` | op |
| `/chunkstats [problems\|frequent\|clear]` | `cs` | Chunk statistics | `performance.admin` | op |
| `/perfsilent [all\|performance\|list]` | `ps`, `silent` | Toggle alert notifications (streamer mode) | `performance.admin` | op |
| `/perfreload` | – | Reload configuration | `performance.admin` | op |

Additional permission: `performance.alerts` (default op) — receives in-game incident alerts.

`/perfdrops` from v2.x still works as an alias of `/perfincidents`.

---

## 🚦 Severity Model

Severity levels are derived from measured values against hard limits **plus** deviation from the learned baseline:

| Level | Meaning |
|-------|---------|
| **OK** | Everything within normal bounds |
| **NOTICE** | Noticeable but harmless (e.g. a single ≥150 ms tick spike, or clearly above the learned normal) |
| **WARNING** | Players can start to feel it (p95 ≥ 50 ms, avg ≥ 35 ms, GC ≥ 3 s/min or a ≥200 ms pause, or avg more than 2× the learned normal) |
| **CRITICAL** | 20 TPS no longer holdable (avg ≥ 50 ms or TPS < 17, or old-gen ≥ 90 % after GC) |
| **EMERGENCY** | Severely degraded (TPS < 10 or avg ≥ 100 ms) |

The **0–100 score** is a weighted sum: up to **45 points** sustained load (avg MSPT vs. the 50 ms deadline), up to **30 points** tail latency (p95 vs. 100 ms), up to **25 points** GC pressure (GC time per minute + old-gen occupancy after GC). The formula lives in `analysis/SeverityModel.java` and is deliberately simple enough to reason about.

The baseline is only updated while the server is healthy, so an ongoing incident can never poison it ("the lag is normal now").

---

## ⚙️ Configuration Reference

```yaml
config_version: 7        # internal, do not edit
language: en             # en | de

database:
  type: sqlite           # sqlite | mysql
  sqlite_file: "plugins/PerformanceAnalyzer/perf.db"
  host: localhost        # MySQL only
  port: 3306
  name: performance
  user: perfuser
  password: ""
  pool:                  # HikariCP
    max_pool_size: 10
    minimum_idle: 2
    connection_timeout_ms: 10000
  retention_days: 30     # automatic cleanup
  fallback_file_logging: true
  fallback_log_file: "plugins/PerformanceAnalyzer/fallback.log"

performance:
  log_interval_seconds: 60   # DB logging interval (detection always runs every second)
  enable_profiling: true     # Spark integration
  packet_analysis: true      # requires ProtocolLib
  debug_mode: false

lag_analysis:
  player_tracking: true      # sampled player-activity tracking
  plugin_analysis: true      # real per-plugin listener timing
  chunk_analysis_timeout_ms: 5000
  chunk_tile_entities_threshold: 10
  chunk_redstone_threshold: 30
  chunk_entity_warning: 50
  chunk_entity_critical: 100
  world_entity_warning: 5000
  world_entity_critical: 10000
  plugin_risk_low: 50
  plugin_risk_medium: 100

thresholds:
  # v3.1.0: fixed MSPT/TPS/heap thresholds are GONE - severity comes from
  # the severity model + learned baseline. Only two knobs remain:
  spike_tick_ms: 100.0         # single tick above this -> immediate analysis
  packet_flood_per_tick: 1000.0  # 0 disables

discord:
  enabled: false
  webhook_url: ""
  alert_types:
    incident_opened: true
    incident_escalated: true
    incident_resolved: true
    packet_flood: true
    performance: true

alerts:
  silent_players: []     # managed via /perfsilent - do not edit manually

gui:
  auto_refresh: true

api:
  enabled: false
  bind: "127.0.0.1"      # safe default: this machine only
  port: 8080
  key: ""                # e.g. openssl rand -hex 32
```

Configs from v2.x are migrated automatically (`config_version` 7): obsolete keys (`anticheat.*`, `entity_cleaner.*`, `thresholds.mspt`, `thresholds.tps_drop`, `thresholds.heap_usage`, `messages.*`, `discord.alert_types.anticheat`) are deleted, new keys are added with safe defaults.

---

## 🌐 REST API

Disabled by default. Binds to `127.0.0.1` — only expose it further behind a TLS-terminating reverse proxy, because the API itself speaks plain HTTP. **The server refuses to start while `api.key` is empty or `"changeme"`.**

| Endpoint | Auth | Description |
|----------|------|-------------|
| `GET /api/health` | No | Liveness probe: status, TPS, severity, active incident flag |
| `GET /api/metrics` | Yes | Full snapshot: tick percentiles, GC, severity + score + reasons, baseline, plugin loads, activity rates |
| `GET /api/incidents` | Yes | Active incident (or `null`) + recent incidents with findings |
| `GET /api/worlds` | Yes | Cached per-world stats (Paper) + measured hot chunks |
| `GET /api/metrics/prometheus` | Yes | Prometheus text exposition (v0.0.4) |

Authentication is a constant-time `Authorization: Bearer` check:

```bash
# Liveness (no auth)
curl http://127.0.0.1:8080/api/health

# Full metrics snapshot
curl -H "Authorization: Bearer YOUR_API_KEY" http://127.0.0.1:8080/api/metrics

# Incidents with root-cause findings
curl -H "Authorization: Bearer YOUR_API_KEY" http://127.0.0.1:8080/api/incidents
```

### Prometheus / Grafana

Point a Prometheus scrape job at `/api/metrics/prometheus`:

```yaml
scrape_configs:
  - job_name: minecraft
    metrics_path: /api/metrics/prometheus
    authorization:
      type: Bearer
      credentials: YOUR_API_KEY
    static_configs:
      - targets: ["127.0.0.1:8080"]
```

Exported gauges include `performanceanalyzer_tps{window="10s|60s"}`, `performanceanalyzer_mspt{stat="avg|p50|p95|p99|max"}`, `performanceanalyzer_severity_score`, `performanceanalyzer_gc_time_ms_60s`, `performanceanalyzer_oldgen_after_gc_percent`, `performanceanalyzer_heap_used_percent`, per-plugin `performanceanalyzer_plugin_listener_ms_per_sec` and `performanceanalyzer_activity{type=...}`. Remember: MSPT here is real tick work time — healthy is single digits.

---

## 🧵 Folia Notes

PerformanceAnalyzer declares `folia-supported: true` and schedules everything through the region-aware schedulers (Global/Region/Async/EntityScheduler).

Semantics differ on Folia because there is no single main thread:

- **Tick samples come from every region thread.** The MSPT distribution spans all regions; `p95`/`max` reflect the slowest regions.
- **TPS is a worst-region estimate** derived from the p95 tick duration — there is no meaningful single global TPS on Folia. On Paper, TPS is exact (counted ticks per window). API consumers can check the `folia` and `event_driven` flags in `/api/metrics`.
- **Paper-only features:** the one-shot **entity hotspot sweep** during incidents and the periodic **world snapshots** (`/api/worlds` worlds array, `/worldstats` full data) require a global world scan and are skipped on Folia. The measured per-chunk **activity rates (hot chunks) work everywhere** and are the fallback signal on Folia.

---

## 🔧 Building from Source

```bash
git clone https://github.com/BoondockSulfur/PerformanceAnalyzer.git
cd PerformanceAnalyzer
mvn clean package
# shaded JAR: target/performance-analyzer-<version>.jar
```

**Requirements:** Java 21 JDK, Maven 3.8+. Unit tests (JUnit 5) run as part of the build.

---

## 📜 License

This project is licensed under the MIT License — see [LICENSE](LICENSE) for details.

---

## 🙏 Acknowledgments

- [Paper Team](https://papermc.io/) for `ServerTickEndEvent` and the region-aware schedulers
- [Spark](https://spark.lucko.me/) for profiling integration
- [HikariCP](https://github.com/brettwooldridge/HikariCP) for database pooling

---

## 📧 Support

- **Discord**: [Join our Discord](https://discord.gg/xEJjF65K46) — get help, share feedback, discuss features
- **Issues**: [GitHub Issues](https://github.com/BoondockSulfur/PerformanceAnalyzer/issues)
- **Discussions**: [GitHub Discussions](https://github.com/BoondockSulfur/PerformanceAnalyzer/discussions)

---

**Made with ❤️ for the Minecraft community**
