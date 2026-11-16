package com.duel.managers;

import com.duel.DuelPlugin;
import com.duel.models.PartyDuelSession;
import com.duel.party.Party;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.*;

/**
 * سكوربورد البارتي (FFA + Teams) — يظهر لكل لاعب داخل الأرينا.
 *
 * - لكل لاعب سكوربورد خاص فيه (عشان البنق يكون بنقه هو).
 * - الأسطر تتحدّث بدون وميض (Team prefix بدل resetScores).
 * - ألوان التيمات (أزرق/أحمر) في TAB تنحفظ لما يكون الماتش Teams.
 * - كل الأسطر قابلة للتعديل من config.yml → party-scoreboard
 *
 * Placeholders: {alive} {total} {leader} {arena} {ping} {time}
 * Teams only: {blue_alive} {blue_total} {red_alive} {red_total}
 */
public class PartyScoreboardManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private static final String DEFAULT_TITLE  = "&b&l⚔ &f&lPARTY &b&l⚔";
    private static final List<String> DEFAULT_LINES = List.of(
            "&8&m                    ",
            " ",
            "&b&l» &7Party Online&8: &a{alive}&8/&f{total}",
            "&b&l» &7Leader&8: &e{leader}",
            "&b&l» &7Arena&8: &f{arena}",
            "  ",
            "&b&l» &7Ping&8: {ping}&7ms",
            "&b&l» &7Time&8: &f{time}",
            "   ",
            "&8&m                    ",
            "&b&ostark-mc.net"
    );

    private static final String DEFAULT_TEAM_TITLE = "&9&l⚔ &f&lPARTY TEAM &c&l⚔";
    private static final List<String> DEFAULT_TEAM_LINES = List.of(
            "&8&m                    ",
            " ",
            "&9&l» &9Blue Team&8: &a{blue_alive}&8/&f{blue_total}",
            "&c&l» &cRed Team&8: &a{red_alive}&8/&f{red_total}",
            "  ",
            "&8&m                    ",
            "&b&l» &7Ping&8: {ping}&7ms",
            "&b&l» &7Time&8: &f{time}",
            "   ",
            "&8&m                    ",
            "&b&ostark-mc.net"
    );

    private final DuelPlugin plugin;

    /** sessionId → حالة السكوربورد */
    private final Map<UUID, Active> active = new HashMap<>();

    public PartyScoreboardManager(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    private static class Active {
        final PartyDuelSession session;
        final Map<UUID, Scoreboard> boards = new HashMap<>();
        final String leaderFallback;
        BukkitTask task;
        Active(PartyDuelSession session, String leaderFallback) {
            this.session = session;
            this.leaderFallback = leaderFallback;
        }
    }

    // ── Config ──────────────────────────────────────────────────────────
    private boolean enabled() {
        return plugin.getConfig().getBoolean("party-scoreboard.enabled", true);
    }

    private String title(PartyDuelSession s) {
        if (!s.isFFA()) return plugin.getConfig().getString("party-team-scoreboard.title", DEFAULT_TEAM_TITLE);
        return plugin.getConfig().getString("party-scoreboard.title", DEFAULT_TITLE);
    }

    private List<String> lines(PartyDuelSession s) {
        List<String> l;
        if (!s.isFFA()) {
            l = plugin.getConfig().getStringList("party-team-scoreboard.lines");
            if (l.isEmpty()) l = DEFAULT_TEAM_LINES;
        } else {
            l = plugin.getConfig().getStringList("party-scoreboard.lines");
            if (l.isEmpty()) l = DEFAULT_LINES;
        }
        // الحد الأقصى 15 سطر في الـ sidebar
        return l.size() > 15 ? l.subList(0, 15) : l;
    }

    private static Component c(String s) {
        return LEGACY.deserialize(ChatColor.translateAlternateColorCodes('&', s));
    }

    // ── Start / Stop ────────────────────────────────────────────────────
    public void start(PartyDuelSession session, List<Player> players) {
        if (!enabled()) return;
        stop(session); // أمان: لو كان فيه واحد قديم لنفس الجلسة

        String leaderFallback = players.isEmpty() ? "-" : players.get(0).getName();
        Party party = players.isEmpty() ? null : Party.getByPlayer(players.get(0));
        if (party != null && party.getLeader() != null) leaderFallback = party.getLeader().getName();

        Active a = new Active(session, leaderFallback);
        active.put(session.getSessionId(), a);

        for (Player p : players) {
            if (!p.isOnline()) continue;
            Scoreboard board = buildBoard(session);
            a.boards.put(p.getUniqueId(), board);
            p.setScoreboard(board);
        }
        update(a);

        a.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (session.getState() == PartyDuelSession.State.ENDED) { stop(session); return; }
            update(a);
        }, 20L, 20L);
    }

    /** يوقف السكوربورد ويرجّع كل اللاعبين للـ main scoreboard */
    public void stop(PartyDuelSession session) {
        Active a = active.remove(session.getSessionId());
        if (a == null) return;
        if (a.task != null) a.task.cancel();
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (UUID u : a.boards.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline() && p.getScoreboard() == a.boards.get(u)) {
                p.setScoreboard(main);
            }
        }
    }

    public void stopAll() {
        for (Active a : new ArrayList<>(active.values())) stop(a.session);
        active.clear();
    }

    // ── Build ───────────────────────────────────────────────────────────
    private Scoreboard buildBoard(PartyDuelSession session) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();

        // ألوان الفريقين في TAB (للماتش Teams فقط)
        if (!session.isFFA()) {
            Team blue = board.registerNewTeam("p_blue");
            blue.setColor(ChatColor.BLUE);
            blue.prefix(c("&9"));
            for (Player p : session.getTeam1()) blue.addEntry(p.getName());

            Team red = board.registerNewTeam("p_red");
            red.setColor(ChatColor.RED);
            red.prefix(c("&c"));
            for (Player p : session.getTeam2()) red.addEntry(p.getName());
        }

        Objective obj = board.registerNewObjective("party_sb", "dummy", c(title(session)));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        try {
            // يخفي أرقام السكور الحمراء على اليمين (1.20.3+)
            obj.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank());
        } catch (Throwable ignored) {}

        // كل سطر = Team بـ entry فريد (مخفي) + prefix هو النص
        List<String> lines = lines(session);
        for (int i = 0; i < lines.size(); i++) {
            Team t = board.registerNewTeam("line_" + i);
            String entry = entryFor(i);
            t.addEntry(entry);
            obj.getScore(entry).setScore(lines.size() - i);
        }
        return board;
    }

    /** entry فريد غير مرئي لكل سطر */
    private static String entryFor(int i) {
        return "§" + Integer.toHexString(i) + "§r";
    }

    // ── Update ──────────────────────────────────────────────────────────
    private void update(Active a) {
        PartyDuelSession s = a.session;
        List<Player> all = s.getAllPlayers();

        long alive = all.stream().filter(p -> p.isOnline() && s.isAlive(p)).count();
        int total  = all.size();
        String arena  = prettyArena(s.getArena() != null ? s.getArena().getId() : "-");
        String time   = formatTime(s);
        String leader = leaderName(a, all);

        List<String> lines = lines(s);

        long blueAlive = s.getTeam1().stream().filter(s::isAlive).count();
        long redAlive  = s.getTeam2().stream().filter(s::isAlive).count();
        int blueTotal  = s.getTeam1().size();
        int redTotal   = s.getTeam2().size();

        for (Map.Entry<UUID, Scoreboard> e : a.boards.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline()) continue;
            // اللاعب طلع من الماتش (spectate/leave) → لا نلمس سكوربورده
            if (p.getScoreboard() != e.getValue()) continue;

            Scoreboard board = e.getValue();
            int ping = p.getPing();

            for (int i = 0; i < lines.size(); i++) {
                Team t = board.getTeam("line_" + i);
                if (t == null) continue;
                String text = lines.get(i)
                        .replace("{alive}",  String.valueOf(alive))
                        .replace("{total}",  String.valueOf(total))
                        .replace("{blue_alive}", String.valueOf(blueAlive))
                        .replace("{blue_total}", String.valueOf(blueTotal))
                        .replace("{red_alive}",  String.valueOf(redAlive))
                        .replace("{red_total}",  String.valueOf(redTotal))
                        .replace("{leader}", leader)
                        .replace("{arena}",  arena)
                        .replace("{ping}",   pingColor(ping) + ping)
                        .replace("{time}",   time);
                t.prefix(c(text));
            }
        }
    }

    private String leaderName(Active a, List<Player> all) {
        for (Player p : all) {
            if (!p.isOnline()) continue;
            Party party = Party.getByPlayer(p);
            if (party != null && party.getLeader() != null) return party.getLeader().getName();
            break;
        }
        return a.leaderFallback;
    }

    private static String pingColor(int ping) {
        if (ping < 80)  return "§a";
        if (ping < 150) return "§e";
        return "§c";
    }

    private static String prettyArena(String id) {
        String[] parts = id.replace('-', '_').split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.length() == 0 ? id : sb.toString();
    }

    private static String formatTime(PartyDuelSession s) {
        if (s.getState() == PartyDuelSession.State.COUNTDOWN) return "00:00";
        long sec = Math.max(0, (System.currentTimeMillis() - s.getStartTime()) / 1000);
        long h = sec / 3600, m = (sec % 3600) / 60, ss = sec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, ss) : String.format("%02d:%02d", m, ss);
    }
}
