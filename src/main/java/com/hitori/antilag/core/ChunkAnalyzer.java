package com.hitori.antilag.core;

import com.hitori.antilag.HitoriAntiLag;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Analyzes chunks for lag sources using async scanning
 */
public class ChunkAnalyzer implements Listener {

    private final HitoriAntiLag plugin;
    private final LagScorer scorer;
    private final Map<ChunkKey, ChunkData> chunkDataCache;

    private BukkitTask scanTask;
    private volatile boolean scanning = false;

    public ChunkAnalyzer(HitoriAntiLag plugin) {
        this.plugin = plugin;
        this.scorer = new LagScorer(plugin);
        this.chunkDataCache = new ConcurrentHashMap<>();

        // Register events for real-time tracking
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void start() {
        int intervalTicks = plugin.getConfigManager().getScanIntervalTicks();

        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!scanning) {
                scanAllChunksAsync();
            }
        }, 100L, intervalTicks);

        plugin.getLogger().info("Chunk analyzer started (interval: " + intervalTicks + " ticks)");
    }

    public void shutdown() {
        if (scanTask != null) {
            scanTask.cancel();
        }
    }

    /**
     * Scan all loaded chunks asynchronously
     * THREAD-SAFETY FIX: Bukkit API calls (getWorlds, getLoadedChunks) MUST be on
     * main thread
     */
    public CompletableFuture<Map<ChunkKey, ChunkData>> scanAllChunksAsync() {
        scanning = true;

        // CRITICAL: Collect chunk snapshot ON MAIN THREAD before going async
        // Bukkit API is NOT thread-safe - accessing from async can cause CME or crashes
        List<Chunk> chunksSnapshot = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            Collections.addAll(chunksSnapshot, world.getLoadedChunks());
        }

        return CompletableFuture.supplyAsync(() -> {
            Map<ChunkKey, ChunkData> results = new ConcurrentHashMap<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>(chunksSnapshot.size());

            for (Chunk chunk : chunksSnapshot) {
                futures.add(analyzeChunkAsync(chunk)
                        .thenAccept(data -> {
                            if (data != null) {
                                results.put(data.getKey(), data);
                                chunkDataCache.put(data.getKey(), data);

                                // Update hotspot tracker if critical
                                if (data.getHotspotLevel().isAtLeast(HotspotLevel.MEDIUM)) {
                                    plugin.getHotspotTracker().updateHotspot(data);
                                }
                            }
                        }));
            }

            // Wait for all analyses to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            scanning = false;
            return results;
        }, plugin.getAsyncExecutor());
    }

    /**
     * Analyze a single chunk - uses main thread briefly for entity access
     */
    public CompletableFuture<ChunkData> analyzeChunkAsync(Chunk chunk) {
        CompletableFuture<ChunkData> future = new CompletableFuture<>();
        ChunkKey key = new ChunkKey(chunk);

        if (plugin.isFolia()) {
            // Folia: Use region scheduler for the chunk's region
            try {
                Bukkit.getRegionScheduler().execute(plugin, chunk.getWorld(),
                        chunk.getX(), chunk.getZ(), () -> {
                            future.complete(collectChunkData(chunk, key));
                        });
            } catch (Exception e) {
                // Fallback if Folia methods not available
                future.complete(null);
            }
        } else {
            // Paper/Spigot: Use main thread briefly for entity snapshot
            Bukkit.getScheduler().runTask(plugin, () -> {
                future.complete(collectChunkData(chunk, key));
            });
        }

        return future;
    }

    /**
     * Collect data from a chunk (must be called on appropriate thread)
     */
    private ChunkData collectChunkData(Chunk chunk, ChunkKey key) {
        try {
            ChunkData.Builder builder = ChunkData.builder(key);

            // Count entities by type
            Map<EntityType, Integer> entityCounts = new EnumMap<>(EntityType.class);
            for (Entity entity : chunk.getEntities()) {
                entityCounts.merge(entity.getType(), 1, Integer::sum);
            }
            builder.entityCounts(entityCounts);

            // Count tile entities by type
            Map<Material, Integer> tileCounts = new EnumMap<>(Material.class);
            for (BlockState state : chunk.getTileEntities()) {
                tileCounts.merge(state.getType(), 1, Integer::sum);
            }
            builder.tileEntityCounts(tileCounts);

            // Build and score the chunk data
            ChunkData data = builder.build();

            // Copy redstone data from cache if available
            ChunkData cached = chunkDataCache.get(key);
            if (cached != null) {
                // Preserve redstone update tracking
                int updates = cached.getRedstoneUpdatesPerSecond();
                for (int i = 0; i < updates; i++) {
                    data.incrementRedstoneUpdates();
                }
            }

            scorer.scoreAndClassify(data);

            return data;
        } catch (Exception e) {
            plugin.getLogger().warning("Error analyzing chunk " + key + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Event listener for redstone updates - tracks activity per chunk
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRedstoneUpdate(BlockRedstoneEvent event) {
        ChunkKey key = new ChunkKey(event.getBlock().getChunk());

        chunkDataCache.computeIfAbsent(key, k -> new ChunkData(k))
                .incrementRedstoneUpdates();
    }

    /**
     * Get cached data for a specific chunk
     */
    public ChunkData getChunkData(ChunkKey key) {
        return chunkDataCache.get(key);
    }

    /**
     * Get all cached chunk data
     */
    public Map<ChunkKey, ChunkData> getAllChunkData() {
        return Collections.unmodifiableMap(chunkDataCache);
    }

    /**
     * Get chunks above a certain hotspot level
     */
    public List<ChunkData> getHotspots(HotspotLevel minLevel) {
        List<ChunkData> hotspots = new ArrayList<>();

        for (ChunkData data : chunkDataCache.values()) {
            if (data.getHotspotLevel().isAtLeast(minLevel)) {
                hotspots.add(data);
            }
        }

        // Sort by lag score descending
        hotspots.sort((a, b) -> Double.compare(b.getLagScore(), a.getLagScore()));

        return hotspots;
    }

    /**
     * Get the lag scorer instance
     */
    public LagScorer getScorer() {
        return scorer;
    }

    /**
     * Clear cached data for a specific chunk
     */
    public void clearChunkData(ChunkKey key) {
        chunkDataCache.remove(key);
    }

    /**
     * Clear all cached data
     */
    public void clearAllData() {
        chunkDataCache.clear();
    }

    /**
     * Check if a scan is currently in progress
     */
    public boolean isScanning() {
        return scanning;
    }
}
