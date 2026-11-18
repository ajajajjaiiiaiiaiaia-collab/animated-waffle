package com.stark.christmastree;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ChristmasTreeCommand implements CommandExecutor {

    private final ChristmasTreePlugin plugin;

    public ChristmasTreeCommand(ChristmasTreePlugin plugin) {
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

        int height = 40; // الحجم الضخم اللي طلبته
        if (args.length >= 1) {
            try {
                height = Integer.parseInt(args[0]);
                height = Math.max(15, Math.min(height, 80)); // حماية من أرقام غريبة
            } catch (NumberFormatException e) {
                player.sendMessage("§cالرقم غير صحيح. مثال: /christmastree 40");
                return true;
            }
        }

        Location base = player.getLocation().getBlock().getLocation();
        player.sendMessage("§a§lجاري بناء شجرة الكريسمس... §7(ارتفاع: " + height + ")");

        TreeBuilder builder = new TreeBuilder(plugin, base, height);
        builder.buildAsync(player);

        return true;
    }
}
