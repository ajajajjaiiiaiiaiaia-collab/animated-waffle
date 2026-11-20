package com.stark.sportal;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Shows a glowing colored box on selected blocks, visible only to the selecting player. */
public class Highlighter {

    private final SPortalPlugin plugin;
    private final Map<UUID, Map<Long, BlockDisplay>> shown = new HashMap<>();

    public Highlighter(SPortalPlugin plugin) {
        this.plugin = plugin;
    }

    public void toggle(Player p, World world, int x, int y, int z, Set<Long> blocks) {
        long key = Portal.pack(x, y, z);
        Map<Long, BlockDisplay> map = shown.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        if (blocks.contains(key)) {
            blocks.remove(key);
            BlockDisplay old = map.remove(key);
            if (old != null) old.remove();
            return;
        }
        blocks.add(key);
        BlockDisplay d = world.spawn(new Location(world, x, y, z), BlockDisplay.class, e -> {
            e.setBlock(Material.LIGHT_BLUE_STAINED_GLASS.createBlockData());
            e.setTransformation(new Transformation(new Vector3f(-0.01f, -0.01f, -0.01f), new Quaternionf(),
                    new Vector3f(1.02f, 1.02f, 1.02f), new Quaternionf()));
            e.setGlowing(true);
            e.setGlowColorOverride(Color.fromRGB(0x33DDFF));
            e.setPersistent(false);
            e.setVisibleByDefault(false);
        });
        p.showEntity(plugin, d);
        map.put(key, d);
    }

    public void clear(Player p) {
        clear(p.getUniqueId());
    }

    public void clear(UUID id) {
        Map<Long, BlockDisplay> map = shown.remove(id);
        if (map == null) return;
        for (BlockDisplay d : map.values()) d.remove();
    }

    public void clearAll() {
        for (UUID id : new java.util.ArrayList<>(shown.keySet())) clear(id);
    }
}
