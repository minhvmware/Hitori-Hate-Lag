package com.hitori.antilag.core;

import org.bukkit.Chunk;
import org.bukkit.World;

/**
 * Immutable key for identifying a chunk across worlds
 */
public final class ChunkKey {

    private final String worldName;
    private final int x;
    private final int z;
    private final int hashCode;

    public ChunkKey(Chunk chunk) {
        this(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
    }

    public ChunkKey(World world, int x, int z) {
        this(world.getName(), x, z);
    }

    public ChunkKey(String worldName, int x, int z) {
        this.worldName = worldName;
        this.x = x;
        this.z = z;
        // Manual hash calculation - avoids Objects.hash() varargs array allocation
        // and int->Integer boxing. Critical for high-frequency object creation.
        int result = worldName.hashCode();
        result = 31 * result + x;
        result = 31 * result + z;
        this.hashCode = result;
    }

    public String getWorldName() {
        return worldName;
    }

    public int getX() {
        return x;
    }

    public int getZ() {
        return z;
    }

    /**
     * Get the center block X coordinate of this chunk
     */
    public int getCenterBlockX() {
        return (x << 4) + 8;
    }

    /**
     * Get the center block Z coordinate of this chunk
     */
    public int getCenterBlockZ() {
        return (z << 4) + 8;
    }

    /**
     * Get minimum block X coordinate of this chunk
     */
    public int getMinBlockX() {
        return x << 4;
    }

    /**
     * Get minimum block Z coordinate of this chunk
     */
    public int getMinBlockZ() {
        return z << 4;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ChunkKey chunkKey = (ChunkKey) o;
        return x == chunkKey.x && z == chunkKey.z && worldName.equals(chunkKey.worldName);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        // Display block coordinates (center of chunk) for easier in-game navigation
        // Instead of: "world:-7364,-7601" (chunk coords - confusing)
        // Now shows: "world:(-117816, -121608)" (block coords - teleportable)
        return worldName + ":(" + getCenterBlockX() + ", " + getCenterBlockZ() + ")";
    }

    /**
     * Parse a ChunkKey from its string representation
     */
    public static ChunkKey fromString(String str) {
        int colonIndex = str.lastIndexOf(':');
        if (colonIndex == -1) {
            throw new IllegalArgumentException("Invalid ChunkKey format: " + str);
        }

        String worldName = str.substring(0, colonIndex);
        String[] coords = str.substring(colonIndex + 1).split(",");

        if (coords.length != 2) {
            throw new IllegalArgumentException("Invalid ChunkKey format: " + str);
        }

        return new ChunkKey(worldName, Integer.parseInt(coords[0]), Integer.parseInt(coords[1]));
    }
}
