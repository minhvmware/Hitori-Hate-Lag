package com.hitori.antilag.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hitori.antilag.HitoriAntiLag;
import com.hitori.antilag.core.HotspotLevel;
import com.hitori.antilag.core.HotspotTracker.HotspotEntry;
import com.hitori.antilag.core.MetricsCollector.MetricsSnapshot;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Embedded web server for the anti-lag dashboard
 */
public class WebServer {

    private final HitoriAntiLag plugin;
    private final Javalin app;
    private final Gson gson;
    private final Set<WsContext> wsClients;
    private final ScheduledExecutorService broadcaster;
    private final String authToken;

    public WebServer(HitoriAntiLag plugin, int port) {
        this.plugin = plugin;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.wsClients = ConcurrentHashMap.newKeySet();
        this.broadcaster = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HitoriAntiLag-WebBroadcaster");
            t.setDaemon(true);
            return t;
        });
        this.authToken = plugin.getConfigManager().getWebAuthToken();

        // Configure Javalin with Gson JSON mapper (no Jackson required)
        this.app = Javalin.create(config -> {
            config.staticFiles.add("/web", Location.CLASSPATH);
            config.http.defaultContentType = "application/json";

            // Use Gson instead of Jackson for JSON serialization
            config.jsonMapper(new io.javalin.json.JsonMapper() {
                @Override
                public String toJsonString(Object obj, java.lang.reflect.Type type) {
                    return gson.toJson(obj, type);
                }

                @Override
                public <T> T fromJsonString(String json, java.lang.reflect.Type type) {
                    return gson.fromJson(json, type);
                }
            });
        });

        // Configure routes
        configureRoutes();
        configureWebSocket();

        // Start server
        app.start(port);

        // Start metrics broadcaster
        startBroadcaster();
    }

    private void configureRoutes() {
        // CORS handling
        app.before(ctx -> {
            String origin = plugin.getConfigManager().getWebCorsOrigin();
            ctx.header("Access-Control-Allow-Origin", origin);
            ctx.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Authorization, Content-Type");
        });

        app.options("/*", ctx -> ctx.status(200));

        // ==================== Public endpoints (no auth) ====================

        // Health check
        app.get("/api/health", ctx -> {
            ctx.json(Map.of("status", "ok", "timestamp", System.currentTimeMillis()));
        });

        // ==================== Protected endpoints (require auth) ====================

        // Current metrics
        app.get("/api/metrics", ctx -> {
            if (!validateAuth(ctx))
                return;

            MetricsSnapshot snapshot = plugin.getMetricsCollector().getCurrentSnapshot();
            ctx.json(snapshot.toMap());
        });

        // Metrics history
        app.get("/api/metrics/history", ctx -> {
            if (!validateAuth(ctx))
                return;

            int count = ctx.queryParamAsClass("count", Integer.class).getOrDefault(60);
            var history = plugin.getMetricsCollector().getHistory().getLastN(count);

            ctx.json(Map.of(
                    "count", history.size(),
                    "data", history.stream().map(MetricsSnapshot::toMap).toList()));
        });

        // All chunk data
        app.get("/api/chunks", ctx -> {
            if (!validateAuth(ctx))
                return;

            var chunks = plugin.getChunkAnalyzer().getAllChunkData();
            ctx.json(Map.of(
                    "count", chunks.size(),
                    "data", chunks.values().stream().map(c -> c.toMap()).toList()));
        });

        // Hotspots
        app.get("/api/hotspots", ctx -> {
            if (!validateAuth(ctx))
                return;

            String levelParam = ctx.queryParam("level");
            HotspotLevel minLevel = HotspotLevel.MEDIUM;
            if (levelParam != null) {
                try {
                    minLevel = HotspotLevel.valueOf(levelParam.toUpperCase());
                } catch (IllegalArgumentException ignored) {
                }
            }

            List<HotspotEntry> hotspots = plugin.getHotspotTracker().getHotspots(minLevel);
            ctx.json(Map.of(
                    "count", hotspots.size(),
                    "data", hotspots.stream().map(HotspotEntry::toMap).toList()));
        });

        // Detections
        app.get("/api/detections", ctx -> {
            if (!validateAuth(ctx))
                return;

            var detections = plugin.getDetectionEngine().getDetectedMachines();
            ctx.json(Map.of(
                    "count", detections.size(),
                    "data", detections.stream().map(d -> d.toMap()).toList()));
        });

        // Trend analysis
        app.get("/api/trend", ctx -> {
            if (!validateAuth(ctx))
                return;

            var trend = plugin.getPredictionEngine().getLatestTrend();
            if (trend != null) {
                ctx.json(trend.toMap());
            } else {
                ctx.json(Map.of("hasData", false));
            }
        });

        // Baseline stats
        app.get("/api/baseline", ctx -> {
            if (!validateAuth(ctx))
                return;

            var stats = plugin.getPredictionEngine().getBaselineManager().getStats();
            ctx.json(stats.toMap());
        });

        // Recent anomalies
        app.get("/api/anomalies", ctx -> {
            if (!validateAuth(ctx))
                return;

            int count = ctx.queryParamAsClass("count", Integer.class).getOrDefault(20);
            var anomalies = plugin.getPredictionEngine().getRecentAnomalies(count);
            ctx.json(Map.of(
                    "count", anomalies.size(),
                    "data", anomalies.stream().map(a -> a.toMap()).toList()));
        });

        // Server status
        app.get("/api/status", ctx -> {
            if (!validateAuth(ctx))
                return;

            MetricsSnapshot metrics = plugin.getMetricsCollector().getCurrentSnapshot();
            var hotspotCounts = plugin.getHotspotTracker().getHotspotCounts();
            var trend = plugin.getPredictionEngine().getLatestTrend();

            Map<String, Object> status = new HashMap<>();
            status.put("metrics", metrics.toMap());
            status.put("protectionMode", plugin.getMitigationEngine().getCurrentMode().name());
            status.put("hotspotCounts", hotspotCounts.entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue)));
            status.put("detectionCount", plugin.getDetectionEngine().getDetectedMachines().size());
            status.put("trend", trend != null ? trend.toMap() : null);
            status.put("timestamp", System.currentTimeMillis());

            ctx.json(status);
        });

        // ==================== Action endpoints ====================

        // Trigger scan
        app.post("/api/action/scan", ctx -> {
            if (!validateAuth(ctx))
                return;

            plugin.getChunkAnalyzer().scanAllChunksAsync();
            ctx.json(Map.of("success", true, "message", "Scan started"));
        });

        // Force cull
        app.post("/api/action/cull", ctx -> {
            if (!validateAuth(ctx))
                return;

            int count = ctx.queryParamAsClass("count", Integer.class).getOrDefault(100);

            // Schedule on main thread
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                int removed = plugin.getMitigationEngine().forceCull(count);
                plugin.getLogger().info("Web API: Culled " + removed + " entities");
            });

            ctx.json(Map.of("success", true, "message", "Cull initiated for " + count + " entities"));
        });

        // Clear specific chunk
        app.post("/api/action/clear-chunk", ctx -> {
            if (!validateAuth(ctx))
                return;

            String world = ctx.queryParam("world");
            Integer chunkX = ctx.queryParamAsClass("x", Integer.class).getOrDefault(null);
            Integer chunkZ = ctx.queryParamAsClass("z", Integer.class).getOrDefault(null);

            if (world == null || chunkX == null || chunkZ == null) {
                ctx.status(400).json(Map.of("error", "Missing world, x, or z parameter"));
                return;
            }

            // Schedule on main thread
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                int removed = plugin.getMitigationEngine().clearChunk(world, chunkX, chunkZ);
                plugin.getLogger()
                        .info("Web API: Cleared " + removed + " entities from " + world + ":" + chunkX + "," + chunkZ);
            });

            ctx.json(Map.of("success", true, "message", "Clear initiated"));
        });
    }

    private void configureWebSocket() {
        app.ws("/ws/metrics", ws -> {
            ws.onConnect(ctx -> {
                String token = ctx.queryParam("token");
                if (!authToken.equals(token)) {
                    ctx.closeSession(4001, "Unauthorized");
                    return;
                }

                int maxConnections = plugin.getConfigManager().getWebMaxConnections();
                if (wsClients.size() >= maxConnections) {
                    ctx.closeSession(4002, "Max connections reached");
                    return;
                }

                wsClients.add(ctx);
                plugin.getLogger().info("WebSocket client connected. Total: " + wsClients.size());
            });

            ws.onClose(ctx -> {
                wsClients.remove(ctx);
                plugin.getLogger().info("WebSocket client disconnected. Total: " + wsClients.size());
            });

            ws.onMessage(ctx -> {
                String message = ctx.message();
                if ("ping".equals(message)) {
                    ctx.send("pong");
                }
            });

            ws.onError(ctx -> {
                wsClients.remove(ctx);
            });
        });
    }

    private void startBroadcaster() {
        int intervalMs = plugin.getConfigManager().getWebUpdateIntervalMs();

        broadcaster.scheduleAtFixedRate(() -> {
            if (wsClients.isEmpty())
                return;

            try {
                MetricsSnapshot snapshot = plugin.getMetricsCollector().getCurrentSnapshot();
                String json = gson.toJson(Map.of(
                        "type", "metrics",
                        "data", snapshot.toMap()));

                Iterator<WsContext> iterator = wsClients.iterator();
                while (iterator.hasNext()) {
                    WsContext client = iterator.next();
                    try {
                        if (client.session.isOpen()) {
                            client.send(json);
                        } else {
                            iterator.remove();
                        }
                    } catch (Exception e) {
                        iterator.remove();
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("WebSocket broadcast error: " + e.getMessage());
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private boolean validateAuth(Context ctx) {
        String authHeader = ctx.header("Authorization");
        String tokenParam = ctx.queryParam("token");

        String providedToken = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            providedToken = authHeader.substring(7);
        } else if (tokenParam != null) {
            providedToken = tokenParam;
        }

        if (!authToken.equals(providedToken)) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return false;
        }

        return true;
    }

    public void shutdown() {
        broadcaster.shutdown();

        for (WsContext client : wsClients) {
            try {
                client.closeSession(1000, "Server shutting down");
            } catch (Exception ignored) {
            }
        }
        wsClients.clear();

        app.stop();
        plugin.getLogger().info("Web server stopped");
    }

    public int getConnectedClients() {
        return wsClients.size();
    }
}
