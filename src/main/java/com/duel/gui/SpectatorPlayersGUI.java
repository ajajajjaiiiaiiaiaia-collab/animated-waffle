package com.duel.gui;

import com.duel.DuelPlugin;
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
 * GUI بسطر واحد للمتفرج: لاعبي الماتش + باريير (أقصى اليمين) للخروج.
 * لو اللاعبين أكثر من 8 → صفحات: سهم يسار (slot 0) وسهم يمين (slot 7) و6 لاعبين بالنص.
 */
public class SpectatorPlayersGUI implements InventoryHolder {

    public static final int EXIT_SLOT = 8;
    public static final int PREV_SLOT = 0;
    public static final int NEXT_SLOT = 7;
    private static final int SINGLE_PAGE_MAX = 8;   // slots 0-7
    private static final int PAGED_SIZE = 6;        // slots 1-6

    private final DuelPlugin plugin;
    private final List<Player> targets;
    private final int page;
    private final int pages;
    private final boolean paged;
    private Inventory inventory;

    public SpectatorPlayersGUI(DuelPlugin plugin, Player spectator, int requestedPage) {
        this.plugin = plugin;
        this.targets = plugin.getFlySpectatorManager().getTargets(spectator);
        this.paged = targets.size() > SINGLE_PAGE_MAX;
        this.pages = paged ? (int) Math.ceil(targets.size() / (double) PAGED_SIZE) : 1;
        this.page = Math.max(0, Math.min(requestedPage, pages - 1));
    }

    public int getPage() { return page; }
    public boolean isPaged() { return paged; }

    /** اللاعب اللي بهذا الـ slot (أو null). */
    public Player getTargetAt(int slot) {
        if (!paged) return slot >= 0 && slot < SINGLE_PAGE_MAX && slot < targets.size() ? targets.get(slot) : null;
        if (slot < 1 || slot > PAGED_SIZE) return null;
        int idx = page * PAGED_SIZE + (slot - 1);
        return idx < targets.size() ? targets.get(idx) : null;
    }

    public void open(Player spectator) {
        String title = paged ? "🧭 Players (" + (page + 1) + "/" + pages + ")" : "🧭 Players";
        inventory = Bukkit.createInventory(this, 9,
                Component.text(title, NamedTextColor.DARK_AQUA).decoration(TextDecoration.ITALIC, false));

        if (targets.isEmpty()) {
            inventory.setItem(3, named(Material.GRAY_STAINED_GLASS_PANE, "No players to watch", NamedTextColor.RED));
        } else if (!paged) {
            for (int i = 0; i < targets.size() && i < SINGLE_PAGE_MAX; i++) inventory.setItem(i, head(targets.get(i)));
        } else {
            for (int s = 1; s <= PAGED_SIZE; s++) {
                Player t = getTargetAt(s);
                if (t != null) inventory.setItem(s, head(t));
            }
            inventory.setItem(PREV_SLOT, page > 0 ? named(Material.ARROW, "◀ Previous Page", NamedTextColor.YELLOW) : glass());
            inventory.setItem(NEXT_SLOT, page < pages - 1 ? named(Material.ARROW, "Next Page ▶", NamedTextColor.YELLOW) : glass());
        }

        // زر الخروج (باريير): ما يظهر للميت في FFA/Team Party — فقط للمتفرج الخارجي وميت الـ 1v1
        if (!plugin.getPartyDuelManager().isInPartyDuel(spectator)) {
            ItemStack barrier = named(Material.BARRIER, "✖ Exit Spectator", NamedTextColor.RED);
            ItemMeta bm = barrier.getItemMeta();
            bm.lore(List.of(Component.text("Click to stop spectating", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            barrier.setItemMeta(bm);
            inventory.setItem(EXIT_SLOT, barrier);
        }

        spectator.openInventory(inventory);
    }

    private ItemStack head(Player t) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        meta.setOwningPlayer(t);
        meta.displayName(Component.text(t.getName(), NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));
        List<Component> lore = new ArrayList<>();
        double hearts = Math.max(0.0, t.getHealth() / 2.0);
        lore.add(Component.text("Name: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(t.getName(), NamedTextColor.WHITE)));
        lore.add(Component.text("Hearts: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text("❤ " + String.format("%.1f", hearts), NamedTextColor.RED)));
        lore.add(Component.empty());
        lore.add(Component.text("Click to teleport", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        skull.setItemMeta(meta);
        return skull;
    }

    private ItemStack named(Material m, String name, NamedTextColor color) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack glass() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
