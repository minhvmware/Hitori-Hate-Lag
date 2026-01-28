package com.hitori.antilag.commands;

import com.hitori.antilag.HitoriAntiLag;
import com.hitori.antilag.core.HotspotLevel;
import com.hitori.antilag.core.HotspotTracker.HotspotEntry;
import com.hitori.antilag.core.MetricsCollector.MetricsSnapshot;
import com.hitori.antilag.detection.LagMachine;
import com.hitori.antilag.mitigation.ProtectionMode;
import com.hitori.antilag.prediction.TrendAnalyzer.TrendResult;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Main command handler for /antilag
 */
public class AntiLagCommand implements CommandExecutor, TabCompleter {

    private final HitoriAntiLag plugin;

    public AntiLagCommand(HitoriAntiLag plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hitori.antilag.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            showStatus(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "status" -> showStatus(sender);
            case "scan" -> runScan(sender);
            case "hotspots" -> showHotspots(sender, args);
            case "detections" -> showDetections(sender);
            case "cull" -> performCull(sender, args);
            case "clear" -> clearChunk(sender, args);
            case "trend" -> showTrend(sender);
            case "baseline" -> showBaseline(sender);
            case "config" -> showConfig(sender);
            case "reload" -> reloadConfig(sender);
            case "web" -> showWebInfo(sender);
            case "help" -> showHelp(sender);
            default -> {
                sender.sendMessage("§cUnknown subcommand. Use /antilag help for a list of commands.");
            }
        }

        return true;
    }

    private void showStatus(CommandSender sender) {
        MetricsSnapshot metrics = plugin.getMetricsCollector().getCurrentSnapshot();
        ProtectionMode mode = plugin.getMitigationEngine().getCurrentMode();

        sender.sendMessage("§6§l=== Hitori Anti-Lag Status ===");
        sender.sendMessage("");

        // Server metrics
        sender.sendMessage("§e§lServer Performance:");
        sender.sendMessage(String.format("  §7TPS: %s%.1f §7| MSPT: %s%.1fms",
                getTpsColor(metrics.tps), metrics.tps,
                getMsptColor(metrics.mspt), metrics.mspt));
        sender.sendMessage(String.format("  §7Entities: §f%d §7| Chunks: §f%d §7| Players: §f%d",
                metrics.entityCount, metrics.loadedChunks, metrics.playerCount));
        sender.sendMessage(String.format("  §7Memory: §f%d/%d MB §7(%.1f%%)",
                metrics.usedMemoryMb, metrics.maxMemoryMb,
                (double) metrics.usedMemoryMb / metrics.maxMemoryMb * 100));

        sender.sendMessage("");

        // Protection mode
        sender.sendMessage("§e§lProtection Mode: " + mode.getColorCode() + mode.getDisplayName());

        // Hotspot summary
        Map<HotspotLevel, Integer> hotspotCounts = plugin.getHotspotTracker().getHotspotCounts();
        sender.sendMessage(String.format("§e§lHotspots: §cCritical: %d §6High: %d §eMedium: %d",
                hotspotCounts.getOrDefault(HotspotLevel.CRITICAL, 0),
                hotspotCounts.getOrDefault(HotspotLevel.HIGH, 0),
                hotspotCounts.getOrDefault(HotspotLevel.MEDIUM, 0)));

        // Active detections
        int detections = plugin.getDetectionEngine().getDetectedMachines().size();
        if (detections > 0) {
            sender.sendMessage("§e§lActive Detections: §c" + detections);
        }

        // Trend info
        TrendResult trend = plugin.getPredictionEngine().getLatestTrend();
        if (trend != null && trend.hasData) {
            String trendIcon = trend.msptRateOfChange > 0 ? "§c↑" : (trend.msptRateOfChange < 0 ? "§a↓" : "§7→");
            sender.sendMessage(String.format("§e§lTrend: %s §7(%.2f ms/s) | Predicted: §f%.1fms",
                    trendIcon, trend.msptRateOfChange, trend.predictedMspt));
        }

        sender.sendMessage("");
        sender.sendMessage("§7Use §f/antilag help §7for more commands.");
    }

    private void runScan(CommandSender sender) {
        sender.sendMessage("§eStarting chunk scan...");

        plugin.getChunkAnalyzer().scanAllChunksAsync().thenAccept(results -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                int total = results.size();
                long hotspots = results.values().stream()
                        .filter(d -> d.getHotspotLevel().isAtLeast(HotspotLevel.MEDIUM))
                        .count();

                sender.sendMessage(String.format("§aScan complete. Analyzed §f%d §achunks, found §c%d §ahotspots.",
                        total, hotspots));
            });
        });
    }

    private void showHotspots(CommandSender sender, String[] args) {
        HotspotLevel minLevel = HotspotLevel.MEDIUM;
        if (args.length > 1) {
            try {
                minLevel = HotspotLevel.valueOf(args[1].toUpperCase());
            } catch (IllegalArgumentException e) {
                sender.sendMessage("§cInvalid level. Use: MEDIUM, HIGH, or CRITICAL");
                return;
            }
        }

        List<HotspotEntry> hotspots = plugin.getHotspotTracker().getHotspots(minLevel);

        if (hotspots.isEmpty()) {
            sender.sendMessage("§aNo hotspots at or above " + minLevel + " level.");
            return;
        }

        sender.sendMessage("§6§l=== Hotspots (" + minLevel + "+) ===");

        int shown = 0;
        for (HotspotEntry entry : hotspots) {
            if (shown >= 10) {
                sender.sendMessage("§7... and " + (hotspots.size() - 10) + " more");
                break;
            }

            String color = entry.getCurrentLevel().getColorCode();
            sender.sendMessage(String.format("%s[%s] §f%s §7Score: %.1f | Entities: %d",
                    color, entry.getCurrentLevel().name().charAt(0),
                    entry.getKey(),
                    entry.getCurrentScore(),
                    entry.getLatestData().getTotalEntityCount()));

            if (sender instanceof Player player) {
                int[] coords = new int[] {
                        entry.getKey().getCenterBlockX(),
                        64,
                        entry.getKey().getCenterBlockZ()
                };
                sender.sendMessage(String.format("  §7  /tp %s %d %d %d",
                        player.getName(), coords[0], coords[1], coords[2]));
            }

            shown++;
        }
    }

    private void showDetections(CommandSender sender) {
        Collection<LagMachine> detections = plugin.getDetectionEngine().getDetectedMachines();

        if (detections.isEmpty()) {
            sender.sendMessage("§aNo active lag machine detections.");
            return;
        }

        sender.sendMessage("§6§l=== Active Detections ===");

        for (LagMachine machine : detections) {
            sender.sendMessage(String.format("§c[%s] §f%s",
                    machine.getType().getDisplayName(),
                    machine.getChunkKey()));
            sender.sendMessage("  §7" + machine.getDetails());
        }
    }

    private void performCull(CommandSender sender, String[] args) {
        int target = 100;
        if (args.length > 1) {
            try {
                target = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid number. Usage: /antilag cull [count]");
                return;
            }
        }

        int finalTarget = target;
        Bukkit.getScheduler().runTask(plugin, () -> {
            int removed = plugin.getMitigationEngine().forceCull(finalTarget);
            sender.sendMessage("§aRemoved §f" + removed + " §aentities.");
        });
    }

    private void clearChunk(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /antilag clear <world> <chunkX> <chunkZ>");
            return;
        }

        String worldName = args[1];
        int chunkX, chunkZ;

        try {
            chunkX = Integer.parseInt(args[2]);
            chunkZ = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cInvalid coordinates.");
            return;
        }

        if (Bukkit.getWorld(worldName) == null) {
            sender.sendMessage("§cWorld not found: " + worldName);
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            int removed = plugin.getMitigationEngine().clearChunk(worldName, chunkX, chunkZ);
            sender.sendMessage(String.format("§aCleared §f%d §aentities from chunk %s:%d,%d",
                    removed, worldName, chunkX, chunkZ));
        });
    }

    private void showTrend(CommandSender sender) {
        TrendResult trend = plugin.getPredictionEngine().getLatestTrend();

        if (trend == null || !trend.hasData) {
            sender.sendMessage("§eInsufficient data for trend analysis. Please wait for more samples.");
            return;
        }

        sender.sendMessage("§6§l=== Trend Analysis ===");
        sender.sendMessage(String.format("§eCurrent MSPT: §f%.1fms", trend.currentMspt));
        sender.sendMessage(String.format("§ePredicted MSPT (60s): §f%.1fms", trend.predictedMspt));
        sender.sendMessage(String.format("§eRate of change: §f%.3f ms/sec", trend.msptRateOfChange));
        sender.sendMessage(String.format("§eCurrent entities: §f%d", trend.currentEntities));
        sender.sendMessage(String.format("§ePredicted entities: §f%d", trend.predictedEntities));
        sender.sendMessage(String.format("§eSeverity: %s%s",
                getSeverityColor(trend.severity.name()), trend.severity));
    }

    private void showBaseline(CommandSender sender) {
        var stats = plugin.getPredictionEngine().getBaselineManager().getStats();

        if (!stats.hasData()) {
            sender.sendMessage("§eBaseline not yet established. Need " +
                    (60 - stats.sampleCount) + " more healthy samples.");
            return;
        }

        sender.sendMessage("§6§l=== Baseline Statistics ===");
        sender.sendMessage(String.format("§eMSPT: §f%.1f ±%.1f ms", stats.meanMspt, stats.stdDevMspt));
        sender.sendMessage(String.format("§eEntities: §f%.0f ±%.0f", stats.meanEntities, stats.stdDevEntities));
        sender.sendMessage(String.format("§eChunks: §f%.0f ±%.0f", stats.meanChunks, stats.stdDevChunks));
        sender.sendMessage(String.format("§eSamples: §f%d", stats.sampleCount));
    }

    private void showConfig(CommandSender sender) {
        sender.sendMessage("§6§l=== Configuration ===");
        sender.sendMessage("§eWeb Dashboard: " +
                (plugin.getConfigManager().isWebEnabled()
                        ? "§aEnabled (port " + plugin.getConfigManager().getWebPort() + ")"
                        : "§cDisabled"));
        sender.sendMessage("§eSmart Culling: " +
                (plugin.getConfigManager().isSmartCullingEnabled() ? "§aEnabled" : "§cDisabled"));
        sender.sendMessage("§eRedstone Throttling: " +
                (plugin.getConfigManager().isRedstoneThrottlingEnabled() ? "§aEnabled" : "§cDisabled"));
        sender.sendMessage("§eDynamic View Distance: " +
                (plugin.getConfigManager().isDynamicViewDistanceEnabled() ? "§aEnabled" : "§cDisabled"));
        sender.sendMessage("§eTrend Analysis: " +
                (plugin.getConfigManager().isTrendAnalysisEnabled() ? "§aEnabled" : "§cDisabled"));
        sender.sendMessage("§eAnomaly Detection: " +
                (plugin.getConfigManager().isAnomalyDetectionEnabled() ? "§aEnabled" : "§cDisabled"));
    }

    private void reloadConfig(CommandSender sender) {
        plugin.reload();
        sender.sendMessage("§aConfiguration reloaded.");
    }

    private void showWebInfo(CommandSender sender) {
        if (!plugin.getConfigManager().isWebEnabled()) {
            sender.sendMessage("§cWeb dashboard is disabled in config.");
            return;
        }

        int port = plugin.getConfigManager().getWebPort();
        sender.sendMessage("§6§l=== Web Dashboard ===");
        sender.sendMessage("§eURL: §fhttp://localhost:" + port);
        sender.sendMessage("§eAuth Token: §7(see config.yml)");
        sender.sendMessage("§7Access the dashboard to view real-time metrics and heatmaps.");
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage("§6§l=== Hitori Anti-Lag Commands ===");
        sender.sendMessage("§e/antilag status §7- Show server status");
        sender.sendMessage("§e/antilag scan §7- Trigger chunk scan");
        sender.sendMessage("§e/antilag hotspots [level] §7- List hotspots");
        sender.sendMessage("§e/antilag detections §7- Show active detections");
        sender.sendMessage("§e/antilag cull [count] §7- Force entity cull");
        sender.sendMessage("§e/antilag clear <world> <x> <z> §7- Clear chunk");
        sender.sendMessage("§e/antilag trend §7- Show trend analysis");
        sender.sendMessage("§e/antilag baseline §7- Show baseline stats");
        sender.sendMessage("§e/antilag config §7- Show current config");
        sender.sendMessage("§e/antilag reload §7- Reload configuration");
        sender.sendMessage("§e/antilag web §7- Web dashboard info");
    }

    // Helper methods for coloring
    private String getTpsColor(double tps) {
        if (tps >= 19)
            return "§a";
        if (tps >= 17)
            return "§e";
        if (tps >= 15)
            return "§6";
        return "§c";
    }

    private String getMsptColor(double mspt) {
        if (mspt <= 35)
            return "§a";
        if (mspt <= 45)
            return "§e";
        if (mspt <= 50)
            return "§6";
        return "§c";
    }

    private String getSeverityColor(String severity) {
        return switch (severity) {
            case "CRITICAL" -> "§c";
            case "WARNING" -> "§6";
            case "ELEVATED" -> "§e";
            default -> "§a";
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterCompletions(args[0],
                    "status", "scan", "hotspots", "detections", "cull", "clear",
                    "trend", "baseline", "config", "reload", "web", "help");
        }

        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "hotspots" -> {
                    return filterCompletions(args[1], "MEDIUM", "HIGH", "CRITICAL");
                }
                case "clear" -> {
                    return Bukkit.getWorlds().stream()
                            .map(World::getName)
                            .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                            .toList();
                }
                case "cull" -> {
                    return filterCompletions(args[1], "50", "100", "200", "500");
                }
            }
        }

        return Collections.emptyList();
    }

    private List<String> filterCompletions(String input, String... options) {
        String lower = input.toLowerCase();
        return Arrays.stream(options)
                .filter(o -> o.toLowerCase().startsWith(lower))
                .toList();
    }
}
