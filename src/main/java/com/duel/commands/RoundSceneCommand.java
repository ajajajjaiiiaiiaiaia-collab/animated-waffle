package com.duel.commands;

import com.duel.DuelPlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /roundscene — تحكم مباشر بالمشهد السينمائي بين الرواندات (يحفظ في config.yml ويسري من الراوند الجاي).
 *   /roundscene                 → يعرض القيم الحالية
 *   /roundscene <مفتاح> <قيمة>  → يغيّر قيمة
 */
public class RoundSceneCommand implements CommandExecutor, TabCompleter {

    private record Opt(String path, double min, double max, boolean integer, String desc) {}

    private static final Map<String, Opt> OPTS = new LinkedHashMap<>();
    static {
        OPTS.put("break",     new Opt("rounds.break-seconds",              3, 30, true,  "مدة الاستراحة (ثواني)"));
        OPTS.put("max-extra", new Opt("rounds.cinematic.max-extra-seconds", 0, 30, true,  "أقصى تمديد للأرينات الكبيرة (ثواني)"));
        OPTS.put("speed",     new Opt("rounds.pull-max-speed",             0.2, 3, false, "أقصى سرعة (بلوك/tick)"));
        OPTS.put("height",    new Opt("rounds.cinematic.rise-height",      3, 30, false, "ارتفاع القمة (بلوك)"));
        OPTS.put("gap",       new Opt("rounds.cinematic.gap",              1.5, 10, false, "المسافة بينهم فوق (بلوك)"));
    }
    private static final String DYNAMIC_PATH = "rounds.cinematic.dynamic";

    private final DuelPlugin plugin;

    public RoundSceneCommand(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("duelplugin.admin")) {
            sender.sendMessage(c("&cNo permission."));
            return true;
        }
        if (args.length == 0) { show(sender); return true; }
        if (args.length != 2) { usage(sender); return true; }

        String key = args[0].toLowerCase(Locale.ROOT);

        if (key.equals("dynamic")) {
            if (!args[1].equalsIgnoreCase("true") && !args[1].equalsIgnoreCase("false")) {
                sender.sendMessage(c("&cاستخدم true أو false."));
                return true;
            }
            plugin.getConfig().set(DYNAMIC_PATH, Boolean.parseBoolean(args[1]));
            plugin.saveConfig();
            sender.sendMessage(c("&a✔ dynamic = &f" + args[1].toLowerCase(Locale.ROOT) + " &7(يسري من الراوند الجاي)"));
            return true;
        }

        Opt o = OPTS.get(key);
        if (o == null) { usage(sender); return true; }

        double v;
        try {
            v = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(c("&cالقيمة لازم تكون رقم."));
            return true;
        }
        if (v < o.min() || v > o.max()) {
            sender.sendMessage(c("&cالقيمة لازم بين &f" + fmt(o.min()) + " &cو &f" + fmt(o.max()) + "&c."));
            return true;
        }
        if (o.integer()) plugin.getConfig().set(o.path(), (int) Math.round(v));
        else plugin.getConfig().set(o.path(), v);
        plugin.saveConfig();
        sender.sendMessage(c("&a✔ " + key + " = &f" + fmt(o.integer() ? Math.round(v) : v) + " &7(يسري من الراوند الجاي)"));
        return true;
    }

    private void show(CommandSender s) {
        s.sendMessage(c("&6⚔ Round Scene &7— القيم الحالية:"));
        for (Map.Entry<String, Opt> e : OPTS.entrySet()) {
            Opt o = e.getValue();
            Object val = plugin.getConfig().get(o.path());
            s.sendMessage(c("&e" + e.getKey() + " &7= &f" + val + " &8— " + o.desc()));
        }
        s.sendMessage(c("&edynamic &7= &f" + plugin.getConfig().getBoolean(DYNAMIC_PATH, true)
                + " &8— يضبط المشهد حسب حجم الأرينا"));
        s.sendMessage(c("&7تغيير: &f/roundscene <مفتاح> <قيمة>"));
    }

    private void usage(CommandSender s) {
        s.sendMessage(c("&cUsage: /roundscene [break|max-extra|speed|height|gap|dynamic] <value>"));
    }

    private String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("duelplugin.admin")) return out;
        if (args.length == 1) {
            List<String> keys = new ArrayList<>(OPTS.keySet());
            keys.add("dynamic");
            for (String k : keys) if (k.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(k);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("dynamic")) {
            out.add("true");
            out.add("false");
        }
        return out;
    }
}
