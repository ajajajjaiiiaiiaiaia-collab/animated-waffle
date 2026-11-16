package com.duel.managers;

import com.duel.DuelPlugin;
import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import com.duel.managers.StatsManager.PlayerStats;
import com.duel.managers.StatsManager.TopType;
import org.bukkit.Location;

import java.util.*;

/**
 * Manages leaderboard holograms using DecentHolograms API.
 * Each hologram is keyed by: <arenaId>_<topType>
 */
public class HologramManager {

    private final DuelPlugin plugin;

    // key → hologram name (for DecentHolograms)
    private final Map<String, String> activeHolograms = new HashMap<>();

    public HologramManager(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    // ─────────────────────────────────────────
    //  Spawn / Update
    // ─────────────────────────────────────────

    public void spawnOrUpdate(Location location, String arenaId, TopType topType) {
        String key  = buildKey(arenaId, topType);
        String name = "duel_top_" + key;

        List<String> lines = buildLines(arenaId, topType);

        Hologram existing = DHAPI.getHologram(name);
        if (existing != null) {
            // Update in place — move to new location and refresh lines
            DHAPI.moveHologram(existing, location);
            DHAPI.setHologramLines(existing, lines);
        } else {
            Hologram holo = DHAPI.createHologram(name, location, lines);
            holo.setDisplayRange(48);
            holo.setUpdateRange(48);
            holo.setUpdateInterval(200); // update every 200 ticks = 10s
        }

        activeHolograms.put(key, name);
    }

    /** Refresh all active leaderboard holograms (call after a duel ends) */
    public void refreshAll() {
        // Collect snapshot to avoid ConcurrentModificationException
        Map<String, String> snapshot = new java.util.HashMap<>(activeHolograms);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            // Build all line lists off the main thread (DB reads)
            Map<String, List<String>> updates = new java.util.HashMap<>();
            for (Map.Entry<String, String> entry : snapshot.entrySet()) {
                String key  = entry.getKey();
                int    sep  = key.lastIndexOf('_');
                if (sep < 0) continue;
                String arenaId = key.substring(0, sep);
                TopType topType;
                try { topType = TopType.valueOf(key.substring(sep + 1)); }
                catch (IllegalArgumentException e) { continue; }
                updates.put(entry.getValue(), buildLines(arenaId, topType));
            }

            // Apply back on main thread (Hologram API is not thread-safe)
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (Map.Entry<String, List<String>> e : updates.entrySet()) {
                    Hologram holo = DHAPI.getHologram(e.getKey());
                    if (holo != null) DHAPI.setHologramLines(holo, e.getValue());
                }
            });
        });
    }

    public void removeHologram(String arenaId, TopType topType) {
        String key  = buildKey(arenaId, topType);
        String name = activeHolograms.remove(key);
        if (name == null) return;
        Hologram holo = DHAPI.getHologram(name);
        if (holo != null) holo.delete();
    }

    public void removeAll() {
        for (String name : activeHolograms.values()) {
            Hologram holo = DHAPI.getHologram(name);
            if (holo != null) holo.delete();
        }
        activeHolograms.clear();
    }

    // ─────────────────────────────────────────
    //  Build hologram lines
    // ─────────────────────────────────────────

    private List<String> buildLines(String arenaId, TopType topType) {
        List<String> lines = new ArrayList<>();

        // ── Header ──
        String typeColor = switch (topType) {
            case WINS    -> "&a";
            case LOSSES  -> "&c";
            case STREAK  -> "&6";
            case POINTS  -> "&b";
        };
        String typeLabel = switch (topType) {
            case WINS    -> "Wins";
            case LOSSES  -> "Losses";
            case STREAK  -> "Best Streak";
            case POINTS  -> "Points";
        };
        String headerIcon = switch (topType) {
            case WINS    -> "🏆";
            case LOSSES  -> "💀";
            case STREAK  -> "🔥";
            case POINTS  -> "⭐";
        };

        lines.add("&8&m        &r  " + typeColor + "&l" + headerIcon + "  " + arenaId.toUpperCase() + "  " + headerIcon + "  &8&m        ");
        lines.add(typeColor + "&lTop " + typeLabel);
        lines.add("&8&m                              ");
        lines.add(" ");

        // ── Top 10 entries ──
        List<PlayerStats> top = arenaId.equals("global")
                ? plugin.getStatsManager().getTopGlobal(topType)
                : plugin.getStatsManager().getTop10(arenaId, topType);

        String[] rankIcons = {"&6#1", "&7#2", "&c#3", "&f#4", "&f#5", "&f#6", "&f#7", "&f#8", "&f#9", "&f#10"};

        for (int i = 0; i < 10; i++) {
            if (i < top.size()) {
                PlayerStats s = top.get(i);
                int value = switch (topType) {
                    case WINS    -> s.wins();
                    case LOSSES  -> s.losses();
                    case STREAK  -> s.bestStreak();
                    case POINTS -> s.points();
                };
                lines.add(rankIcons[i] + " &f" + s.name() + "  " + typeColor + value);
            } else {
                lines.add(rankIcons[i] + " &8—  &7Empty");
            }
        }

        lines.add(" ");
        lines.add("&8&m                              ");

        return lines;
    }

    private String buildKey(String arenaId, TopType topType) {
        return arenaId + "_" + topType.name();
    }
}
