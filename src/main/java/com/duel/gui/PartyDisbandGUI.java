package com.duel.gui;

import com.duel.DuelPlugin;
import com.duel.utils.ItemUtils;
import com.duel.utils.SoundUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * 3-row confirmation GUI for disbanding a party (leader only).
 * All glass, with a single "Disband Party" chest in the center (slot 13).
 */
public class PartyDisbandGUI implements InventoryHolder {

    public static final int SIZE = 27;
    public static final int DISBAND_SLOT = 13;

    private final Inventory inventory;

    public PartyDisbandGUI() {
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("Disband Party")
                        .color(NamedTextColor.DARK_RED)
                        .decoration(TextDecoration.BOLD, false)
                        .decoration(TextDecoration.ITALIC, false));
        populate();
    }

    private static NamespacedKey tagKey() {
        return new NamespacedKey(DuelPlugin.getInstance(), "disband_gui_item");
    }

    private static ItemStack tag(ItemStack item) {
        ItemMeta m = item.getItemMeta();
        m.getPersistentDataContainer().set(tagKey(), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(m);
        return item;
    }

    private static boolean isTagged(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(tagKey(), PersistentDataType.BYTE);
    }

    /** Removes any GUI item that somehow ended up in the player's inventory or cursor. */
    public static void scrub(Player player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (isTagged(inv.getItem(i))) inv.setItem(i, null);
        }
        if (isTagged(inv.getItemInOffHand())) inv.setItemInOffHand(null);
        if (isTagged(player.getItemOnCursor())) player.setItemOnCursor(null);
    }

    private void populate() {
        ItemStack filler = tag(ItemUtils.buildFiller(Material.GRAY_STAINED_GLASS_PANE));
        for (int i = 0; i < SIZE; i++) inventory.setItem(i, filler);

        ItemStack chest = new ItemStack(Material.CHEST);
        ItemMeta meta = chest.getItemMeta();
        meta.displayName(Component.text("Disband Party")
                .color(NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, false));
        meta.lore(List.of(
                Component.text("Click to disband your party")
                        .color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        chest.setItemMeta(meta);
        inventory.setItem(DISBAND_SLOT, tag(chest));
    }

    public static void open(DuelPlugin plugin, Player player) {
        var party = plugin.getPartyManager().getParty(player);
        if (party == null) {
            player.sendMessage("§cYou are not in a party.");
            return;
        }
        if (!party.getLeader().equals(player)) {
            player.sendMessage("§cOnly the leader can disband the party.");
            return;
        }
        SoundUtils.playGuiClick(player);
        player.openInventory(new PartyDisbandGUI().getInventory());
    }

    @Override
    public Inventory getInventory() { return inventory; }
}
