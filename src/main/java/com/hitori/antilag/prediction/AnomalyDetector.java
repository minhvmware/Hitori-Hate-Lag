package com.hitori.antilag.prediction;

import com.hitori.antilag.HitoriAntiLag;
import com.hitori.antilag.core.MetricsCollector.MetricsSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Detects anomalies in server metrics using statistical analysis
 */
public class AnomalyDetector {

    private final HitoriAntiLag plugin;
    private final BaselineManager baseline;
    private final double zScoreThreshold;

    public AnomalyDetector(HitoriAntiLag plugin, BaselineManager baseline) {
        this.plugin = plugin;
        this.baseline = baseline;
        this.zScoreThreshold = plugin.getConfigManager().getAnomalyZScoreThreshold();
    }

    /**
     * Detect anomalies in current metrics
     */
    public List<Anomaly> detectAnomalies(MetricsSnapshot current) {
        List<Anomaly> anomalies = new ArrayList<>();
        BaselineManager.BaselineStats stats = baseline.getStats();

        if (!stats.hasData()) {
            return anomalies;
        }

        // Check MSPT anomaly
        double msptZScore = stats.calculateZScore(current.mspt, stats.meanMspt, stats.stdDevMspt);
        if (msptZScore > zScoreThreshold) {
            anomalies.add(new Anomaly(
                    AnomalyType.MSPT_SPIKE,
                    msptZScore,
                    String.format("MSPT %.1fms is %.1f σ above normal (mean: %.1fms)",
                            current.mspt, msptZScore, stats.meanMspt)));
        }

        // Check entity count anomaly
        double entityZScore = stats.calculateZScore(current.entityCount, stats.meanEntities, stats.stdDevEntities);
        if (entityZScore > zScoreThreshold) {
            anomalies.add(new Anomaly(
                    AnomalyType.ENTITY_SPIKE,
                    entityZScore,
                    String.format("Entity count %d is %.1f σ above normal (mean: %.0f)",
                            current.entityCount, entityZScore, stats.meanEntities)));
        }

        // Check chunk count anomaly
        double chunkZScore = stats.calculateZScore(current.loadedChunks, stats.meanChunks, stats.stdDevChunks);
        if (chunkZScore > zScoreThreshold) {
            anomalies.add(new Anomaly(
                    AnomalyType.CHUNK_SPIKE,
                    chunkZScore,
                    String.format("Loaded chunks %d is %.1f σ above normal (mean: %.0f)",
                            current.loadedChunks, chunkZScore, stats.meanChunks)));
        }

        // Check memory pressure
        double memoryUsagePercent = (double) current.usedMemoryMb / current.maxMemoryMb * 100;
        if (memoryUsagePercent > 90) {
            anomalies.add(new Anomaly(
                    AnomalyType.MEMORY_PRESSURE,
                    memoryUsagePercent / 10, // Convert to pseudo z-score
                    String.format("Memory usage at %.1f%% (%d/%d MB)",
                            memoryUsagePercent, current.usedMemoryMb, current.maxMemoryMb)));
        }

        // Check TPS drop
        if (current.tps < 15) {
            double tpsSeverity = (20 - current.tps) / 5; // 0-4 scale
            anomalies.add(new Anomaly(
                    AnomalyType.TPS_DROP,
                    tpsSeverity,
                    String.format("TPS dropped to %.1f (target: 20.0)", current.tps)));
        }

        return anomalies;
    }

    /**
     * Anomaly types
     */
    public enum AnomalyType {
        MSPT_SPIKE("MSPT Spike", "Milliseconds per tick exceeded normal range"),
        ENTITY_SPIKE("Entity Spike", "Entity count exceeded normal range"),
        CHUNK_SPIKE("Chunk Spike", "Loaded chunks exceeded normal range"),
        MEMORY_PRESSURE("Memory Pressure", "Memory usage critically high"),
        TPS_DROP("TPS Drop", "Server ticks per second dropped significantly");

        private final String displayName;
        private final String description;

        AnomalyType(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Anomaly data class
     */
    public static class Anomaly {
        private final AnomalyType type;
        private final double severity;
        private final String message;
        private final long timestamp;

        public Anomaly(AnomalyType type, double severity, String message) {
            this.type = type;
            this.severity = severity;
            this.message = message;
            this.timestamp = System.currentTimeMillis();
        }

        public AnomalyType getType() {
            return type;
        }

        public double getSeverity() {
            return severity;
        }

        public String getMessage() {
            return message;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public Map<String, Object> toMap() {
            return Map.of(
                    "type", type.name(),
                    "typeName", type.getDisplayName(),
                    "severity", severity,
                    "message", message,
                    "timestamp", timestamp);
        }

        @Override
        public String toString() {
            return String.format("[%s] %s (severity: %.2f)", type.getDisplayName(), message, severity);
        }
    }
}
