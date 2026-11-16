package com.duel.managers;

import com.duel.DuelPlugin;
import com.duel.models.Kit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

public class KitManager {

    private final DuelPlugin plugin;
    private final Map<String, Kit> kits = new HashMap<>();

    public KitManager(DuelPlugin plugin) {
        this.plugin = plugin;
        loadAll();
    }

    // ─────────────────────────────────────────
    //  Load all kits from DB
    // ─────────────────────────────────────────

    private void loadAll() {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT name, items FROM kits");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String name  = rs.getString("name");
                String items = rs.getString("items");
                ItemStack[] contents = deserialize(items);
                kits.put(name.toLowerCase(), new Kit(name, contents));
            }
            plugin.getLogger().info("[DuelPlugin] Loaded " + kits.size() + " kit(s) from database.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to load kits from DB!", e);
        }
    }

    // ─────────────────────────────────────────
    //  Save kit to DB
    // ─────────────────────────────────────────

    public void saveKit(Kit kit) {
        kits.put(kit.getName().toLowerCase(), kit);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO kits (name, items)
                    VALUES (?, ?)
                    ON CONFLICT(name) DO UPDATE SET items = excluded.items
                 """)) {
                ps.setString(1, kit.getName());
                ps.setString(2, serialize(kit.getContents()));
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to save kit: " + kit.getName(), e);
            }
        });
    }

    // ─────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────

    public Kit getKit(String name)        { return kits.get(name.toLowerCase()); }
    public boolean kitExists(String name) { return kits.containsKey(name.toLowerCase()); }
    public Set<String> getAllKitNames()   { return kits.keySet(); }

    public Kit createKit(String name) {
        Kit kit = new Kit(name);
        kits.put(name.toLowerCase(), kit);
        return kit;
    }

    public void applyKit(Player player, Kit kit) {
        player.getInventory().clear(); player.setItemOnCursor(null);
        ItemStack[] contents = kit.getContents();

        for (int i = 0; i <= 35 && i < contents.length; i++) {
            if (contents[i] != null) player.getInventory().setItem(i, contents[i]);
        }
        if (contents.length > 36 && contents[36] != null) player.getInventory().setBoots(contents[36]);
        if (contents.length > 37 && contents[37] != null) player.getInventory().setLeggings(contents[37]);
        if (contents.length > 38 && contents[38] != null) player.getInventory().setChestplate(contents[38]);
        if (contents.length > 39 && contents[39] != null) player.getInventory().setHelmet(contents[39]);
        if (contents.length > 40 && contents[40] != null) player.getInventory().setItemInOffHand(contents[40]);

        player.updateInventory();
    }

    // ─────────────────────────────────────────
    //  Serialize / Deserialize
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
            return new ItemStack[41];
        }
    }
}
