package com.duel.listeners;

import com.duel.DuelPlugin;
import com.duel.managers.SettingsManager.Setting;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Hides chat messages for players who have enabled HIDE_CHAT in their settings.
 */
public class ChatListener implements Listener {

    private final DuelPlugin plugin;

    public ChatListener(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncPlayerChatEvent event) {
        // Only remove recipients who have HIDE_CHAT enabled.
        // ConversationManager handles its own cancellation separately.
        Set<Player> toRemove = new HashSet<>();
        for (Player recipient : event.getRecipients()) {
            if (plugin.getSettingsManager().isEnabled(recipient.getUniqueId(), Setting.HIDE_CHAT)) {
                toRemove.add(recipient);
            }
        }
        event.getRecipients().removeAll(toRemove);
    }
}
