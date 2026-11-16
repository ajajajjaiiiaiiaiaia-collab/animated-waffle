package com.duel.commands;

import com.duel.DuelPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * /duelbarrier <world>  — يبني جدار بارير مرة وحدة في عالم القالب من حدود بوردره ويكبّر البوردر.
 * قف في العالم على ارتفاع الـ spawn (الجدار من Y حقك: -height-below لين +height-above).
 * بعدها كل نسخة من العالم تجي بالجدار جاهز، بدون بوردر ولا بناء وقت الماتش.
 */
public class BarrierCommand implements CommandExecutor, TabCompleter {

    private final DuelPlugin plugin;

    public BarrierCommand(DuelPlugin plugin) { this.plugin = plugin; }

    private static String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Only players."); return true; }
        if (args.length < 1) { p.sendMessage(c("&cUsage: /duelbarrier <template-world>")); return true; }

        World w = Bukkit.getWorld(args[0]);
        if (w == null) { p.sendMessage(c("&cWorld not loaded: &e" + args[0])); return true; }

        int baseY = w.equals(p.getWorld()) ? p.getLocation().getBlockY() : w.getSpawnLocation().getBlockY();
        boolean ok = plugin.getArenaManager().buildTemplateBarrier(w, baseY,
                () -> p.sendMessage(c("&a✔ Barrier wall built in &e" + w.getName()
                        + "&a. Border enlarged, bounds saved. New copies include it.")));
        if (!ok) p.sendMessage(c("&cThat world has no real border to convert (or it's already enlarged)."));
        else p.sendMessage(c("&eBuilding barrier wall in &f" + w.getName() + "&e (Y around " + baseY + ")..."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length != 1) return List.of();
        return Bukkit.getWorlds().stream().map(World::getName)
                .filter(n -> n.toLowerCase().startsWith(a[0].toLowerCase())).collect(Collectors.toList());
    }
}
