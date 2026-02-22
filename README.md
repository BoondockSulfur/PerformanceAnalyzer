# PerformanceAnalyzer

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.x-green.svg)](https://www.minecraft.net/)
[![Paper](https://img.shields.io/badge/Paper-1.21.4--1.21.10-blue.svg)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/)

**Advanced performance monitoring and analysis plugin for Minecraft Paper 1.21.x servers.**

PerformanceAnalyzer provides comprehensive server performance tracking, automatic lag detection, intelligent entity management, and a powerful REST API for external monitoring tools.

---

## ✨ Key Features

- 📊 **Real-time Performance Monitoring** - TPS, MSPT, Memory with Spark integration
- 🔍 **Intelligent Lag Detection** - Automatic cause analysis for performance drops
- 🛡️ **AntiCheat Module** - Movement & XRay detection with Y-level analysis
- 🌐 **REST API** - JSON endpoints for Grafana/Prometheus integration
- 🧹 **Auto Entity Cleaner** - Smart entity management to prevent lag
- 📈 **Trend Analysis** - Historical tracking with pattern recognition
- 🎨 **Interactive GUIs** - Multi-page dashboard with auto-refresh
- 💾 **Database Logging** - SQLite/MySQL with automatic retention management
- 🔔 **Discord Alerts** - Real-time notifications with rate-limiting
- 🔄 **Auto Update Checker** - Modrinth integration for version notifications

---

## 🚀 Quick Start

### Installation
1. Download the latest release
2. Place JAR in `plugins/` folder
3. Restart server
4. Configure in `plugins/PerformanceAnalyzer/config.yml`

### Requirements
- **Minecraft**: Paper 1.21.4 - 1.21.10
- **Java**: 21 or higher

### Optional Dependencies
- [Spark](https://spark.lucko.me/) - Enhanced profiling (recommended)
- [ProtocolLib](https://www.spigotmc.org/resources/protocollib.1997/) - Packet analysis
- [LuckPerms](https://luckperms.net/) - Group-based permissions

---

## 📖 Documentation

### Commands

| Command | Description | Permission |
|---------|-------------|-----------|
| `/perfgui` | Open main GUI | `performance.gui` |
| `/perfstatus` | Live performance data | `performance.status` |
| `/perfdrops` | View recent lag spikes | `performance.admin` |
| `/worldstats` | Per-world statistics | `performance.admin` |
| `/entitystats` | Entity analysis | `performance.admin` |
| `/chunkstats` | Chunk performance | `performance.admin` |
| `/perfanalyzer reload` | Reload config | `performance.admin` |

### Configuration Example

```yaml
# Performance Monitoring
performance:
  log_interval_seconds: 60
  mspt_threshold: 50.0
  heap_threshold: 80.0

# Database (auto-cleanup enabled)
database:
  type: sqlite
  retention_days: 30

# REST API
api:
  enabled: true
  port: 8080
  key: "your-secure-api-key"

# Auto Entity Cleaner
entity_cleaner:
  enabled: true
  dry_run: true  # Test mode
  world_limit: 5000
  chunk_limit: 100

# AntiCheat
anticheat:
  enabled: true
  movement_checks: true
  xray_detection: true
```

### REST API

#### Authentication
```bash
curl -H "Authorization: Bearer YOUR_API_KEY" \
  http://localhost:8080/api/metrics
```

#### Endpoints

| Endpoint | Auth | Description |
|----------|------|-------------|
| `GET /api/health` | No | Health check |
| `GET /api/metrics` | Yes | TPS, MSPT, Memory |
| `GET /api/worlds` | Yes | World statistics |
| `GET /api/trends` | Yes | Trend analysis |

#### Response Example
```json
{
  "timestamp": 1708532400000,
  "tps": 19.85,
  "mspt": {"avg": 45.23, "p95": 48.67},
  "memory": {
    "heap_used_mb": 2048,
    "heap_max_mb": 4096,
    "heap_usage_percent": 50.0
  }
}
```

---

## 🎯 Advanced Features

### Performance Drop Analyzer
- Automatically detects TPS drops and MSPT spikes
- Identifies problematic chunks with high redstone/tile entities
- **Async chunk analysis** (90% less main-thread load)
- Tracks active players and suspicious plugins
- Provides actionable insights with color-coded reports

### AntiCheat Module
- **Lag compensation** for high-ping players
- **Knockback detection** (2s immunity after damage)
- **Y-level pattern analysis** for XRay (optimal mining height tracking)
- Environmental checks (slime blocks, bubble columns)
- **Reduced false positives** through smart detection

### Auto Entity Cleaner
- Priority-based removal (items → projectiles → monsters)
- Protection for named entities, tamed animals, villagers
- Boss mob blacklist (Ender Dragon, Wither, Warden)
- Per-world and per-chunk limits
- Dry-run mode for safe testing

### Trend Analysis
- Historical tracking (24h @ 5min intervals)
- Identifies entity accumulation patterns
- Change rate calculation (entities/hour)
- Detects: `INCREASING`, `DECREASING`, `STABLE`
- Alerts for mob farms and entity overflow

### Auto Update Checker
- **Modrinth Integration** - Checks for new versions on startup
- **Semantic Versioning** - Intelligent version comparison
- **Admin Notifications** - Alerts ops about available updates
- **Non-intrusive** - Async checks, no performance impact
- **Release-Only** - Only notifies about stable releases (no beta/alpha)

---

## 🔧 Building from Source

```bash
git clone https://github.com/BoondockSulfur/PerformanceAnalyzer.git
cd PerformanceAnalyzer
mvn clean package
# JAR file in target/
```

**Requirements:** Java 21 JDK, Maven 3.8+

---

## 📊 Integration Examples

### Grafana Dashboard
```bash
# Add JSON datasource
# Import dashboard from docs/grafana-dashboard.json
```

### Python Monitoring
```python
import requests

r = requests.get("http://localhost:8080/api/metrics",
    headers={"Authorization": "Bearer your-key"})
data = r.json()
print(f"TPS: {data['tps']}, MSPT: {data['mspt']['avg']}ms")
```

---

## 📝 Changelog

### v2.3.0 (Latest)

**New Features:**
- ✅ Lag compensation for movement checks
- ✅ Y-level analysis for XRay detection
- ✅ Async chunk analysis (performance boost)
- ✅ Auto-cleanup for old database entries
- ✅ GUI auto-refresh system
- ✅ Trend analysis for world stats
- ✅ REST API endpoints
- ✅ Automatic entity killer

**Improvements:**
- 90% less main-thread load during chunk analysis
- Reduced AntiCheat false-positives
- Better memory management
- Enhanced security (API key auth, SSRF protection)

See [CHANGELOG.md](CHANGELOG.md) for full history.

---

## 📜 License

This project is licensed under the MIT License - see [LICENSE](LICENSE) for details.

---

## 🙏 Acknowledgments

- [Spark](https://spark.lucko.me/) for profiling integration
- [HikariCP](https://github.com/brettwooldridge/HikariCP) for database pooling
- [Paper Team](https://papermc.io/) for excellent server platform

---

## 📧 Support

- **Discord**: [Join our Discord](https://discord.gg/xEJjF65K46) - Get help, share feedback, discuss features
- **Issues**: [GitHub Issues](https://github.com/BoondockSulfur/PerformanceAnalyzer/issues)
- **Discussions**: [GitHub Discussions](https://github.com/BoondockSulfur/PerformanceAnalyzer/discussions)

---

**Made with ❤️ for the Minecraft community**
