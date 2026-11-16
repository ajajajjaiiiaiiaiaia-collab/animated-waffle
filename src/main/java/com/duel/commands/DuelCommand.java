package com.duel.commands;

import com.duel.DuelPlugin;
import com.duel.gui.DuelArenaPickerGUI;
import com.duel.managers.SettingsManager;
import com.duel.utils.SoundUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class DuelCommand implements CommandExecutor, TabCompleter {

    private final DuelPlugin plugin;

    public DuelCommand(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(color("&cUsage: /duel <player>"));
            return true;
        }

        if (plugin.getDuelManager().isInDuel(player)) {
            player.sendMessage(color("&c⚔ You are already in a duel!"));
            return true;
        }

        if (plugin.getQueueManager().isQueued(player)) {
            player.sendMessage(color("&c⚔ You are already in queue. Leave the queue first."));
            return true;
        }

        if (plugin.getPartyManager().isInParty(player)) {
            player.sendMessage("§c⚔ You can't duel while in a party. Leave your party first.");
            return true;
        }

        if (plugin.getDuelRequestManager().hasPendingRequestAsSender(player)) {
            player.sendMessage(color("&c⚔ You already have a pending duel request. Wait for it to expire."));
            return true;
        }

        String targetName = args[0];
        Player target = Bukkit.getPlayerExact(targetName);

        if (target == null) {
            player.sendMessage(color("&c⚔ Player &e" + targetName + " &cis offline."));
            return true;
        }

        if (target.equals(player)) {
            player.sendMessage(color("&c⚔ You cannot challenge yourself!"));
            return true;
        }

        if (plugin.getDuelManager().isInDuel(target)) {
            player.sendMessage(color("&c⚔ &e" + target.getName() + " &cis currently in a duel."));
            return true;
        }

        if (plugin.getPartyManager().isInParty(target)) {
            player.sendMessage("§c⚔ §e" + target.getName() + " §cis in a party and can't duel.");
            return true;
        }

        if (plugin.getQueueManager().isQueued(target)) {
            player.sendMessage(color("&c⚔ &e" + target.getName() + " &cis currently in queue."));
            return true;
        }

        if (plugin.getDuelRequestManager().hasPendingRequestAsSender(target)) {
            player.sendMessage(color("&c⚔ &e" + target.getName() + " &cis busy with another duel request."));
            return true;
        }

        if (plugin.getDuelRequestManager().hasPendingRequestAsTarget(target)) {
            player.sendMessage(color("&c⚔ &e" + target.getName() + " &calready has a pending duel request."));
            return true;
        }

        if (plugin.getSettingsManager().isEnabled(target.getUniqueId(), SettingsManager.Setting.BLOCK_DUEL_REQUESTS)) {
            player.sendMessage(color("&c⚔ &e" + target.getName() + " &chas disabled duel requests."));
            return true;
        }

        if (plugin.getArenaManager().getAllArenas().isEmpty()) {
            player.sendMessage(color("&c⚔ No arenas configured. Contact an admin."));
            return true;
        }

        SoundUtils.playGuiClick(player);
        DuelArenaPickerGUI.open(plugin, player, target);
        player.sendMessage(color("&a⚔ Choose the arena to challenge &e" + target.getName() + "&a in."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                    .filter(name -> !name.equals(sender.getName()))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
