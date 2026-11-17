package com.duel.managers;

import com.duel.DuelPlugin;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;

public class SettingsManager {

    public enum Setting {
        HIDE_CHAT,
        HIDE_SCOREBOARD,
        HIDE_PLAYERS,
        BLOCK_DUEL_REQUESTS,
        MUTE_SOUNDS,
        HIDE_ACTIONBAR,
        HIDE_DEATH_MESSAGES,
        HIDE_JOIN_QUIT_MESSAGES,
        AURA_GG
    }

    private final DuelPlugin plugin;
    private final Map<UUID, Map<Setting, Boolean>> cache = new java.util.concurrent.ConcurrentHashMap<>();

    public SettingsManager(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled(UUID uuid, Setting setting) {
        return load(uuid).getOrDefault(setting, false);
    }

    public void toggle(Player player, Setting setting) {
        Map<Setting, Boolean> map = load(player.getUniqueId());
        boolean newVal = !map.getOrDefault(setting, false);
        map.put(setting, newVal);
        cache.put(player.getUniqueId(), map);
        persistAsync(player.getUniqueId(), map);
        applyEffect(player, setting, newVal);
    }

    public void unload(UUID uuid) {
        cache.remove(uuid);
    }

    public void applyAll(Player player) {
        Map<Setting, Boolean> map = load(player.getUniqueId());
        for (Map.Entry<Setting, Boolean> entry : map.entrySet()) {
            if (entry.getValue()) applyEffect(player, entry.getKey(), true);
        }
    }

    /**
     * يتحكم بسكوربورد TAB عن طريق الـ API (بالريفلكشن عشان ما نحتاج dependency).
     * لو TabPlayer لسا ما تحمّل (أول ما يدخل اللاعب) نعيد المحاولة كل ثانية (5 مرات).
     */
    private void setTabScoreboardVisible(Player player, boolean visible, int attempt) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("TAB")) return;
        try {
            Class<?> apiClass = Class.forName("me.neznamy.tab.api.TabAPI");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) return;
            Object tabPlayer = apiClass.getMethod("getPlayer", java.util.UUID.class).invoke(api, player.getUniqueId());
            Object sbm = apiClass.getMethod("getScoreboardManager").invoke(api);
            if (tabPlayer == null || sbm == null) {
                if (attempt < 5 && player.isOnline()) {
                    plugin.getServer().getScheduler().runTaskLater(plugin,
                            () -> setTabScoreboardVisible(player, visible, attempt + 1), 20L);
                }
                return;
            }
            Class<?> sbmClass = Class.forName("me.neznamy.tab.api.scoreboard.ScoreboardManager");
            Class<?> tpClass  = Class.forName("me.neznamy.tab.api.TabPlayer");
            sbmClass.getMethod("setScoreboardVisible", tpClass, boolean.class, boolean.class)
                    .invoke(sbm, tabPlayer, visible, false);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "[DuelPlugin] Failed to toggle TAB scoreboard: " + t);
        }
    }

    public void applyEffect(Player player, Setting setting, boolean enabled) {
        switch (setting) {
            case HIDE_SCOREBOARD -> {
                // يخفي/يظهر سكوربورد السيرفر حق TAB (packets) — غير سكوربورد الدول (Bukkit)
                setTabScoreboardVisible(player, !enabled, 0);
                try {
                    ScoreboardManager sbManager = plugin.getServer().getScoreboardManager();
                    if (sbManager == null) return;
                    if (enabled) {
                        // Give player an empty scoreboard to hide it
                        Scoreboard empty = sbManager.getNewScoreboard();
                        player.setScoreboard(empty);
                    } else {
                        // Restore main scoreboard
                        player.setScoreboard(sbManager.getMainScoreboard());
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "[DuelPlugin] Failed to toggle scoreboard: " + e.getMessage());
                }
            }
            case HIDE_PLAYERS -> {
                for (Player other : plugin.getServer().getOnlinePlayers()) {
                    if (other.equals(player)) continue;
                    if (enabled) {
                        boolean inDuel = plugin.getDuelManager().isInDuel(player)
                                && plugin.getDuelManager().getSession(player) != null
                                && plugin.getDuelManager().getSession(player).getOpponent(player) != null
                                && plugin.getDuelManager().getSession(player).getOpponent(player).equals(other);
                        boolean inPartyDuel = plugin.getPartyDuelManager().isInPartyDuel(player)
                                && plugin.getPartyDuelManager().isInPartyDuel(other);
                        if (inDuel || inPartyDuel) continue;
                        player.hidePlayer(plugin, other);
                    } else {
                        player.showPlayer(plugin, other);
                    }
                }
                // مزامنة الـ TAB — نفس تأثير تشغيل/إطفاء الخيار يدوياً
                if (!enabled) plugin.getTabIsolationManager().scheduleResync(player);
            }
            case HIDE_CHAT, MUTE_SOUNDS, HIDE_ACTIONBAR,
                 HIDE_DEATH_MESSAGES, HIDE_JOIN_QUIT_MESSAGES,
                 BLOCK_DUEL_REQUESTS, AURA_GG -> {
                // Handled in their respective listeners/managers
            }
        }
    }

    private Map<Setting, Boolean> load(UUID uuid) {
        Map<Setting, Boolean> cached = cache.get(uuid);
        if (cached != null) return cached;

        Map<Setting, Boolean> map = readFromDb(uuid);
        Map<Setting, Boolean> existing = cache.putIfAbsent(uuid, map);
        return existing != null ? existing : map;
    }

    public void preloadAsync(UUID uuid) {
        if (cache.containsKey(uuid)) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<Setting, Boolean> map = readFromDb(uuid);
            if (!plugin.isEnabled()) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (plugin.getServer().getPlayer(uuid) == null) return;
                cache.putIfAbsent(uuid, map);
            });
        });
    }

    private Map<Setting, Boolean> readFromDb(UUID uuid) {
        Map<Setting, Boolean> map = new HashMap<>();
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM player_settings WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                map.put(Setting.HIDE_CHAT,               rs.getInt("hide_chat")           == 1);
                map.put(Setting.HIDE_SCOREBOARD,         rs.getInt("hide_scoreboard")      == 1);
                map.put(Setting.HIDE_PLAYERS,            rs.getInt("hide_players")         == 1);
                map.put(Setting.BLOCK_DUEL_REQUESTS,     rs.getInt("block_duel_requests")  == 1);
                map.put(Setting.MUTE_SOUNDS,             rs.getInt("mute_sounds")          == 1);
                map.put(Setting.HIDE_ACTIONBAR,          rs.getInt("hide_actionbar")       == 1);
                map.put(Setting.HIDE_DEATH_MESSAGES,     rs.getInt("hide_death_messages")  == 1);
                map.put(Setting.HIDE_JOIN_QUIT_MESSAGES, rs.getInt("hide_join_quit")       == 1);
                map.put(Setting.AURA_GG,                 rs.getInt("aura_gg")              == 1);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[DuelPlugin] Failed to load settings for " + uuid, e);
        }
        return map;
    }

    private void persistAsync(UUID uuid, Map<Setting, Boolean> map) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO player_settings
                        (uuid, hide_chat, hide_scoreboard, hide_players, block_duel_requests,
                         mute_sounds, hide_actionbar, hide_death_messages, hide_join_quit, aura_gg)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET
                        hide_chat           = excluded.hide_chat,
                        hide_scoreboard     = excluded.hide_scoreboard,
                        hide_players        = excluded.hide_players,
                        block_duel_requests = excluded.block_duel_requests,
                        mute_sounds         = excluded.mute_sounds,
                        hide_actionbar      = excluded.hide_actionbar,
                        hide_death_messages = excluded.hide_death_messages,
                        hide_join_quit      = excluded.hide_join_quit,
                        aura_gg             = excluded.aura_gg
                """)) {
                ps.setString(1, uuid.toString());
                ps.setInt(2,  b(map, Setting.HIDE_CHAT));
                ps.setInt(3,  b(map, Setting.HIDE_SCOREBOARD));
                ps.setInt(4,  b(map, Setting.HIDE_PLAYERS));
                ps.setInt(5,  b(map, Setting.BLOCK_DUEL_REQUESTS));
                ps.setInt(6,  b(map, Setting.MUTE_SOUNDS));
                ps.setInt(7,  b(map, Setting.HIDE_ACTIONBAR));
                ps.setInt(8,  b(map, Setting.HIDE_DEATH_MESSAGES));
                ps.setInt(9,  b(map, Setting.HIDE_JOIN_QUIT_MESSAGES));
                ps.setInt(10, b(map, Setting.AURA_GG));
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to save settings for " + uuid, e);
            }
        });
    }

    private int b(Map<Setting, Boolean> map, Setting s) {
        return map.getOrDefault(s, false) ? 1 : 0;
    }
}
