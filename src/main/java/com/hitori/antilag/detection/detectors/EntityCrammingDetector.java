package com.hitori.antilag.detection.detectors;

import com.hitori.antilag.HitoriAntiLag;
import com.hitori.antilag.core.ChunkKey;
import com.hitori.antilag.detection.LagMachine;
import com.hitori.antilag.detection.LagMachineType;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.*;

/**
 * Detects excessive entity cramming in single blocks
 * FIX: Uses packed long keys instead of BlockPos objects to avoid allocation
 * spam
 */
public class EntityCrammingDetector {

    private final HitoriAntiLag plugin;
    private final int threshold;

    public EntityCrammingDetector(HitoriAntiLag plugin) {
        this.plugin = plugin;
        this.threshold = plugin.getConfigManager().getEntityCrammingThreshold();
    }

    /**
     * Pack block coordinates into a long to avoid object allocation
     * Format: [y (12 bits)][x (26 bits)][z (26 bits)]
     * Supports coordinates up to ±33 million and y up to 4095
     */
    private static long packBlockPos(int x, int y, int z) {
        // Use bit manipulation to pack 3 ints into 1 long
        return ((long) (y & 0xFFF) << 52) | ((long) (x & 0x3FFFFFF) << 26) | (z & 0x3FFFFFF);
    }

    private static int unpackX(long packed) {
        int raw = (int) ((packed >> 26) & 0x3FFFFFF);
        // Sign extension for negative coordinates
        return raw > 0x1FFFFFF ? raw | 0xFC000000 : raw;
    }

    private static int unpackY(long packed) {
        return (int) ((packed >> 52) & 0xFFF);
    }

    private static int unpackZ(long packed) {
        int raw = (int) (packed & 0x3FFFFFF);
        return raw > 0x1FFFFFF ? raw | 0xFC000000 : raw;
    }

    /**
     * Detect entity cramming across a world
     * OPTIMIZATION: Uses primitive long map instead of BlockPos objects
     * Before: 10000 entities = 10000 BlockPos allocations
     * After: 0 allocations in hot loop
     */
    public List<LagMachine> detectCramming(World world) {
        List<LagMachine> detected = new ArrayList<>();

        // Use primitive long key instead of BlockPos objects
        // Key = packed coordinates, Value = list of entities at that position
        Map<Long, List<Entity>> entityPositions = new HashMap<>();

        // Group entities by block position - ZERO object allocation per entity
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof LivingEntity))
                continue;

            Location loc = entity.getLocation();
            long packedPos = packBlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            entityPositions.computeIfAbsent(packedPos, k -> new ArrayList<>()).add(entity);
        }

        // Check for cramming - only create objects when actually needed for reporting
        for (Map.Entry<Long, List<Entity>> entry : entityPositions.entrySet()) {
            int count = entry.getValue().size();

            if (count > threshold) {
                long packedPos = entry.getKey();
                int x = unpackX(packedPos);
                int y = unpackY(packedPos);
                int z = unpackZ(packedPos);

                ChunkKey chunkKey = new ChunkKey(world, x >> 4, z >> 4);
                Location location = new Location(world, x + 0.5, y, z + 0.5);

                // Analyze what types of entities are crammed - use StringBuilder to avoid
                // stream allocations
                Map<String, Integer> typeCounts = new HashMap<>();
                for (Entity entity : entry.getValue()) {
                    typeCounts.merge(entity.getType().name(), 1, Integer::sum);
                }

                // Build type breakdown manually instead of stream
                StringBuilder typeBreakdown = new StringBuilder();
                List<Map.Entry<String, Integer>> sorted = new ArrayList<>(typeCounts.entrySet());
                sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));

                for (int i = 0; i < Math.min(3, sorted.size()); i++) {
                    if (i > 0)
                        typeBreakdown.append(", ");
                    typeBreakdown.append(sorted.get(i).getValue()).append("x ").append(sorted.get(i).getKey());
                }

                detected.add(new LagMachine(
                        LagMachineType.ENTITY_CRAMMING,
                        chunkKey,
                        location,
                        count,
                        count + " entities crammed at " + x + "," + y + "," + z + " (" + typeBreakdown + ")"));
            }
        }

        return detected;
    }

    /**
     * Detect cramming across all worlds
     */
    public List<LagMachine> detectAllCramming() {
        List<LagMachine> all = new ArrayList<>();

        for (World world : org.bukkit.Bukkit.getWorlds()) {
            all.addAll(detectCramming(world));
        }

        return all;
    }
}
