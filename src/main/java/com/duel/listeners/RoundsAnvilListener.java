package com.duel.listeners;

import com.duel.DuelPlugin;
import com.duel.gui.DuelConfirmGUI;
import com.duel.gui.RoundsAnvilGUI;
import com.duel.gui.RoundsAnvilGUI.Session;
import com.duel.utils.SoundUtils;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.view.AnvilView;

import java.util.UUID;

/** Handles the rounds Anvil GUI: result item, click on the green glass, cleanup on close. */
public class RoundsAnvilListener implements Listener {

    private final DuelPlugin plugin;

    public RoundsAnvilListener(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    private Session sessionOf(Player p, Inventory top) {
        if (top.getType() != InventoryType.ANVIL) return null;
        return RoundsAnvilGUI.SESSIONS.get(p.getUniqueId());
    }

    /** Always show the green glass as the result so the player has something to click. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepare(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player p)) return;
        if (sessionOf(p, event.getInventory()) == null) return;
        event.setResult(RoundsAnvilGUI.buildResult());
        event.getView().setRepairCost(0);

        // Safety net: if the server wipes the result slot after this event, put the glass back next tick
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            Inventory top = p.getOpenInventory().getTopInventory();
            if (sessionOf(p, top) == null) return;
            top.setItem(RoundsAnvilGUI.SLOT_RESULT, RoundsAnvilGUI.buildResult());
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        Session s = sessionOf(p, event.getView().getTopInventory());
        if (s == null) return;

        // Nothing can be taken / moved in this GUI
        event.setCancelled(true);

        if (event.getRawSlot() != RoundsAnvilGUI.SLOT_RESULT || s.done) return;
        s.done = true;

        String text = (event.getView() instanceof AnvilView av) ? av.getRenameText() : null;
        int rounds = RoundsAnvilGUI.parseRounds(text, s.rounds);
        SoundUtils.playGuiClick(p);

        // Open next tick — never swap inventories inside the click event
        plugin.getServer().getScheduler().runTask(plugin, () -> backToConfirm(p, s, rounds));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (sessionOf(p, event.getView().getTopInventory()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        Session s = sessionOf(p, event.getInventory());
        if (s == null) return;
        RoundsAnvilGUI.SESSIONS.remove(p.getUniqueId());

        // The anvil would hand the paper back to the player on close — wipe it
        Inventory inv = event.getInventory();
        inv.setItem(RoundsAnvilGUI.SLOT_PAPER, null);
        inv.setItem(RoundsAnvilGUI.SLOT_RESULT, null);

        // ESC → back to the Duel GUI with the rounds unchanged
        if (!s.done && event.getReason() == InventoryCloseEvent.Reason.PLAYER && p.isOnline()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> backToConfirm(p, s, s.rounds));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        RoundsAnvilGUI.SESSIONS.remove(id);
    }

    private void backToConfirm(Player p, Session s, int rounds) {
        if (!p.isOnline()) return;
        if (!s.target.isOnline()) {
            p.closeInventory();
            p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&c⚔ &e" + s.target.getName() + " &cis now offline."));
            return;
        }
        DuelConfirmGUI.open(plugin, p, s.target, s.arena, rounds, s.mapWorld);
    }
}
