package com.duel.listeners;

import com.duel.DuelPlugin;
import com.duel.gui.DuelArenaPickerGUI;
import com.duel.managers.SettingsManager;
import com.duel.utils.SoundUtils;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Shift + right-click on a player → فتح DuelArenaPickerGUI مباشرة.
 */
public class ShiftClickDuelListener implements Listener {

    private final DuelPlugin plugin;

    public ShiftClickDuelListener(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onShiftRightClick(PlayerInteractAtEntityEvent event) {
        // Main hand only — لا نكرر على الـ off-hand
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();

        // لازم يكون شيفت مضغوط
        if (!player.isSneaking()) return;

        Entity clicked = event.getRightClicked();
        if (!(clicked instanceof Player target)) return;

        // ─── نفس تشييكات /duel ───────────────────────────────────────

        if (player.equals(target)) return;

        if (plugin.getDuelManager().isInDuel(player)) {
            player.sendMessage("§c⚔ You are already in a duel!");
            event.setCancelled(true);
            return;
        }

        if (plugin.getPartyManager().isInParty(player)) {
            player.sendMessage("§c⚔ You can't duel while in a party. Leave your party first.");
            event.setCancelled(true);
            return;
        }

        if (plugin.getPartyManager().isInParty(target)) {
            player.sendMessage("§c⚔ §e" + target.getName() + " §cis in a party and can't duel.");
            event.setCancelled(true);
            return;
        }

        if (plugin.getQueueManager().isQueued(player)) {
            player.sendMessage("§c⚔ You are already in queue. Leave the queue first.");
            event.setCancelled(true);
            return;
        }

        if (plugin.getDuelRequestManager().hasPendingRequestAsSender(player)) {
            player.sendMessage("§c⚔ You already have a pending duel request.");
            event.setCancelled(true);
            return;
        }

        if (plugin.getDuelManager().isInDuel(target)) {
            player.sendMessage("§c⚔ §e" + target.getName() + " §cis currently in a duel.");
            event.setCancelled(true);
            return;
        }

        if (plugin.getQueueManager().isQueued(target)) {
            player.sendMessage("§c⚔ §e" + target.getName() + " §cis currently in queue.");
            event.setCancelled(true);
            return;
        }

        if (plugin.getDuelRequestManager().hasPendingRequestAsSender(target)) {
            player.sendMessage("§c⚔ §e" + target.getName() + " §cis busy with another duel request.");
            event.setCancelled(true);
            return;
        }

        if (plugin.getDuelRequestManager().hasPendingRequestAsTarget(target)) {
            player.sendMessage("§c⚔ §e" + target.getName() + " §calready has a pending duel request.");
            event.setCancelled(true);
            return;
        }

        if (plugin.getSettingsManager().isEnabled(target.getUniqueId(), SettingsManager.Setting.BLOCK_DUEL_REQUESTS)) {
            player.sendMessage("§c⚔ §e" + target.getName() + " §chas disabled duel requests.");
            event.setCancelled(true);
            return;
        }

        if (plugin.getArenaManager().getAllArenas().isEmpty()) {
            player.sendMessage("§c⚔ No arenas configured. Contact an admin.");
            event.setCancelled(true);
            return;
        }

        // ─── افتح الـ GUI ─────────────────────────────────────────────
        event.setCancelled(true);
        SoundUtils.playGuiClick(player);
        DuelArenaPickerGUI.open(plugin, player, target);
        player.sendMessage("§a⚔ Choose the arena to challenge §e" + target.getName() + " §ain.");
    }
}
