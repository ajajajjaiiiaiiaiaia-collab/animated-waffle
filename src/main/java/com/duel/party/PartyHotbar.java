package com.duel.party;

import com.duel.DuelPlugin;
import com.duel.utils.ItemUtils;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Manages hotbar items for the party system.
 * Called every time a player's party state changes (create, join, leave, disband).
 *
 * All slots, materials, names, and lore are configurable via config.yml
 * under the "party-hotbar" section.
 */
public class PartyHotbar {

    /**
     * Refresh this player's hotbar slots 0-8 based on their current party state.
     * Preserves slot 8 (kit editor) and restores the duel sword when no party.
     */
    public static void refresh(Player player) {
        DuelPlugin plugin = DuelPlugin.getInstance();
        Party party = plugin.getPartyManager().getParty(player);

        // Clear slots 0-7 (leave 8 alone at first, we set it at the end)
        for (int i = 0; i < 8; i++) {
            player.getInventory().setItem(i, null);
        }

        com.duel.managers.HotbarManager hm = plugin.getHotbarManager();
        ConfigurationSection hotbarConfig = plugin.getConfig().getConfigurationSection("party-hotbar");

        if (party == null) {
            ConfigurationSection noPartyConfig = hotbarConfig != null ? hotbarConfig.getConfigurationSection("no-party") : null;
            player.getInventory().setItem(
                hm.getSlot(com.duel.managers.HotbarManager.HotbarItem.DUEL_MENU),
                hm.buildItem(com.duel.managers.HotbarManager.HotbarItem.DUEL_MENU));

            ConfigurationSection createSec = noPartyConfig != null ? noPartyConfig.getConfigurationSection("create") : null;
            placeItem(player, createSec, 4, Material.NAME_TAG, "§6✦ Create Party",
                    List.of("§7Click to create a party", "§7and invite your friends!"));
        } else if (party.getLeader().equals(player)) {
            ConfigurationSection leaderConfig = hotbarConfig != null ? hotbarConfig.getConfigurationSection("leader") : null;
            placeItem(player, sec(leaderConfig, "info"), 1, Material.BOOK, "§b✦ Party Info",
                    List.of("§7Click to view party information."));
            placeItem(player, sec(leaderConfig, "other-parties"), 3, Material.COMPASS, "§a✦ Other Parties",
                    List.of("§7Click to view other parties."));
            placeItem(player, sec(leaderConfig, "ffa"), 4, Material.BLAZE_ROD, "§6✦ Party FFA",
                    List.of("§7Click to start FFA within your party.", "§7Everyone vs Everyone!"));
            boolean open = party.getPrivacy() == PartyPrivacy.OPEN;
            placeToggleItem(player, sec(leaderConfig, "open-party"), 5, Material.LIME_DYE, "§a✦ Open Party",
                    List.of("§7Click to open or close your party.", "§7Status: %status%"), open);
            placeItem(player, sec(leaderConfig, "disband"), 6, Material.FIRE_CHARGE, "§c✦ Disband Party",
                    List.of("§7Click to disband your party."));
        } else {
            ConfigurationSection memberConfig = hotbarConfig != null ? hotbarConfig.getConfigurationSection("member") : null;
            placeItem(player, sec(memberConfig, "info"), 1, Material.BOOK, "§b✦ Party Info",
                    List.of("§7Click to view party information."));
            placeItem(player, sec(memberConfig, "other-parties"), 3, Material.COMPASS, "§a✦ Other Parties",
                    List.of("§7Click to view other parties."));
            placeItem(player, sec(memberConfig, "leave"), 5, Material.BARRIER, "§c✦ Leave Party",
                    List.of("§7Click to leave the party."));
        }

        // Settings / Kit Editor (and Spectate when no party) — from HotbarManager so they match config
        player.getInventory().setItem(
            hm.getSlot(com.duel.managers.HotbarManager.HotbarItem.SETTINGS),
            hm.buildItem(com.duel.managers.HotbarManager.HotbarItem.SETTINGS));
        // Spectate item is only for players without a party
        if (party == null) {
            player.getInventory().setItem(
                hm.getSlot(com.duel.managers.HotbarManager.HotbarItem.SPECTATE),
                hm.buildItem(com.duel.managers.HotbarManager.HotbarItem.SPECTATE));
        }
        player.getInventory().setItem(
            hm.getSlot(com.duel.managers.HotbarManager.HotbarItem.KIT_EDITOR),
            hm.buildItem(com.duel.managers.HotbarManager.HotbarItem.KIT_EDITOR));
        player.updateInventory();
    }

    private static ConfigurationSection sec(ConfigurationSection parent, String key) {
        return parent != null ? parent.getConfigurationSection(key) : null;
    }

    /**
     * Builds an item from a config section (slot/material/name/lore) and places it,
     * falling back to the given defaults if the section or a field is missing.
     */
    private static void placeItem(Player player, ConfigurationSection sec, int defaultSlot,
                                   Material defaultMat, String defaultName, List<String> defaultLore) {
        int slot = defaultSlot;
        Material mat = defaultMat;
        String name = defaultName;
        List<String> lore = defaultLore;

        if (sec != null) {
            slot = sec.getInt("slot", defaultSlot);
            String matName = sec.getString("material");
            if (matName != null) {
                Material parsed = Material.matchMaterial(matName);
                if (parsed != null) mat = parsed;
            }
            String cfgName = sec.getString("name");
            if (cfgName != null) name = translateColors(cfgName);
            List<String> cfgLore = sec.getStringList("lore");
            if (!cfgLore.isEmpty()) {
                lore = cfgLore.stream().map(PartyHotbar::translateColors).toList();
            }
        }

        if (slot < 0 || slot > 8) slot = defaultSlot;

        player.getInventory().setItem(slot, glow(mat, name, lore.toArray(new String[0])));
    }

    /**
     * Like placeItem, but replaces %status% in the lore with the current party state
     * (Open / Closed) and shows the state in the item name.
     */
    private static void placeToggleItem(Player player, ConfigurationSection sec, int defaultSlot,
                                         Material defaultMat, String defaultName, List<String> defaultLore,
                                         boolean open) {
        String status = open ? "§aOpen" : "§cClosed";
        List<String> lore = defaultLore;
        int slot = defaultSlot;
        Material mat = defaultMat;
        String name = defaultName;

        if (sec != null) {
            slot = sec.getInt("slot", defaultSlot);
            String matName = sec.getString("material");
            if (matName != null) {
                Material parsed = Material.matchMaterial(matName);
                if (parsed != null) mat = parsed;
            }
            String cfgName = sec.getString("name");
            if (cfgName != null) name = translateColors(cfgName);
            List<String> cfgLore = sec.getStringList("lore");
            if (!cfgLore.isEmpty()) lore = cfgLore.stream().map(PartyHotbar::translateColors).toList();
        }
        if (slot < 0 || slot > 8) slot = defaultSlot;

        List<String> finalLore = lore.stream().map(l -> l.replace("%status%", status)).toList();
        player.getInventory().setItem(slot, glow(mat, name, finalLore.toArray(new String[0])));
    }

    private static String translateColors(String s) {
        return s.replace('&', '§');
    }

    private static ItemStack glow(Material mat, String name, String... lore) {
        ItemStack item = ItemUtils.build(mat, name, NamedTextColor.WHITE, lore);
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("luck_of_the_sea")), 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    // ── Item identity helpers (for click detection) ───

    public static boolean isCreateItem(ItemStack item)       { return matchesByName(item, "Create Party"); }
    public static boolean isDisbandItem(ItemStack item)      { return matchesByName(item, "Disband Party"); }
    public static boolean isLeaveItem(ItemStack item)        { return matchesByName(item, "Leave Party"); }
    public static boolean isInfoItem(ItemStack item)         { return matchesByName(item, "Party Info"); }
    public static boolean isOtherPartiesItem(ItemStack item) { return matchesByName(item, "Other Parties"); }
    public static boolean isOpenPartyItem(ItemStack item)    { return matchesByName(item, "Open Party"); }
    public static boolean isFFAItem(ItemStack item)          { return matchesByName(item, "Party FFA"); }

    /**
     * Returns true if the item is ANY party hotbar item (create / info / disband / leave / ffa / other-parties).
     * Used by InventoryListener to block moving party items out of the hotbar.
     */
    public static boolean isAnyPartyItem(ItemStack item) {
        return isCreateItem(item) || isInfoItem(item) || isDisbandItem(item)
                || isLeaveItem(item) || isFFAItem(item) || isOtherPartiesItem(item)
                || isOpenPartyItem(item);
    }

    /**
     * Matches items by a stable keyword inside the display name rather than exact string,
     * since the name text itself is now configurable and may be customised by server admins.
     */
    private static boolean matchesByName(ItemStack item, String keyword) {
        if (item == null || !item.hasItemMeta()) return false;
        String name = item.getItemMeta().getDisplayName();
        if (name == null) return false;
        String stripped = name.replaceAll("§.", "").trim();
        return stripped.contains(keyword);
    }
}
