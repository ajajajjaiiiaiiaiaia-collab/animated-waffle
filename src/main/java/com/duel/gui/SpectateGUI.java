package com.duel.gui;

import com.duel.DuelPlugin;
import com.duel.models.DuelSession;
import com.duel.models.PartyDuelSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

/**
 * GUI لاختيار مباراة للتفرج عليها.
 *
 * Layout (54 slot — 6 rows):
 *  - Rows 0-3 (slots 0-35): مباريات (max 28 per page مع الأطار)
 *  - Row 4 (slots 36-44): فارغ
 *  - Row 5: Previous (slot 45), filler, Next (slot 53)
 */
public class SpectateGUI {

    private static final int PAGE_SIZE   = 28; // slots 10-16, 19-25, 28-34 — inner 4×7
    private static final int ROWS        = 6;
    private static final int SIZE        = ROWS * 9; // 54

    private final DuelPlugin plugin;

    public SpectateGUI(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    // ────────────────────────────────────────────
    //  Open
    // ────────────────────────────────────────────

    public void open(Player viewer, int page) {
        List<MatchEntry> matches = collectMatches();

        int totalPages = Math.max(1, (int) Math.ceil(matches.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, totalPages - 1));

        Inventory inv = Bukkit.createInventory(null, SIZE,
                Component.text("👁 Spectate — Page " + (page + 1) + "/" + totalPages)
                        .color(NamedTextColor.DARK_AQUA)
                        .decoration(TextDecoration.ITALIC, false));

        // Fill border with gray glass
        ItemStack border = borderPane();
        for (int i = 0; i < SIZE; i++) inv.setItem(i, border);

        // Inner content slots (4 rows × 7 cols = 28)
        int[] contentSlots = buildContentSlots();
        int start = page * PAGE_SIZE;

        for (int i = 0; i < PAGE_SIZE && (start + i) < matches.size(); i++) {
            inv.setItem(contentSlots[i], buildMatchItem(matches.get(start + i)));
        }

        // Navigation
        if (page > 0)          inv.setItem(45, prevButton(page));
        if (page < totalPages - 1) inv.setItem(53, nextButton(page));

        // Empty indicator
        if (matches.isEmpty()) {
            inv.setItem(22, noMatchesItem());
        }

        viewer.openInventory(inv);

        // Store page in metadata so listener can flip pages
        viewer.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(plugin, "spectate_page"),
                org.bukkit.persistence.PersistentDataType.INTEGER, page);
    }

    // ────────────────────────────────────────────
    //  Collect all active matches
    // ────────────────────────────────────────────

    private List<MatchEntry> collectMatches() {
        List<MatchEntry> list = new ArrayList<>();

        // 1v1
        for (DuelSession s : plugin.getDuelManager().getAllActiveSessions()) {
            list.add(new MatchEntry(s));
        }

        // Party vs Party / Party FFA
        for (PartyDuelSession s : plugin.getPartyDuelManager().getAllActiveSessions()) {
            list.add(new MatchEntry(s));
        }

        // Sort newest first
        list.sort(Comparator.comparingLong(MatchEntry::getStartTime).reversed());
        return list;
    }

    // ────────────────────────────────────────────
    //  Build match item (player skull)
    // ────────────────────────────────────────────

    private ItemStack buildMatchItem(MatchEntry entry) {
        // Pick a random player from the match as the skull
        Player representative = entry.getRandomPlayer();

        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();

        // Set skull owner
        if (representative != null) meta.setOwningPlayer(representative);

        // Title
        meta.displayName(
                Component.text("⚔ " + entry.getTitle())
                        .color(NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
        );

        // Lore
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());

        // Match type
        lore.add(line("⚡ Type", entry.getType(), NamedTextColor.YELLOW));

        // Players
        lore.add(line("👥 Players", entry.getPlayersString(), NamedTextColor.GREEN));

        // Duration
        lore.add(line("⏱ Duration", entry.getDurationString(), NamedTextColor.GOLD));

        // Arena
        lore.add(line("🗺 Arena", entry.getArenaName(), NamedTextColor.LIGHT_PURPLE));

        lore.add(Component.empty());
        lore.add(Component.text("  Click to spectate!")
                .color(NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        skull.setItemMeta(meta);
        return skull;
    }

    private Component line(String label, String value, NamedTextColor valueColor) {
        return Component.text("  " + label + ": ")
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(value).color(valueColor).decoration(TextDecoration.ITALIC, false));
    }

    // ────────────────────────────────────────────
    //  Navigation buttons
    // ────────────────────────────────────────────

    private ItemStack prevButton(int currentPage) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("◀ Previous Page")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Page " + currentPage)
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack nextButton(int currentPage) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Next Page ▶")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Page " + (currentPage + 2))
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack noMatchesItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("No active matches")
                .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Check back later!")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack borderPane() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    // ────────────────────────────────────────────
    //  Slot layout — inner 4 rows × 7 cols
    // ────────────────────────────────────────────

    private int[] buildContentSlots() {
        int[] slots = new int[PAGE_SIZE];
        int idx = 0;
        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 7; col++) {
                slots[idx++] = row * 9 + col;
            }
        }
        return slots;
    }

    // ────────────────────────────────────────────
    //  MatchEntry — wrapper for both session types
    // ────────────────────────────────────────────

    public static class MatchEntry {
        private final Object session; // DuelSession or PartyDuelSession
        private final boolean isParty;
        private final Random rng = new Random();

        public MatchEntry(DuelSession s)      { this.session = s; this.isParty = false; }
        public MatchEntry(PartyDuelSession s) { this.session = s; this.isParty = true;  }

        public long getStartTime() {
            return isParty ? ((PartyDuelSession) session).getStartTime()
                           : ((DuelSession) session).getStartTime();
        }

        public String getType() {
            if (!isParty) return "1v1";
            PartyDuelSession ps = (PartyDuelSession) session;
            return ps.isFFA() ? "Party FFA" : "Party vs Party";
        }

        public String getTitle() {
            if (!isParty) {
                DuelSession ds = (DuelSession) session;
                return ds.getPlayer1().getName() + " vs " + ds.getPlayer2().getName();
            }
            PartyDuelSession ps = (PartyDuelSession) session;
            String t1 = ps.getTeam1().isEmpty() ? "?" : ps.getTeam1().get(0).getName();
            String t2 = ps.isFFA() ? "FFA" : ps.getTeam2().get(0).getName();
            return t1 + "'s party vs " + t2 + "'s party";
        }

        public String getPlayersString() {
            if (!isParty) return "2 players";
            PartyDuelSession ps = (PartyDuelSession) session;
            int total = ps.getTeam1().size() + ps.getTeam2().size();
            long alive = ps.getAlivePlayers().size();
            return alive + "/" + total + " alive";
        }

        public String getDurationString() {
            long elapsed = (System.currentTimeMillis() - getStartTime()) / 1000;
            long mins = elapsed / 60;
            long secs = elapsed % 60;
            return String.format("%02d:%02d", mins, secs);
        }

        public String getArenaName() {
            if (!isParty) return ((DuelSession) session).getArena().getId();
            return ((PartyDuelSession) session).getArena().getId();
        }

        public Player getRandomPlayer() {
            if (!isParty) {
                DuelSession ds = (DuelSession) session;
                return rng.nextBoolean() ? ds.getPlayer1() : ds.getPlayer2();
            }
            PartyDuelSession ps = (PartyDuelSession) session;
            List<Player> all = ps.getAllPlayers();
            return all.isEmpty() ? null : all.get(rng.nextInt(all.size()));
        }

        public Object getSession() { return session; }
        public boolean isParty()   { return isParty; }
    }
}
