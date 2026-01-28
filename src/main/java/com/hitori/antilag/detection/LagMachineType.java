package com.hitori.antilag.detection;

/**
 * Types of lag machines that can be detected
 */
public enum LagMachineType {

    REDSTONE_CLOCK("Redstone Clock", "High-frequency redstone updates detected"),
    ENTITY_CRAMMING("Entity Cramming", "Too many entities in single block"),
    PROJECTILE_SPAM("Projectile Spam", "Excessive projectiles in chunk"),
    ITEM_SPAM("Item Spam", "Excessive dropped items in chunk"),
    TNT_SPAM("TNT Spam", "Excessive TNT entities in chunk"),
    HOPPER_CHAIN("Hopper Chain", "Long chain of hoppers detected"),
    NBT_OVERFLOW("NBT Overflow", "Excessive NBT data in item/block");

    private final String displayName;
    private final String description;

    LagMachineType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
