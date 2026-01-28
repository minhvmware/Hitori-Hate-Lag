package com.hitori.antilag.prediction;

import com.hitori.antilag.HitoriAntiLag;
import com.hitori.antilag.core.MetricsCollector.MetricsSnapshot;
import com.hitori.antilag.prediction.AnomalyDetector.Anomaly;
import com.hitori.antilag.prediction.TrendAnalyzer.TrendResult;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Main prediction engine that coordinates trend analysis and anomaly detection
 */
public class PredictionEngine {

    private final HitoriAntiLag plugin;

    // Components
    private final TrendAnalyzer trendAnalyzer;
    private final AnomalyDetector anomalyDetector;
    private final BaselineManager baselineManager;

    // State
    private BukkitTask analysisTask;
    private TrendResult latestTrend;
    private final List<Anomaly> recentAnomalies;
    private static final int MAX_ANOMALY_HISTORY = 100;

    // Listeners
    private final List<PredictionListener> listeners;

    public PredictionEngine(HitoriAntiLag plugin) {
        this.plugin = plugin;
        this.baselineManager = new BaselineManager(plugin);
        this.trendAnalyzer = new TrendAnalyzer(plugin);
        this.anomalyDetector = new AnomalyDetector(plugin, baselineManager);
        this.recentAnomalies = new CopyOnWriteArrayList<>();
        this.listeners = new ArrayList<>();
    }

    public void start() {
        // Run analysis every 5 seconds
        analysisTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::runAnalysis, 100L, 100L);
        plugin.getLogger().info("Prediction engine started");
    }

    public void shutdown() {
        if (analysisTask != null) {
            analysisTask.cancel();
        }
    }

    /**
     * Run prediction analysis
     */
    private void runAnalysis() {
        MetricsSnapshot current = plugin.getMetricsCollector().getCurrentSnapshot();

        // Update baseline
        baselineManager.update(current);

        // Run trend analysis
        if (plugin.getConfigManager().isTrendAnalysisEnabled()) {
            latestTrend = trendAnalyzer.analyzeTrend();

            // Notify listeners of critical trends
            if (latestTrend.hasData && latestTrend.severity == TrendAnalyzer.TrendSeverity.CRITICAL) {
                notifyTrendWarning(latestTrend);

                if (plugin.getConfigManager().isLogAnomalies()) {
                    plugin.getLogger().warning("Critical trend detected: " + latestTrend.getSummary());
                }
            }
        }

        // Run anomaly detection
        if (plugin.getConfigManager().isAnomalyDetectionEnabled() && baselineManager.hasValidBaseline()) {
            List<Anomaly> anomalies = anomalyDetector.detectAnomalies(current);

            for (Anomaly anomaly : anomalies) {
                addAnomaly(anomaly);
                notifyAnomaly(anomaly);

                if (plugin.getConfigManager().isLogAnomalies()) {
                    plugin.getLogger().warning("Anomaly detected: " + anomaly);
                }
            }
        }

        // Trigger mitigation evaluation
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getMitigationEngine().evaluate();
        });
    }

    private void addAnomaly(Anomaly anomaly) {
        recentAnomalies.add(anomaly);

        // Trim old anomalies
        while (recentAnomalies.size() > MAX_ANOMALY_HISTORY) {
            recentAnomalies.remove(0);
        }
    }

    private void notifyTrendWarning(TrendResult trend) {
        for (PredictionListener listener : listeners) {
            try {
                listener.onCriticalTrend(trend);
            } catch (Exception e) {
                plugin.getLogger().warning("Prediction listener error: " + e.getMessage());
            }
        }
    }

    private void notifyAnomaly(Anomaly anomaly) {
        for (PredictionListener listener : listeners) {
            try {
                listener.onAnomalyDetected(anomaly);
            } catch (Exception e) {
                plugin.getLogger().warning("Prediction listener error: " + e.getMessage());
            }
        }
    }

    // Getters
    public TrendAnalyzer getTrendAnalyzer() {
        return trendAnalyzer;
    }

    public AnomalyDetector getAnomalyDetector() {
        return anomalyDetector;
    }

    public BaselineManager getBaselineManager() {
        return baselineManager;
    }

    public TrendResult getLatestTrend() {
        return latestTrend;
    }

    public List<Anomaly> getRecentAnomalies() {
        return new ArrayList<>(recentAnomalies);
    }

    public List<Anomaly> getRecentAnomalies(int count) {
        int start = Math.max(0, recentAnomalies.size() - count);
        return new ArrayList<>(recentAnomalies.subList(start, recentAnomalies.size()));
    }

    public void clearAnomalies() {
        recentAnomalies.clear();
    }

    /**
     * Add a prediction listener
     */
    public void addListener(PredictionListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a prediction listener
     */
    public void removeListener(PredictionListener listener) {
        listeners.remove(listener);
    }

    /**
     * Listener interface for prediction events
     */
    public interface PredictionListener {
        void onCriticalTrend(TrendResult trend);

        void onAnomalyDetected(Anomaly anomaly);
    }
}
