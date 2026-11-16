package com.duel.gui;

import com.duel.DuelPlugin;
import com.duel.managers.DuelRequestManager.DuelRequest;
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

import java.util.ArrayList;
import java.util.List;

public class DuelInviteGUI implements InventoryHolder {

    public static final int SLOT_ACCEPT  = 11;
    public static final int SLOT_DECLINE = 15;
    public static final int SLOT_INFO    = 13;

    private final DuelPlugin   plugin;
    private final Inventory    inventory;
    private final DuelRequest  request;

    public DuelInviteGUI(DuelPlugin plugin, DuelRequest request) {
        this.plugin  = plugin;
        this.request = request;

        Player sender = request.getSender();
        this.inventory = Bukkit.createInventory(this, 27,
                Component.text("⚔  Duel Request — ")
                        .color(NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, true)
                        .append(Component.text(sender.getName())
                                .color(NamedTextColor.YELLOW)
                                .decoration(TextDecoration.BOLD, false)));
        populate();
    }

    private void populate() {
        ItemStack border = ItemUtils.buildFiller(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 27; i++) inventory.setItem(i, border);
        inventory.setItem(SLOT_INFO,    buildInfoItem());
        inventory.setItem(SLOT_ACCEPT,  buildAcceptItem());
        inventory.setItem(SLOT_DECLINE, buildDeclineItem());
    }

    private ItemStack buildInfoItem() {
        Player sender = request.getSender();
        int ping = sender.getPing();

        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("📋  Duel Request Info")
                .color(NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.text("Challenger: ").color(NamedTextColor.GRAY)
                .append(Component.text(sender.getName()).color(NamedTextColor.WHITE)));
        lore.add(Component.text("Ping: ").color(NamedTextColor.GRAY)
                .append(Component.text(ping + " ms").color(
                        ping < 80 ? NamedTextColor.GREEN : ping < 150 ? NamedTextColor.YELLOW : NamedTextColor.RED)));
        lore.add(Component.text("Arena: ").color(NamedTextColor.GRAY)
                .append(Component.text(com.duel.utils.RandomArena.is(request.getMapWorld()) ? com.duel.utils.RandomArena.NAME : request.getArena().getId()).color(NamedTextColor.AQUA)));
        lore.add(Component.empty());
        lore.add(Component.text("Request expires in 30 seconds.").color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, true));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildAcceptItem() {
        ItemStack item = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("✔  Accept")
                .color(NamedTextColor.GREEN)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.text("Click to accept and enter the arena.").color(NamedTextColor.GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildDeclineItem() {
        ItemStack item = new ItemStack(Material.RED_CONCRETE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("✘  Decline")
                .color(NamedTextColor.RED)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.text("Click to decline the request.").color(NamedTextColor.GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static void open(DuelPlugin plugin, Player target, DuelRequest request) {
        DuelInviteGUI gui = new DuelInviteGUI(plugin, request);
        target.openInventory(gui.getInventory());
    }

    public DuelRequest getRequest() { return request; }

    @Override
    public Inventory getInventory() { return inventory; }
}
