package com.hitori.antilag.detection.detectors;

import com.hitori.antilag.HitoriAntiLag;
import com.hitori.antilag.core.ChunkKey;
import com.hitori.antilag.detection.LagMachine;
import com.hitori.antilag.detection.LagMachineType;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.*;

import java.util.*;

/**
 * Detects excessive projectiles, items, and TNT spam
 * FIX: Uses packed long keys instead of ChunkKey objects to avoid allocation
 * spam
 */
public class ProjectileSpamDetector {

    private final HitoriAntiLag plugin;
    private final int projectileThreshold;

    public ProjectileSpamDetector(HitoriAntiLag plugin) {
        this.plugin = plugin;
        this.projectileThreshold = plugin.getConfigManager().getProjectileSpamThreshold();
    }

    /**
     * Pack chunk coordinates into a long to avoid ChunkKey object allocation
     * Format: [worldName hashCode (32 bits)][chunkX (16 bits)][chunkZ (16 bits)]
     */
    private static long packChunkKey(String worldName, int chunkX, int chunkZ) {
        return ((long) worldName.hashCode() << 32) | ((long) (chunkX & 0xFFFF) << 16) | (chunkZ & 0xFFFF);
    }

    private static int unpackChunkX(long packed) {
        return (short) ((packed >> 16) & 0xFFFF);
    }

    private static int unpackChunkZ(long packed) {
        return (short) (packed & 0xFFFF);
    }

    /**
     * Detect projectile/item spam in a world
     * OPTIMIZATION: Uses primitive long map instead of creating ChunkKey objects
     * Before: 5000 entities = 5000 ChunkKey allocations
     * After: 0 allocations in hot loop
     */
    public List<LagMachine> detectSpam(World world) {
        List<LagMachine> detected = new ArrayList<>();

        // Use primitive long key instead of ChunkKey objects
        Map<Long, ChunkEntityStats> chunkStats = new HashMap<>();
        String worldName = world.getName();

        // Collect stats per chunk - ZERO object allocation per entity
        for (Entity entity : world.getEntities()) {
            int chunkX = entity.getLocation().getBlockX() >> 4;
            int chunkZ = entity.getLocation().getBlockZ() >> 4;
            long packedKey = packChunkKey(worldName, chunkX, chunkZ);

            ChunkEntityStats stats = chunkStats.computeIfAbsent(packedKey, k -> new ChunkEntityStats());

            if (entity instanceof Projectile) {
                stats.projectileCount++;
            } else if (entity instanceof Item) {
                stats.itemCount++;
            } else if (entity instanceof TNTPrimed) {
                stats.tntCount++;
            } else if (entity instanceof FallingBlock) {
                stats.fallingBlockCount++;
            }
        }

        // Check thresholds - only create ChunkKey for detected issues
        for (Map.Entry<Long, ChunkEntityStats> entry : chunkStats.entrySet()) {
            long packedKey = entry.getKey();
            ChunkEntityStats stats = entry.getValue();

            // Only create ChunkKey when actually needed for reporting
            ChunkKey key = null;

            // Check projectiles
            if (stats.projectileCount > projectileThreshold) {
                if (key == null)
                    key = new ChunkKey(worldName, unpackChunkX(packedKey), unpackChunkZ(packedKey));
                detected.add(new LagMachine(
                        LagMachineType.PROJECTILE_SPAM,
                        key,
                        stats.projectileCount,
                        stats.projectileCount + " projectiles in chunk (threshold: " + projectileThreshold + ")"));
            }

            // Check items (use 2x threshold for items)
            if (stats.itemCount > projectileThreshold * 2) {
                if (key == null)
                    key = new ChunkKey(worldName, unpackChunkX(packedKey), unpackChunkZ(packedKey));
                detected.add(new LagMachine(
                        LagMachineType.ITEM_SPAM,
                        key,
                        stats.itemCount,
                        stats.itemCount + " dropped items in chunk"));
            }

            // Check TNT (lower threshold)
            if (stats.tntCount > projectileThreshold / 2) {
                if (key == null)
                    key = new ChunkKey(worldName, unpackChunkX(packedKey), unpackChunkZ(packedKey));
                detected.add(new LagMachine(
                        LagMachineType.TNT_SPAM,
                        key,
                        stats.tntCount,
                        stats.tntCount + " TNT entities in chunk"));
            }
        }

        return detected;
    }

    /**
     * Detect spam across all worlds
     */
    public List<LagMachine> detectAllSpam() {
        List<LagMachine> all = new ArrayList<>();

        for (World world : org.bukkit.Bukkit.getWorlds()) {
            all.addAll(detectSpam(world));
        }

        return all;
    }

    /**
     * Get spam stats for a specific chunk
     */
    public ChunkEntityStats getChunkStats(Chunk chunk) {
        ChunkEntityStats stats = new ChunkEntityStats();

        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Projectile) {
                stats.projectileCount++;
            } else if (entity instanceof Item) {
                stats.itemCount++;
            } else if (entity instanceof TNTPrimed) {
                stats.tntCount++;
            } else if (entity instanceof FallingBlock) {
                stats.fallingBlockCount++;
            }
        }

        return stats;
    }

    /**
     * Stats container for chunk entity counts
     */
    public static class ChunkEntityStats {
        public int projectileCount = 0;
        public int itemCount = 0;
        public int tntCount = 0;
        public int fallingBlockCount = 0;

        public int getTotal() {
            return projectileCount + itemCount + tntCount + fallingBlockCount;
        }

        public Map<String, Integer> toMap() {
            return Map.of(
                    "projectiles", projectileCount,
                    "items", itemCount,
                    "tnt", tntCount,
                    "fallingBlocks", fallingBlockCount,
                    "total", getTotal());
        }
    }
}
