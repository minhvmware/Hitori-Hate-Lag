package com.hitori.antilag.storage;

import com.hitori.antilag.HitoriAntiLag;
import com.hitori.antilag.core.MetricsCollector.MetricsSnapshot;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SQLite storage implementation for historical data
 */
public class SQLiteStorage implements StorageManager {

    private final HitoriAntiLag plugin;
    private final HikariDataSource dataSource;
    private final ExecutorService writeExecutor;

    public SQLiteStorage(HitoriAntiLag plugin) {
        this.plugin = plugin;
        this.writeExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "HitoriAntiLag-SQLite");
            t.setDaemon(true);
            return t;
        });

        // Create database file path
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        String dbFile = plugin.getConfigManager().getSqliteFile();
        File dbPath = new File(dataFolder, dbFile);

        // Configure HikariCP for SQLite
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbPath.getAbsolutePath());
        config.setMaximumPoolSize(1); // SQLite single-writer
        config.setPoolName("HitoriAntiLag-SQLite");

        // SQLite specific settings
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");

        this.dataSource = new HikariDataSource(config);

        initializeTables();

        plugin.getLogger().info("SQLite storage initialized: " + dbPath.getAbsolutePath());
    }

    private void initializeTables() {
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {

            // Metrics snapshots table
            stmt.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS metric_snapshots (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            timestamp INTEGER NOT NULL,
                            mspt REAL NOT NULL,
                            tps REAL NOT NULL,
                            entity_count INTEGER NOT NULL,
                            loaded_chunks INTEGER NOT NULL,
                            player_count INTEGER NOT NULL,
                            used_memory_mb INTEGER NOT NULL,
                            free_memory_mb INTEGER NOT NULL
                        )
                    """);

            // Chunk hotspots table
            stmt.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS chunk_hotspots (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            timestamp INTEGER NOT NULL,
                            world TEXT NOT NULL,
                            chunk_x INTEGER NOT NULL,
                            chunk_z INTEGER NOT NULL,
                            lag_score REAL NOT NULL,
                            entity_count INTEGER NOT NULL,
                            tile_entity_count INTEGER NOT NULL,
                            hotspot_level TEXT NOT NULL
                        )
                    """);

            // Lag events/detections table
            stmt.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS lag_events (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            timestamp INTEGER NOT NULL,
                            event_type TEXT NOT NULL,
                            severity TEXT NOT NULL,
                            description TEXT,
                            world TEXT,
                            chunk_x INTEGER,
                            chunk_z INTEGER
                        )
                    """);

            // Anomaly log table
            stmt.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS anomaly_log (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            timestamp INTEGER NOT NULL,
                            anomaly_type TEXT NOT NULL,
                            severity REAL NOT NULL,
                            message TEXT NOT NULL
                        )
                    """);

            // Create indexes for time-based queries
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_metrics_time ON metric_snapshots(timestamp)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_hotspots_time ON chunk_hotspots(timestamp)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_events_time ON lag_events(timestamp)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_anomaly_time ON anomaly_log(timestamp)");

        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize SQLite tables: " + e.getMessage());
        }
    }

    @Override
    public void saveMetricSnapshot(MetricsSnapshot snapshot) {
        writeExecutor.execute(() -> {
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(
                            """
                                        INSERT INTO metric_snapshots
                                        (timestamp, mspt, tps, entity_count, loaded_chunks, player_count, used_memory_mb, free_memory_mb)
                                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                    """)) {

                stmt.setLong(1, snapshot.timestamp);
                stmt.setDouble(2, snapshot.mspt);
                stmt.setDouble(3, snapshot.tps);
                stmt.setInt(4, snapshot.entityCount);
                stmt.setInt(5, snapshot.loadedChunks);
                stmt.setInt(6, snapshot.playerCount);
                stmt.setLong(7, snapshot.usedMemoryMb);
                stmt.setLong(8, snapshot.freeMemoryMb);

                stmt.executeUpdate();
            } catch (SQLException e) {
                // Log but don't fail - storage is non-critical
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().warning("Failed to save metric snapshot: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Save a lag event to the database
     */
    public void saveLagEvent(String eventType, String severity, String description,
            String world, Integer chunkX, Integer chunkZ) {
        writeExecutor.execute(() -> {
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement stmt = conn.prepareStatement("""
                                INSERT INTO lag_events
                                (timestamp, event_type, severity, description, world, chunk_x, chunk_z)
                                VALUES (?, ?, ?, ?, ?, ?, ?)
                            """)) {

                stmt.setLong(1, System.currentTimeMillis());
                stmt.setString(2, eventType);
                stmt.setString(3, severity);
                stmt.setString(4, description);
                stmt.setString(5, world);
                if (chunkX != null) {
                    stmt.setInt(6, chunkX);
                } else {
                    stmt.setNull(6, Types.INTEGER);
                }
                if (chunkZ != null) {
                    stmt.setInt(7, chunkZ);
                } else {
                    stmt.setNull(7, Types.INTEGER);
                }

                stmt.executeUpdate();
            } catch (SQLException e) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().warning("Failed to save lag event: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Save an anomaly to the database
     */
    public void saveAnomaly(String type, double severity, String message) {
        writeExecutor.execute(() -> {
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement stmt = conn.prepareStatement("""
                                INSERT INTO anomaly_log (timestamp, anomaly_type, severity, message)
                                VALUES (?, ?, ?, ?)
                            """)) {

                stmt.setLong(1, System.currentTimeMillis());
                stmt.setString(2, type);
                stmt.setDouble(3, severity);
                stmt.setString(4, message);

                stmt.executeUpdate();
            } catch (SQLException e) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().warning("Failed to save anomaly: " + e.getMessage());
                }
            }
        });
    }

    @Override
    public void cleanupOldData(int daysToKeep) {
        long cutoff = System.currentTimeMillis() - ((long) daysToKeep * 24 * 60 * 60 * 1000);

        writeExecutor.execute(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String[] tables = { "metric_snapshots", "chunk_hotspots", "lag_events", "anomaly_log" };

                for (String table : tables) {
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "DELETE FROM " + table + " WHERE timestamp < ?")) {
                        stmt.setLong(1, cutoff);
                        int deleted = stmt.executeUpdate();

                        if (deleted > 0 && plugin.getConfigManager().isDebug()) {
                            plugin.getLogger().info("Cleaned up " + deleted + " old records from " + table);
                        }
                    }
                }

                // Vacuum to reclaim space
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("VACUUM");
                }

            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to cleanup old data: " + e.getMessage());
            }
        });
    }

    @Override
    public String getType() {
        return "SQLite";
    }

    @Override
    public void close() {
        writeExecutor.shutdown();
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        plugin.getLogger().info("SQLite storage closed");
    }
}
