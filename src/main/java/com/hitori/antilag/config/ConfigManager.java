package com.hitori.antilag.config;

import com.hitori.antilag.HitoriAntiLag;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Manages plugin configuration and provides typed access to settings
 */
public class ConfigManager {

    private final HitoriAntiLag plugin;
    private FileConfiguration config;

    // Cached weights
    private Map<EntityType, Double> entityWeights;
    private Map<Material, Double> tileEntityWeights;

    public ConfigManager(HitoriAntiLag plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.config = plugin.getConfig();
        loadWeights();
    }

    private void loadWeights() {
        // Load entity weights
        this.entityWeights = new EnumMap<>(EntityType.class);
        ConfigurationSection entitySection = config.getConfigurationSection("scoring.entity-weights");
        if (entitySection != null) {
            for (String key : entitySection.getKeys(false)) {
                try {
                    EntityType type = EntityType.valueOf(key.toUpperCase());
                    double weight = entitySection.getDouble(key);
                    entityWeights.put(type, weight);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Unknown entity type in config: " + key);
                }
            }
        }

        // Load tile entity weights
        this.tileEntityWeights = new EnumMap<>(Material.class);
        ConfigurationSection tileSection = config.getConfigurationSection("scoring.tile-entity-weights");
        if (tileSection != null) {
            for (String key : tileSection.getKeys(false)) {
                try {
                    Material material = Material.valueOf(key.toUpperCase());
                    double weight = tileSection.getDouble(key);
                    tileEntityWeights.put(material, weight);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Unknown material in config: " + key);
                }
            }
        }
    }

    // ==================== Monitoring Settings ====================

    public int getScanIntervalTicks() {
        return config.getInt("monitoring.scan-interval-ticks", 100);
    }

    public int getMsptSampleRateMs() {
        return config.getInt("monitoring.mspt-sample-rate-ms", 1000);
    }

    public int getHistoryRetentionDays() {
        return config.getInt("monitoring.history-retention-days", 7);
    }

    // ==================== Scoring Settings ====================

    public double getHotspotThresholdMedium() {
        return config.getDouble("scoring.hotspot-threshold-medium", 50);
    }

    public double getHotspotThresholdHigh() {
        return config.getDouble("scoring.hotspot-threshold-high", 100);
    }

    public double getHotspotThresholdCritical() {
        return config.getDouble("scoring.hotspot-threshold-critical", 150);
    }

    public Map<EntityType, Double> getEntityWeights() {
        return entityWeights;
    }

    public Map<Material, Double> getTileEntityWeights() {
        return tileEntityWeights;
    }

    // ==================== Detection Settings ====================

    public boolean isRedstoneClockDetectionEnabled() {
        return config.getBoolean("detection.redstone-clock.enabled", true);
    }

    public int getRedstoneClockThreshold() {
        return config.getInt("detection.redstone-clock.threshold-updates-per-second", 20);
    }

    public int getRedstoneWindowSizeMs() {
        return config.getInt("detection.redstone-clock.window-size-ms", 1000);
    }

    public boolean isEntityCrammingDetectionEnabled() {
        return config.getBoolean("detection.entity-cramming.enabled", true);
    }

    public int getEntityCrammingThreshold() {
        return config.getInt("detection.entity-cramming.threshold-entities-per-block", 24);
    }

    public boolean isProjectileSpamDetectionEnabled() {
        return config.getBoolean("detection.projectile-spam.enabled", true);
    }

    public int getProjectileSpamThreshold() {
        return config.getInt("detection.projectile-spam.threshold-projectiles-per-chunk", 50);
    }

    public boolean isNbtProtectionEnabled() {
        return config.getBoolean("detection.nbt-protection.enabled", true);
    }

    public int getMaxShulkerNbtBytes() {
        return config.getInt("detection.nbt-protection.max-shulker-nbt-bytes", 2000000);
    }

    public int getMaxBookPages() {
        return config.getInt("detection.nbt-protection.max-book-pages", 100);
    }

    public int getMaxPageLength() {
        return config.getInt("detection.nbt-protection.max-page-length", 32767);
    }

    // ==================== Mitigation Settings ====================

    public boolean isSmartCullingEnabled() {
        return config.getBoolean("mitigation.smart-culling.enabled", true);
    }

    public boolean isProtectNamedMobs() {
        return config.getBoolean("mitigation.smart-culling.protect-named-mobs", true);
    }

    public boolean isProtectTamedMobs() {
        return config.getBoolean("mitigation.smart-culling.protect-tamed-mobs", true);
    }

    public boolean isProtectLeashedMobs() {
        return config.getBoolean("mitigation.smart-culling.protect-leashed-mobs", true);
    }

    public boolean isProtectVehiclePassengers() {
        return config.getBoolean("mitigation.smart-culling.protect-vehicle-passengers", true);
    }

    public String getProtectionTag() {
        return config.getString("mitigation.smart-culling.protection-tag", "antilag:protected");
    }

    public int getCullingCooldownSeconds() {
        return config.getInt("mitigation.smart-culling.cooldown-seconds", 30);
    }

    public boolean isRedstoneThrottlingEnabled() {
        return config.getBoolean("mitigation.redstone-throttling.enabled", true);
    }

    public int getThrottleTargetUpdatesPerSecond() {
        return config.getInt("mitigation.redstone-throttling.target-updates-per-second", 10);
    }

    public boolean isAutoThrottleEnabled() {
        return config.getBoolean("mitigation.redstone-throttling.auto-throttle", true);
    }

    public boolean isDynamicViewDistanceEnabled() {
        return config.getBoolean("mitigation.dynamic-view-distance.enabled", true);
    }

    public double getMsptWarningThreshold() {
        return config.getDouble("mitigation.dynamic-view-distance.mspt-warning-threshold", 35.0);
    }

    public double getMsptCriticalThreshold() {
        return config.getDouble("mitigation.dynamic-view-distance.mspt-critical-threshold", 45.0);
    }

    public double getMsptEmergencyThreshold() {
        return config.getDouble("mitigation.dynamic-view-distance.mspt-emergency-threshold", 48.0);
    }

    public int getMinViewDistance() {
        return config.getInt("mitigation.dynamic-view-distance.min-view-distance", 4);
    }

    public int getMinSimulationDistance() {
        return config.getInt("mitigation.dynamic-view-distance.min-simulation-distance", 3);
    }

    public int getViewDistanceRecoveryDelaySeconds() {
        return config.getInt("mitigation.dynamic-view-distance.recovery-delay-seconds", 30);
    }

    // ==================== Prediction Settings ====================

    public boolean isTrendAnalysisEnabled() {
        return config.getBoolean("prediction.trend-analysis.enabled", true);
    }

    public int getTrendSampleWindowSeconds() {
        return config.getInt("prediction.trend-analysis.sample-window-seconds", 300);
    }

    public int getTrendMinSamples() {
        return config.getInt("prediction.trend-analysis.min-samples", 30);
    }

    public boolean isAnomalyDetectionEnabled() {
        return config.getBoolean("prediction.anomaly-detection.enabled", true);
    }

    public double getAnomalyZScoreThreshold() {
        return config.getDouble("prediction.anomaly-detection.z-score-threshold", 2.5);
    }

    public double getBaselineMaxMspt() {
        return config.getDouble("prediction.anomaly-detection.baseline-max-mspt", 40.0);
    }

    public double getBaselineMinTps() {
        return config.getDouble("prediction.anomaly-detection.baseline-min-tps", 18.0);
    }

    // ==================== Web Settings ====================

    public boolean isWebEnabled() {
        return config.getBoolean("web.enabled", true);
    }

    public int getWebPort() {
        return config.getInt("web.port", 8080);
    }

    public String getWebAuthToken() {
        return config.getString("web.auth-token", "CHANGE_THIS_TO_A_SECURE_RANDOM_TOKEN");
    }

    public int getWebUpdateIntervalMs() {
        return config.getInt("web.update-interval-ms", 100);
    }

    public int getWebMaxConnections() {
        return config.getInt("web.max-connections", 10);
    }

    public String getWebCorsOrigin() {
        return config.getString("web.cors-origin", "*");
    }

    // ==================== Storage Settings ====================

    public String getStorageType() {
        return config.getString("storage.type", "sqlite");
    }

    public String getSqliteFile() {
        return config.getString("storage.sqlite.file", "data.db");
    }

    public String getMysqlHost() {
        return config.getString("storage.mysql.host", "localhost");
    }

    public int getMysqlPort() {
        return config.getInt("storage.mysql.port", 3306);
    }

    public String getMysqlDatabase() {
        return config.getString("storage.mysql.database", "hitori_antilag");
    }

    public String getMysqlUsername() {
        return config.getString("storage.mysql.username", "minecraft");
    }

    public String getMysqlPassword() {
        return config.getString("storage.mysql.password", "");
    }

    public int getMysqlPoolSize() {
        return config.getInt("storage.mysql.pool-size", 5);
    }

    // ==================== Logging Settings ====================

    public boolean isLogDetections() {
        return config.getBoolean("logging.log-detections", true);
    }

    public boolean isLogMitigations() {
        return config.getBoolean("logging.log-mitigations", true);
    }

    public boolean isLogAnomalies() {
        return config.getBoolean("logging.log-anomalies", true);
    }

    public boolean isDebug() {
        return config.getBoolean("logging.debug", false);
    }
}
