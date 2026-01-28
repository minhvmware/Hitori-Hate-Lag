package com.hitori.antilag.core;

/**
 * Classification levels for chunk hotspots based on lag score
 */
public enum HotspotLevel {

    /**
     * Normal chunk, no concerns
     */
    NORMAL(0, "§a"),

    /**
     * Medium lag potential, should be monitored
     */
    MEDIUM(1, "§e"),

    /**
     * High lag potential, may need intervention
     */
    HIGH(2, "§6"),

    /**
     * Critical lag, immediate action recommended
     */
    CRITICAL(3, "§c");

    private final int severity;
    private final String colorCode;

    HotspotLevel(int severity, String colorCode) {
        this.severity = severity;
        this.colorCode = colorCode;
    }

    public int getSeverity() {
        return severity;
    }

    public String getColorCode() {
        return colorCode;
    }

    public boolean isWorseThan(HotspotLevel other) {
        return this.severity > other.severity;
    }

    public boolean isAtLeast(HotspotLevel level) {
        return this.severity >= level.severity;
    }
}
