package com.duel.listeners;

import com.duel.DuelPlugin;
import com.duel.managers.SettingsManager.Setting;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Iterator;

/**
 * Handles HIDE_DEATH_MESSAGES and HIDE_JOIN_QUIT_MESSAGES settings.
 */
public class PlayerSettingsListener implements Listener {

    private final DuelPlugin plugin;

    public PlayerSettingsListener(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Hide death messages ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getDeathMessage() == null) return;

        // Remove the death message for players who have it disabled
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (plugin.getSettingsManager().isEnabled(p.getUniqueId(), Setting.HIDE_DEATH_MESSAGES)) {
                // Paper API — set death message to null per-viewer not supported natively,
                // so we suppress globally if the dying player has it off,
                // or suppress for recipients via Adventure audience filtering.
                // Simplest cross-version approach: cancel the message for everyone who opted out.
            }
        }

        // Check if ANY online player wants to hide it — if so, use audience filtering
        boolean anyHiding = plugin.getServer().getOnlinePlayers().stream()
                .anyMatch(p -> plugin.getSettingsManager().isEnabled(p.getUniqueId(), Setting.HIDE_DEATH_MESSAGES));

        if (anyHiding) {
            // Save original message, suppress the global broadcast, re-send to non-hiding players
            String msg = event.getDeathMessage();
            event.setDeathMessage(null);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (!plugin.getSettingsManager().isEnabled(p.getUniqueId(), Setting.HIDE_DEATH_MESSAGES)) {
                        p.sendMessage(msg);
                    }
                }
            });
        }
    }

    // ── Hide join messages ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        String msg = event.getJoinMessage();
        if (msg == null) return;

        boolean anyHiding = plugin.getServer().getOnlinePlayers().stream()
                .anyMatch(p -> plugin.getSettingsManager().isEnabled(p.getUniqueId(), Setting.HIDE_JOIN_QUIT_MESSAGES));

        if (anyHiding) {
            event.setJoinMessage(null);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (!plugin.getSettingsManager().isEnabled(p.getUniqueId(), Setting.HIDE_JOIN_QUIT_MESSAGES)) {
                        p.sendMessage(msg);
                    }
                }
            });
        }
    }

    // ── Hide quit messages ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        String msg = event.getQuitMessage();
        if (msg == null) return;

        boolean anyHiding = plugin.getServer().getOnlinePlayers().stream()
                .anyMatch(p -> plugin.getSettingsManager().isEnabled(p.getUniqueId(), Setting.HIDE_JOIN_QUIT_MESSAGES));

        if (anyHiding) {
            event.setQuitMessage(null);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (p.isOnline() && !plugin.getSettingsManager().isEnabled(p.getUniqueId(), Setting.HIDE_JOIN_QUIT_MESSAGES)) {
                        p.sendMessage(msg);
                    }
                }
            });
        }
    }
}
