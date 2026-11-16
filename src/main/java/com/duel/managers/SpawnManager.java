package com.duel.managers;

import com.duel.DuelPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.sql.*;
import java.util.logging.Level;

/**
 * Manages the server spawn location — stored in SQLite.
 * Used by 1v1, Party vs Party, and Party FFA to return players after matches.
 *
 * Table: server_spawn
 *   world TEXT, x REAL, y REAL, z REAL, yaw REAL, pitch REAL
 */
public class SpawnManager {

    private final DuelPlugin plugin;
    private Location cachedSpawn = null;

    public SpawnManager(DuelPlugin plugin) {
        this.plugin = plugin;
        createTable();
        loadSpawn();
    }

    // ────────────────────────────────────────────
    //  Init
    // ────────────────────────────────────────────

    private void createTable() {
        plugin.getDatabaseManager().execute("""
            CREATE TABLE IF NOT EXISTS server_spawn (
                id    INTEGER PRIMARY KEY DEFAULT 1,
                world TEXT    NOT NULL,
                x     REAL    NOT NULL,
                y     REAL    NOT NULL,
                z     REAL    NOT NULL,
                yaw   REAL    NOT NULL DEFAULT 0,
                pitch REAL    NOT NULL DEFAULT 0
            )
        """);
    }

    private void loadSpawn() {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT world, x, y, z, yaw, pitch FROM server_spawn WHERE id = 1")) {

            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String worldName = rs.getString("world");
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    plugin.getLogger().warning("[SpawnManager] World '" + worldName + "' not loaded — spawn not set.");
                    return;
                }
                cachedSpawn = new Location(
                        world,
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        rs.getDouble("z"),
                        rs.getFloat("yaw"),
                        rs.getFloat("pitch")
                );
                plugin.getLogger().info("[SpawnManager] Spawn loaded: " + formatLoc(cachedSpawn));
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[SpawnManager] Failed to load spawn!", e);
        }
    }

    // ────────────────────────────────────────────
    //  Set spawn
    // ────────────────────────────────────────────

    public void setSpawn(Location loc) {
        cachedSpawn = loc.clone();

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO server_spawn (id, world, x, y, z, yaw, pitch)
                VALUES (1, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    world = excluded.world,
                    x     = excluded.x,
                    y     = excluded.y,
                    z     = excluded.z,
                    yaw   = excluded.yaw,
                    pitch = excluded.pitch
             """)) {

            ps.setString(1, loc.getWorld().getName());
            ps.setDouble(2, loc.getX());
            ps.setDouble(3, loc.getY());
            ps.setDouble(4, loc.getZ());
            ps.setFloat (5, loc.getYaw());
            ps.setFloat (6, loc.getPitch());
            ps.executeUpdate();

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[SpawnManager] Failed to save spawn!", e);
        }
    }

    // ────────────────────────────────────────────
    //  Get spawn
    // ────────────────────────────────────────────

    /**
     * Returns the saved spawn, or falls back to world 0 spawn if not set.
     */
    public Location getSpawn() {
        if (cachedSpawn != null) return cachedSpawn.clone();
        // Fallback
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    public boolean isSpawnSet() {
        return cachedSpawn != null;
    }

    // ────────────────────────────────────────────
    //  Util
    // ────────────────────────────────────────────

    private String formatLoc(Location loc) {
        return String.format("%s (%.1f, %.1f, %.1f)", loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ());
    }
}
