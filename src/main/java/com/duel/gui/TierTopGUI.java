package com.duel.gui;

import com.duel.DuelPlugin;
import com.duel.managers.CountryManager;
import com.duel.models.Arena;
import com.duel.tier.Tier;
import com.duel.tier.TierManager;
import com.duel.tier.TierProfile;
import com.duel.tier.TierTopDialog;
import com.duel.utils.SoundUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TierTopGUI implements Listener {

    private static final int SIZE = 54;
    private static final int ARENAS_PER_PAGE = 5;
    private static final int SLOT_HEADER = 4;
    private static final int[] PODIUM_SLOTS = {13, 11, 15};
    private static final int[] REST_SLOTS = {28, 29, 30, 31, 32, 33, 34};
    private static final int SLOT_MINE = 40;
    private static final int SLOT_CLOSE = 45;
    private static final int SLOT_PREV = 46;
    private static final int SLOT_FIRST_ARENA = 47;
    private static final int SLOT_NEXT = 52;

    private static final TextColor GOLD = TextColor.color(0xFFAA00);
    private static final TextColor SILVER = TextColor.color(0xC0C0C0);
    private static final TextColor BRONZE = TextColor.color(0xCD7F32);

    public static final class Holder implements InventoryHolder {
        private final String arenaId;
        private final int page;
        private Inventory inventory;

        private Holder(String arenaId, int page) {
            this.arenaId = arenaId;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final DuelPlugin plugin;

    public TierTopGUI(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    // ────────────────────────────────────────────
    //  Open
    // ────────────────────────────────────────────

    public static void open(DuelPlugin plugin, Player player, String arenaId) {
        TierManager tm = plugin.getTierManager();
        if (tm == null || !tm.isEnabled()) {
            player.sendMessage(Component.text("Tier system is disabled.", NamedTextColor.RED));
            return;
        }
        List<Arena> arenas = sortedArenas(plugin);
        if (arenas.isEmpty()) {
            player.sendMessage(Component.text("No arenas available.", NamedTextColor.RED));
            return;
        }
        Arena arena = arenaId == null ? null : plugin.getArenaManager().getArena(arenaId);
        if (arena == null) arena = TierTopDialog.defaultArena(plugin, player);
        if (arena == null) arena = arenas.get(0);

        int idx = 0;
        for (int i = 0; i < arenas.size(); i++) {
            if (arenas.get(i).getId().equalsIgnoreCase(arena.getId())) { idx = i; break; }
        }
        render(plugin, player, arena, arenas, idx / ARENAS_PER_PAGE);
    }

    private static List<Arena> sortedArenas(DuelPlugin plugin) {
        List<Arena> all = new ArrayList<>(plugin.getArenaManager().getAllArenas());
        all.sort((x, y) -> x.getId().compareToIgnoreCase(y.getId()));
        return all;
    }

    private static int pageCount(int arenaCount) {
        return Math.max(1, (int) Math.ceil(arenaCount / (double) ARENAS_PER_PAGE));
    }

    // ────────────────────────────────────────────
    //  Render
    // ────────────────────────────────────────────

    private static void render(DuelPlugin plugin, Player player, Arena arena, List<Arena> arenas, int page) {
        TierManager tm = plugin.getTierManager();
        page = Math.max(0, Math.min(page, pageCount(arenas.size()) - 1));

        Holder holder = new Holder(arena.getId(), page);
        Component title = Component.text("\u2605 Tier Top ", GOLD)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(arena.getId().toUpperCase(), NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false));
        Inventory inv = Bukkit.createInventory(holder, SIZE, title);
        holder.inventory = inv;

        ItemStack filler = filler();
        for (int i = 0; i < SIZE; i++) inv.setItem(i, filler);

        // Header
        ItemStack header = new ItemStack(arenaMaterial(arena));
        ItemMeta hm = header.getItemMeta();
        hm.displayName(text(arena.getId().toUpperCase(), NamedTextColor.AQUA, true));
        hm.lore(List.of(
                text("Season " + tm.getSeason(), NamedTextColor.GRAY, false),
                text("Top 10 by rating", NamedTextColor.GRAY, false)));
        hm.addItemFlags(ItemFlag.values());
        header.setItemMeta(hm);
        inv.setItem(SLOT_HEADER, header);

        // Top 10
        List<TierManager.TopEntry> top = tm.getTop(arena.getId());
        if (top.isEmpty()) {
            ItemStack none = new ItemStack(Material.BARRIER);
            ItemMeta nm = none.getItemMeta();
            nm.displayName(text("No ranked matches yet", NamedTextColor.GRAY, false));
            none.setItemMeta(nm);
            inv.setItem(PODIUM_SLOTS[0], none);
        } else {
            for (int i = 0; i < top.size() && i < 10; i++) {
                TierManager.TopEntry e = top.get(i);
                int rank = i + 1;
                int slot = rank <= 3 ? PODIUM_SLOTS[i] : REST_SLOTS[rank - 4];
                inv.setItem(slot, playerHead(plugin, e.uuid(), e.name(), rank, e.tier(), e.rating()));
            }
        }

        // Own entry
        TierProfile mine = tm.getFresh(player.getUniqueId(), player.getName(), arena.getId());
        inv.setItem(SLOT_MINE, mineItem(plugin, player, mine, 0));

        // Bottom row
        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta cm = close.getItemMeta();
        cm.displayName(text("Close", NamedTextColor.RED, true));
        close.setItemMeta(cm);
        inv.setItem(SLOT_CLOSE, close);

        int pages = pageCount(arenas.size());
        if (page > 0) inv.setItem(SLOT_PREV, navItem("Previous arenas", page, pages));
        if (page < pages - 1) inv.setItem(SLOT_NEXT, navItem("Next arenas", page + 2, pages));

        int start = page * ARENAS_PER_PAGE;
        for (int i = 0; i < ARENAS_PER_PAGE && start + i < arenas.size(); i++) {
            Arena other = arenas.get(start + i);
            boolean selected = other.getId().equalsIgnoreCase(arena.getId());
            inv.setItem(SLOT_FIRST_ARENA + i, arenaButton(plugin, player, other, selected));
        }

        player.openInventory(inv);

        final int rankSlotPage = page;
        tm.getRankAsync(player.getUniqueId(), arena.getId(), rank -> {
            if (!player.isOnline()) return;
            Inventory current = player.getOpenInventory().getTopInventory();
            if (current.getHolder() != holder || holder.page != rankSlotPage) return;
            current.setItem(SLOT_MINE, mineItem(plugin, player, mine, rank));
        });
    }

    // ────────────────────────────────────────────
    //  Items
    // ────────────────────────────────────────────

    private static ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        item.setItemMeta(meta);
        return item;
    }

    private static Material arenaMaterial(Arena arena) {
        List<ItemStack> items = arena.getGuiItems();
        if (items != null) {
            for (ItemStack s : items) {
                if (s != null && s.getType() != Material.AIR) return s.getType();
            }
        }
        return Material.NETHER_STAR;
    }

    private static ItemStack navItem(String label, int shownPage, int pages) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(label, NamedTextColor.YELLOW, true));
        meta.lore(List.of(text("Page " + shownPage + "/" + pages, NamedTextColor.GRAY, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack arenaButton(DuelPlugin plugin, Player viewer, Arena arena, boolean selected) {
        ItemStack item = new ItemStack(arenaMaterial(arena));
        ItemMeta meta = item.getItemMeta();
        Tier t = plugin.getTierManager().getTier(viewer.getUniqueId(), arena.getId());
        meta.displayName(text(arena.getId().toUpperCase(), selected ? NamedTextColor.GREEN : NamedTextColor.AQUA, true));
        List<Component> lore = new ArrayList<>();
        lore.add(text("Your tier: ", NamedTextColor.GRAY, false).append(legacy(t.chat())));
        lore.add(Component.empty());
        lore.add(selected
                ? text("Currently viewing", NamedTextColor.GREEN, false)
                : text("Click to view this top", NamedTextColor.YELLOW, false));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        if (selected) meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack playerHead(DuelPlugin plugin, UUID uuid, String name, int rank, Tier tier, int rating) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
        meta.displayName(text("#" + rank + " ", rankColor(rank), true)
                .append(text(name, NamedTextColor.WHITE, false)));
        List<Component> lore = new ArrayList<>();
        lore.add(text("Tier: ", NamedTextColor.GRAY, false).append(legacy(tier.chat())));
        lore.add(text("Rating: ", NamedTextColor.GRAY, false).append(text(String.valueOf(rating), NamedTextColor.WHITE, false)));
        String country = countryName(plugin, uuid);
        if (country != null) {
            lore.add(text("Country: ", NamedTextColor.GRAY, false).append(text(country, NamedTextColor.WHITE, false)));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** rank: 0 = لسه يتحمّل، -1 = ما لعب ranked بهالأرينا، غير كذا = المركز. */
    private static ItemStack mineItem(DuelPlugin plugin, Player player, TierProfile mine, int rank) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(player);
        String shown = rank > 0 ? "#" + rank : (rank == 0 ? "..." : "Unranked");
        meta.displayName(text("Your rank: ", NamedTextColor.GREEN, true)
                .append(text(shown, NamedTextColor.WHITE, true)));
        List<Component> lore = new ArrayList<>();
        lore.add(text("Tier: ", NamedTextColor.GRAY, false).append(legacy(mine.tier.chat())));
        lore.add(text("Rating: ", NamedTextColor.GRAY, false).append(text(String.valueOf(mine.rating), NamedTextColor.WHITE, false)));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String countryName(DuelPlugin plugin, UUID uuid) {
        if (plugin.getCountryManager() == null) return null;
        String code = plugin.getCountryManager().getCountryCode(uuid);
        if (code == null || code.equals("XX")) return null;
        String name = CountryManager.getCountryName(code);
        return "Unknown".equals(name) ? null : name;
    }

    private static TextColor rankColor(int rank) {
        return switch (rank) {
            case 1 -> GOLD;
            case 2 -> SILVER;
            case 3 -> BRONZE;
            default -> NamedTextColor.GRAY;
        };
    }

    private static Component text(String s, TextColor color, boolean bold) {
        return Component.text(s, color)
                .decoration(TextDecoration.BOLD, bold)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static Component legacy(String section) {
        return LegacyComponentSerializer.legacySection().deserialize(section)
                .decoration(TextDecoration.ITALIC, false);
    }

    // ────────────────────────────────────────────
    //  Listener
    // ────────────────────────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getTopInventory()) return;

        int slot = event.getRawSlot();
        List<Arena> arenas = sortedArenas(plugin);
        int pages = pageCount(arenas.size());

        if (slot == SLOT_CLOSE) {
            SoundUtils.playGuiClick(player);
            player.closeInventory();
            return;
        }
        if (slot == SLOT_PREV && holder.page > 0) {
            SoundUtils.playGuiClick(player);
            Arena current = plugin.getArenaManager().getArena(holder.arenaId);
            if (current != null) render(plugin, player, current, arenas, holder.page - 1);
            return;
        }
        if (slot == SLOT_NEXT && holder.page < pages - 1) {
            SoundUtils.playGuiClick(player);
            Arena current = plugin.getArenaManager().getArena(holder.arenaId);
            if (current != null) render(plugin, player, current, arenas, holder.page + 1);
            return;
        }
        if (slot >= SLOT_FIRST_ARENA && slot < SLOT_FIRST_ARENA + ARENAS_PER_PAGE) {
            int idx = holder.page * ARENAS_PER_PAGE + (slot - SLOT_FIRST_ARENA);
            if (idx < arenas.size()) {
                SoundUtils.playGuiClick(player);
                render(plugin, player, arenas.get(idx), arenas, holder.page);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
