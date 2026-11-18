package com.stark.christmastree;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class OakTreeCommand implements CommandExecutor {

    private final ChristmasTreePlugin plugin;

    public OakTreeCommand(ChristmasTreePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cهذا الأمر يعمل فقط داخل اللعبة.");
            return true;
        }

        if (!player.hasPermission("christmastree.build")) {
            player.sendMessage("§cما عندك صلاحية تنفيذ هذا الأمر.");
            return true;
        }

        int height = 30;
        int width = 10;

        if (args.length >= 1) {
            try {
                height = Math.max(10, Math.min(Integer.parseInt(args[0]), 60));
            } catch (NumberFormatException e) {
                player.sendMessage("§cالارتفاع غير صحيح. مثال: /oaktree 30 10");
                return true;
            }
        }
        if (args.length >= 2) {
            try {
                width = Math.max(4, Math.min(Integer.parseInt(args[1]), 25));
            } catch (NumberFormatException e) {
                player.sendMessage("§cالعرض غير صحيح. مثال: /oaktree 30 10");
                return true;
            }
        }

        Location base = player.getLocation().getBlock().getLocation();
        player.sendMessage("§a§lجاري بناء شجرة البلوط... §7(ارتفاع: " + height + " | عرض: " + width + ")");

        OakTreeBuilder builder = new OakTreeBuilder(plugin, base, height, width);
        builder.buildAsync(player);

        return true;
    }
}
