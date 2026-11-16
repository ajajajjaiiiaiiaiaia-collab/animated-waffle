package com.duel.party;

import com.duel.DuelPlugin;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PartyListener implements Listener {

    private final DuelPlugin plugin;

    public PartyListener(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    /** Messages starting with @ or ! go to party chat */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        String msg = event.getMessage();
        if (!msg.startsWith("@") && !msg.startsWith("!")) return;

        Party party = plugin.getPartyManager().getParty(event.getPlayer());
        if (party == null) return;

        event.setCancelled(true);
        party.sendChat(event.getPlayer(), ChatColor.stripColor(msg.substring(1)));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        plugin.getPartyManager().handleQuit(event.getPlayer());
    }
}
