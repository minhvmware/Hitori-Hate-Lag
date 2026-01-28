package com.hitori.antilag.mitigation.strategies;

import com.hitori.antilag.HitoriAntiLag;
import com.hitori.antilag.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;

import java.util.*;

/**
 * Smart entity culling that prioritizes what to remove
 * FIX: No longer sorts entire world entity list during emergency
 * Uses chunk-by-chunk processing to minimize lag spikes
 */
public class SmartCulling {

    private final HitoriAntiLag plugin;
    private final ConfigManager config;

    // Cooldown tracking
    private long lastCullTime = 0;

    public SmartCulling(HitoriAntiLag plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }

    /**
     * Priority levels for culling (lower = removed first)
     */
    private enum CullPriority {
        DROPPED_ITEMS(1),
        XP_ORBS(2),
        PROJECTILES(3),
        DISTANT_HOSTILE(4),
        UNNAMED_HOSTILE(5),
        UNNAMED_PASSIVE(6),
        PROTECTED(Integer.MAX_VALUE);

        final int priority;

        CullPriority(int priority) {
            this.priority = priority;
        }
    }

    /**
     * Perform emergency cull - aggressive removal
     * FIX: Process per-chunk instead of sorting entire world list
     * This prevents massive lag spike during emergency mode
     */
    public int performEmergencyCull() {
        if (!checkCooldown())
            return 0;

        int total = 0;
        for (World world : Bukkit.getWorlds()) {
            // Direct removal without sorting - much faster during emergency
            total += fastCullByType(world, Item.class, Integer.MAX_VALUE);
            total += fastCullByType(world, ExperienceOrb.class, Integer.MAX_VALUE);
            total += fastCullByType(world, Projectile.class, 500);
            total += fastCullDistantHostiles(world, 300);
        }

        return total;
    }

    /**
     * Perform critical cull - moderate removal
     */
    public int performCriticalCull() {
        if (!checkCooldown())
            return 0;

        int total = 0;
        for (World world : Bukkit.getWorlds()) {
            total += fastCullByType(world, Item.class, 200);
            total += fastCullByType(world, ExperienceOrb.class, 100);
            total += fastCullByType(world, Projectile.class, 50);
        }

        return total;
    }

    /**
     * Cull only items and XP orbs
     */
    public int cullItemsAndXp() {
        if (!checkCooldown())
            return 0;

        int total = 0;
        for (World world : Bukkit.getWorlds()) {
            total += fastCullByType(world, Item.class, 100);
            total += fastCullByType(world, ExperienceOrb.class, 50);
        }

        return total;
    }

    /**
     * FIX: Fast cull by entity type - no sorting, no wrapper objects
     * Process chunk-by-chunk to minimize list allocation
     */
    private <T extends Entity> int fastCullByType(World world, Class<T> entityClass, int maxCount) {
        int removed = 0;

        // Process per-loaded-chunk instead of getting all world entities
        for (Chunk chunk : world.getLoadedChunks()) {
            if (removed >= maxCount)
                break;

            for (Entity entity : chunk.getEntities()) {
                if (removed >= maxCount)
                    break;
                if (!entityClass.isInstance(entity))
                    continue;

                entity.remove();
                removed++;
            }
        }

        return removed;
    }

    /**
     * FIX: Fast cull distant hostiles without creating candidate list
     */
    private int fastCullDistantHostiles(World world, int maxCount) {
        int removed = 0;
        List<Player> players = world.getPlayers();
        if (players.isEmpty())
            return 0;

        double distSquared = 64 * 64; // 64 blocks

        for (Chunk chunk : world.getLoadedChunks()) {
            if (removed >= maxCount)
                break;

            for (Entity entity : chunk.getEntities()) {
                if (removed >= maxCount)
                    break;
                if (!(entity instanceof Monster monster))
                    continue;
                if (!canCull(entity))
                    continue;

                // Check distance from all players
                boolean isDistant = true;
                Location loc = entity.getLocation();
                for (Player player : players) {
                    if (player.getLocation().distanceSquared(loc) < distSquared) {
                        isDistant = false;
                        break;
                    }
                }

                if (isDistant) {
                    monster.remove();
                    removed++;
                }
            }
        }

        return removed;
    }

    /**
     * Perform smart cull in a world with target reduction
     * FIX: Use bounded priority queue instead of sorting all candidates
     */
    public int performSmartCull(World world, int targetReduction) {
        // Use priority queue with limited capacity - avoids sorting entire list
        PriorityQueue<EntityCullCandidate> candidates = new PriorityQueue<>(
                Math.min(targetReduction * 2, 500), // Cap capacity
                Comparator.comparingInt(c -> c.priority.priority));

        int scanned = 0;
        final int maxScan = targetReduction * 10; // Don't scan infinite entities

        for (Chunk chunk : world.getLoadedChunks()) {
            for (Entity entity : chunk.getEntities()) {
                if (scanned++ > maxScan)
                    break;

                CullPriority priority = calculatePriority(entity);
                if (priority != CullPriority.PROTECTED) {
                    candidates.offer(new EntityCullCandidate(entity, priority));

                    // Keep queue bounded
                    while (candidates.size() > targetReduction * 2) {
                        candidates.poll();
                    }
                }
            }
            if (scanned > maxScan)
                break;
        }

        int removed = 0;
        while (!candidates.isEmpty() && removed < targetReduction) {
            EntityCullCandidate candidate = candidates.poll();
            if (candidate.entity.isValid()) {
                candidate.entity.remove();
                removed++;
            }
        }

        return removed;
    }

    /**
     * Calculate cull priority for an entity
     */
    private CullPriority calculatePriority(Entity entity) {
        // Protected entities - NEVER cull
        if (entity instanceof Player)
            return CullPriority.PROTECTED;

        // Check for tamed mobs
        if (config.isProtectTamedMobs() && entity instanceof Tameable tameable) {
            if (tameable.isTamed())
                return CullPriority.PROTECTED;
        }

        // Check for named mobs
        if (config.isProtectNamedMobs() && entity.getCustomName() != null) {
            return CullPriority.PROTECTED;
        }

        // Check for leashed mobs
        if (config.isProtectLeashedMobs() && entity instanceof LivingEntity living) {
            if (living.isLeashed())
                return CullPriority.PROTECTED;
        }

        // Check for vehicle passengers
        if (config.isProtectVehiclePassengers()) {
            if (entity.isInsideVehicle())
                return CullPriority.PROTECTED;
            if (!entity.getPassengers().isEmpty())
                return CullPriority.PROTECTED;
        }

        // Check for protection tag
        String protectionTag = config.getProtectionTag();
        if (entity.getScoreboardTags().contains(protectionTag)) {
            return CullPriority.PROTECTED;
        }

        // Categorize by type
        if (entity instanceof Item)
            return CullPriority.DROPPED_ITEMS;
        if (entity instanceof ExperienceOrb)
            return CullPriority.XP_ORBS;
        if (entity instanceof Projectile)
            return CullPriority.PROJECTILES;

        if (entity instanceof Monster) {
            return CullPriority.UNNAMED_HOSTILE;
        }

        if (entity instanceof Animals) {
            return CullPriority.UNNAMED_PASSIVE;
        }

        return CullPriority.UNNAMED_HOSTILE;
    }

    /**
     * Check if entity can be culled
     */
    public boolean canCull(Entity entity) {
        return calculatePriority(entity) != CullPriority.PROTECTED;
    }

    /**
     * Check cooldown before culling
     */
    private boolean checkCooldown() {
        long now = System.currentTimeMillis();
        int cooldownMs = config.getCullingCooldownSeconds() * 1000;

        if (now - lastCullTime < cooldownMs) {
            return false;
        }

        lastCullTime = now;
        return true;
    }

    /**
     * Internal class for priority queue sorting
     */
    private static class EntityCullCandidate {
        final Entity entity;
        final CullPriority priority;

        EntityCullCandidate(Entity entity, CullPriority priority) {
            this.entity = entity;
            this.priority = priority;
        }
    }
}
