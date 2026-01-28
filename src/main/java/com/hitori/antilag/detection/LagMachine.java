package com.hitori.antilag.detection;

import com.hitori.antilag.core.ChunkKey;

import java.util.Map;
import java.util.UUID;

/**
 * Represents a detected lag machine or problematic structure
 * FIX: Stores primitives instead of Location to prevent memory leak when World
 * unloads
 */
public class LagMachine {

    private final UUID id;
    private final LagMachineType type;
    private final ChunkKey chunkKey;

    // FIX: Store primitives instead of Location to prevent memory leak
    // If we store Location, and the World gets unloaded, GC can't release the World
    // RAM
    private final String worldName;
    private final int locX, locY, locZ;
    private final boolean hasExactLocation;

    private final double severity;
    private final String details;
    private final long detectedAt;

    public LagMachine(LagMachineType type, ChunkKey chunkKey, double severity, String details) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.chunkKey = chunkKey;
        this.worldName = chunkKey.getWorldName();
        this.locX = chunkKey.getCenterBlockX();
        this.locY = 64;
        this.locZ = chunkKey.getCenterBlockZ();
        this.hasExactLocation = false;
        this.severity = severity;
        this.details = details;
        this.detectedAt = System.currentTimeMillis();
    }

    public LagMachine(LagMachineType type, ChunkKey chunkKey, org.bukkit.Location location, double severity,
            String details) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.chunkKey = chunkKey;

        // Extract primitives from Location immediately - don't hold reference
        if (location != null) {
            this.worldName = location.getWorld() != null ? location.getWorld().getName() : chunkKey.getWorldName();
            this.locX = location.getBlockX();
            this.locY = location.getBlockY();
            this.locZ = location.getBlockZ();
            this.hasExactLocation = true;
        } else {
            this.worldName = chunkKey.getWorldName();
            this.locX = chunkKey.getCenterBlockX();
            this.locY = 64;
            this.locZ = chunkKey.getCenterBlockZ();
            this.hasExactLocation = false;
        }

        this.severity = severity;
        this.details = details;
        this.detectedAt = System.currentTimeMillis();
    }

    public UUID getId() {
        return id;
    }

    public LagMachineType getType() {
        return type;
    }

    public ChunkKey getChunkKey() {
        return chunkKey;
    }

    public String getWorldName() {
        return worldName;
    }

    public int getX() {
        return locX;
    }

    public int getY() {
        return locY;
    }

    public int getZ() {
        return locZ;
    }

    public boolean hasExactLocation() {
        return hasExactLocation;
    }

    public double getSeverity() {
        return severity;
    }

    public String getDetails() {
        return details;
    }

    public long getDetectedAt() {
        return detectedAt;
    }

    /**
     * Get block coordinates
     */
    public int[] getApproximateCoordinates() {
        return new int[] { locX, locY, locZ };
    }

    /**
     * Convert to a JSON-serializable map
     */
    public Map<String, Object> toMap() {
        // Use HashMap since we have > 10 entries (Map.of max is 10)
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", id.toString());
        map.put("type", type.name());
        map.put("typeName", type.getDisplayName());
        map.put("chunkKey", chunkKey.toString());
        map.put("worldName", worldName);
        map.put("x", locX);
        map.put("y", locY);
        map.put("z", locZ);
        map.put("severity", severity);
        map.put("details", details);
        map.put("detectedAt", detectedAt);
        return map;
    }

    @Override
    public String toString() {
        return String.format("%s at %s (severity: %.1f) - %s",
                type.getDisplayName(), chunkKey, severity, details);
    }
}
