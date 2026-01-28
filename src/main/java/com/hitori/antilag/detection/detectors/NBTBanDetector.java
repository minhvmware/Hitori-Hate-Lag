package com.hitori.antilag.detection.detectors;

import com.hitori.antilag.HitoriAntiLag;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Prevents NBT-based chunk bans and server crashes
 * SECURITY: Implements depth limiting, time budgets, and actual NBT size
 * validation
 */
public class NBTBanDetector implements Listener {

    private final HitoriAntiLag plugin;

    // Security limits to prevent exploits
    private static final int MAX_RECURSION_DEPTH = 5; // Prevent stack overflow
    private static final long MAX_CHECK_TIME_NS = 5_000_000; // 5ms time budget
    private final int maxShulkerNbtBytes;
    private final int maxBookPages;
    private final int maxPageLength;

    public NBTBanDetector(HitoriAntiLag plugin) {
        this.plugin = plugin;
        this.maxShulkerNbtBytes = plugin.getConfigManager().getMaxShulkerNbtBytes();
        this.maxBookPages = plugin.getConfigManager().getMaxBookPages();
        this.maxPageLength = plugin.getConfigManager().getMaxPageLength();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.getConfigManager().isNbtProtectionEnabled()) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItemInHand();

        // SECURITY FIX: Use Tag API instead ofstring comparison
        if (Tag.SHULKER_BOXES.isTagged(item.getType())) {
            long startTime = System.nanoTime();
            SafetyResult result = checkShulkerSafe(item, 0, startTime);

            if (!result.isSafe()) {
                event.setCancelled(true);
                warnPlayer(player, "Shulker box blocked: " + result.getReason());
                plugin.getLogger().warning("Blocked NBT exploit from " + player.getName() + ": " + result.getReason());
            }
        } else if (item.getType() == Material.WRITTEN_BOOK || item.getType() == Material.WRITABLE_BOOK) {
            SafetyResult result = checkBookSafe(item);

            if (!result.isSafe()) {
                event.setCancelled(true);
                warnPlayer(player, "Book blocked: " + result.getReason());
                plugin.getLogger().warning("Blocked book exploit from " + player.getName() + ": " + result.getReason());
            }
        }
    }

    /**
     * SECURITY-HARDENED shulker box validation
     * - Depth limiting prevents stack overflow attacks (nested shulkers)
     * - Time budget prevents lag spike exploits
     * - Checks actual serialized NBT size
     */
    private SafetyResult checkShulkerSafe(ItemStack item, int depth, long startTime) {
        // SECURITY FIX #1: Depth limit to prevent stack overflow from nested shulkers
        if (depth > MAX_RECURSION_DEPTH) {
            return SafetyResult.unsafe("Excessive nesting depth (> " + MAX_RECURSION_DEPTH + ")");
        }

        // SECURITY FIX #2: Time budget to prevent main thread blocking
        long elapsed = System.nanoTime() - startTime;
        if (elapsed > MAX_CHECK_TIME_NS) {
            return SafetyResult.unsafe("Check timeout (suspicious item complexity)");
        }

        if (!(item.getItemMeta() instanceof BlockStateMeta meta)) {
            return SafetyResult.safe();
        }

        if (!(meta.getBlockState() instanceof org.bukkit.block.ShulkerBox shulker)) {
            return SafetyResult.safe();
        }

        org.bukkit.inventory.Inventory inv = shulker.getInventory();
        int totalSize = 0;

        for (ItemStack content : inv.getContents()) {
            if (content == null || content.getType().isAir()) {
                continue;
            }

            // Check for unsafe book content
            SafetyResult bookResult = checkBookSafe(content);
            if (!bookResult.isSafe()) {
                return SafetyResult.unsafe("Contains unsafe book: " + bookResult.getReason());
            }

            // SECURITY FIX #1: Recursive check with depth tracking
            if (Tag.SHULKER_BOXES.isTagged(content.getType())) {
                SafetyResult nestedResult = checkShulkerSafe(content, depth + 1, startTime);
                if (!nestedResult.isSafe()) {
                    return nestedResult;
                }
            }

            // SECURITY FIX #3: Get actual NBT size via serialization (catches PDC exploits)
            int actualSize = getActualNBTSize(content);
            totalSize += actualSize;
        }

        if (totalSize > maxShulkerNbtBytes) {
            return SafetyResult.unsafe(
                    String.format("Total NBT size %d bytes exceeds limit of %d bytes", totalSize, maxShulkerNbtBytes));
        }

        return SafetyResult.safe();
    }

    /**
     * SECURITY FIX #3: Get ACTUAL NBT size via serialization
     * This catches PDC (PersistentDataContainer) exploits and custom tags
     */
    private int getActualNBTSize(ItemStack item) {
        try {
            // Serialize item to NBT and measure actual size
            byte[] serialized = item.serializeAsBytes();
            return serialized.length;
        } catch (Exception e) {
            // Fallback to estimation if serialization fails
            return estimateNBTSize(item);
        }
    }

    /**
     * Check if a book is safe
     */
    private SafetyResult checkBookSafe(ItemStack book) {
        if (!book.hasItemMeta()) {
            return SafetyResult.safe();
        }

        if (!(book.getItemMeta() instanceof BookMeta meta)) {
            return SafetyResult.safe();
        }

        int pageCount = meta.getPageCount();
        if (pageCount > maxBookPages) {
            return SafetyResult.unsafe(
                    String.format("Book has %d pages, exceeds limit of %d", pageCount, maxBookPages));
        }

        for (int i = 1; i <= pageCount; i++) {
            String page = meta.getPage(i);
            if (page.length() > maxPageLength) {
                return SafetyResult.unsafe(
                        String.format("Page %d has %d characters, exceeds limit of %d",
                                i, page.length(), maxPageLength));
            }
        }

        return SafetyResult.safe();
    }

    /**
     * Fallback NBT size estimation (used if serialization fails)
     */
    private int estimateNBTSize(ItemStack item) {
        int base = 50; // Base item data

        if (!item.hasItemMeta()) {
            return base * item.getAmount();
        }

        ItemMeta meta = item.getItemMeta();

        // Display name
        if (meta.hasDisplayName()) {
            base += meta.displayName().toString().length() * 2;
        }

        // Lore
        if (meta.hasLore()) {
            for (net.kyori.adventure.text.Component line : meta.lore()) {
                base += line.toString().length() * 2;
            }
        }

        // Enchantments
        if (meta.hasEnchants()) {
            base += meta.getEnchants().size() * 20;
        }

        // Attribute modifiers
        if (meta.hasAttributeModifiers()) {
            base += meta.getAttributeModifiers().size() * 30;
        }

        return base * item.getAmount();
    }

    private void warnPlayer(Player player, String message) {
        player.sendMessage("§c[AntiLag] §7" + message);
    }

    /**
     * Result class for NBT safety checks
     */
    public static class SafetyResult {
        private final boolean safe;
        private final String reason;

        private SafetyResult(boolean safe, String reason) {
            this.safe = safe;
            this.reason = reason;
        }

        public static SafetyResult safe() {
            return new SafetyResult(true, null);
        }

        public static SafetyResult unsafe(String reason) {
            return new SafetyResult(false, reason);
        }

        public boolean isSafe() {
            return safe;
        }

        public String getReason() {
            return reason;
        }
    }
}
