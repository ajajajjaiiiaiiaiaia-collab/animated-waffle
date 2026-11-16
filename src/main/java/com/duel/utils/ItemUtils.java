package com.duel.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ItemUtils {

    public static ItemStack build(Material mat, String name, NamedTextColor color, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text(name).color(color).decoration(TextDecoration.ITALIC, false));

        if (lore.length > 0) {
            List<Component> loreList = Arrays.stream(lore)
                    .map(l -> Component.text(l).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                    .collect(Collectors.toList());
            meta.lore(loreList);
        }

        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack buildGlow(Material mat, String name, NamedTextColor color, String... lore) {
        ItemStack item = build(mat, name, color, lore);
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack buildFiller(Material mat) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    /** The duel selector sword given on join */
    public static ItemStack getDuelSword() {
        ItemStack sword = build(
                Material.IRON_SWORD,
                "⚔ Duel Menu",
                NamedTextColor.AQUA,
                "Click to open the duel arena selector"
        );
        ItemMeta meta = sword.getItemMeta();
        meta.addEnchant(Enchantment.SHARPNESS, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        sword.setItemMeta(meta);
        return sword;
    }

    /** Kit-edit book given to players on join (slot 8 — far right hotbar) */
    public static ItemStack getKitEditBook() {
        ItemStack book = build(
                Material.BOOK,
                "§d✦ Kit Editor",
                NamedTextColor.LIGHT_PURPLE,
                "Right-click to customise your kit",
                "§7for each arena"
        );
        ItemMeta meta = book.getItemMeta();
        meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        book.setItemMeta(meta);
        return book;
    }

    /** Axe given to admin during spawn-point selection */
    public static ItemStack getSpawnAxe(String worldName) {
        return buildGlow(
                Material.GOLDEN_AXE,
                "§6Set Spawn — §e" + worldName,
                NamedTextColor.GOLD,
                "Right-click to set spawn for " + worldName,
                "§7Sneak + Right-click to finish"
        );
    }

    // ── Party hotbar items ────────────────────

    public static ItemStack getPartyCreateItem() {
        return buildGlow(Material.BLAZE_POWDER, "§6Create Party",
                NamedTextColor.GOLD, "Right-click to create a party");
    }

    public static ItemStack getPartyInfoItem() {
        return build(Material.PAPER, "§eParty Info",
                NamedTextColor.YELLOW, "Right-click to view party info");
    }

    public static ItemStack getPartyLeaveItem() {
        return buildGlow(Material.REDSTONE, "§cLeave Party",
                NamedTextColor.RED, "Right-click to leave the party");
    }

    public static ItemStack getPartyDisbandItem() {
        return buildGlow(Material.TNT, "§4Disband Party",
                NamedTextColor.DARK_RED, "Right-click to disband the party");
    }

    /** Settings clock — slot 0 (far left hotbar), opens SettingsGUI on right-click */
    public static ItemStack getSettingsClock() {
        ItemStack clock = build(
                Material.CLOCK,
                "§e⚙ Settings",
                NamedTextColor.YELLOW,
                "§7Right-click to open settings"
        );
        ItemMeta meta = clock.getItemMeta();
        meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        clock.setItemMeta(meta);
        return clock;
    }

    /** Spectate item — slot 6 */
    public static ItemStack getSpectateItem() {
        return buildGlow(
                Material.ENDER_EYE,
                "👁 Spectate",
                NamedTextColor.DARK_AQUA,
                "Right-click to browse active matches",
                "§7Click on a match to spectate it"
        );
    }

    public static ItemStack getPartyFFAItem() {
        return buildGlow(Material.NETHER_STAR, "§6⚔ Party FFA",
                NamedTextColor.GOLD,
                "§7Right-click to start FFA",
                "§7Every member against everyone — last one standing wins");
    }
}
