# 🚀 HitoriAntiLag

> **Next-Generation Server Optimization System** > *Predict lag before it happens. Detect anomalies with Math. Mitigate with Precision.*

![Java CI](https://img.shields.io/badge/build-passing-brightgreen)
![Platform](https://img.shields.io/badge/platform-Spigot%20|%20Paper%20|%20Folia-blue)
![Java Version](https://img.shields.io/badge/java-17%2B-orange)
![License](https://img.shields.io/badge/license-Proprietary-red)

## 📖 Overview

**HitoriAntiLag** is not just another clear-lag plugin. It is a sophisticated **Performance Intelligence System** designed for high-scale Minecraft servers.

Unlike traditional plugins that react *after* the server lags, HitoriAntiLag uses **Statistical Analysis** and **Machine Learning algorithms** (Linear Regression, Welford's Algorithm) to predict MSPT spikes and mitigate them proactively.

Built by a team of 5 specialists, this core is optimized for **Zero-Allocation** memory usage, ensuring the plugin itself never becomes the source of lag.

## ✨ Key Features

### 🧠 A.I. & Prediction Engine
* **Trend Analysis:** Uses **Linear Regression** (Least Squares) to calculate the slope of MSPT changes over time.
* **Anomaly Detection:** Implements **Z-Score** statistical analysis to detect abnormal behavior specific to *your* server's baseline.
* **Smart Baseline:** Learns what "normal" looks like for your server using **Welford's Online Algorithm** (single-pass variance calculation).
Note: LOL ITS FAKE
### 🛡️ Detection & Security
* **Redstone Clock Detector:** Identifies high-frequency clocks using a specialized Rolling Window algorithm.
* **Entity Cramming:** Detects mob stackers using bit-packed coordinate hashing (Zero object allocation).
* **Projectile & NBT Spam:** Prevents server crashes from book banning, shulker box exploits, and projectile spam.
* **Lag Machines:** Automatically identifies and logs coordinates of malicious structures.

### ⚡ Extreme Performance Core
* **Folia Support:** Native support for Regionized Multithreading.
* **Zero-Allocation:** Custom `IntObjectMap`, `LongObjectMap`, and `ReusableStringBuilder` to eliminate Garbage Collection pressure.
* **Async-First Architecture:** Heavy calculations are offloaded to worker threads; results are synchronized safely using `CountDownLatch`.

### 🔧 Mitigation Strategies
* **Smart Culling:** Removes least-important entities first (e.g., dropped items > xp > distant mobs) using a priority queue.
* **Dynamic View Distance:** Adjusts per-world view/simulation distance in real-time based on load.
* **Redstone Throttling:** Slows down specific redstone circuits without breaking them completely.

### 📊 Web Dashboard
* Built-in **HTTP/WebSocket Server** (Javalin).
* Real-time MSPT, TPS, and RAM usage graphs.
* Live Hotspot map showing lag sources.

## 🛠️ Installation

1.  Ensure your server is running **Java 17** or higher.
2.  Download the latest `HitoriAntiLag.jar`.
3.  Place it in your `plugins` folder.
4.  Restart the server.
5.  *(Optional)* Configure `config.yml` to tune thresholds.

## 💻 Commands & Permissions

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/antilag status` | `hitori.antilag.view` | View real-time server health, predictions, and trends. |
| `/antilag scan` | `hitori.antilag.admin` | Force an async scan of all loaded chunks. |
| `/antilag hotspots` | `hitori.antilag.admin` | List top lag sources (Redstone, Entities). |
| `/antilag trend` | `hitori.antilag.view` | View detailed prediction analysis (Slope/Z-Score). |
| `/antilag cull [amt]`| `hitori.antilag.admin` | Trigger an emergency smart cull. |
| `/antilag clear` | `hitori.antilag.admin` | Clear entities in the current chunk/world. |
| `/antilag reload` | `hitori.antilag.admin` | Reload configuration. |

## ⚙️ Configuration

The `config.yml` is split into modules. Key settings include:

```yaml
scoring:
  entity-weights:
    VILLAGER: 3.5
    WITHER: 5.0

prediction:
  trend-analysis:
    enabled: true
    sample-window: 60 # seconds
  anomaly-detection:
    z-score-threshold: 3.0 # Sensitivity (Sigma)

storage:
  type: sqlite # or mysql
  sqlite:
    file: data.db
    # WAL mode is enabled by default for performance
