package com.hitori.antilag.util;

import org.bukkit.Bukkit;

/**
 * Utility class for Folia API compatibility
 */
public final class FoliaCompat {

    private static final boolean IS_FOLIA;

    static {
        boolean folia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException e) {
            // Not Folia
        }
        IS_FOLIA = folia;
    }

    private FoliaCompat() {
        // Utility class
    }

    /**
     * Check if the server is running Folia
     */
    public static boolean isFolia() {
        return IS_FOLIA;
    }

    /**
     * Get the server implementation name
     */
    public static String getServerType() {
        if (IS_FOLIA) {
            return "Folia";
        }

        String version = Bukkit.getVersion();
        if (version.contains("Paper")) {
            return "Paper";
        } else if (version.contains("Spigot")) {
            return "Spigot";
        }
        return "Unknown";
    }
}
