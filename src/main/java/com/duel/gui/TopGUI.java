package com.duel.gui;

import com.duel.DuelPlugin;
import com.duel.managers.StatsManager;
import com.duel.managers.StatsManager.PlayerStats;
import com.duel.managers.StatsManager.TopType;
import com.duel.utils.ItemUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Leaderboard hologram-style GUI.
 *
 * Layout (54 slots):
 *   Row 0 (0-8)   : border
 *   Slot 4        : title item (arena + type)
 *   Row 1 (9-17)  : border sides, top-10 entries in slots 10-16 (slots 1-7)
 *   Rows 2-5      : entries continue in interior columns 1-7
 *   Row 5 (45-53) : border
 */
public class TopGUI implements InventoryHolder {

    // Interior slots for the 10 entries (column 1-7, rows 1-5)
    private static final int[] ENTRY_SLOTS = {10, 12, 14, 16, 19, 21, 23, 25, 28, 30};

    private final DuelPlugin plugin;
    private final Inventory  inventory;
    private final String     arenaId;
    private final TopType    topType;

    public TopGUI(DuelPlugin plugin, String arenaId, TopType topType) {
        this.plugin   = plugin;
        this.arenaId  = arenaId;
        this.topType  = topType;

        String typeLabel = switch (topType) {
            case WINS    -> "Wins";
            case LOSSES  -> "Losses";
            case STREAK  -> "Streak";
            case POINTS  -> "Points";
        };

        NamedTextColor titleColor = switch (topType) {
            case WINS    -> NamedTextColor.GREEN;
            case LOSSES  -> NamedTextColor.RED;
            case STREAK  -> NamedTextColor.GOLD;
            case POINTS  -> NamedTextColor.AQUA;
        };

        String displayName = arenaId.equals("global") ? "Global" : arenaId;
        this.inventory = Bukkit.createInventory(this, 54,
                Component.text("🏆  " + displayName + "  —  Top " + typeLabel)
                        .color(titleColor)
                        .decoration(TextDecoration.BOLD, true));

        populate();
    }

    private void populate() {
        // ── Border ──
        ItemStack border = ItemUtils.buildFiller(getBorderMaterial());
        for (int i = 0; i < 54; i++) inventory.setItem(i, border);

        // ── Title Item (slot 4) ──
        inventory.setItem(4, buildTitleItem());

        // ── Entries ──
        List<PlayerStats> top = arenaId.equals("global")
                ? plugin.getStatsManager().getTopGlobal(topType)
                : plugin.getStatsManager().getTop10(arenaId, topType);

        for (int i = 0; i < ENTRY_SLOTS.length; i++) {
            if (i < top.size()) {
                inventory.setItem(ENTRY_SLOTS[i], buildEntryItem(i + 1, top.get(i)));
            } else {
                inventory.setItem(ENTRY_SLOTS[i], buildEmptySlot(i + 1));
            }
        }
    }

    private Material getBorderMaterial() {
        return switch (topType) {
            case WINS    -> Material.LIME_STAINED_GLASS_PANE;
            case LOSSES  -> Material.RED_STAINED_GLASS_PANE;
            case STREAK  -> Material.ORANGE_STAINED_GLASS_PANE;
            case POINTS  -> Material.CYAN_STAINED_GLASS_PANE;
        };
    }

    private ItemStack buildTitleItem() {
        Material mat = switch (topType) {
            case WINS    -> Material.GOLDEN_SWORD;
            case LOSSES  -> Material.BONE;
            case STREAK  -> Material.BLAZE_ROD;
            case POINTS  -> Material.NETHER_STAR;
        };

        NamedTextColor color = switch (topType) {
            case WINS    -> NamedTextColor.GREEN;
            case LOSSES  -> NamedTextColor.RED;
            case STREAK  -> NamedTextColor.GOLD;
            case POINTS  -> NamedTextColor.AQUA;
        };

        String typeLabel = switch (topType) {
            case WINS    -> "Wins";
            case LOSSES  -> "Losses";
            case STREAK  -> "Best Streak";
            case POINTS  -> "Points";
        };

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text("🏆  " + arenaId)
                .color(color)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.text("Leaderboard: ").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(typeLabel).color(color).decoration(TextDecoration.ITALIC, false)));
        lore.add(Component.text("Arena: ").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(arenaId.equals("global") ? "All Arenas" : arenaId).color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false)));
        lore.add(Component.empty());
        lore.add(Component.text("Top 10 Players").color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, true));
        meta.lore(lore);

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildEntryItem(int rank, PlayerStats stats) {
        // Player head
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();

        // Try to fetch offline player for the skull texture
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(stats.uuid()));

        // Rank number color
        NamedTextColor rankColor = switch (rank) {
            case 1  -> NamedTextColor.GOLD;
            case 2  -> NamedTextColor.GRAY;
            case 3  -> NamedTextColor.RED;
            default -> NamedTextColor.DARK_GRAY;
        };

        String rankPrefix = switch (rank) {
            case 1 -> "🥇";
            case 2 -> "🥈";
            case 3 -> "🥉";
            default -> "#" + rank;
        };

        // Value displayed depends on top type
        int value = switch (topType) {
            case WINS    -> stats.wins();
            case LOSSES  -> stats.losses();
            case STREAK  -> stats.bestStreak();
            case POINTS  -> stats.points();
        };

        String valueLabel = switch (topType) {
            case WINS    -> "Wins";
            case LOSSES  -> "Losses";
            case STREAK  -> "Best Streak";
            case POINTS  -> "Points";
        };

        NamedTextColor nameColor = NamedTextColor.WHITE;
        NamedTextColor valueColor = switch (topType) {
            case WINS    -> NamedTextColor.GREEN;
            case LOSSES  -> NamedTextColor.RED;
            case STREAK  -> NamedTextColor.GOLD;
            case POINTS  -> NamedTextColor.AQUA;
        };

        meta.displayName(
                Component.text(rankPrefix + "  ").color(rankColor)
                        .decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(stats.name()).color(nameColor)
                                .decoration(TextDecoration.BOLD, false))
        );

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.text(valueLabel + ": ").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(String.valueOf(value)).color(valueColor)
                        .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)));
        lore.add(Component.text("Wins: ").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(stats.wins()).color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)));
        lore.add(Component.text("Losses: ").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(stats.losses()).color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)));
        lore.add(Component.text("Best Streak: ").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(stats.bestStreak()).color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)));
        lore.add(Component.text("Points: ").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(stats.points()).color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false)));
        lore.add(Component.empty());
        meta.lore(lore);

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildEmptySlot(int rank) {
        ItemStack item = new ItemStack(Material.GRAY_CONCRETE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("#" + rank + "  —  Empty")
                .color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    public static void open(DuelPlugin plugin, Player player, String arenaId, TopType topType) {
        TopGUI gui = new TopGUI(plugin, arenaId, topType);
        player.openInventory(gui.getInventory());
    }

    @Override
    public Inventory getInventory() { return inventory; }
}
