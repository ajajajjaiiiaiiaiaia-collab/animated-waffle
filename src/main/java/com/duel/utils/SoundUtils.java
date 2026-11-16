package com.duel.utils;

import com.duel.DuelPlugin;
import com.duel.managers.SettingsManager;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

public class SoundUtils {

    private static boolean isMuted(Player player) {
        return DuelPlugin.getInstance().getSettingsManager()
                .isEnabled(player.getUniqueId(), SettingsManager.Setting.MUTE_SOUNDS);
    }

    public static void playCountdown(Player player) {
        if (isMuted(player)) return;
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
    }

    public static void playGo(Player player) {
        if (isMuted(player)) return;
        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.5f);
    }

    public static void playWinner(Player player) {
        if (isMuted(player)) return;
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.8f, 1f);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    public static void playLoser(Player player) {
        if (isMuted(player)) return;
        player.playSound(player.getLocation(), Sound.ENTITY_WITHER_DEATH, 0.5f, 0.8f);
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_HURT, 1f, 0.6f);
    }

    public static void playQueueJoin(Player player) {
        if (isMuted(player)) return;
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
    }

    public static void playQueueFound(Player player) {
        if (isMuted(player)) return;
        player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1f, 1f);
    }

    public static void playGuiClick(Player player) {
        if (isMuted(player)) return;
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
    }

    public static void playSave(Player player) {
        if (isMuted(player)) return;
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.2f);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
    }

    public static void playSpeedChange(Player player, float pitch) {
        if (isMuted(player)) return;
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, Math.max(0.5f, Math.min(2f, pitch)));
    }
}
