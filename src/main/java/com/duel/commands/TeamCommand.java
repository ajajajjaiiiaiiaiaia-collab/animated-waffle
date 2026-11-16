package com.duel.commands;

import com.duel.DuelPlugin;
import com.duel.party.Party;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /team disband — القائد يفك التيمات ويرجع البارتي طبيعي.
 */
public class TeamCommand implements CommandExecutor, TabCompleter {

    private final DuelPlugin plugin;

    public TeamCommand(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (args.length == 0 || !args[0].equalsIgnoreCase("disband")) {
            player.sendMessage("§cUsage: /team disband");
            return true;
        }

        Party party = plugin.getPartyManager().getParty(player);
        if (party == null) { player.sendMessage("§cYou are not in a party."); return true; }
        if (!party.getLeader().equals(player)) { player.sendMessage("§cOnly the party leader can disband the teams."); return true; }
        if (!party.hasTeams()) { player.sendMessage("§c[Party] Your party has no teams. Use §eParty FFA → Team §cto create them."); return true; }
        if (plugin.getPartyDuelManager().isInPartyDuel(player)) {
            player.sendMessage("§c[Party] You can't disband the teams while a match is running.");
            return true;
        }

        party.clearTeams();
        party.broadcastAll("§6[Party] §cThe teams were disbanded. §7Back to a normal party.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1 && "disband".startsWith(args[0].toLowerCase())) out.add("disband");
        return out;
    }
}
