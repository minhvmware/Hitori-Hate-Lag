package com.hitori.antilag.detection;

import com.hitori.antilag.HitoriAntiLag;
import com.hitori.antilag.detection.detectors.*;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Main detection engine that coordinates all detectors
 * FIX: Proper async/sync coordination using CountDownLatch
 */
public class DetectionEngine {

    private final HitoriAntiLag plugin;

    // Detectors
    private final RedstoneClockDetector redstoneDetector;
    private final EntityCrammingDetector crammingDetector;
    private final ProjectileSpamDetector spamDetector;
    private final NBTBanDetector nbtDetector;

    // Detection results cache
    private final Map<UUID, LagMachine> detectedMachines;
    private BukkitTask detectionTask;

    // Detection listeners - use thread-safe list
    private final List<DetectionListener> listeners;

    public DetectionEngine(HitoriAntiLag plugin) {
        this.plugin = plugin;
        this.detectedMachines = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>(); // Thread-safe list

        // Initialize detectors
        this.redstoneDetector = new RedstoneClockDetector(plugin);
        this.crammingDetector = new EntityCrammingDetector(plugin);
        this.spamDetector = new ProjectileSpamDetector(plugin);
        this.nbtDetector = new NBTBanDetector(plugin);

        // Register event listeners
        Bukkit.getPluginManager().registerEvents(redstoneDetector, plugin);
        Bukkit.getPluginManager().registerEvents(nbtDetector, plugin);
    }

    public void start() {
        // Run detection every 5 seconds
        detectionTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::runDetection, 100L, 100L);
        plugin.getLogger().info("Detection engine started");
    }

    public void shutdown() {
        if (detectionTask != null) {
            detectionTask.cancel();
        }
    }

    /**
     * Run all detectors - FIXED async/sync coordination
     * 
     * CRITICAL FIX: Previously this was a "fire-and-forget" pattern where:
     * 1. Main thread tasks were scheduled but not awaited
     * 2. Cleanup and notification happened before tasks completed
     * 3. Race condition caused data loss and premature deletion
     * 
     * NOW: Uses CountDownLatch to properly wait for all detection phases
     */
    private void runDetection() {
        // Thread-safe collections for results
        List<LagMachine> newDetections = new CopyOnWriteArrayList<>();
        Set<UUID> stillActive = ConcurrentHashMap.newKeySet();

        // Count how many sync tasks we need to wait for
        int syncTaskCount = 0;
        if (plugin.getConfigManager().isEntityCrammingDetectionEnabled())
            syncTaskCount++;
        if (plugin.getConfigManager().isProjectileSpamDetectionEnabled())
            syncTaskCount++;

        final CountDownLatch latch = new CountDownLatch(syncTaskCount);

        // Redstone clocks (async-safe, already tracks via events)
        if (plugin.getConfigManager().isRedstoneClockDetectionEnabled()) {
            try {
                for (LagMachine machine : redstoneDetector.detectClocks()) {
                    processDetection(machine, newDetections, stillActive);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Redstone detection error: " + e.getMessage());
            }
        }

        // Entity cramming (needs main thread for entity access)
        if (plugin.getConfigManager().isEntityCrammingDetectionEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    for (LagMachine machine : crammingDetector.detectAllCramming()) {
                        processDetection(machine, newDetections, stillActive);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Cramming detection error: " + e.getMessage());
                } finally {
                    latch.countDown(); // Signal completion
                }
            });
        }

        // Projectile/item spam (needs main thread)
        if (plugin.getConfigManager().isProjectileSpamDetectionEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    for (LagMachine machine : spamDetector.detectAllSpam()) {
                        processDetection(machine, newDetections, stillActive);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Spam detection error: " + e.getMessage());
                } finally {
                    latch.countDown(); // Signal completion
                }
            });
        }

        // CRITICAL FIX: Wait for main thread tasks to complete
        // Timeout after 5 seconds to prevent deadlock if server is shutting down
        try {
            if (syncTaskCount > 0) {
                boolean completed = latch.await(5, TimeUnit.SECONDS);
                if (!completed) {
                    plugin.getLogger().warning("Detection tasks timed out - server may be lagging");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        // NOW safe to cleanup and notify - all detections are complete
        detectedMachines.entrySet().removeIf(entry -> !stillActive.contains(entry.getKey()));

        // Notify listeners of new detections
        for (LagMachine machine : newDetections) {
            notifyListeners(machine);
        }
    }

    /**
     * Process a single detection - thread-safe
     */
    private void processDetection(LagMachine machine, List<LagMachine> newDetections, Set<UUID> stillActive) {
        // Check if this is a new detection
        boolean isNew = true;
        for (LagMachine existing : detectedMachines.values()) {
            if (existing.getType() == machine.getType() &&
                    existing.getChunkKey().equals(machine.getChunkKey())) {
                isNew = false;
                stillActive.add(existing.getId());
                break;
            }
        }

        if (isNew) {
            detectedMachines.put(machine.getId(), machine);
            newDetections.add(machine);
            stillActive.add(machine.getId());

            if (plugin.getConfigManager().isLogDetections()) {
                plugin.getLogger().warning("Lag machine detected: " + machine);
            }
        }
    }

    private void notifyListeners(LagMachine machine) {
        for (DetectionListener listener : listeners) {
            try {
                listener.onLagMachineDetected(machine);
            } catch (Exception e) {
                plugin.getLogger().warning("Detection listener error: " + e.getMessage());
            }
        }
    }

    // Getters for detectors
    public RedstoneClockDetector getRedstoneDetector() {
        return redstoneDetector;
    }

    public EntityCrammingDetector getCrammingDetector() {
        return crammingDetector;
    }

    public ProjectileSpamDetector getSpamDetector() {
        return spamDetector;
    }

    public NBTBanDetector getNbtDetector() {
        return nbtDetector;
    }

    /**
     * Get all currently detected lag machines
     */
    public Collection<LagMachine> getDetectedMachines() {
        return Collections.unmodifiableCollection(detectedMachines.values());
    }

    /**
     * Get detected machines by type
     */
    public List<LagMachine> getDetectedMachines(LagMachineType type) {
        List<LagMachine> result = new ArrayList<>();
        for (LagMachine machine : detectedMachines.values()) {
            if (machine.getType() == type) {
                result.add(machine);
            }
        }
        return result;
    }

    /**
     * Add a detection listener
     */
    public void addListener(DetectionListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a detection listener
     */
    public void removeListener(DetectionListener listener) {
        listeners.remove(listener);
    }

    /**
     * Listener interface for detection events
     */
    public interface DetectionListener {
        void onLagMachineDetected(LagMachine machine);
    }
}
