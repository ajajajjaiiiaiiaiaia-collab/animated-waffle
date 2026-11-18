package com.duel.commands;

import com.duel.DuelPlugin;
import com.duel.tier.LeaderboardDialog;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /leaderboard — لوحة الصدارة العامة (مجموع نقاط التيرات). */
public class LeaderboardCommand implements CommandExecutor {

    private final DuelPlugin plugin;

    public LeaderboardCommand(DuelPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        LeaderboardDialog.open(plugin, p);
        return true;
    }
}
