package com.hitori.antilag.prediction;

import com.hitori.antilag.HitoriAntiLag;
import com.hitori.antilag.core.MetricsCollector.MetricsSnapshot;

/**
 * Manages baseline statistics for anomaly detection
 * Only learns from "healthy" server states
 */
public class BaselineManager {

    private final HitoriAntiLag plugin;

    // Running statistics for each metric
    private final RunningStats msptStats = new RunningStats();
    private final RunningStats entityStats = new RunningStats();
    private final RunningStats chunkStats = new RunningStats();

    // Minimum samples before baseline is valid
    private static final int MIN_SAMPLES = 60;

    public BaselineManager(HitoriAntiLag plugin) {
        this.plugin = plugin;
    }

    /**
     * Update baseline with current metrics (only if server is healthy)
     */
    public void update(MetricsSnapshot metrics) {
        double maxMspt = plugin.getConfigManager().getBaselineMaxMspt();
        double minTps = plugin.getConfigManager().getBaselineMinTps();

        // Only learn from healthy server states
        if (metrics.mspt < maxMspt && metrics.tps > minTps) {
            msptStats.push(metrics.mspt);
            entityStats.push(metrics.entityCount);
            chunkStats.push(metrics.loadedChunks);
        }
    }

    /**
     * Get current baseline statistics
     */
    public BaselineStats getStats() {
        return new BaselineStats(
                msptStats.getMean(),
                msptStats.getStdDev(),
                entityStats.getMean(),
                entityStats.getStdDev(),
                chunkStats.getMean(),
                chunkStats.getStdDev(),
                msptStats.getCount());
    }

    /**
     * Check if baseline has enough data
     */
    public boolean hasValidBaseline() {
        return msptStats.getCount() >= MIN_SAMPLES;
    }

    /**
     * Reset baseline data
     */
    public void reset() {
        msptStats.reset();
        entityStats.reset();
        chunkStats.reset();
    }

    /**
     * Baseline statistics container
     */
    public static class BaselineStats {
        public final double meanMspt;
        public final double stdDevMspt;
        public final double meanEntities;
        public final double stdDevEntities;
        public final double meanChunks;
        public final double stdDevChunks;
        public final int sampleCount;

        public BaselineStats(double meanMspt, double stdDevMspt,
                double meanEntities, double stdDevEntities,
                double meanChunks, double stdDevChunks,
                int sampleCount) {
            this.meanMspt = meanMspt;
            this.stdDevMspt = stdDevMspt;
            this.meanEntities = meanEntities;
            this.stdDevEntities = stdDevEntities;
            this.meanChunks = meanChunks;
            this.stdDevChunks = stdDevChunks;
            this.sampleCount = sampleCount;
        }

        public boolean hasData() {
            return sampleCount >= MIN_SAMPLES;
        }

        public double calculateZScore(double value, double mean, double stdDev) {
            if (stdDev == 0)
                return 0;
            return (value - mean) / stdDev;
        }

        public java.util.Map<String, Object> toMap() {
            return java.util.Map.of(
                    "meanMspt", meanMspt,
                    "stdDevMspt", stdDevMspt,
                    "meanEntities", meanEntities,
                    "stdDevEntities", stdDevEntities,
                    "meanChunks", meanChunks,
                    "stdDevChunks", stdDevChunks,
                    "sampleCount", sampleCount,
                    "hasData", hasData());
        }
    }

    /**
     * Welford's online algorithm for computing running mean and variance
     * Thread-safe and numerically stable
     */
    private static class RunningStats {
        private int count = 0;
        private double mean = 0;
        private double m2 = 0;

        public synchronized void push(double value) {
            count++;
            double delta = value - mean;
            mean += delta / count;
            double delta2 = value - mean;
            m2 += delta * delta2;
        }

        public synchronized double getMean() {
            return mean;
        }

        public synchronized double getVariance() {
            if (count < 2)
                return 0;
            return m2 / (count - 1);
        }

        public synchronized double getStdDev() {
            return Math.sqrt(getVariance());
        }

        public synchronized int getCount() {
            return count;
        }

        public synchronized void reset() {
            count = 0;
            mean = 0;
            m2 = 0;
        }
    }
}
