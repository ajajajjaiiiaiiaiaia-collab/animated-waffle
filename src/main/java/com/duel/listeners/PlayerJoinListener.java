package com.duel.listeners;

import com.duel.DuelPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerJoinListener implements Listener {

    private final DuelPlugin plugin;

    public PlayerJoinListener(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // إخفاء رسالة الدخول من الشات
        if (plugin.getConfig().getBoolean("settings.hide-join-quit-messages", true)) {
            event.joinMessage(null);
        }
        plugin.getSettingsManager().preloadAsync(player.getUniqueId());
        // لو بقي له أثر جلسة بارتي قديمة (خرج وسط ماتش) → ننظّفه ونرجّعه للسبون
        if (plugin.getPartyDuelManager().isInPartyDuel(player)) {
            plugin.getPartyDuelManager().forceRelease(player);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            giveDuelSword(player);
            // Apply saved settings (hide players, scoreboard, etc.)
            plugin.getSettingsManager().applyAll(player);
            // Refresh party hotbar (in case they rejoined mid-party, unlikely but safe)
            plugin.getPartyManager().refreshHotbar(player);
            // أبلغ TAB isolation manager بالجوين الجديد
            // حتى يُخفى من TAB اللاعبين المعزولين
            plugin.getTabIsolationManager().onPlayerJoin(player);
        }, 5L);
    }

    /** رجوع للسبون (أو أي تغيير عالم) → مزامنة الـ TAB بعد التيليبورت. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        plugin.getTabIsolationManager().scheduleResync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.getTabIsolationManager().onPlayerQuit(player);
        // إخفاء رسالة المغادرة من الشات
        if (plugin.getConfig().getBoolean("settings.hide-join-quit-messages", true)) {
            event.quitMessage(null);
        }

        plugin.getDuelRequestManager().cancelAllFor(player);

        // Remove from queue if queued
        if (plugin.getQueueManager().isQueued(player)) {
            plugin.getQueueManager().removeFromQueue(player);
        }

        // 1v1 — الخصم يفوز تلقائياً
        if (plugin.getDuelManager().isInDuel(player)) {
            var session = plugin.getDuelManager().getSession(player);
            if (session != null) {
                Player opponent = session.getOpponent(player);
                if (opponent != null && opponent.isOnline()) {
                    plugin.getDuelManager().forfeitMatch(session, opponent);
                } else {
                    // كلاهما offline — نظف الجلسة بدون endDuel
                    plugin.getDuelManager().forceCleanup(session);
                }
            }
        }

        // Party Duel (Party vs Party أو Party FFA)
        if (plugin.getPartyDuelManager().isInPartyDuel(player)) {
            plugin.getPartyDuelManager().onPlayerDisconnect(player);
        }

        // Party cleanup is handled by PartyListener

        // Free caches
        plugin.getPlayerKitManager().unloadPlayer(player.getUniqueId());
        plugin.getSettingsManager().unload(player.getUniqueId());
    }

    public void giveDuelSword(Player player) {
        // لو في انتظار rematch ورجع للسبون = رفض
        if (plugin.getRematchManager().isOffering(player)) {
            plugin.getRematchManager().decline(player);
        }
        plugin.getHotbarManager().applyHotbar(player);
    }
}
