package com.duel.managers;

import com.duel.DuelPlugin;
import com.duel.models.DuelSession;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.scoreboard.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public class DuelScoreboardManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final DuelPlugin plugin;

    /** آخر نص لكل سطر لكل بورد — نحدّث بس اللي تغيّر (بدل مسح وإعادة كتابة كل شي كل ثانية = وميض) */
    private final Map<Scoreboard, String[]> lastText = new WeakHashMap<>();

    public DuelScoreboardManager(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    // ── قراءة الكونفق مع دعم & للألوان ─────────────────────────────────
    private String cfg(String path) {
        String raw = plugin.getConfig().getString(path, "");
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    // ── استبدال الـ placeholders في خط ─────────────────────────────────
    private String applyPlaceholders(String template,
                                     String self, int selfPing,
                                     String opponent, int oppPing,
                                     String time) {
        return template
            .replace("{self}",          self)
            .replace("{self_ping}",     String.valueOf(selfPing))
            .replace("{opponent}",      opponent)
            .replace("{opponent_ping}", String.valueOf(oppPing))
            .replace("{time}",          time);
    }

    // ── تنسيق الوقت ─────────────────────────────────────────────────────
    private String formatTime(long startTime) {
        long seconds = (System.currentTimeMillis() - startTime) / 1000;
        long hours   = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs    = seconds % 60;
        if (hours > 0)
            return String.format("%dh %02dm %02ds", hours, minutes, secs);
        return String.format("%dm %02ds", minutes, secs);
    }

    // ── بناء السكوربورد ──────────────────────────────────────────────────
    public void start(DuelSession session) {
        Player p1 = session.getPlayer1();
        Player p2 = session.getPlayer2();

        Scoreboard board1 = buildBoard(p1, p2, session);
        Scoreboard board2 = buildBoard(p2, p1, session);
        p1.setScoreboard(board1);
        p2.setScoreboard(board2);
        session.setTabScoreboard(board1);

        org.bukkit.scheduler.BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (session.getState() == DuelSession.State.ENDED) return;
            if (p1.isOnline()) refresh(board1, p1, p2, session);
            if (p2.isOnline()) refresh(board2, p2, p1, session);
        }, 20L, 20L);

        session.setPingTask(task);
    }

    public void stop(DuelSession session) {
        if (session.getPingTask() != null) {
            session.getPingTask().cancel();
            session.setPingTask(null);
        }
        org.bukkit.scoreboard.Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        Player p1 = session.getPlayer1();
        Player p2 = session.getPlayer2();
        if (p1.isOnline()) p1.setScoreboard(main);
        if (p2.isOnline()) p2.setScoreboard(main);
    }

    private Scoreboard buildBoard(Player self, Player opponent, DuelSession session) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        String title = cfg("scoreboard.title");
        Objective obj = board.registerNewObjective("duel_sb", "dummy", title);
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        initLines(board, obj, self, opponent, session);
        applyTeamColors(board, self, opponent);
        return board;
    }

    // ── ألوان الأسماء (nametag + TAB): أنا أزرق، الخصم أحمر ─────────────
    private ChatColor colorFromConfig(String path, ChatColor def) {
        try {
            return ChatColor.valueOf(plugin.getConfig().getString(path, def.name()).toUpperCase());
        } catch (Exception e) {
            return def;
        }
    }

    private void applyTeamColors(Scoreboard board, Player self, Player opponent) {
        if (!plugin.getConfig().getBoolean("duel-name-colors.enabled", true)) return;
        try {
            ChatColor selfColor = colorFromConfig("duel-name-colors.self", ChatColor.BLUE);
            ChatColor oppColor  = colorFromConfig("duel-name-colors.opponent", ChatColor.RED);

            Team selfTeam = board.registerNewTeam("duel_self");
            selfTeam.setColor(selfColor);
            selfTeam.addEntry(self.getName());

            Team oppTeam = board.registerNewTeam("duel_enemy");
            oppTeam.setColor(oppColor);
            oppTeam.addEntry(opponent.getName());
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[DuelScoreboard] Name colors failed", t);
        }
    }

    private void refresh(Scoreboard board, Player self, Player opponent, DuelSession session) {
        String[] prev = lastText.get(board);
        if (prev == null) return;
        List<String> lines = lines(self, opponent, session);
        for (int i = 0; i < lines.size() && i < prev.length; i++) {
            String text = lines.get(i);
            if (text.equals(prev[i])) continue;   // ما تغيّر → ما نرسل شي
            Team t = board.getTeam("duel_line_" + i);
            if (t == null) continue;
            t.prefix(c(text));
            prev[i] = text;
        }
    }

    /** كل سطر = Team بـ entry فريد مخفي + prefix هو النص → التحديث بدون مسح/إضافة = بدون وميض. */
    private void initLines(Scoreboard board, Objective obj, Player self, Player opponent, DuelSession session) {
        List<String> lines = lines(self, opponent, session);
        String[] prev = new String[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            Team t = board.registerNewTeam("duel_line_" + i);
            String entry = entryFor(i);
            t.addEntry(entry);
            t.prefix(c(lines.get(i)));
            obj.getScore(entry).setScore(lines.size() - 1 - i);
            prev[i] = lines.get(i);
        }
        lastText.put(board, prev);
    }

    /** entry فريد غير مرئي لكل سطر */
    private static String entryFor(int i) {
        return "§" + Integer.toHexString(i) + "§r";
    }

    private static Component c(String text) {
        return LEGACY.deserialize(text);
    }

    /** الأسطر من فوق لتحت — من config: scoreboard.lines (كل سطر يدعم الـ placeholders). */
    private List<String> lines(Player self, Player opponent, DuelSession session) {
        long seconds = (System.currentTimeMillis() - session.getStartTime()) / 1000;
        String time = seconds >= 3600
                ? String.format("%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
                : String.format("%02d:%02d", seconds / 60, seconds % 60);

        String arena = "-";
        try {
            if (session.getArena() != null) {
                String dn = session.getArena().getId();
                arena = dn != null ? dn : session.getArena().getId();
            }
        } catch (Throwable ignored) {}

        List<String> tpl = plugin.getConfig().getStringList("scoreboard.lines");
        if (tpl.isEmpty()) {
            tpl = List.of(
                " &7Arena &b» &f{arena}",
                " &7Round &b» &e{round}&7/&e{rounds}",
                "&8────────────────",
                " &aYou &b» &a{self_score}",
                " &cFoe &b» &c{opp_score}",
                "&8────────────────",
                " &a{self_ping}ms &8| &c{opponent_ping}ms  &f{time}");
        }

        List<String> out = new ArrayList<>();
        for (String line : tpl) {
            out.add(ChatColor.translateAlternateColorCodes('&', line
                .replace("{arena}",         arena)
                .replace("{round}",         String.valueOf(session.getCurrentRound()))
                .replace("{rounds}",        String.valueOf(session.getTotalRounds()))
                .replace("{self_score}",    String.valueOf(session.getRoundScore(self)))
                .replace("{opp_score}",     String.valueOf(session.getRoundScore(opponent)))
                .replace("{self}",          self.getName())
                .replace("{opponent}",      opponent.getName())
                .replace("{self_ping}",     String.valueOf(self.getPing()))
                .replace("{opponent_ping}", String.valueOf(opponent.getPing()))
                .replace("{time}",          time)));
        }
        return out;
    }
}
