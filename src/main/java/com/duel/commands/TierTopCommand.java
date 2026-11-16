package com.duel.commands;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import com.duel.tier.TierTopDialog;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class TierTopCommand implements CommandExecutor, TabCompleter {

    private final DuelPlugin plugin;

    public TierTopCommand(DuelPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        String arena = args.length > 0 ? args[0] : null;
        if (arena != null && plugin.getArenaManager().getArena(arena) == null) {
            p.sendMessage("\u00a7cUnknown arena.");
            return true;
        }
        TierTopDialog.open(plugin, p, arena);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (Arena a : plugin.getArenaManager().getAllArenas()) {
                if (a.getId().toLowerCase().startsWith(args[0].toLowerCase())) out.add(a.getId());
            }
        }
        return out;
    }
}
