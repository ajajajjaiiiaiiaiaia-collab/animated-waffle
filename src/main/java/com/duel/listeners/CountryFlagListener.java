package com.duel.listeners;

import com.duel.DuelPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class CountryFlagListener implements Listener {

    private final DuelPlugin plugin;

    public CountryFlagListener(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        plugin.getCountryManager().lookupAsync(e.getPlayer());

    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        plugin.getCountryManager().clear(e.getPlayer().getUniqueId());
    }
}
