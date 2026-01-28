package com.hitori.antilag.mitigation;

/**
 * Server protection modes based on performance thresholds
 */
public enum ProtectionMode {

    /**
     * Normal operation - no intervention
     */
    NORMAL("Normal", "§a", 0),

    /**
     * Warning level - light mitigation
     */
    WARNING("Warning", "§e", 1),

    /**
     * Critical level - moderate mitigation
     */
    CRITICAL("Critical", "§6", 2),

    /**
     * Emergency level - aggressive mitigation
     */
    EMERGENCY("Emergency", "§c", 3);

    private final String displayName;
    private final String colorCode;
    private final int severity;

    ProtectionMode(String displayName, String colorCode, int severity) {
        this.displayName = displayName;
        this.colorCode = colorCode;
        this.severity = severity;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getColorCode() {
        return colorCode;
    }

    public int getSeverity() {
        return severity;
    }

    public boolean isWorseThan(ProtectionMode other) {
        return this.severity > other.severity;
    }

    public boolean requiresIntervention() {
        return this.severity > 0;
    }
}
