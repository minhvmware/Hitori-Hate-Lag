package com.hitori.antilag.storage;

import com.hitori.antilag.core.MetricsCollector.MetricsSnapshot;

/**
 * Interface for storage implementations
 * FIXED: Added all methods that SQLiteStorage implements
 */
public interface StorageManager {

    /**
     * Save a metrics snapshot
     */
    void saveMetricSnapshot(MetricsSnapshot snapshot);

    /**
     * Save a lag event (detection, mitigation, etc.)
     * 
     * @param eventType   Type of event (e.g., "REDSTONE_CLOCK", "ENTITY_CULL")
     * @param severity    Severity level (e.g., "WARNING", "CRITICAL")
     * @param description Description of the event
     * @param world       World name (nullable)
     * @param chunkX      Chunk X coordinate (nullable)
     * @param chunkZ      Chunk Z coordinate (nullable)
     */
    void saveLagEvent(String eventType, String severity, String description,
            String world, Integer chunkX, Integer chunkZ);

    /**
     * Save an anomaly detection record
     * 
     * @param type     Type of anomaly (e.g., "MSPT_SPIKE", "ENTITY_SPIKE")
     * @param severity Numeric severity (z-score or similar)
     * @param message  Human-readable description
     */
    void saveAnomaly(String type, double severity, String message);

    /**
     * Get the storage type name
     */
    String getType();

    /**
     * Close the storage connection
     */
    void close();

    /**
     * Clean up old data
     * 
     * @param daysToKeep Number of days of data to retain
     */
    void cleanupOldData(int daysToKeep);
}
