package com.stark.sportal;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PortalManager {

    private final SPortalPlugin plugin;
    private final File file;

    // name -> portal
    private final Map<String, Portal> portals = new ConcurrentHashMap<>();

    // world -> set of packed block longs that belong to at least one portal (fast reject)
    private final Map<String, Set<Long>> worldBlockIndex = new ConcurrentHashMap<>();

    // world -> packed block long -> list of portal names occupying that block
    private final Map<String, Map<Long, List<String>>> worldBlockToPortals = new ConcurrentHashMap<>();

    // player uuid -> active selection session
    private final Map<UUID, SelectionSession> sessions = new ConcurrentHashMap<>();

    public PortalManager(SPortalPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "portals.yml");
    }

    // ---------- Selection sessions ----------

    public void startSelection(Player p, String name, World world) {
        sessions.put(p.getUniqueId(), new SelectionSession(name, world));
    }

    public SelectionSession getSession(Player p) {
        return sessions.get(p.getUniqueId());
    }

    public boolean hasSession(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    public void cancelSelection(Player p) {
        sessions.remove(p.getUniqueId());
    }

    public SelectionSession finishSelection(Player p) {
        return sessions.remove(p.getUniqueId());
    }

    // ---------- Portal CRUD ----------

    public boolean exists(String name) {
        return portals.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public Portal get(String name) {
        return portals.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Portal> all() {
        return portals.values();
    }

    public void createPortal(String name, String world, Set<Long> blocks, PortalType type) {
        String key = name.toLowerCase(Locale.ROOT);
        Portal portal = new Portal(key, world, blocks, type);
        portals.put(key, portal);
        indexPortal(portal);
        save();
    }

    public void setType(String name, PortalType type) {
        Portal portal = get(name);
        if (portal != null) {
            portal.setType(type);
            save();
        }
    }

    public void setDestination(String name, Location loc) {
        Portal portal = get(name);
        if (portal != null) {
            portal.setDestination(loc);
            save();
        }
    }

    public boolean removePortal(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Portal portal = portals.remove(key);
        if (portal == null) return false;
        unindexPortal(portal);
        save();
        return true;
    }

    // ---------- Fast lookup used by the teleport listener ----------

    /**
     * Returns the list of portal names whose region contains this block,
     * or an empty list if none. Uses a two-level map for O(1) average lookup
     * and to avoid scanning every portal every tick.
     */
    public List<String> getPortalsAt(String world, int x, int y, int z) {
        Map<Long, List<String>> byBlock = worldBlockToPortals.get(world);
        if (byBlock == null) return Collections.emptyList();
        long key = Portal.pack(x, y, z);
        List<String> names = byBlock.get(key);
        return names == null ? Collections.emptyList() : names;
    }

    private void indexPortal(Portal portal) {
        String world = portal.getWorld();
        worldBlockIndex.computeIfAbsent(world, w -> ConcurrentHashMap.newKeySet()).addAll(portal.getBlocks());
        Map<Long, List<String>> byBlock = worldBlockToPortals.computeIfAbsent(world, w -> new ConcurrentHashMap<>());
        for (Long b : portal.getBlocks()) {
            byBlock.computeIfAbsent(b, k -> new ArrayList<>()).add(portal.getName());
        }
    }

    private void unindexPortal(Portal portal) {
        String world = portal.getWorld();
        Map<Long, List<String>> byBlock = worldBlockToPortals.get(world);
        if (byBlock == null) return;
        for (Long b : portal.getBlocks()) {
            List<String> list = byBlock.get(b);
            if (list != null) {
                list.remove(portal.getName());
                if (list.isEmpty()) byBlock.remove(b);
            }
        }
    }

    // ---------- Persistence ----------

    public void load() {
        portals.clear();
        worldBlockIndex.clear();
        worldBlockToPortals.clear();

        if (!file.exists()) return;

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        if (!cfg.isConfigurationSection("portals")) return;

        for (String key : cfg.getConfigurationSection("portals").getKeys(false)) {
            String path = "portals." + key;
            String world = cfg.getString(path + ".world");
            String typeStr = cfg.getString(path + ".type", "ALL");
            PortalType type = PortalType.fromString(typeStr);
            if (type == null) type = PortalType.ALL;

            List<String> packed = cfg.getStringList(path + ".blocks");
            Set<Long> blocks = new HashSet<>();
            for (String s : packed) {
                try {
                    blocks.add(Long.parseLong(s));
                } catch (NumberFormatException ignored) {
                }
            }

            Portal portal = new Portal(key, world, blocks, type);

            if (cfg.isSet(path + ".destWorld")) {
                String dw = cfg.getString(path + ".destWorld");
                double dx = cfg.getDouble(path + ".destX");
                double dy = cfg.getDouble(path + ".destY");
                double dz = cfg.getDouble(path + ".destZ");
                float dyaw = (float) cfg.getDouble(path + ".destYaw");
                float dpitch = (float) cfg.getDouble(path + ".destPitch");
                World w = plugin.getServer().getWorld(dw);
                if (w != null) {
                    portal.setDestination(new Location(w, dx, dy, dz, dyaw, dpitch));
                } else {
                    // world may not be loaded yet at this point; store raw via reflection-free manual set
                    portal.setDestination(new Location(plugin.getServer().getWorlds().get(0), dx, dy, dz, dyaw, dpitch));
                }
            }

            portals.put(key, portal);
            indexPortal(portal);
        }

        plugin.getLogger().info("Loaded " + portals.size() + " portal(s).");
    }

    public void save() {
        FileConfiguration cfg = new YamlConfiguration();

        for (Portal portal : portals.values()) {
            String path = "portals." + portal.getName();
            cfg.set(path + ".world", portal.getWorld());
            cfg.set(path + ".type", portal.getType().name());

            List<String> packed = new ArrayList<>();
            for (Long b : portal.getBlocks()) {
                packed.add(String.valueOf(b));
            }
            cfg.set(path + ".blocks", packed);

            if (portal.isDestSet()) {
                Location d = portal.getDestination();
                if (d != null) {
                    cfg.set(path + ".destWorld", d.getWorld().getName());
                    cfg.set(path + ".destX", d.getX());
                    cfg.set(path + ".destY", d.getY());
                    cfg.set(path + ".destZ", d.getZ());
                    cfg.set(path + ".destYaw", (double) d.getYaw());
                    cfg.set(path + ".destPitch", (double) d.getPitch());
                }
            }
        }

        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save portals.yml: " + e.getMessage());
        }
    }
}
