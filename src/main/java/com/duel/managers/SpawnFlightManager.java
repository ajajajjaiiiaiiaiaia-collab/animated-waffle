package com.duel.managers;

import com.duel.DuelPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Grants flight only to players with {@link #PERMISSION} while they are inside
 * the server spawn world (the world SpawnManager's saved location is in).
 *
 * Behaviour:
 *  - Runs a lightweight periodic check (every second) over online players.
 *  - Players in CREATIVE / SPECTATOR are never touched — vanilla already
 *    handles their flight.
 *  - Flight is only revoked for players WE granted it to (tracked in
 *    {@link #managedFlight}), so this never interferes with flight granted
 *    by another plugin (Essentials /fly, WorldGuard regions, OP toggles, etc).
 *  - Leaving the spawn world (or losing the permission) instantly disables
 *    flight and stops the player from falling mid-air by leaving fall damage
 *    to vanilla (players should land normally since they were flying, not
 *    falling).
 *
 * Config (config.yml):
 *   spawn-fly.enabled:          true/false — master switch
 *   spawn-fly.notify-player:    true/false — send grant/revoke messages
 *   spawn-fly.granted-message:  message sent when flight is granted
 *   spawn-fly.revoked-message:  message sent when flight is revoked
 *
 * Permission: duelplugin.fly.spawn (default: false) — give to whichever
 * ranks/groups should be able to fly in spawn (via LuckPerms etc).
 */
public class SpawnFlightManager {

    public static final String PERMISSION = "duelplugin.fly.spawn";

    private final DuelPlugin plugin;
    private final Set<UUID>  managedFlight = new HashSet<>();
    private BukkitTask       task;

    public SpawnFlightManager(DuelPlugin plugin) {
        this.plugin = plugin;
        start();
    }

    private void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        // Leave flight state as-is on shutdown/reload — don't yank flight
        // out from under players mid-flight on a plugin reload.
        managedFlight.clear();
    }

    private void tick() {
        if (!plugin.getConfig().getBoolean("spawn-fly.enabled", true)) return;

        Location spawnLoc = plugin.getSpawnManager().getSpawn();
        World spawnWorld = spawnLoc != null ? spawnLoc.getWorld() : null;
        if (spawnWorld == null) return;

        for (Player p : Bukkit.getOnlinePlayers()) {
            GameMode gm = p.getGameMode();
            if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) continue;

            boolean hasPerm      = p.hasPermission(PERMISSION);
            boolean inSpawnWorld = p.getWorld().equals(spawnWorld);
            boolean shouldFly    = hasPerm && inSpawnWorld;

            UUID uuid = p.getUniqueId();

            if (shouldFly) {
                if (!p.getAllowFlight()) {
                    p.setAllowFlight(true);
                    managedFlight.add(uuid);
                    notifyPlayer(p, "spawn-fly.granted-message",
                            "&a✔ Flight enabled — you're in the spawn world.");
                }
            } else if (managedFlight.remove(uuid)) {
                // Only revoke flight that WE granted — never touch flight
                // granted by another plugin or an admin toggle.
                p.setAllowFlight(false);
                p.setFlying(false);
                notifyPlayer(p, "spawn-fly.revoked-message",
                        "&c✖ Flight disabled — you left the spawn world.");
            }
        }
    }

    private void notifyPlayer(Player p, String path, String defaultMsg) {
        if (!plugin.getConfig().getBoolean("spawn-fly.notify-player", true)) return;
        String raw = plugin.getConfig().getString(path, defaultMsg);
        p.sendMessage(ChatColor.translateAlternateColorCodes('&', raw));
    }
}
