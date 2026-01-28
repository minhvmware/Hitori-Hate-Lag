package com.hitori.antilag.mitigation.strategies;

import com.hitori.antilag.HitoriAntiLag;
import com.hitori.antilag.core.ChunkKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Throttles redstone updates in problematic chunks
 * FIX: Added ChunkUnloadEvent listener to prevent memory leak
 */
public class RedstoneThrottler implements Listener {

    private final HitoriAntiLag plugin;
    private final Map<ChunkKey, ThrottleState> throttledChunks;

    public RedstoneThrottler(HitoriAntiLag plugin) {
        this.plugin = plugin;
        this.throttledChunks = new ConcurrentHashMap<>();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRedstoneUpdate(BlockRedstoneEvent event) {
        if (!plugin.getConfigManager().isRedstoneThrottlingEnabled()) {
            return;
        }

        ChunkKey key = new ChunkKey(event.getBlock().getChunk());
        ThrottleState state = throttledChunks.get(key);

        if (state != null && state.shouldThrottle()) {
            // Cancel this update (effectively slowing down the clock)
            event.setNewCurrent(event.getOldCurrent());
            state.incrementThrottled();
        }
    }

    /**
     * FIX: Clean up throttled chunks when they unload to prevent memory leak
     * Without this, chunks that get unloaded stay in the map forever
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        ChunkKey key = new ChunkKey(event.getChunk());
        ThrottleState removed = throttledChunks.remove(key);

        if (removed != null && plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("Auto-removed throttle for unloaded chunk: " + key);
        }
    }

    /**
     * Enable throttling for a chunk
     */
    public void enableThrottling(ChunkKey chunk, int targetUpdatesPerSecond) {
        ThrottleState existing = throttledChunks.get(chunk);

        if (existing == null) {
            throttledChunks.put(chunk, new ThrottleState(targetUpdatesPerSecond));

            if (plugin.getConfigManager().isLogMitigations()) {
                plugin.getLogger().info("Enabled redstone throttling for " + chunk +
                        " (target: " + targetUpdatesPerSecond + " updates/sec)");
            }
        }
    }

    /**
     * Disable throttling for a chunk
     */
    public void disableThrottling(ChunkKey chunk) {
        ThrottleState removed = throttledChunks.remove(chunk);

        if (removed != null && plugin.getConfigManager().isLogMitigations()) {
            plugin.getLogger().info("Disabled redstone throttling for " + chunk +
                    " (throttled " + removed.getTotalThrottled() + " updates)");
        }
    }

    /**
     * Disable all throttling
     */
    public void disableAllThrottling() {
        int count = throttledChunks.size();
        throttledChunks.clear();

        if (count > 0 && plugin.getConfigManager().isLogMitigations()) {
            plugin.getLogger().info("Disabled redstone throttling for " + count + " chunks");
        }
    }

    /**
     * Check if a chunk is being throttled
     */
    public boolean isThrottled(ChunkKey chunk) {
        return throttledChunks.containsKey(chunk);
    }

    /**
     * Get number of throttled chunks
     */
    public int getThrottledCount() {
        return throttledChunks.size();
    }

    /**
     * Get all throttled chunks
     */
    public Map<ChunkKey, ThrottleState> getThrottledChunks() {
        return Map.copyOf(throttledChunks);
    }

    /**
     * Throttle state for a chunk
     */
    public static class ThrottleState {
        private final int maxUpdatesPerSecond;
        private final AtomicInteger updateCount = new AtomicInteger(0);
        private final AtomicInteger totalThrottled = new AtomicInteger(0);
        private volatile long windowStart = System.currentTimeMillis();

        public ThrottleState(int maxUpdatesPerSecond) {
            this.maxUpdatesPerSecond = maxUpdatesPerSecond;
        }

        public boolean shouldThrottle() {
            long now = System.currentTimeMillis();

            // Reset window every second
            if (now - windowStart > 1000) {
                windowStart = now;
                updateCount.set(0);
            }

            return updateCount.incrementAndGet() > maxUpdatesPerSecond;
        }

        public void incrementThrottled() {
            totalThrottled.incrementAndGet();
        }

        public int getMaxUpdatesPerSecond() {
            return maxUpdatesPerSecond;
        }

        public int getCurrentCount() {
            return updateCount.get();
        }

        public int getTotalThrottled() {
            return totalThrottled.get();
        }
    }
}
