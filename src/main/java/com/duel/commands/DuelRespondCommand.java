package com.duel.commands;

import com.duel.DuelPlugin;
import com.duel.managers.DuelRequestManager.DuelRequest;
import com.duel.utils.SoundUtils;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class DuelRespondCommand implements CommandExecutor {

    private final DuelPlugin plugin;
    private final boolean accept;

    public DuelRespondCommand(DuelPlugin plugin, boolean accept) {
        this.plugin = plugin;
        this.accept = accept;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player target)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        DuelRequest request = plugin.getDuelRequestManager().getRequestForTarget(target);

        if (request == null) {
            target.sendMessage(color("&c⚔ You have no pending duel request."));
            return true;
        }

        if (accept) handleAccept(target, request);
        else        handleDecline(target, request);

        return true;
    }

    private void handleAccept(Player target, DuelRequest request) {
        plugin.getDuelRequestManager().removeRequest(target);
        Player sender = request.getSender();

        if (!sender.isOnline()) {
            target.sendMessage(color("&c⚔ &e" + sender.getName() + " &chas disconnected."));
            return;
        }
        if (plugin.getPartyManager().isInParty(sender) || plugin.getPartyManager().isInParty(target)) {
            target.sendMessage(color("&c⚔ One of the players is in a party. Duel cancelled."));
            sender.sendMessage(color("&c⚔ Duel cancelled because one of you is in a party."));
            return;
        }
        if (plugin.getQueueManager().isQueued(sender) || plugin.getQueueManager().isQueued(target)) {
            target.sendMessage(color("&c⚔ One of the players is in queue."));
            return;
        }
        if (plugin.getDuelManager().isInDuel(sender) || plugin.getDuelManager().isInDuel(target)) {
            target.sendMessage(color("&c⚔ One of the players has already entered another duel."));
            if (sender.isOnline()) sender.sendMessage(color("&c⚔ The match was cancelled because one of you is in a duel."));
            return;
        }

        sender.sendMessage(color("&a⚔ &e" + target.getName() + " &aaccepted the challenge! Starting match..."));
        target.sendMessage(color("&a⚔ Challenge accepted! Entering the arena..."));
        SoundUtils.playQueueFound(sender);
        SoundUtils.playQueueFound(target);
        plugin.getDuelManager().startDuelWithIntro(sender, target, request.getArena(), request.getRounds(), request.getMapWorld());
    }

    private void handleDecline(Player target, DuelRequest request) {
        plugin.getDuelRequestManager().removeRequest(target);
        Player sender = request.getSender();
        target.sendMessage(color("&c⚔ You declined the duel request from &e" + sender.getName() + "&c."));
        if (sender.isOnline()) {
            sender.sendMessage(color("&c⚔ &e" + target.getName() + " &cdeclined your duel request."));
            SoundUtils.playLoser(sender);
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
