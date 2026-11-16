package com.duel.trim;

import com.duel.DuelPlugin;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.Registry;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * Manages Armor Trim selections per player, per arena, per slot.
 *
 * DB table: player_trims
 *   uuid       TEXT
 *   arena      TEXT  (arena id — trims are scoped per arena)
 *   slot       TEXT  (HELMET | CHESTPLATE | LEGGINGS | BOOTS)
 *   pattern    TEXT  (e.g. silence)
 *   material   TEXT  (e.g. amethyst)
 *   PRIMARY KEY (uuid, arena, slot)
 *
 * Permission: duelplugin.trm  (one permission unlocks the whole trim system)
 */
public class TrimManager {

    public enum ArmorSlot {
        HELMET, CHESTPLATE, LEGGINGS, BOOTS
    }

    /** One saved trim: pattern + material keys */
    public record TrimEntry(String pattern, String material) {}

    // In-memory cache:  uuid → (arenaId → (slot → TrimEntry))
    private final Map<UUID, Map<String, Map<ArmorSlot, TrimEntry>>> cache = new HashMap<>();

    private final DuelPlugin plugin;

    public TrimManager(DuelPlugin plugin) {
        this.plugin = plugin;
        createTable();
        loadAll();
    }

    // ── DB setup ─────────────────────────────────────────────────────────

    private void createTable() {
        try (Connection c = plugin.getDatabaseManager().getConnection();
             Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS player_trims (
                    uuid     TEXT NOT NULL,
                    arena    TEXT NOT NULL,
                    slot     TEXT NOT NULL,
                    pattern  TEXT NOT NULL,
                    material TEXT NOT NULL,
                    PRIMARY KEY (uuid, arena, slot)
                )
            """);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[TrimManager] Failed to create player_trims table", e);
        }
    }

    private void loadAll() {
        try (Connection c = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT uuid, arena, slot, pattern, material FROM player_trims");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID uuid        = UUID.fromString(rs.getString("uuid"));
                String arena     = rs.getString("arena");
                ArmorSlot slot   = ArmorSlot.valueOf(rs.getString("slot"));
                String pattern   = rs.getString("pattern");
                String material  = rs.getString("material");
                cache.computeIfAbsent(uuid, u -> new HashMap<>())
                     .computeIfAbsent(arena, a -> new EnumMap<>(ArmorSlot.class))
                     .put(slot, new TrimEntry(pattern, material));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[TrimManager] Failed to load player_trims", e);
        }
    }

    // ── Public API ───────────────────────────────────────────────────────

    /** صلاحية واحدة تفتح نظام التريم كامل: duelplugin.trm (أو الأدمن) */
    public static final String PERMISSION = "duelplugin.trm";

    public static boolean canUsePattern(org.bukkit.entity.Player p, String pattern) {
        return p.hasPermission(PERMISSION) || p.hasPermission("duelplugin.admin");
    }

    /** Get trim for a specific arena + slot (null = none set). */
    public TrimEntry getTrim(UUID uuid, String arenaId, ArmorSlot slot) {
        Map<String, Map<ArmorSlot, TrimEntry>> byArena = cache.get(uuid);
        if (byArena == null) return null;
        Map<ArmorSlot, TrimEntry> m = byArena.get(arenaId);
        return m == null ? null : m.get(slot);
    }

    /** Get all trims for a player, scoped to one arena. */
    public Map<ArmorSlot, TrimEntry> getAllTrims(UUID uuid, String arenaId) {
        Map<String, Map<ArmorSlot, TrimEntry>> byArena = cache.get(uuid);
        if (byArena == null) return new EnumMap<>(ArmorSlot.class);
        return byArena.getOrDefault(arenaId, new EnumMap<>(ArmorSlot.class));
    }

    /** Save / update a trim for a player, scoped to one arena + slot. */
    public void setTrim(UUID uuid, String arenaId, ArmorSlot slot, String pattern, String material) {
        cache.computeIfAbsent(uuid, u -> new HashMap<>())
             .computeIfAbsent(arenaId, a -> new EnumMap<>(ArmorSlot.class))
             .put(slot, new TrimEntry(pattern, material));

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection c = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO player_trims (uuid, arena, slot, pattern, material)
                     VALUES (?, ?, ?, ?, ?)
                     ON CONFLICT(uuid, arena, slot) DO UPDATE
                         SET pattern = excluded.pattern,
                             material = excluded.material
                 """)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, arenaId);
                ps.setString(3, slot.name());
                ps.setString(4, pattern);
                ps.setString(5, material);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "[TrimManager] Failed to save trim", e);
            }
        });
    }

    /** Remove trim from a specific arena + slot. */
    public void removeTrim(UUID uuid, String arenaId, ArmorSlot slot) {
        Map<String, Map<ArmorSlot, TrimEntry>> byArena = cache.get(uuid);
        if (byArena != null) {
            Map<ArmorSlot, TrimEntry> m = byArena.get(arenaId);
            if (m != null) m.remove(slot);
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
            plugin.getDatabaseManager().execute(
                "DELETE FROM player_trims WHERE uuid = ? AND arena = ? AND slot = ?",
                uuid.toString(), arenaId, slot.name())
        );
    }

    /**
     * Apply the player's saved trims (for a specific arena) to a set of armor items.
     * Pass in the four armor pieces (can be null).
     * Returns the same array with trims applied.
     */
    public ItemStack[] applyTrims(UUID uuid, String arenaId, ItemStack helmet, ItemStack chestplate,
                                  ItemStack leggings, ItemStack boots) {
        ItemStack[] pieces = {helmet, chestplate, leggings, boots};
        ArmorSlot[] slots  = ArmorSlot.values(); // HELMET, CHESTPLATE, LEGGINGS, BOOTS

        for (int i = 0; i < slots.length; i++) {
            TrimEntry entry = getTrim(uuid, arenaId, slots[i]);
            if (entry == null || pieces[i] == null) continue;
            pieces[i] = applyTrimToItem(pieces[i].clone(), entry);
        }
        return pieces;
    }

    /** نفس applyTrims لكن يتجاوز الباترنات اللي اللاعب ما عنده صلاحيتها. */
    public ItemStack[] applyTrims(org.bukkit.entity.Player player, String arenaId, ItemStack helmet,
                                  ItemStack chestplate, ItemStack leggings, ItemStack boots) {
        ItemStack[] pieces = {helmet, chestplate, leggings, boots};
        ArmorSlot[] slots  = ArmorSlot.values();

        for (int i = 0; i < slots.length; i++) {
            TrimEntry entry = getTrim(player.getUniqueId(), arenaId, slots[i]);
            if (entry == null || pieces[i] == null) continue;
            if (!canUsePattern(player, entry.pattern())) continue;
            pieces[i] = applyTrimToItem(pieces[i].clone(), entry);
        }
        return pieces;
    }

    /** Apply a single TrimEntry to an ItemStack. Returns the modified clone. */
    public static ItemStack applyTrimToItem(ItemStack item, TrimEntry entry) {
        if (item == null) return null;
        var meta = item.getItemMeta();
        if (!(meta instanceof ArmorMeta armorMeta)) return item;

        TrimPattern pattern  = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft(entry.pattern()));
        TrimMaterial material = Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft(entry.material()));
        if (pattern == null || material == null) return item;

        armorMeta.setTrim(new ArmorTrim(material, pattern));
        item.setItemMeta(armorMeta);
        return item;
    }

    /** Remove trim from an ItemStack. */
    public static ItemStack removeTrimFromItem(ItemStack item) {
        if (item == null) return null;
        var meta = item.getItemMeta();
        if (!(meta instanceof ArmorMeta armorMeta)) return item;
        armorMeta.setTrim(null);
        item.setItemMeta(armorMeta);
        return item;
    }
}
