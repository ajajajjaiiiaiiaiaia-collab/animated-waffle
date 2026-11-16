package com.duel.managers;

import com.duel.DuelPlugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * Manages per-player kit arrangements per arena — stored in SQLite.
 */
public class PlayerKitManager {

    private final DuelPlugin plugin;
    // Cache: UUID+arena → items
    private final Map<String, ItemStack[]> cache = new HashMap<>();

    public PlayerKitManager(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    public ItemStack[] getPlayerKit(UUID uuid, String arenaId) {
        String key = uuid + ":" + arenaId;
        if (cache.containsKey(key)) return cache.get(key);

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT items FROM player_kits WHERE uuid = ? AND arena_id = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, arenaId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                ItemStack[] items = deserialize(rs.getString("items"));
                cache.put(key, items);
                return items;
            }
            cache.put(key, null);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[DuelPlugin] Failed to load player kit", e);
        }
        return null;
    }

    public void savePlayerKit(UUID uuid, String arenaId, ItemStack[] items) {
        String key = uuid + ":" + arenaId;
        cache.put(key, items);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO player_kits (uuid, arena_id, items)
                    VALUES (?, ?, ?)
                    ON CONFLICT(uuid, arena_id) DO UPDATE SET items = excluded.items
                """)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, arenaId);
                ps.setString(3, serialize(items));
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to save player kit", e);
            }
        });
    }

    public void unloadPlayer(UUID uuid) {
        cache.entrySet().removeIf(e -> e.getKey().startsWith(uuid.toString()));
    }

    // ─────────────────────────────────────────
    //  Serialize / Deserialize ItemStack[]
    // ─────────────────────────────────────────

    private String serialize(ItemStack[] items) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(bos)) {
            oos.writeInt(items.length);
            for (ItemStack item : items) oos.writeObject(item);
            return Base64Coder.encodeLines(bos.toByteArray());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to serialize kit", e);
            return "";
        }
    }

    private ItemStack[] deserialize(String data) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(Base64Coder.decodeLines(data));
             BukkitObjectInputStream ois = new BukkitObjectInputStream(bis)) {
            int len = ois.readInt();
            ItemStack[] items = new ItemStack[len];
            for (int i = 0; i < len; i++) items[i] = (ItemStack) ois.readObject();
            return items;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to deserialize kit", e);
            return new ItemStack[0];
        }
    }
}
