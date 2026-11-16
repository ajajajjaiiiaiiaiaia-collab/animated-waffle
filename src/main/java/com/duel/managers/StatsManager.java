package com.duel.managers;

import com.duel.DuelPlugin;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class StatsManager {

    public enum TopType { WINS, LOSSES, STREAK, POINTS }

    public record PlayerStats(String name, UUID uuid, int wins, int losses, int streak, int bestStreak, int points) {}

    private final DuelPlugin plugin;

    // ── Top-10 cache ────────────────────────────────────────────────────
    // Reading top10/topGlobal used to run a live SQL query (GROUP BY / ORDER BY)
    // on whichever thread called it — including the MAIN thread every time a
    // %duel_top_*% placeholder was resolved (TAB, scoreboards, etc). With
    // several online players that meant repeated synchronous DB hits per
    // second and visible lag/freezes. Now a background task refreshes this
    // cache periodically and getTop10()/getTopGlobal() just read from memory.
    private final Map<String, List<PlayerStats>> topCache = new ConcurrentHashMap<>();
    private static final long CACHE_REFRESH_TICKS = 100L; // 5 seconds
    private static final long FORCED_REFRESH_MS = 5 * 60 * 1000L;

    private final java.util.concurrent.atomic.AtomicBoolean dirty = new java.util.concurrent.atomic.AtomicBoolean(true);
    private final java.util.concurrent.atomic.AtomicLong lastRefresh = new java.util.concurrent.atomic.AtomicLong(0L);

    // ── Per-player stats cache ───────────────────────────────────────────
    // Keyed by UUID → aggregated (SUM across all arenas) PlayerStats.
    // Refreshed on the same background cycle as the top-10 cache.
    private final Map<UUID, PlayerStats> playerStatsCache = new ConcurrentHashMap<>();

    // ── Rank caches (for %duel_top_<type>_me*%) ──────────────────────────
    // key = cacheKey(arenaOrNull, type) → (uuid → 1-based rank among ALL players)
    private final Map<String, Map<UUID, Integer>> rankCache = new ConcurrentHashMap<>();
    // arena (lowercase) → (uuid → stats in that arena)
    private final Map<String, Map<UUID, PlayerStats>> arenaStatsCache = new ConcurrentHashMap<>();

    public StatsManager(DuelPlugin plugin) {
        this.plugin = plugin;
        startCacheRefresher();
    }

    private void startCacheRefresher() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            boolean forced = System.currentTimeMillis() - lastRefresh.get() >= FORCED_REFRESH_MS;
            if (!dirty.getAndSet(false) && !forced) return;
            lastRefresh.set(System.currentTimeMillis());

            // Snapshot arena IDs on the MAIN thread — ArenaManager's internal map
            // isn't thread-safe, so we must not iterate it from the async task below.
            List<String> arenaIds = new ArrayList<>();
            for (com.duel.models.Arena arena : plugin.getArenaManager().getAllArenas()) {
                arenaIds.add(arena.getId());
            }

            // Now do the actual (heavier) DB work off-thread.
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                for (TopType type : TopType.values()) {
                    topCache.put(cacheKey(null, type), queryTopGlobal(type));
                    for (String arenaId : arenaIds) {
                        topCache.put(cacheKey(arenaId, type), queryTop10(arenaId, type));
                    }
                }
                refreshPlayerStatsCache();
                refreshRankCaches();
            });
        }, 20L, CACHE_REFRESH_TICKS);
    }

    private String cacheKey(String arenaId, TopType type) {
        return (arenaId == null ? "*global*" : arenaId.toLowerCase()) + ":" + type;
    }

    // ─────────────────────────────────────────
    //  Record win
    // ─────────────────────────────────────────

    public void recordWin(UUID uuid, String playerName, String arenaId) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO stats (uuid, arena_id, name, wins, losses, streak, best_streak, points)
                    VALUES (?, ?, ?, 1, 0, 1, 1, 1)
                    ON CONFLICT(uuid, arena_id) DO UPDATE SET
                        name        = excluded.name,
                        wins        = wins + 1,
                        streak      = streak + 1,
                        best_streak = MAX(best_streak, streak + 1),
                        points      = points + 1
                 """)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, arenaId);
                ps.setString(3, playerName);
                ps.executeUpdate();
                dirty.set(true);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to record win", e);
            }
        });
    }

    // ─────────────────────────────────────────
    //  Record loss
    // ─────────────────────────────────────────

    public void recordLoss(UUID uuid, String playerName, String arenaId) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO stats (uuid, arena_id, name, wins, losses, streak, best_streak, points)
                    VALUES (?, ?, ?, 0, 1, 0, 0, -1)
                    ON CONFLICT(uuid, arena_id) DO UPDATE SET
                        name   = excluded.name,
                        losses = losses + 1,
                        streak = 0,
                        points = points - 1
                 """)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, arenaId);
                ps.setString(3, playerName);
                ps.executeUpdate();
                dirty.set(true);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to record loss", e);
            }
        });
    }

    // ─────────────────────────────────────────
    //  Top 10
    // ─────────────────────────────────────────

    /** Reads from the periodically-refreshed cache — no DB I/O, safe to call from the main thread. */
    public List<PlayerStats> getTop10(String arenaId, TopType type) {
        return topCache.getOrDefault(cacheKey(arenaId, type), List.of());
    }

    private List<PlayerStats> queryTop10(String arenaId, TopType type) {
        String orderCol = switch (type) {
            case WINS   -> "wins";
            case LOSSES -> "losses";
            case STREAK -> "best_streak";
            case POINTS -> "points";
        };

        List<PlayerStats> result = new ArrayList<>();
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT uuid, name, wins, losses, streak, best_streak, points " +
                "FROM stats WHERE arena_id = ? ORDER BY " + orderCol + " DESC LIMIT 10")) {
            ps.setString(1, arenaId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new PlayerStats(
                        rs.getString("name"),
                        UUID.fromString(rs.getString("uuid")),
                        rs.getInt("wins"),
                        rs.getInt("losses"),
                        rs.getInt("streak"),
                        rs.getInt("best_streak"),
                        rs.getInt("points")
                ));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to fetch top 10", e);
        }
        return result;
    }
    // ─────────────────────────────────────────
    //  Top 10 — all arenas combined (SUM per player)
    // ─────────────────────────────────────────

    /** Reads from the periodically-refreshed cache — no DB I/O, safe to call from the main thread. */
    public List<PlayerStats> getTopGlobal(TopType type) {
        return topCache.getOrDefault(cacheKey(null, type), List.of());
    }

    private List<PlayerStats> queryTopGlobal(TopType type) {
        String orderCol = switch (type) {
            case WINS   -> "total_wins";
            case LOSSES -> "total_losses";
            case STREAK -> "total_best_streak";
            case POINTS -> "total_points";
        };

        List<PlayerStats> result = new ArrayList<>();
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT uuid, name, " +
                "SUM(wins) AS total_wins, SUM(losses) AS total_losses, " +
                "SUM(streak) AS total_streak, SUM(best_streak) AS total_best_streak, " +
                "SUM(points) AS total_points " +
                "FROM stats GROUP BY uuid " +
                "ORDER BY " + orderCol + " DESC LIMIT 10")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new PlayerStats(
                        rs.getString("name"),
                        UUID.fromString(rs.getString("uuid")),
                        rs.getInt("total_wins"),
                        rs.getInt("total_losses"),
                        rs.getInt("total_streak"),
                        rs.getInt("total_best_streak"),
                        rs.getInt("total_points")
                ));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to fetch global top 10", e);
        }
        return result;
    }

    // ─────────────────────────────────────────
    //  Per-player stats (global sum)
    // ─────────────────────────────────────────

    /**
     * Returns the cached aggregated stats for a player across all arenas.
     * Returns null if the player has never played a duel.
     * Safe to call from the main thread — no DB I/O.
     */
    public PlayerStats getPlayerStats(UUID uuid) {
        return playerStatsCache.get(uuid);
    }

    /** Queries the DB and rebuilds playerStatsCache. Must be called off the main thread. */
    private void refreshPlayerStatsCache() {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT uuid, name, " +
                "SUM(wins) AS total_wins, SUM(losses) AS total_losses, " +
                "SUM(streak) AS total_streak, SUM(best_streak) AS total_best_streak, " +
                "SUM(points) AS total_points " +
                "FROM stats GROUP BY uuid")) {
            ResultSet rs = ps.executeQuery();
            Map<UUID, PlayerStats> fresh = new HashMap<>();
            while (rs.next()) {
                UUID uid = UUID.fromString(rs.getString("uuid"));
                fresh.put(uid, new PlayerStats(
                        rs.getString("name"),
                        uid,
                        rs.getInt("total_wins"),
                        rs.getInt("total_losses"),
                        rs.getInt("total_streak"),
                        rs.getInt("total_best_streak"),
                        rs.getInt("total_points")
                ));
            }
            playerStatsCache.putAll(fresh);
            playerStatsCache.keySet().retainAll(fresh.keySet());
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to refresh player stats cache", e);
        }
    }

    // ─────────────────────────────────────────
    //  Personal rank ("me") — global + per arena
    // ─────────────────────────────────────────

    private static int valueOf(PlayerStats ps, TopType type) {
        return switch (type) {
            case WINS   -> ps.wins();
            case LOSSES -> ps.losses();
            case STREAK -> ps.bestStreak();
            case POINTS -> ps.points();
        };
    }

    /** Stats of a player in one arena (arenaId == null → all arenas combined). null if none. */
    public PlayerStats getStatsFor(UUID uuid, String arenaId) {
        if (arenaId == null) return playerStatsCache.get(uuid);
        Map<UUID, PlayerStats> m = arenaStatsCache.get(arenaId.toLowerCase());
        return m == null ? null : m.get(uuid);
    }

    /** 1-based rank of the player (arenaId == null → global). 0 if the player has no stats. */
    public int getRank(UUID uuid, String arenaId, TopType type) {
        Map<UUID, Integer> m = rankCache.get(cacheKey(arenaId, type));
        if (m == null) return 0;
        return m.getOrDefault(uuid, 0);
    }

    /** Rebuilds rank caches for every player (not just top 10). Must be called off the main thread. */
    private void refreshRankCaches() {
        Map<String, Map<UUID, PlayerStats>> perArena = new HashMap<>();
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT uuid, arena_id, name, wins, losses, streak, best_streak, points FROM stats")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                UUID uid = UUID.fromString(rs.getString("uuid"));
                String arena = rs.getString("arena_id").toLowerCase();
                perArena.computeIfAbsent(arena, k -> new HashMap<>()).put(uid, new PlayerStats(
                        rs.getString("name"), uid,
                        rs.getInt("wins"), rs.getInt("losses"), rs.getInt("streak"),
                        rs.getInt("best_streak"), rs.getInt("points")));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to refresh rank caches", e);
            return;
        }

        Map<String, Map<UUID, Integer>> freshRanks = new HashMap<>();
        for (TopType type : TopType.values()) {
            freshRanks.put(cacheKey(null, type), buildRanks(new ArrayList<>(playerStatsCache.values()), type));
            for (Map.Entry<String, Map<UUID, PlayerStats>> e : perArena.entrySet()) {
                freshRanks.put(cacheKey(e.getKey(), type), buildRanks(new ArrayList<>(e.getValue().values()), type));
            }
        }
        rankCache.putAll(freshRanks);
        rankCache.keySet().retainAll(freshRanks.keySet());
        arenaStatsCache.putAll(perArena);
        arenaStatsCache.keySet().retainAll(perArena.keySet());
    }

    /** Sorted descending by value; ties share the same rank (1,2,2,4...). */
    private Map<UUID, Integer> buildRanks(List<PlayerStats> list, TopType type) {
        list.sort((a, b) -> Integer.compare(valueOf(b, type), valueOf(a, type)));
        Map<UUID, Integer> ranks = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            int rank = (i > 0 && valueOf(list.get(i), type) == valueOf(list.get(i - 1), type))
                    ? ranks.get(list.get(i - 1).uuid()) : i + 1;
            ranks.put(list.get(i).uuid(), rank);
        }
        return ranks;
    }

    // ─────────────────────────────────────────
    //  Weekly reset (all arenas, all stats)
    // ─────────────────────────────────────────

    /**
     * Async: reads the current GLOBAL top (live from DB) for each given type, archives the whole
     * stats table into stats_history, wipes stats, clears caches, then calls onDone ON THE MAIN THREAD
     * with the unique winners (top {@code topCount} per type, score &gt; 0, each player once).
     */
    public void weeklyReset(List<TopType> types, int topCount, java.util.function.Consumer<List<PlayerStats>> onDone) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, PlayerStats> winners = new LinkedHashMap<>();
            try {
                for (TopType type : types) {
                    int n = 0;
                    for (PlayerStats ps : queryTopGlobal(type)) {
                        if (n >= topCount) break;
                        if (valueOf(ps, type) <= 0) break;
                        winners.putIfAbsent(ps.uuid(), ps);
                        n++;
                    }
                }
                try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                    boolean oldAuto = conn.getAutoCommit();
                    conn.setAutoCommit(false);
                    try (Statement st = conn.createStatement()) {
                        st.execute("CREATE TABLE IF NOT EXISTS stats_history (" +
                                "reset_at INTEGER, uuid TEXT, arena_id TEXT, name TEXT, " +
                                "wins INTEGER, losses INTEGER, streak INTEGER, best_streak INTEGER, points INTEGER)");
                        st.execute("INSERT INTO stats_history SELECT " + System.currentTimeMillis() +
                                ", uuid, arena_id, name, wins, losses, streak, best_streak, points FROM stats");
                        st.execute("DELETE FROM stats");
                        conn.commit();
                    } catch (SQLException e) {
                        conn.rollback();
                        throw e;
                    } finally {
                        conn.setAutoCommit(oldAuto);
                    }
                }
                topCache.clear();
                playerStatsCache.clear();
                rankCache.clear();
                arenaStatsCache.clear();
                dirty.set(true);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Weekly reset FAILED", e);
                plugin.getServer().getScheduler().runTask(plugin, () -> onDone.accept(null));
                return;
            }
            List<PlayerStats> result = new ArrayList<>(winners.values());
            plugin.getServer().getScheduler().runTask(plugin, () -> onDone.accept(result));
        });
    }
}
