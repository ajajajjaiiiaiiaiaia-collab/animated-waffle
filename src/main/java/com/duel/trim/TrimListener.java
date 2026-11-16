package com.duel.trim;

import com.duel.DuelPlugin;
import com.duel.utils.SoundUtils;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * Handles clicks in:
 *  - TrimGUI            → pattern click → open MaterialSelectorGUI
 *                       → reset click  → remove trim
 *                       → back click   → close
 *  - MaterialSelectorGUI → handled by the GUI itself
 *
 * Armor slot clicks in PlayerKitEditorGUI are handled in InventoryListener.
 */
public class TrimListener implements Listener {

    private final DuelPlugin plugin;

    public TrimListener(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    // ── TrimGUI clicks ────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onTrimGUIClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof TrimGUI trimGUI)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        UUID uuid = player.getUniqueId();

        // RESET (slot 45) — remove trim for this arena + slot
        if (slot == 45) {
            plugin.getTrimManager().removeTrim(uuid, trimGUI.getArenaId(), trimGUI.getArmorSlot());
            SoundUtils.playGuiClick(player);
            player.sendMessage("§c✖ Trim removed from §e"
                + TrimGUI.titleCase(trimGUI.getArmorSlot().name()) + "§c.");

            // Reopen kit editor so armor shows without the old trim
            String arenaId = com.duel.gui.PlayerKitEditorGUI.OPEN_EDITORS.get(uuid);
            if (arenaId != null) {
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () ->
                    com.duel.gui.PlayerKitEditorGUI.open(plugin, player, arenaId));
            } else {
                player.closeInventory();
            }
            return;
        }

        // BACK (slot 49) — return to kit editor
        if (slot == 49) {
            SoundUtils.playGuiClick(player);
            String arenaId = com.duel.gui.PlayerKitEditorGUI.OPEN_EDITORS.get(uuid);
            if (arenaId != null) {
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () ->
                    com.duel.gui.PlayerKitEditorGUI.open(plugin, player, arenaId));
            } else {
                player.closeInventory();
            }
            return;
        }

        // Pattern button
        String patternKey = trimGUI.getPatternForSlot(slot);
        if (patternKey == null) return;

        if (!TrimManager.canUsePattern(player, patternKey)) {
            player.sendMessage("§c[Trim] You don't have permission to use the §e"
                + TrimGUI.titleCase(patternKey) + " §ctrim.");
            return;
        }

        SoundUtils.playGuiClick(player);
        MaterialSelectorGUI.open(plugin, player, trimGUI.getArmorSlot(), trimGUI.getArenaId(), patternKey);
    }

    // ── MaterialSelectorGUI clicks ────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onMaterialGUIClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MaterialSelectorGUI matGUI)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        matGUI.handleClick(slot);
    }

    // ── Cleanup on close ──────────────────────────────────────────────────

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();

        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof TrimGUI) {
            TrimGUI.OPEN_GUI.remove(uuid);
            TrimGUI.OPEN_GUI_INSTANCE.remove(uuid);
        } else if (holder instanceof MaterialSelectorGUI) {
            MaterialSelectorGUI.OPEN_INSTANCES.remove(uuid);
        }
    }

}
