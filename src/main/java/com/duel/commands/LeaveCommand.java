package com.duel.commands;

import com.duel.DuelPlugin;
import com.duel.managers.PartyDuelManager;
import com.duel.models.DuelSession;
import com.duel.models.PartyDuelSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /leave — lets a player back out of whatever duel-related state they're
 * currently stuck in: queued for a match, mid 1v1 duel, or mid party duel.
 * Leaving a live duel counts as a forfeit for the leaving player.
 */
public class LeaveCommand implements CommandExecutor {

    private final DuelPlugin plugin;

    public LeaveCommand(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        // 1) Waiting in queue
        if (plugin.getQueueManager().isQueued(player)) {
            plugin.getQueueManager().removeFromQueue(player);
            player.sendMessage(color("&aYou left the queue."));
            return true;
        }

        // 2) In a live 1v1 duel — forfeit
        if (plugin.getDuelManager().isInDuel(player)) {
            DuelSession session = plugin.getDuelManager().getSession(player);
            if (session == null) {
                player.sendMessage(color("&cSomething went wrong — you don't appear to be in an active duel."));
                return true;
            }
            if (session.getState() == DuelSession.State.ENDED) {
                player.sendMessage(color("&cThis duel has already ended."));
                return true;
            }
            Player opponent = session.getOpponent(player);
            player.sendMessage(color(session.isRoundMatch()
                    ? "&cYou left the match — the whole match is over (forfeit)."
                    : "&cYou left the duel — it counts as a forfeit."));
            if (opponent != null && opponent.isOnline()) {
                opponent.sendMessage(color("&a" + player.getName() + " left the duel — you win"
                        + (session.isRoundMatch() ? " the whole match" : "") + " by forfeit!"));
            }
            plugin.getDuelManager().forfeitMatch(session, opponent);
            return true;
        }

        // 3) In a live party duel / party FFA — forfeit (counted as an elimination)
        PartyDuelManager partyDuelManager = plugin.getPartyDuelManager();
        if (partyDuelManager.isInPartyDuel(player)) {
            PartyDuelSession session = partyDuelManager.getSession(player);
            if (session == null) {
                player.sendMessage(color("&cSomething went wrong — you don't appear to be in an active match."));
                return true;
            }
            // جلسة منتهية/عالقة → حرّره مباشرة وارجعه للسبون
            if (session.getState() == PartyDuelSession.State.ENDED) {
                partyDuelManager.forceRelease(player);
                player.sendMessage(color("&aYou left the match."));
                return true;
            }
            if (partyDuelManager.leaveAsDeadSpectator(player)) {
                player.sendMessage(color("&a👁 You have exited spectator mode."));
                return true;
            }
            if (session.getState() != PartyDuelSession.State.FIGHTING) {
                player.sendMessage(color("&cYou can't leave during the countdown — wait for the match to start."));
                return true;
            }
            player.sendMessage(color("&cYou left the match — you've been eliminated."));
            partyDuelManager.onPlayerDeath(player);
            return true;
        }

        // 4) Not in anything
        player.sendMessage(color("&cYou are not queued or in a duel."));
        return true;
    }

    private Component colorComponent(String s) {
        return Component.text(ChatColor.translateAlternateColorCodes('&', s));
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
