package com.hitori.antilag.core;

import com.hitori.antilag.HitoriAntiLag;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Collects server performance metrics (MSPT, TPS, entity counts, etc.)
 */
public class MetricsCollector {

    private final HitoriAntiLag plugin;
    private BukkitTask collectionTask;

    // Current metrics (thread-safe)
    private final AtomicReference<MetricsSnapshot> currentSnapshot = new AtomicReference<>(new MetricsSnapshot());

    // Rolling buffer for historical data
    private final RingBuffer<MetricsSnapshot> history;

    // TPS calculation
    private long lastTickTime = System.nanoTime();
    private final double[] tpsBuffer = new double[20];
    private int tpsBufferIndex = 0;

    // Reflection for server MSPT (Paper API)
    private Method getTickTimesMethod;
    private Object minecraftServer;

    public MetricsCollector(HitoriAntiLag plugin) {
        this.plugin = plugin;
        this.history = new RingBuffer<>(plugin.getConfigManager().getTrendSampleWindowSeconds());

        // Try to get Paper's tick times via reflection
        initializeMsptReflection();
    }

    /**
     * Initialize MSPT data source
     * FIX B: Removed fragile NMS reflection - field names like "tick" get
     * obfuscated to "a", "b", etc
     * on production servers. Only Paper API is reliable.
     */
    private void initializeMsptReflection() {
        try {
            // Paper API: Bukkit.getServer().getAverageTickTime()
            // This is the ONLY reliable way to get MSPT on modern servers
            Method getAverageTickTime = Bukkit.getServer().getClass().getMethod("getAverageTickTime");
            if (getAverageTickTime != null) {
                getTickTimesMethod = getAverageTickTime;
                minecraftServer = Bukkit.getServer();
                plugin.getLogger().info("Using Paper's getAverageTickTime() for MSPT");
                return;
            }
        } catch (NoSuchMethodException e) {
            // Not Paper - will fallback to TPS-based estimation
        }

        // NOTE: We intentionally DO NOT attempt NMS reflection fallback
        // Reason: Field names are obfuscated in production JARs (e.g. "tickTimes" ->
        // "aB")
        // The old approach (field.getName().contains("tick")) would NEVER work on real
        // servers
        // TPS-based estimation is more reliable than broken reflection
        plugin.getLogger().info("Paper API not found - MSPT will be estimated from TPS");
    }

    public void start() {
        int intervalTicks = plugin.getConfigManager().getMsptSampleRateMs() / 50;

        collectionTask = Bukkit.getScheduler().runTaskTimer(plugin, this::collectMetrics, 20L, intervalTicks);
        plugin.getLogger().info("Metrics collector started (interval: " + intervalTicks + " ticks)");
    }

    public void shutdown() {
        if (collectionTask != null) {
            collectionTask.cancel();
        }
    }

    private void collectMetrics() {
        long now = System.currentTimeMillis();

        MetricsSnapshot snapshot = new MetricsSnapshot();
        snapshot.timestamp = now;

        // Calculate TPS
        long currentTime = System.nanoTime();
        long elapsed = currentTime - lastTickTime;
        lastTickTime = currentTime;

        double currentTps = 1_000_000_000.0 / elapsed * (plugin.getConfigManager().getMsptSampleRateMs() / 50.0);
        currentTps = Math.min(20.0, currentTps); // Cap at 20
        tpsBuffer[tpsBufferIndex++ % 20] = currentTps;

        // Average TPS
        double tpsSum = 0;
        int count = Math.min(tpsBufferIndex, 20);
        for (int i = 0; i < count; i++) {
            tpsSum += tpsBuffer[i];
        }
        snapshot.tps = tpsSum / count;

        // Get MSPT
        snapshot.mspt = getMspt();

        // Count entities across all worlds
        int totalEntities = 0;
        int totalChunks = 0;

        for (World world : Bukkit.getWorlds()) {
            totalEntities += world.getEntityCount();
            totalChunks += world.getLoadedChunks().length;
        }

        snapshot.entityCount = totalEntities;
        snapshot.loadedChunks = totalChunks;
        snapshot.playerCount = Bukkit.getOnlinePlayers().size();

        // Memory stats
        Runtime runtime = Runtime.getRuntime();
        snapshot.usedMemoryMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        snapshot.maxMemoryMb = runtime.maxMemory() / (1024 * 1024);
        snapshot.freeMemoryMb = runtime.freeMemory() / (1024 * 1024);

        // Store snapshot
        currentSnapshot.set(snapshot);
        history.add(snapshot);

        // Save to database asynchronously
        plugin.getAsyncExecutor().execute(() -> {
            if (plugin.getStorageManager() != null) {
                plugin.getStorageManager().saveMetricSnapshot(snapshot);
            }
        });
    }

    private double getMspt() {
        // Try Paper API first
        if (getTickTimesMethod != null && minecraftServer != null) {
            try {
                Object result = getTickTimesMethod.invoke(minecraftServer);
                if (result instanceof Double) {
                    return (Double) result;
                }
            } catch (Exception e) {
                // Fall through to estimation
            }
        }

        // Estimate from TPS: MSPT ≈ 1000 / TPS
        double tps = currentSnapshot.get().tps;
        if (tps > 0) {
            return 1000.0 / tps;
        }
        return 50.0; // Default to 50ms (20 TPS)
    }

    public MetricsSnapshot getCurrentSnapshot() {
        return currentSnapshot.get();
    }

    public RingBuffer<MetricsSnapshot> getHistory() {
        return history;
    }

    /**
     * Get the average MSPT over the last N samples
     */
    public double getAverageMspt(int samples) {
        return history.getLastN(samples).stream()
                .mapToDouble(s -> s.mspt)
                .average()
                .orElse(50.0);
    }

    /**
     * Get the average entity count over the last N samples
     */
    public double getAverageEntityCount(int samples) {
        return history.getLastN(samples).stream()
                .mapToDouble(s -> s.entityCount)
                .average()
                .orElse(0);
    }

    /**
     * Data class for a snapshot of server metrics
     */
    public static class MetricsSnapshot {
        public long timestamp;
        public double mspt;
        public double tps;
        public int entityCount;
        public int loadedChunks;
        public int playerCount;
        public long usedMemoryMb;
        public long maxMemoryMb;
        public long freeMemoryMb;

        public MetricsSnapshot() {
            this.timestamp = System.currentTimeMillis();
            this.mspt = 50.0;
            this.tps = 20.0;
        }

        /**
         * Convert to a JSON-serializable map
         */
        public java.util.Map<String, Object> toMap() {
            return java.util.Map.of(
                    "timestamp", timestamp,
                    "mspt", mspt,
                    "tps", tps,
                    "entityCount", entityCount,
                    "loadedChunks", loadedChunks,
                    "playerCount", playerCount,
                    "usedMemoryMb", usedMemoryMb,
                    "maxMemoryMb", maxMemoryMb,
                    "freeMemoryMb", freeMemoryMb);
        }
    }
}
