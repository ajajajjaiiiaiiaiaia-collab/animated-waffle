package com.duel.managers;

import com.duel.DuelPlugin;
import com.duel.managers.StatsManager.PlayerStats;
import com.duel.managers.StatsManager.TopType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Weekly reset of ALL global tops (every Friday 00:00 Iraq time by default) +
 * temp LuckPerms rank for the top-N players of each top type.
 * Survives restarts: the last reset time is saved, and a missed reset runs on next startup.
 */
public class WeeklyResetManager {

    private final DuelPlugin plugin;
    private final File dataFile;
    private BukkitTask task;
    private boolean running = false;

    public WeeklyResetManager(DuelPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "weekly_reset.yml");
        if (plugin.getConfig().getBoolean("weekly-reset.enabled", true)) {
            // check every 30 seconds
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::check, 100L, 600L);
        }
    }

    public void stop() { if (task != null) task.cancel(); }

    private ZoneId zone() {
        try { return ZoneId.of(plugin.getConfig().getString("weekly-reset.timezone", "Asia/Baghdad")); }
        catch (Exception e) { return ZoneId.of("Asia/Baghdad"); }
    }

    /** Most recent reset moment (≤ now). */
    private ZonedDateTime lastBoundary() {
        DayOfWeek day;
        try { day = DayOfWeek.valueOf(plugin.getConfig().getString("weekly-reset.day", "FRIDAY").toUpperCase()); }
        catch (Exception e) { day = DayOfWeek.FRIDAY; }
        LocalTime time;
        try { time = LocalTime.parse(plugin.getConfig().getString("weekly-reset.time", "00:00")); }
        catch (Exception e) { time = LocalTime.MIDNIGHT; }
        ZonedDateTime now = ZonedDateTime.now(zone());
        ZonedDateTime b = now.with(TemporalAdjusters.previousOrSame(day)).with(time);
        if (b.isAfter(now)) b = b.minusWeeks(1);
        return b;
    }

    private long loadLast() {
        if (!dataFile.exists()) return -1L;
        return YamlConfiguration.loadConfiguration(dataFile).getLong("last-reset", -1L);
    }

    private void saveLast(long millis) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("last-reset", millis);
        try { y.save(dataFile); } catch (IOException e) { plugin.getLogger().warning("Could not save weekly_reset.yml: " + e.getMessage()); }
    }

    private void check() {
        if (running) return;
        long boundary = lastBoundary().toInstant().toEpochMilli();
        long last = loadLast();
        if (last < 0) { saveLast(boundary); return; } // first run: don't wipe immediately
        if (last < boundary) reset(boundary);
    }

    /** Run the reset right now (also used by /weeklyreset confirm). */
    public void reset(long markAs) {
        if (running) return;
        running = true;
        List<TopType> types = new ArrayList<>();
        for (String n : plugin.getConfig().getStringList("weekly-reset.reward.types")) {
            try { types.add(TopType.valueOf(n.toUpperCase())); } catch (Exception ignored) {}
        }
        if (types.isEmpty()) { types.add(TopType.WINS); types.add(TopType.POINTS); types.add(TopType.STREAK); }
        int topCount = Math.max(1, plugin.getConfig().getInt("weekly-reset.reward.top-count", 2));

        plugin.getStatsManager().weeklyReset(types, topCount, winners -> {
            running = false;
            if (winners == null) return; // failed — will retry on next check
            saveLast(markAs);
            giveRewards(winners);
            plugin.getLogger().info("[WeeklyReset] Tops reset. Rewarded " + winners.size() + " player(s).");
        });
    }

    public void resetNow() { reset(System.currentTimeMillis()); }

    private void giveRewards(List<PlayerStats> winners) {
        String rank = plugin.getConfig().getString("weekly-reset.reward.rank", "stark");
        String dur  = plugin.getConfig().getString("weekly-reset.reward.duration", "3d");
        Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Weekly tops have been reset!");
        for (PlayerStats w : winners) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "lp user " + w.uuid() + " parent addtemp " + rank + " " + dur + " replace");
            Bukkit.broadcastMessage(ChatColor.YELLOW + " \u2605 " + w.name() + ChatColor.GRAY
                    + " got " + ChatColor.AQUA + rank.toUpperCase() + ChatColor.GRAY + " for " + dur);
        }
    }
}
