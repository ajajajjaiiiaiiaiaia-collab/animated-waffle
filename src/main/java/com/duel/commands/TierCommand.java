package com.duel.commands;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import com.duel.tier.Tier;
import com.duel.tier.TierGUI;
import com.duel.tier.TierManager;
import com.duel.tier.TierProfile;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /tier                      → قائمة تيراتك
 * /tier info [arena]         → تفاصيل أرينا
 * /tier top [arena]          → أفضل 10 تقييم
 * /tier hall [arena]         → Hall of Legends (الموسم السابق)
 * /tier list                 → التيرات وعتباتها
 * /tier admin season end | set <player> <arena> <tier> | rating <player> <arena> <n> | reset <player> [arena]
 */
public class TierCommand implements CommandExecutor, TabCompleter {

    private final DuelPlugin plugin;

    public TierCommand(DuelPlugin plugin) { this.plugin = plugin; }

    private static String c(String s) { return s.replace('&', '§'); }

    private String defaultArena() {
        for (Arena a : plugin.getArenaManager().getAllArenas()) return a.getId();
        return null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        TierManager tm = plugin.getTierManager();
        if (!tm.isEnabled()) { sender.sendMessage(c("&cTier system is disabled.")); return true; }

        if (args.length == 0) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
            TierGUI.open(plugin, p);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "info" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                String arena = args.length > 1 ? args[1] : defaultArena();
                if (arena == null || plugin.getArenaManager().getArena(arena) == null) {
                    p.sendMessage(c("&cUnknown arena.")); return true;
                }
                TierProfile pr = tm.getFresh(p.getUniqueId(), p.getName(), arena);
                Tier next = tm.nextTarget(pr);
                p.sendMessage(c("&8&m          &r &b" + arena + " &8&m          "));
                p.sendMessage(c("&7Tier&8: " + tm.label(pr) + " &8| &7Peak&8: " + (pr.placed ? pr.peakTier.colored() : tm.unrankedLabel())));
                p.sendMessage(c("&7Rating&8: &f" + pr.rating + " &8| &7Season peak&8: &f" + pr.seasonPeakRating));
                p.sendMessage(c("&7Record&8: &a" + pr.wins + "W &c" + pr.losses + "L &8(&7" + pr.games + " this season&8)"));
                if (pr.defensePending) p.sendMessage(c("&c&l⚠ Tier Defense required &7— next ranked match."));
                else if (pr.challengePending && next != null) p.sendMessage(c("&6&l★ Rankup Challenge ready &7→ " + next.colored()));
                else if (next != null) p.sendMessage(c("&7Next&8: " + next.colored() + " &7at &f" + tm.threshold(next)
                        + " &8(&e" + Math.max(0, tm.threshold(next) - pr.rating) + " &7more&8)"));
                return true;
            }
            case "top" -> {
                if (sender instanceof Player tp) {
                    String want = args.length > 1 ? args[1] : null;
                    if (want != null && plugin.getArenaManager().getArena(want) == null) {
                        tp.sendMessage(c("&cUnknown arena.")); return true;
                    }
                    com.duel.tier.TierTopDialog.open(plugin, tp, want);
                    return true;
                }
                String arena = args.length > 1 ? args[1] : defaultArena();
                if (arena == null || plugin.getArenaManager().getArena(arena) == null) {
                    sender.sendMessage(c("&cUnknown arena.")); return true;
                }
                List<TierManager.TopEntry> top = tm.getTop(arena);
                sender.sendMessage(c("&8&m          &r &b" + arena + " &7Top Rating &8&m          "));
                if (top.isEmpty()) sender.sendMessage(c("&7No ranked matches yet."));
                int i = 1;
                for (TierManager.TopEntry e : top) {
                    sender.sendMessage(c("&e#" + i++ + " &f" + e.name() + " &8— &f" + e.rating() + " &8| " + e.tier().colored()));
                }
                return true;
            }
            case "hall" -> {
                String arena = args.length > 1 ? args[1] : defaultArena();
                if (arena == null || plugin.getArenaManager().getArena(arena) == null) {
                    sender.sendMessage(c("&cUnknown arena.")); return true;
                }
                final String a = arena;
                tm.hallOfLegends(arena, lines -> {
                    sender.sendMessage(c("&8&m          &r &6Hall of Legends &8» &b" + a + " &8&m          "));
                    if (lines.isEmpty()) sender.sendMessage(c("&7No finished seasons yet."));
                    for (String l : lines) sender.sendMessage(c(l));
                });
                return true;
            }
            case "list" -> {
                sender.sendMessage(c("&8&m          &r &bTiers &8&m          "));
                Tier[] all = Tier.values();
                for (int i = all.length - 1; i >= 0; i--) {
                    sender.sendMessage(c(all[i].colored() + " &8— &7rating &f" + tm.threshold(all[i]) + "+"));
                }
                return true;
            }
            case "admin" -> { return admin(sender, tm, args); }
            default -> {
                sender.sendMessage(c("&7/tier &8| &7info [arena] &8| &7top [arena] &8| &7hall [arena] &8| &7list"));
                return true;
            }
        }
    }

    private boolean admin(CommandSender sender, TierManager tm, String[] args) {
        if (!sender.hasPermission("duelplugin.admin")) { sender.sendMessage(c("&cNo permission.")); return true; }
        if (args.length < 2) {
            sender.sendMessage(c("&7/tier admin season end | set <player> <arena> <tier> | rating <player> <arena> <n> | reset <player> [arena]"));
            return true;
        }
        switch (args[1].toLowerCase()) {
            case "season" -> {
                if (args.length >= 3 && args[2].equalsIgnoreCase("end")) {
                    sender.sendMessage(c("&eEnding season " + tm.getSeason() + "..."));
                    tm.endSeason(msg -> sender.sendMessage(c("&a" + msg)));
                } else sender.sendMessage(c("&7/tier admin season end &8(current: &f" + tm.getSeason() + "&8)"));
            }
            case "set" -> {
                if (args.length < 5) { sender.sendMessage(c("&c/tier admin set <player> <arena> <tier>")); return true; }
                Player t = Bukkit.getPlayerExact(args[2]);
                Tier tier = Tier.parse(args[4], null);
                if (t == null || tier == null || plugin.getArenaManager().getArena(args[3]) == null) {
                    sender.sendMessage(c("&cInvalid player / arena / tier.")); return true;
                }
                tm.adminSetTier(t, args[3], tier);
                sender.sendMessage(c("&aSet " + t.getName() + " to " + tier.colored() + " &ain " + args[3]));
            }
            case "rating" -> {
                if (args.length < 5) { sender.sendMessage(c("&c/tier admin rating <player> <arena> <n>")); return true; }
                Player t = Bukkit.getPlayerExact(args[2]);
                int n;
                try { n = Integer.parseInt(args[4]); } catch (NumberFormatException e) { sender.sendMessage(c("&cInvalid number.")); return true; }
                if (t == null || plugin.getArenaManager().getArena(args[3]) == null) {
                    sender.sendMessage(c("&cInvalid player / arena.")); return true;
                }
                tm.adminSetRating(t, args[3], n);
                sender.sendMessage(c("&aRating of " + t.getName() + " in " + args[3] + " set to " + n));
            }
            case "reset" -> {
                if (args.length < 3) { sender.sendMessage(c("&c/tier admin reset <player> [arena]")); return true; }
                Player t = Bukkit.getPlayerExact(args[2]);
                if (t == null) { sender.sendMessage(c("&cPlayer not online.")); return true; }
                tm.adminReset(t, args.length > 3 ? args[3] : null);
                sender.sendMessage(c("&aReset " + t.getName() + (args.length > 3 ? " in " + args[3] : " (all arenas)")));
            }
            default -> sender.sendMessage(c("&cUnknown admin action."));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("info", "top", "hall", "list")) out.add(s);
            if (sender.hasPermission("duelplugin.admin")) out.add("admin");
        } else if (args.length == 2 && List.of("info", "top", "hall").contains(args[0].toLowerCase())) {
            for (Arena a : plugin.getArenaManager().getAllArenas()) out.add(a.getId());
        } else if (args[0].equalsIgnoreCase("admin") && sender.hasPermission("duelplugin.admin")) {
            if (args.length == 2) out.addAll(List.of("season", "set", "rating", "reset"));
            else if (args.length == 3 && args[1].equalsIgnoreCase("season")) out.add("end");
            else if (args.length == 3) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            else if (args.length == 4) for (Arena a : plugin.getArenaManager().getAllArenas()) out.add(a.getId());
            else if (args.length == 5 && args[1].equalsIgnoreCase("set")) for (Tier t : Tier.values()) out.add(t.name());
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase();
        out.removeIf(s -> !s.toLowerCase().startsWith(last));
        return out;
    }
}
