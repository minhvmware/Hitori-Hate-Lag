package com.hitori.antilag;

import com.hitori.antilag.config.ConfigManager;
import com.hitori.antilag.core.ChunkAnalyzer;
import com.hitori.antilag.core.HotspotTracker;
import com.hitori.antilag.core.MetricsCollector;
import com.hitori.antilag.detection.DetectionEngine;
import com.hitori.antilag.mitigation.MitigationEngine;
import com.hitori.antilag.prediction.PredictionEngine;
import com.hitori.antilag.storage.StorageManager;
import com.hitori.antilag.storage.SQLiteStorage;
import com.hitori.antilag.web.WebServer;
import com.hitori.antilag.commands.AntiLagCommand;
import com.hitori.antilag.util.FoliaCompat;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Hitori Anti-Lag Plugin
 * Advanced anti-lag system with predictive analysis and web dashboard
 */
public class HitoriAntiLag extends JavaPlugin {

    private static HitoriAntiLag instance;
    
    // Core systems
    private ConfigManager configManager;
    private ExecutorService asyncExecutor;
    private boolean isFolia;
    
    // Modules
    private MetricsCollector metricsCollector;
    private ChunkAnalyzer chunkAnalyzer;
    private HotspotTracker hotspotTracker;
    private DetectionEngine detectionEngine;
    private MitigationEngine mitigationEngine;
    private PredictionEngine predictionEngine;
    private StorageManager storageManager;
    private WebServer webServer;

    @Override
    public void onEnable() {
        instance = this;
        long startTime = System.currentTimeMillis();
        
        // Detect Folia
        this.isFolia = FoliaCompat.isFolia();
        getLogger().info("Server type detected: " + (isFolia ? "Folia" : "Paper/Spigot"));
        
        // Initialize async executor
        this.asyncExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "HitoriAntiLag-Async");
                t.setDaemon(true);
                return t;
            }
        );
        
        // Load configuration
        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        
        // Initialize storage
        initializeStorage();
        
        // Initialize core modules
        initializeModules();
        
        // Start web server if enabled
        if (configManager.isWebEnabled()) {
            startWebServer();
        }
        
        // Register commands
        registerCommands();
        
        long loadTime = System.currentTimeMillis() - startTime;
        getLogger().info("Hitori Anti-Lag enabled successfully in " + loadTime + "ms");
    }

    @Override
    public void onDisable() {
        getLogger().info("Shutting down Hitori Anti-Lag...");
        
        // Shutdown web server
        if (webServer != null) {
            webServer.shutdown();
        }
        
        // Shutdown modules
        if (metricsCollector != null) {
            metricsCollector.shutdown();
        }
        if (chunkAnalyzer != null) {
            chunkAnalyzer.shutdown();
        }
        if (detectionEngine != null) {
            detectionEngine.shutdown();
        }
        if (predictionEngine != null) {
            predictionEngine.shutdown();
        }
        
        // Close storage
        if (storageManager != null) {
            storageManager.close();
        }
        
        // Shutdown executor
        if (asyncExecutor != null) {
            asyncExecutor.shutdown();
        }
        
        getLogger().info("Hitori Anti-Lag disabled.");
    }

    private void initializeStorage() {
        try {
            String storageType = configManager.getStorageType();
            
            if (storageType.equalsIgnoreCase("mysql")) {
                // MySQL storage - TODO: implement MySQLStorage
                getLogger().warning("MySQL storage not yet implemented, falling back to SQLite");
                this.storageManager = new SQLiteStorage(this);
            } else {
                this.storageManager = new SQLiteStorage(this);
            }
            
            getLogger().info("Storage initialized: " + storageManager.getClass().getSimpleName());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize storage", e);
        }
    }

    private void initializeModules() {
        // Core monitoring
        this.metricsCollector = new MetricsCollector(this);
        this.hotspotTracker = new HotspotTracker(this);
        this.chunkAnalyzer = new ChunkAnalyzer(this);
        
        // Detection
        this.detectionEngine = new DetectionEngine(this);
        
        // Mitigation
        this.mitigationEngine = new MitigationEngine(this);
        
        // Prediction
        this.predictionEngine = new PredictionEngine(this);
        
        // Start all modules
        metricsCollector.start();
        chunkAnalyzer.start();
        detectionEngine.start();
        predictionEngine.start();
        
        getLogger().info("All modules initialized and started");
    }

    private void startWebServer() {
        try {
            int port = configManager.getWebPort();
            this.webServer = new WebServer(this, port);
            getLogger().info("Web dashboard started on port " + port);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to start web server", e);
        }
    }

    private void registerCommands() {
        AntiLagCommand command = new AntiLagCommand(this);
        getCommand("antilag").setExecutor(command);
        getCommand("antilag").setTabCompleter(command);
    }

    /**
     * Reload the plugin configuration
     */
    public void reload() {
        reloadConfig();
        configManager.reload();
        getLogger().info("Configuration reloaded");
    }

    // Getters
    public static HitoriAntiLag getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public ExecutorService getAsyncExecutor() {
        return asyncExecutor;
    }

    public boolean isFolia() {
        return isFolia;
    }

    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    public ChunkAnalyzer getChunkAnalyzer() {
        return chunkAnalyzer;
    }

    public HotspotTracker getHotspotTracker() {
        return hotspotTracker;
    }

    public DetectionEngine getDetectionEngine() {
        return detectionEngine;
    }

    public MitigationEngine getMitigationEngine() {
        return mitigationEngine;
    }

    public PredictionEngine getPredictionEngine() {
        return predictionEngine;
    }

    public StorageManager getStorageManager() {
        return storageManager;
    }

    public WebServer getWebServer() {
        return webServer;
    }
}
