package com.hitori.antilag.detection.detectors;

import com.hitori.antilag.HitoriAntiLag;
import com.hitori.antilag.core.ChunkKey;
import com.hitori.antilag.detection.LagMachine;
import com.hitori.antilag.detection.LagMachineType;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects high-frequency redstone clocks
 */
public class RedstoneClockDetector implements Listener {

    private final HitoriAntiLag plugin;
    private final Map<ChunkKey, RollingWindow> updateWindows;
    private final int threshold;
    private final int windowSizeMs;

    public RedstoneClockDetector(HitoriAntiLag plugin) {
        this.plugin = plugin;
        this.updateWindows = new ConcurrentHashMap<>();
        this.threshold = plugin.getConfigManager().getRedstoneClockThreshold();
        this.windowSizeMs = plugin.getConfigManager().getRedstoneWindowSizeMs();
    }

    /**
     * Record a redstone update
     */
    public void recordUpdate(Block block) {
        ChunkKey key = new ChunkKey(block.getChunk());
        updateWindows.computeIfAbsent(key, k -> new RollingWindow(windowSizeMs))
                .addEvent(System.currentTimeMillis());
    }

    /**
     * Detect all active redstone clocks
     */
    public List<LagMachine> detectClocks() {
        List<LagMachine> detected = new ArrayList<>();
        long now = System.currentTimeMillis();

        Iterator<Map.Entry<ChunkKey, RollingWindow>> iterator = updateWindows.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<ChunkKey, RollingWindow> entry = iterator.next();
            RollingWindow window = entry.getValue();

            // Cleanup old windows
            if (window.isEmpty()) {
                iterator.remove();
                continue;
            }

            int updatesPerSecond = window.getEventCount(now);

            if (updatesPerSecond > threshold) {
                detected.add(new LagMachine(
                        LagMachineType.REDSTONE_CLOCK,
                        entry.getKey(),
                        updatesPerSecond,
                        "High-frequency redstone: " + updatesPerSecond + " updates/sec (threshold: " + threshold
                                + ")"));
            }
        }

        return detected;
    }

    /**
     * Get the current update rate for a chunk
     */
    public int getUpdateRate(ChunkKey key) {
        RollingWindow window = updateWindows.get(key);
        if (window == null) {
            return 0;
        }
        return window.getEventCount(System.currentTimeMillis());
    }

    /**
     * Clear tracking data for a chunk
     */
    public void clearChunk(ChunkKey key) {
        updateWindows.remove(key);
    }

    /**
     * Clear all tracking data
     */
    public void clearAll() {
        updateWindows.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRedstoneUpdate(BlockRedstoneEvent event) {
        recordUpdate(event.getBlock());
    }

    /**
     * Lock-free ring buffer for timestamp tracking.
     * Uses fixed primitive long[] array - ZERO allocations after construction.
     * 
     * Critical for extreme scale: thousands of redstone clocks = millions of
     * events/sec
     * ConcurrentLinkedQueue would create Node objects constantly → GC pressure
     */
    private static class RollingWindow {
        private final long[] timestamps;
        private final int capacity;
        private final int windowMs;
        private final AtomicInteger writeIndex = new AtomicInteger(0);
        private final AtomicInteger size = new AtomicInteger(0);

        public RollingWindow(int windowMs) {
            this.windowMs = windowMs;
            // Allocate enough space for worst case: 1000 updates/sec * (window in seconds)
            this.capacity = Math.max(64, (windowMs / 1000) * 1000);
            this.timestamps = new long[capacity];
        }

        /**
         * Add event - zero allocations, lock-free
         */
        public void addEvent(long timestamp) {
            int idx = writeIndex.getAndIncrement() % capacity;
            timestamps[idx] = timestamp;

            // Increment size up to capacity
            int currentSize = size.get();
            if (currentSize < capacity) {
                size.compareAndSet(currentSize, currentSize + 1);
            }
        }

        /**
         * Count events in window - zero allocations
         */
        public int getEventCount(long now) {
            long cutoff = now - windowMs;
            int count = 0;
            int currentSize = Math.min(size.get(), capacity);

            // Scan ring buffer (modern CPUs love array iteration)
            for (int i = 0; i < currentSize; i++) {
                if (timestamps[i] >= cutoff) {
                    count++;
                }
            }

            return count;
        }

        public boolean isEmpty() {
            return size.get() == 0;
        }
    }
}
