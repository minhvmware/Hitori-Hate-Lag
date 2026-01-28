package com.hitori.antilag.core;

import com.hitori.antilag.HitoriAntiLag;
import com.hitori.antilag.config.ConfigManager;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.Map;

import static java.util.Map.entry;

/**
 * Calculates lag scores for chunks based on weighted entity and tile entity
 * counts
 */
public class LagScorer {

    private final HitoriAntiLag plugin;
    private final ConfigManager config;

    // Default entity weights - higher = more lag impact
    private static final Map<EntityType, Double> DEFAULT_ENTITY_WEIGHTS = Map.ofEntries(
            // High impact entities (complex AI)
            entry(EntityType.VILLAGER, 3.5),
            entry(EntityType.IRON_GOLEM, 2.0),
            entry(EntityType.WITHER, 5.0),
            entry(EntityType.ENDER_DRAGON, 8.0),
            entry(EntityType.WARDEN, 4.0),
            entry(EntityType.PIGLIN, 2.0),
            entry(EntityType.PIGLIN_BRUTE, 2.0),

            // Medium impact (standard mob AI)
            entry(EntityType.ZOMBIE, 1.2),
            entry(EntityType.SKELETON, 1.2),
            entry(EntityType.CREEPER, 1.0),
            entry(EntityType.SPIDER, 1.1),
            entry(EntityType.ENDERMAN, 1.3),
            entry(EntityType.BLAZE, 1.3),
            entry(EntityType.WITCH, 1.5),
            entry(EntityType.PILLAGER, 1.5),
            entry(EntityType.VINDICATOR, 1.5),
            entry(EntityType.RAVAGER, 2.5),
            entry(EntityType.HOGLIN, 1.5),
            entry(EntityType.ZOGLIN, 1.5),

            // Low impact (simple AI)
            entry(EntityType.CHICKEN, 0.5),
            entry(EntityType.COW, 0.5),
            entry(EntityType.SHEEP, 0.5),
            entry(EntityType.PIG, 0.5),
            entry(EntityType.RABBIT, 0.4),
            entry(EntityType.BAT, 0.3),
            entry(EntityType.SQUID, 0.4),
            entry(EntityType.GLOW_SQUID, 0.4),
            entry(EntityType.COD, 0.3),
            entry(EntityType.SALMON, 0.3),
            entry(EntityType.TROPICAL_FISH, 0.3),

            // Items and projectiles
            entry(EntityType.ITEM, 0.3),
            entry(EntityType.EXPERIENCE_ORB, 0.2),
            entry(EntityType.ARROW, 0.4),
            entry(EntityType.SPECTRAL_ARROW, 0.4),
            entry(EntityType.TRIDENT, 0.5),
            entry(EntityType.SNOWBALL, 0.2),
            entry(EntityType.EGG, 0.2),
            entry(EntityType.ENDER_PEARL, 0.3),

            // Physics entities
            entry(EntityType.TNT, 2.5),
            entry(EntityType.FALLING_BLOCK, 1.0),
            entry(EntityType.FIREWORK_ROCKET, 1.5),

            // Decorative (low impact)
            entry(EntityType.ITEM_FRAME, 0.2),
            entry(EntityType.GLOW_ITEM_FRAME, 0.2),
            entry(EntityType.ARMOR_STAND, 0.3),
            entry(EntityType.PAINTING, 0.1));

    // Default tile entity weights
    private static final Map<Material, Double> DEFAULT_TILE_ENTITY_WEIGHTS = Map.of(
            Material.HOPPER, 2.0,
            Material.FURNACE, 0.8,
            Material.BLAST_FURNACE, 0.8,
            Material.SMOKER, 0.8,
            Material.BREWING_STAND, 0.5,
            Material.BEACON, 1.5,
            Material.CONDUIT, 1.2,
            Material.PISTON, 0.6,
            Material.OBSERVER, 1.5,
            Material.COMPARATOR, 1.2);

    public LagScorer(HitoriAntiLag plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }

    /**
     * Calculate the lag score for a chunk based on its contents
     */
    public double calculateChunkScore(ChunkData data) {
        double score = 0.0;

        // Get configured weights (fall back to defaults)
        Map<EntityType, Double> entityWeights = config.getEntityWeights();
        Map<Material, Double> tileWeights = config.getTileEntityWeights();

        // Calculate entity score
        for (Map.Entry<EntityType, Integer> entry : data.getEntityCounts().entrySet()) {
            double weight = entityWeights.getOrDefault(entry.getKey(),
                    DEFAULT_ENTITY_WEIGHTS.getOrDefault(entry.getKey(), 1.0));
            score += entry.getValue() * weight;
        }

        // Calculate tile entity score
        for (Map.Entry<Material, Integer> entry : data.getTileEntityCounts().entrySet()) {
            double weight = tileWeights.getOrDefault(entry.getKey(),
                    DEFAULT_TILE_ENTITY_WEIGHTS.getOrDefault(entry.getKey(), 0.5));
            score += entry.getValue() * weight;
        }

        // Apply redstone activity multiplier (1.0 to 2.0 based on activity)
        int redstoneUpdates = data.getRedstoneUpdatesPerSecond();
        double redstoneMultiplier = 1.0 + Math.min(redstoneUpdates / 100.0, 1.0);
        score *= redstoneMultiplier;

        return score;
    }

    /**
     * Classify a chunk's hotspot level based on its lag score
     */
    public HotspotLevel classifyChunk(double score) {
        double critical = config.getHotspotThresholdCritical();
        double high = config.getHotspotThresholdHigh();
        double medium = config.getHotspotThresholdMedium();

        if (score >= critical)
            return HotspotLevel.CRITICAL;
        if (score >= high)
            return HotspotLevel.HIGH;
        if (score >= medium)
            return HotspotLevel.MEDIUM;
        return HotspotLevel.NORMAL;
    }

    /**
     * Calculate score and classify in one operation
     */
    public void scoreAndClassify(ChunkData data) {
        double score = calculateChunkScore(data);
        data.setLagScore(score);
        data.setHotspotLevel(classifyChunk(score));
    }

    /**
     * Get the weight for a specific entity type
     */
    public double getEntityWeight(EntityType type) {
        Map<EntityType, Double> configured = config.getEntityWeights();
        return configured.getOrDefault(type,
                DEFAULT_ENTITY_WEIGHTS.getOrDefault(type, 1.0));
    }

    /**
     * Get the weight for a specific tile entity material
     */
    public double getTileEntityWeight(Material material) {
        Map<Material, Double> configured = config.getTileEntityWeights();
        return configured.getOrDefault(material,
                DEFAULT_TILE_ENTITY_WEIGHTS.getOrDefault(material, 0.5));
    }
}
