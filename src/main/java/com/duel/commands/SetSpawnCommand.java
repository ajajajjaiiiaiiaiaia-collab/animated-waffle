package com.duel.commands;

import com.duel.DuelPlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /setspawn — يحدد الـ spawn الرئيسي للسيرفر.
 * Permission: duelplugin.admin
 */
public class SetSpawnCommand implements CommandExecutor {

    private final DuelPlugin plugin;

    public SetSpawnCommand(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        if (!player.hasPermission("duelplugin.admin")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        plugin.getSpawnManager().setSpawn(player.getLocation());
        player.sendMessage(ChatColor.GREEN + "✔ Server spawn set to your current location.");
        return true;
    }
}
