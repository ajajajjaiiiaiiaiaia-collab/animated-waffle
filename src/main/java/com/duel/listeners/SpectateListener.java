package com.duel.listeners;

import com.duel.DuelPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * يحمي وضع المتفرج:
 *  - يمنع أي بلجن (Multiverse وغيره) من تغيير الـ gamemode لمتفرج نشط عند تغيير العالم.
 *  - لو المتفرج طلع من السيرفر يرجّعه لوضعه ومكانه الأصلي.
 */
public class SpectateListener implements Listener {

    private final DuelPlugin plugin;

    public SpectateListener(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (plugin.getSpectateCommand().isSpectating(p)) {
            plugin.getSpectateCommand().stopSpectating(p);
        }
    }
}
