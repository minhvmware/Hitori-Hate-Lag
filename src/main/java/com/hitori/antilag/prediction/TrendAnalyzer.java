package com.hitori.antilag.prediction;

import com.hitori.antilag.HitoriAntiLag;
import com.hitori.antilag.core.MetricsCollector.MetricsSnapshot;
import com.hitori.antilag.core.RingBuffer;

import java.util.List;

/**
 * Analyzes trends in server metrics to predict future performance issues
 */
public class TrendAnalyzer {

    private final HitoriAntiLag plugin;
    private final RingBuffer<MetricsSnapshot> history;

    public TrendAnalyzer(HitoriAntiLag plugin) {
        this.plugin = plugin;
        this.history = plugin.getMetricsCollector().getHistory();
    }

    /**
     * Analyze current trends and predict future state
     */
    public TrendResult analyzeTrend() {
        int minSamples = plugin.getConfigManager().getTrendMinSamples();

        if (history.size() < minSamples) {
            return TrendResult.insufficientData();
        }

        List<MetricsSnapshot> samples = history.getLastN(60); // Last 60 samples

        // Extract MSPT values
        double[] msptValues = samples.stream()
                .mapToDouble(s -> s.mspt)
                .toArray();

        // Extract entity counts
        double[] entityValues = samples.stream()
                .mapToDouble(s -> s.entityCount)
                .toArray();

        // Calculate slopes
        double msptSlope = calculateSlope(msptValues);
        double entitySlope = calculateSlope(entityValues);

        // Current values
        MetricsSnapshot current = history.getLast();
        double currentMspt = current != null ? current.mspt : 50.0;
        int currentEntities = current != null ? current.entityCount : 0;

        // Predict MSPT in 60 seconds if trend continues
        double predictedMspt = currentMspt + (msptSlope * 60);
        int predictedEntities = (int) (currentEntities + (entitySlope * 60));

        // Build result
        TrendResult result = new TrendResult();
        result.currentMspt = currentMspt;
        result.predictedMspt = predictedMspt;
        result.msptRateOfChange = msptSlope;
        result.currentEntities = currentEntities;
        result.predictedEntities = predictedEntities;
        result.entityRateOfChange = entitySlope;
        result.severity = calculateSeverity(msptSlope, predictedMspt);
        result.hasData = true;

        return result;
    }

    /**
     * Calculate linear regression slope
     */
    private double calculateSlope(double[] values) {
        int n = values.length;
        if (n < 2)
            return 0;

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;

        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += values[i];
            sumXY += i * values[i];
            sumX2 += i * i;
        }

        double denominator = n * sumX2 - sumX * sumX;
        if (denominator == 0)
            return 0;

        return (n * sumXY - sumX * sumY) / denominator;
    }

    /**
     * Calculate trend severity based on rate of change and predicted values
     */
    private TrendSeverity calculateSeverity(double msptSlope, double predictedMspt) {
        // If MSPT is dropping or stable, no concern
        if (msptSlope <= 0) {
            return TrendSeverity.NORMAL;
        }

        // Check predicted MSPT
        if (predictedMspt >= 50 || msptSlope > 0.5) {
            return TrendSeverity.CRITICAL;
        }
        if (predictedMspt >= 45 || msptSlope > 0.3) {
            return TrendSeverity.WARNING;
        }
        if (predictedMspt >= 40 || msptSlope > 0.1) {
            return TrendSeverity.ELEVATED;
        }

        return TrendSeverity.NORMAL;
    }

    /**
     * Result class for trend analysis
     */
    public static class TrendResult {
        public boolean hasData = false;
        public double currentMspt;
        public double predictedMspt;
        public double msptRateOfChange;
        public int currentEntities;
        public int predictedEntities;
        public double entityRateOfChange;
        public TrendSeverity severity = TrendSeverity.NORMAL;

        public static TrendResult insufficientData() {
            TrendResult result = new TrendResult();
            result.hasData = false;
            return result;
        }

        /**
         * Get a human-readable summary
         */
        public String getSummary() {
            if (!hasData) {
                return "Insufficient data for trend analysis";
            }

            String trend = msptRateOfChange > 0 ? "increasing" : (msptRateOfChange < 0 ? "decreasing" : "stable");

            return String.format(
                    "MSPT %s (%.2f ms/sec), predicted: %.1f ms in 60s. Severity: %s",
                    trend, msptRateOfChange, predictedMspt, severity);
        }

        public java.util.Map<String, Object> toMap() {
            return java.util.Map.of(
                    "hasData", hasData,
                    "currentMspt", currentMspt,
                    "predictedMspt", predictedMspt,
                    "msptRateOfChange", msptRateOfChange,
                    "currentEntities", currentEntities,
                    "predictedEntities", predictedEntities,
                    "entityRateOfChange", entityRateOfChange,
                    "severity", severity.name());
        }
    }

    /**
     * Severity levels for trend analysis
     */
    public enum TrendSeverity {
        NORMAL,
        ELEVATED,
        WARNING,
        CRITICAL
    }
}
