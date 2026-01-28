package com.hitori.antilag.core;

import com.hitori.antilag.HitoriAntiLag;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks and manages chunk hotspots across the server
 */
public class HotspotTracker {

    private final HitoriAntiLag plugin;
    private final Map<ChunkKey, HotspotEntry> hotspots;

    // Track how long chunks have been hotspots
    private static final long HOTSPOT_EXPIRY_MS = 60000; // 1 minute without updates = expired

    public HotspotTracker(HitoriAntiLag plugin) {
        this.plugin = plugin;
        this.hotspots = new ConcurrentHashMap<>();
    }

    /**
     * Update or add a hotspot
     */
    public void updateHotspot(ChunkData data) {
        if (data.getHotspotLevel() == HotspotLevel.NORMAL) {
            hotspots.remove(data.getKey());
            return;
        }

        HotspotEntry entry = hotspots.compute(data.getKey(), (key, existing) -> {
            if (existing == null) {
                return new HotspotEntry(data);
            } else {
                existing.update(data);
                return existing;
            }
        });

        // Log if this is a new critical hotspot
        if (data.getHotspotLevel() == HotspotLevel.CRITICAL &&
                entry.getConsecutiveCriticalCount() == 1 &&
                plugin.getConfigManager().isLogDetections()) {

            plugin.getLogger().warning("New CRITICAL hotspot detected: " + data.getKey() +
                    " (Score: " + String.format("%.1f", data.getLagScore()) +
                    ", Entities: " + data.getTotalEntityCount() + ")");
        }
    }

    /**
     * Get all current hotspots
     */
    public List<HotspotEntry> getHotspots() {
        cleanupExpired();
        return new ArrayList<>(hotspots.values());
    }

    /**
     * Get hotspots at or above the specified level
     */
    public List<HotspotEntry> getHotspots(HotspotLevel minLevel) {
        cleanupExpired();
        List<HotspotEntry> result = new ArrayList<>();

        for (HotspotEntry entry : hotspots.values()) {
            if (entry.getCurrentLevel().isAtLeast(minLevel)) {
                result.add(entry);
            }
        }

        // Sort by severity and score
        result.sort((a, b) -> {
            int levelCompare = Integer.compare(
                    b.getCurrentLevel().getSeverity(),
                    a.getCurrentLevel().getSeverity());
            if (levelCompare != 0)
                return levelCompare;
            return Double.compare(b.getCurrentScore(), a.getCurrentScore());
        });

        return result;
    }

    /**
     * Get the highest hotspot level across all chunks
     */
    public HotspotLevel getHighestLevel() {
        HotspotLevel highest = HotspotLevel.NORMAL;

        for (HotspotEntry entry : hotspots.values()) {
            if (entry.getCurrentLevel().isWorseThan(highest)) {
                highest = entry.getCurrentLevel();
            }
        }

        return highest;
    }

    /**
     * Get count of hotspots at each level
     */
    public Map<HotspotLevel, Integer> getHotspotCounts() {
        cleanupExpired();
        Map<HotspotLevel, Integer> counts = new EnumMap<>(HotspotLevel.class);

        for (HotspotLevel level : HotspotLevel.values()) {
            counts.put(level, 0);
        }

        for (HotspotEntry entry : hotspots.values()) {
            counts.merge(entry.getCurrentLevel(), 1, Integer::sum);
        }

        return counts;
    }

    /**
     * Remove a hotspot
     */
    public void removeHotspot(ChunkKey key) {
        hotspots.remove(key);
    }

    /**
     * Clear all hotspots
     */
    public void clearAll() {
        hotspots.clear();
    }

    /**
     * Cleanup expired hotspots
     */
    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        hotspots.entrySet().removeIf(entry -> now - entry.getValue().getLastUpdate() > HOTSPOT_EXPIRY_MS);
    }

    /**
     * Entry class for tracking hotspot history
     */
    public static class HotspotEntry {
        private final ChunkKey key;
        private ChunkData latestData;
        private HotspotLevel currentLevel;
        private double currentScore;
        private long firstDetected;
        private long lastUpdate;
        private int updateCount;
        private int consecutiveCriticalCount;
        private double peakScore;

        public HotspotEntry(ChunkData data) {
            this.key = data.getKey();
            this.latestData = data;
            this.currentLevel = data.getHotspotLevel();
            this.currentScore = data.getLagScore();
            this.firstDetected = System.currentTimeMillis();
            this.lastUpdate = firstDetected;
            this.updateCount = 1;
            this.peakScore = data.getLagScore();

            if (currentLevel == HotspotLevel.CRITICAL) {
                consecutiveCriticalCount = 1;
            }
        }

        public void update(ChunkData data) {
            this.latestData = data;
            this.currentLevel = data.getHotspotLevel();
            this.currentScore = data.getLagScore();
            this.lastUpdate = System.currentTimeMillis();
            this.updateCount++;

            if (data.getLagScore() > peakScore) {
                peakScore = data.getLagScore();
            }

            if (currentLevel == HotspotLevel.CRITICAL) {
                consecutiveCriticalCount++;
            } else {
                consecutiveCriticalCount = 0;
            }
        }

        // Getters
        public ChunkKey getKey() {
            return key;
        }

        public ChunkData getLatestData() {
            return latestData;
        }

        public HotspotLevel getCurrentLevel() {
            return currentLevel;
        }

        public double getCurrentScore() {
            return currentScore;
        }

        public long getFirstDetected() {
            return firstDetected;
        }

        public long getLastUpdate() {
            return lastUpdate;
        }

        public int getUpdateCount() {
            return updateCount;
        }

        public int getConsecutiveCriticalCount() {
            return consecutiveCriticalCount;
        }

        public double getPeakScore() {
            return peakScore;
        }

        /**
         * Get duration this chunk has been a hotspot (ms)
         */
        public long getDuration() {
            return System.currentTimeMillis() - firstDetected;
        }

        /**
         * Convert to a JSON-serializable map
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("key", key.toString());
            map.put("worldName", key.getWorldName());
            map.put("chunkX", key.getX());
            map.put("chunkZ", key.getZ());
            map.put("centerX", key.getCenterBlockX());
            map.put("centerZ", key.getCenterBlockZ());
            map.put("level", currentLevel.name());
            map.put("score", currentScore);
            map.put("peakScore", peakScore);
            map.put("firstDetected", firstDetected);
            map.put("lastUpdate", lastUpdate);
            map.put("duration", getDuration());
            map.put("updateCount", updateCount);
            map.put("entityCount", latestData.getTotalEntityCount());
            map.put("tileEntityCount", latestData.getTotalTileEntityCount());
            map.put("redstoneUpdates", latestData.getRedstoneUpdatesPerSecond());
            return map;
        }
    }
}
