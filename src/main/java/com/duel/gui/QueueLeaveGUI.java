package com.duel.gui;

import com.duel.DuelPlugin;
import com.duel.utils.ItemUtils;
import com.duel.utils.SoundUtils;
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

import java.util.List;

/**
 * Simple 9-slot GUI shown when a queued player right-clicks the duel sword.
 * Contains one item: "Leave Queue" in the center slot (slot 4).
 */
public class QueueLeaveGUI implements InventoryHolder {

    private final Inventory inventory;

    public QueueLeaveGUI(String arenaId) {
        this.inventory = Bukkit.createInventory(this, 9,
                Component.text("⚔ Waiting — " + arenaId)
                        .color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.BOLD, false));
        populate();
    }

    private void populate() {
        // Filler
        ItemStack filler = ItemUtils.buildFiller(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 9; i++) inventory.setItem(i, filler);

        // Leave button — center slot
        ItemStack leave = new ItemStack(Material.RED_BED);
        ItemMeta meta = leave.getItemMeta();
        meta.displayName(Component.text("✖ Leave Queue")
                .color(NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, false));
        meta.lore(List.of(
                Component.text("Click to exit the queue")
                        .color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        leave.setItemMeta(meta);
        inventory.setItem(4, leave);
    }

    public static void open(DuelPlugin plugin, Player player) {
        String arenaId = plugin.getQueueManager().getQueuedArena(player);
        if (arenaId == null) return;
        SoundUtils.playGuiClick(player);
        player.openInventory(new QueueLeaveGUI(arenaId).getInventory());
    }

    /** Returns true if the given slot is the Leave button. */
    public static boolean isLeaveSlot(int slot) {
        return slot == 4;
    }

    @Override
    public Inventory getInventory() { return inventory; }
}
