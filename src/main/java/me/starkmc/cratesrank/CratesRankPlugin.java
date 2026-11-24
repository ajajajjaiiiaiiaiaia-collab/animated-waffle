package me.starkmc.cratesrank;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class CratesRankPlugin extends JavaPlugin implements Listener {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_.\\-]{1,48}");
    private static final String USAGE = "Usage: /crates givekey rank <key> <rank> <amount>";

    private LuckPerms luckPerms;
    private List<String> labels;
    private String giveCommand;
    private int maxAmount;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        luckPerms = getServer().getServicesManager().load(LuckPerms.class);
        if (luckPerms == null) {
            getLogger().severe("LuckPerms API not found, disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        labels = new ArrayList<>();
        for (String l : getConfig().getStringList("labels")) labels.add(l.toLowerCase(Locale.ROOT));
        giveCommand = getConfig().getString("give-command", "crate giveKey %key% %player% %amount%");
        maxAmount = Math.max(1, getConfig().getInt("max-amount", 10000));
        getServer().getPluginManager().registerEvents(this, this);
    }

    private String[] parse(String line) {
        String l = line.startsWith("/") ? line.substring(1) : line;
        return l.trim().split("\\s+");
    }

    private boolean matches(String[] a) {
        if (a.length < 3) return false;
        String label = a[0].toLowerCase(Locale.ROOT);
        int colon = label.lastIndexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        return labels.contains(label)
                && a[1].equalsIgnoreCase("givekey")
                && a[2].equalsIgnoreCase("rank");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        String[] a = parse(e.getMessage());
        if (!matches(a)) return;
        e.setCancelled(true);
        run(e.getPlayer(), a);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsoleCommand(ServerCommandEvent e) {
        String[] a = parse(e.getCommand());
        if (!matches(a)) return;
        e.setCancelled(true);
        run(e.getSender(), a);
    }

    @EventHandler
    public void onTab(TabCompleteEvent e) {
        String buffer = e.getBuffer();
        String[] a = parse(buffer);
        if (a.length < 2) return;
        String label = a[0].toLowerCase(Locale.ROOT);
        int colon = label.lastIndexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (!labels.contains(label) || !a[1].equalsIgnoreCase("givekey")) return;
        if (!e.getSender().hasPermission("cratesrank.admin")) return;

        boolean trailingSpace = buffer.endsWith(" ");
        int index = trailingSpace ? a.length : a.length - 1;
        String prefix = trailingSpace ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(e.getCompletions());

        if (index == 2) {
            if ("rank".startsWith(prefix) && !out.contains("rank")) out.add("rank");
            e.setCompletions(out);
        } else if (index == 4 && a.length >= 3 && a[2].equalsIgnoreCase("rank")) {
            for (Group g : luckPerms.getGroupManager().getLoadedGroups()) {
                if (g.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(g.getName());
            }
            e.setCompletions(out);
        }
    }

    private void run(CommandSender sender, String[] a) {
        if (!sender.hasPermission("cratesrank.admin")) {
            sender.sendMessage("You do not have permission to use this command.");
            return;
        }
        if (a.length != 6) {
            sender.sendMessage(USAGE);
            return;
        }

        String key = a[3];
        String rank = a[4];
        if (!SAFE.matcher(key).matches() || !SAFE.matcher(rank).matches()) {
            sender.sendMessage("Invalid key or rank name.");
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(a[5]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(USAGE);
            return;
        }
        if (amount < 1 || amount > maxAmount) {
            sender.sendMessage("Amount must be between 1 and " + maxAmount + ".");
            return;
        }

        Group group = luckPerms.getGroupManager().getGroup(rank.toLowerCase(Locale.ROOT));
        if (group == null) {
            sender.sendMessage("Rank '" + rank + "' does not exist.");
            return;
        }

        int given = 0;
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            User user = luckPerms.getUserManager().getUser(p.getUniqueId());
            if (user == null) continue;
            boolean has = user.getNodes(NodeType.INHERITANCE).stream()
                    .anyMatch(n -> n.getGroupName().equalsIgnoreCase(group.getName()) && !n.hasExpired());
            if (!has) continue;
            String cmd = giveCommand
                    .replace("%key%", key)
                    .replace("%player%", p.getName())
                    .replace("%amount%", String.valueOf(amount));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            given++;
        }

        if (given == 0) {
            sender.sendMessage("No online player has the rank '" + group.getName() + "'.");
        } else {
            sender.sendMessage("Gave " + amount + " key(s) '" + key + "' to " + given + " online player(s) with rank '" + group.getName() + "'.");
        }
    }
}
