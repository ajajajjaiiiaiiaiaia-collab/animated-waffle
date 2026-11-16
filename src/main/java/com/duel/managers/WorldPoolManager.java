package com.duel.managers;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

public class WorldPoolManager {

    private final DuelPlugin plugin;

    private final Map<String, Deque<String>> ready = new HashMap<>();
    private final Map<String, Integer> failures = new HashMap<>();

    private BukkitTask fillTask;
    private boolean cloning = false;
    private boolean enabled = false;
    private int size = 10;
    private int counter = 0;

    public WorldPoolManager(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        FileConfiguration cfg = plugin.getConfig();
        enabled = cfg.getBoolean("world-pool.enabled", true);
        size = Math.max(0, cfg.getInt("world-pool.size", 10));
        long interval = Math.max(1, cfg.getInt("world-pool.fill-interval-seconds", 5)) * 20L;
        long startDelay = Math.max(0, cfg.getInt("world-pool.startup-delay-seconds", 10)) * 20L;

        if (!enabled || size == 0) {
            plugin.getLogger().info("[WorldPool] Disabled — arenas are cloned on demand.");
            return;
        }

        fillTask = Bukkit.getScheduler().runTaskTimer(plugin, this::fillTick, startDelay, interval);
        plugin.getLogger().info("[WorldPool] Enabled — target " + size + " ready world(s) per arena world.");
    }

    public void shutdown() {
        if (fillTask != null) {
            fillTask.cancel();
            fillTask = null;
        }
        if (!plugin.getConfig().getBoolean("world-pool.delete-on-shutdown", true)) return;

        for (Deque<String> queue : ready.values()) {
            for (String name : queue) {
                try {
                    plugin.getArenaManager().deleteWorld(name);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "[WorldPool] Failed to delete pooled world " + name, e);
                }
            }
        }
        ready.clear();
    }

    public String acquire(String templateWorld, String fallbackName) {
        if (enabled) {
            Deque<String> queue = ready.get(templateWorld);
            while (queue != null && !queue.isEmpty()) {
                String name = queue.pollFirst();
                if (Bukkit.getWorld(name) != null) return name;
            }
        }
        plugin.getArenaManager().copyWorld(templateWorld, fallbackName);
        return fallbackName;
    }

    public int getReadyCount(String templateWorld) {
        Deque<String> queue = ready.get(templateWorld);
        return queue == null ? 0 : queue.size();
    }

    private Set<String> templates() {
        Set<String> result = new LinkedHashSet<>();
        for (Arena arena : plugin.getArenaManager().getAllArenas()) {
            result.addAll(arena.getWorlds());
        }
        return result;
    }

    private void fillTick() {
        if (cloning) return;
        for (String template : templates()) {
            if (failures.getOrDefault(template, 0) >= 3) continue;
            Deque<String> queue = ready.computeIfAbsent(template, k -> new ArrayDeque<>());
            if (queue.size() < size) {
                cloneOne(template, queue);
                return;
            }
        }
    }

    private void cloneOne(String template, Deque<String> queue) {
        cloning = true;

        String prefix = plugin.getConfig().getString("settings.arena-copy-prefix", "duel_arena_");
        String name = prefix + "pool_" + template + "_" + System.currentTimeMillis() + "_" + (counter++);

        plugin.getArenaManager().copyWorld(template, name);

        final int[] attempts = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            attempts[0]++;
            World world = Bukkit.getWorld(name);
            if (world != null) {
                task.cancel();
                plugin.getArenaManager().tuneWorld(world);
                queue.addLast(name);
                failures.remove(template);
                cloning = false;
                if (queue.size() >= size) {
                    plugin.getLogger().info("[WorldPool] '" + template + "' pool ready (" + queue.size() + "/" + size + ").");
                }
            } else if (plugin.getArenaManager().isCopyFailed(name) || attempts[0] > 200) {
                task.cancel();
                cloning = false;
                int count = failures.merge(template, 1, Integer::sum);
                plugin.getLogger().warning("[WorldPool] Clone timed out for '" + template + "' (" + count + "/3).");
                if (count >= 3) {
                    plugin.getLogger().severe("[WorldPool] Giving up filling '" + template + "' — check that the template world is loaded.");
                }
            }
        }, 10L, 10L);
    }
}
