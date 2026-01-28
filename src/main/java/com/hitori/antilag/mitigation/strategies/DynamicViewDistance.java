package com.hitori.antilag.mitigation.strategies;

import com.hitori.antilag.HitoriAntiLag;
import com.hitori.antilag.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamically adjusts view and simulation distance based on server performance
 * FIX: Store base distance per-world to handle different world settings
 */
public class DynamicViewDistance {

    private final HitoriAntiLag plugin;
    private final ConfigManager config;

    // FIX: Store base values per-world, not globally
    // Admin may set Overworld=10, Nether=6, End=8 differently
    private final Map<String, Integer> baseViewDistances = new ConcurrentHashMap<>();
    private final Map<String, Integer> baseSimDistances = new ConcurrentHashMap<>();

    // Current adjusted values (still global for simplicity in reduction ratio)
    private int reductionLevel = 0; // 0 = none, 1 = warning, 2 = critical, 3 = emergency

    // Recovery tracking
    private long lastCriticalTime = 0;

    public DynamicViewDistance(HitoriAntiLag plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();

        // Capture initial view distances for ALL worlds
        captureBaseDistances();
    }

    /**
     * FIX: Capture base distances for each world individually
     */
    private void captureBaseDistances() {
        for (World world : Bukkit.getWorlds()) {
            String name = world.getName();
            baseViewDistances.put(name, world.getViewDistance());
            baseSimDistances.put(name, world.getSimulationDistance());

            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("Captured base distances for " + name +
                        ": view=" + world.getViewDistance() + ", sim=" + world.getSimulationDistance());
            }
        }
    }

    /**
     * Evaluate current MSPT and adjust view distance if needed
     */
    public void evaluate(double currentMspt) {
        if (!config.isDynamicViewDistanceEnabled()) {
            return;
        }

        // Ensure we have base values (in case worlds loaded after plugin)
        for (World world : Bukkit.getWorlds()) {
            if (!baseViewDistances.containsKey(world.getName())) {
                baseViewDistances.put(world.getName(), world.getViewDistance());
                baseSimDistances.put(world.getName(), world.getSimulationDistance());
            }
        }

        double emergency = config.getMsptEmergencyThreshold();
        double critical = config.getMsptCriticalThreshold();
        double warning = config.getMsptWarningThreshold();

        int targetReduction;
        if (currentMspt >= emergency) {
            targetReduction = 3; // Emergency - aggressive
            lastCriticalTime = System.currentTimeMillis();
        } else if (currentMspt >= critical) {
            targetReduction = 2; // Critical - moderate
            lastCriticalTime = System.currentTimeMillis();
        } else if (currentMspt >= warning) {
            targetReduction = 1; // Warning - light
        } else {
            // Check if we can recover
            long recoveryDelayMs = config.getViewDistanceRecoveryDelaySeconds() * 1000L;
            if (System.currentTimeMillis() - lastCriticalTime < recoveryDelayMs) {
                targetReduction = reductionLevel; // Keep current during recovery
            } else {
                targetReduction = 0; // Back to normal
            }
        }

        // Apply if changed
        if (targetReduction != reductionLevel) {
            applyReduction(targetReduction);
            reductionLevel = targetReduction;
        }
    }

    /**
     * FIX: Apply reduction relative to each world's base distance
     */
    private void applyReduction(int level) {
        int viewReduction = switch (level) {
            case 3 -> 6; // Emergency
            case 2 -> 4; // Critical
            case 1 -> 2; // Warning
            default -> 0; // Normal
        };

        int simReduction = switch (level) {
            case 3 -> 4;
            case 2 -> 3;
            case 1 -> 2;
            default -> 0;
        };

        for (World world : Bukkit.getWorlds()) {
            String name = world.getName();
            int baseView = baseViewDistances.getOrDefault(name, world.getViewDistance());
            int baseSim = baseSimDistances.getOrDefault(name, world.getSimulationDistance());

            int targetView = Math.max(config.getMinViewDistance(), baseView - viewReduction);
            int targetSim = Math.max(config.getMinSimulationDistance(), baseSim - simReduction);

            try {
                if (world.getViewDistance() != targetView) {
                    world.setViewDistance(targetView);
                }
                if (world.getSimulationDistance() != targetSim) {
                    world.setSimulationDistance(targetSim);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to set view distance for " + name + ": " + e.getMessage());
            }
        }

        if (plugin.getConfigManager().isLogMitigations()) {
            String levelName = switch (level) {
                case 3 -> "EMERGENCY";
                case 2 -> "CRITICAL";
                case 1 -> "WARNING";
                default -> "NORMAL";
            };
            plugin.getLogger().info("View distance level: " + levelName +
                    " (reduction: -" + viewReduction + " view, -" + simReduction + " sim)");
        }
    }

    /**
     * Reset to base view distances for all worlds
     */
    public void reset() {
        for (World world : Bukkit.getWorlds()) {
            String name = world.getName();
            int baseView = baseViewDistances.getOrDefault(name, 10);
            int baseSim = baseSimDistances.getOrDefault(name, 10);

            try {
                world.setViewDistance(baseView);
                world.setSimulationDistance(baseSim);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to reset view distance for " + name);
            }
        }
        reductionLevel = 0;
    }

    /**
     * Get current view distance for a specific world
     */
    public int getCurrentViewDistance(World world) {
        return world.getViewDistance();
    }

    /**
     * Get base view distance for a specific world
     */
    public int getBaseViewDistance(World world) {
        return baseViewDistances.getOrDefault(world.getName(), world.getViewDistance());
    }

    /**
     * Get current reduction level (0-3)
     */
    public int getReductionLevel() {
        return reductionLevel;
    }

    /**
     * Check if view distance is currently reduced
     */
    public boolean isReduced() {
        return reductionLevel > 0;
    }
}
