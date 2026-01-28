package com.hitori.antilag.core;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Data class holding analysis results for a single chunk
 */
public class ChunkData {

    private final ChunkKey key;
    private final long timestamp;

    // Entity counts by type
    private Map<EntityType, Integer> entityCounts;
    private int totalEntityCount;

    // Tile entity counts by type
    private Map<Material, Integer> tileEntityCounts;
    private int totalTileEntityCount;

    // Redstone activity
    private final AtomicInteger redstoneUpdatesInWindow = new AtomicInteger(0);
    private final AtomicLong lastRedstoneReset = new AtomicLong(System.currentTimeMillis());

    // Calculated metrics
    private double lagScore;
    private HotspotLevel hotspotLevel = HotspotLevel.NORMAL;

    public ChunkData(ChunkKey key) {
        this.key = key;
        this.timestamp = System.currentTimeMillis();
        this.entityCounts = new EnumMap<>(EntityType.class);
        this.tileEntityCounts = new EnumMap<>(Material.class);
    }

    private ChunkData(Builder builder) {
        this.key = builder.key;
        this.timestamp = System.currentTimeMillis();
        this.entityCounts = builder.entityCounts != null ? new EnumMap<>(builder.entityCounts)
                : new EnumMap<>(EntityType.class);
        this.tileEntityCounts = builder.tileEntityCounts != null ? new EnumMap<>(builder.tileEntityCounts)
                : new EnumMap<>(Material.class);

        // Manual sum - avoid stream() allocation overhead (HOT PATH!)
        int entityTotal = 0;
        for (int count : this.entityCounts.values()) {
            entityTotal += count;
        }
        this.totalEntityCount = entityTotal;

        int tileTotal = 0;
        for (int count : this.tileEntityCounts.values()) {
            tileTotal += count;
        }
        this.totalTileEntityCount = tileTotal;
    }

    public ChunkKey getKey() {
        return key;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Map<EntityType, Integer> getEntityCounts() {
        return Collections.unmodifiableMap(entityCounts);
    }

    public void setEntityCounts(Map<EntityType, Integer> counts) {
        this.entityCounts = new EnumMap<>(counts);
        this.totalEntityCount = counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int getTotalEntityCount() {
        return totalEntityCount;
    }

    public Map<Material, Integer> getTileEntityCounts() {
        return Collections.unmodifiableMap(tileEntityCounts);
    }

    public void setTileEntityCounts(Map<Material, Integer> counts) {
        this.tileEntityCounts = new EnumMap<>(counts);
        this.totalTileEntityCount = counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int getTotalTileEntityCount() {
        return totalTileEntityCount;
    }

    /**
     * Increment redstone update counter (thread-safe)
     */
    public void incrementRedstoneUpdates() {
        long now = System.currentTimeMillis();
        long lastReset = lastRedstoneReset.get();

        // Reset counter if window has passed (1 second)
        if (now - lastReset > 1000) {
            if (lastRedstoneReset.compareAndSet(lastReset, now)) {
                redstoneUpdatesInWindow.set(1);
                return;
            }
        }

        redstoneUpdatesInWindow.incrementAndGet();
    }

    /**
     * Get redstone updates per second
     * FIX: Prevent data spikes by requiring minimum sample window
     */
    public int getRedstoneUpdatesPerSecond() {
        long elapsed = System.currentTimeMillis() - lastRedstoneReset.get();
        int count = redstoneUpdatesInWindow.get();

        // If full second has passed, return actual count
        if (elapsed >= 1000) {
            return count;
        }

        // SPIKE PREVENTION: Don't extrapolate if window is too short
        // Extrapolating 1 event from 1ms would give 1000/s - false positive!
        // Require at least 100ms of data to extrapolate
        if (elapsed < 100) {
            return count; // Return raw count, don't extrapolate
        }

        // Safe to extrapolate
        return (int) (count * 1000.0 / elapsed);
    }

    public double getLagScore() {
        return lagScore;
    }

    public void setLagScore(double lagScore) {
        this.lagScore = lagScore;
    }

    public HotspotLevel getHotspotLevel() {
        return hotspotLevel;
    }

    public void setHotspotLevel(HotspotLevel level) {
        this.hotspotLevel = level;
    }

    /**
     * Convert to a JSON-serializable map for web API
     */
    public Map<String, Object> toMap() {
        return Map.of(
                "key", key.toString(),
                "worldName", key.getWorldName(),
                "chunkX", key.getX(),
                "chunkZ", key.getZ(),
                "timestamp", timestamp,
                "entityCount", totalEntityCount,
                "tileEntityCount", totalTileEntityCount,
                "redstoneUpdates", getRedstoneUpdatesPerSecond(),
                "lagScore", lagScore,
                "hotspotLevel", hotspotLevel.name());
    }

    // Builder pattern for clean construction
    public static Builder builder(ChunkKey key) {
        return new Builder(key);
    }

    public static class Builder {
        private final ChunkKey key;
        private Map<EntityType, Integer> entityCounts;
        private Map<Material, Integer> tileEntityCounts;

        public Builder(ChunkKey key) {
            this.key = key;
        }

        public Builder entityCounts(Map<EntityType, Integer> counts) {
            this.entityCounts = counts;
            return this;
        }

        public Builder tileEntityCounts(Map<Material, Integer> counts) {
            this.tileEntityCounts = counts;
            return this;
        }

        public ChunkData build() {
            return new ChunkData(this);
        }
    }
}
