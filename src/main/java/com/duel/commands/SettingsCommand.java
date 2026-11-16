package com.duel.commands;

import com.duel.DuelPlugin;
import com.duel.gui.SettingsGUI;
import com.duel.utils.SoundUtils;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SettingsCommand implements CommandExecutor {

    private final DuelPlugin plugin;

    public SettingsCommand(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        SoundUtils.playGuiClick(player);
        SettingsGUI.open(plugin, player);
        return true;
    }
}
