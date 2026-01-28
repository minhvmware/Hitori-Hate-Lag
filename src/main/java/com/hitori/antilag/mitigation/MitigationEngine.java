package com.hitori.antilag.mitigation;

import com.hitori.antilag.HitoriAntiLag;
import com.hitori.antilag.core.ChunkKey;
import com.hitori.antilag.mitigation.strategies.*;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * Main mitigation engine that coordinates all mitigation strategies
 */
public class MitigationEngine {

    private final HitoriAntiLag plugin;

    // Strategies
    private final SmartCulling smartCulling;
    private final RedstoneThrottler redstoneThrottler;
    private final DynamicViewDistance dynamicViewDistance;

    // State
    private ProtectionMode currentMode = ProtectionMode.NORMAL;

    public MitigationEngine(HitoriAntiLag plugin) {
        this.plugin = plugin;

        // Initialize strategies
        this.smartCulling = new SmartCulling(plugin);
        this.redstoneThrottler = new RedstoneThrottler(plugin);
        this.dynamicViewDistance = new DynamicViewDistance(plugin);

        // Register event listeners
        Bukkit.getPluginManager().registerEvents(redstoneThrottler, plugin);
    }

    /**
     * Evaluate current server state and take action if needed
     */
    public void evaluate() {
        double mspt = plugin.getMetricsCollector().getCurrentSnapshot().mspt;

        ProtectionMode newMode = determineMode(mspt);

        if (newMode != currentMode) {
            transitionToMode(newMode, mspt);
        }

        // Always evaluate dynamic view distance
        if (plugin.getConfigManager().isDynamicViewDistanceEnabled()) {
            dynamicViewDistance.evaluate(mspt);
        }
    }

    private ProtectionMode determineMode(double mspt) {
        double emergency = plugin.getConfigManager().getMsptEmergencyThreshold();
        double critical = plugin.getConfigManager().getMsptCriticalThreshold();
        double warning = plugin.getConfigManager().getMsptWarningThreshold();

        if (mspt >= emergency)
            return ProtectionMode.EMERGENCY;
        if (mspt >= critical)
            return ProtectionMode.CRITICAL;
        if (mspt >= warning)
            return ProtectionMode.WARNING;
        return ProtectionMode.NORMAL;
    }

    private void transitionToMode(ProtectionMode newMode, double mspt) {
        ProtectionMode oldMode = currentMode;
        currentMode = newMode;

        if (plugin.getConfigManager().isLogMitigations()) {
            plugin.getLogger().warning(String.format(
                    "Protection mode changed: %s -> %s (MSPT: %.1fms)",
                    oldMode, newMode, mspt));
        }

        // Take action based on mode
        switch (newMode) {
            case EMERGENCY -> handleEmergency();
            case CRITICAL -> handleCritical();
            case WARNING -> handleWarning();
            case NORMAL -> handleNormal();
        }
    }

    private void handleEmergency() {
        // Aggressive culling
        if (plugin.getConfigManager().isSmartCullingEnabled()) {
            int removed = smartCulling.performEmergencyCull();
            plugin.getLogger().warning("Emergency cull removed " + removed + " entities");
        }

        // Enable throttling on all detected clocks
        enableAllThrottling();
    }

    private void handleCritical() {
        // Moderate culling
        if (plugin.getConfigManager().isSmartCullingEnabled()) {
            int removed = smartCulling.performCriticalCull();
            if (removed > 0) {
                plugin.getLogger().info("Critical cull removed " + removed + " entities");
            }
        }

        // Throttle worst offenders
        enableAllThrottling();
    }

    private void handleWarning() {
        // Light culling (items and XP only)
        if (plugin.getConfigManager().isSmartCullingEnabled()) {
            int removed = smartCulling.cullItemsAndXp();
            if (removed > 0) {
                plugin.getLogger().info("Warning cull removed " + removed + " items/XP orbs");
            }
        }
    }

    private void handleNormal() {
        // Disable throttling when back to normal
        redstoneThrottler.disableAllThrottling();
    }

    private void enableAllThrottling() {
        if (!plugin.getConfigManager().isRedstoneThrottlingEnabled())
            return;

        // Get detected redstone clocks and throttle them
        Collection<ChunkKey> clockChunks = plugin.getDetectionEngine()
                .getRedstoneDetector()
                .detectClocks()
                .stream()
                .map(m -> m.getChunkKey())
                .toList();

        int targetRate = plugin.getConfigManager().getThrottleTargetUpdatesPerSecond();
        for (ChunkKey chunk : clockChunks) {
            redstoneThrottler.enableThrottling(chunk, targetRate);
        }
    }

    // ==================== Public Mitigation Methods ====================

    /**
     * Clear all entities in a specific chunk
     */
    public int clearChunk(String worldName, int chunkX, int chunkZ) {
        World world = Bukkit.getWorld(worldName);
        if (world == null)
            return 0;

        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        if (!chunk.isLoaded())
            return 0;

        int removed = 0;
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Player)) {
                if (smartCulling.canCull(entity)) {
                    entity.remove();
                    removed++;
                }
            }
        }

        if (plugin.getConfigManager().isLogMitigations()) {
            plugin.getLogger()
                    .info("Cleared " + removed + " entities from chunk " + worldName + ":" + chunkX + "," + chunkZ);
        }

        return removed;
    }

    /**
     * Force a cull operation
     */
    public int forceCull(int targetCount) {
        int total = 0;
        for (World world : Bukkit.getWorlds()) {
            total += smartCulling.performSmartCull(world, targetCount / Bukkit.getWorlds().size());
        }
        return total;
    }

    /**
     * Get current protection mode
     */
    public ProtectionMode getCurrentMode() {
        return currentMode;
    }

    // Strategy getters
    public SmartCulling getSmartCulling() {
        return smartCulling;
    }

    public RedstoneThrottler getRedstoneThrottler() {
        return redstoneThrottler;
    }

    public DynamicViewDistance getDynamicViewDistance() {
        return dynamicViewDistance;
    }
}
