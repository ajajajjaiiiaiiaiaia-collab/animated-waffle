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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Highlighter {

    private static final int BLUE = 0x33DDFF;
    private static final int RED = 0xFF4444;
    private static final int YELLOW = 0xFFDD33;

    private static class Hole {
        BlockDisplay d;
        int x, y, z;
    }

    private static class State {
        final Map<Long, BlockDisplay> singles = new HashMap<>();
        final Map<Long, Hole> holes = new HashMap<>();
        final List<BlockDisplay> boxes = new ArrayList<>();
        BlockDisplay corner;
    }

    private final SPortalPlugin plugin;
    private final Map<UUID, State> states = new HashMap<>();

    public Highlighter(SPortalPlugin plugin) {
        this.plugin = plugin;
    }

    private State st(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new State());
    }

    private BlockDisplay spawn(Player p, World w, double x, double y, double z,
                               double sx, double sy, double sz, Material m, int color) {
        BlockDisplay d = w.spawn(new Location(w, x, y, z), BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setTransformation(new Transformation(new Vector3f(-0.01f, -0.01f, -0.01f), new Quaternionf(),
                    new Vector3f((float) sx + 0.02f, (float) sy + 0.02f, (float) sz + 0.02f), new Quaternionf()));
            e.setGlowing(true);
            e.setGlowColorOverride(Color.fromRGB(color));
            e.setPersistent(false);
            e.setVisibleByDefault(false);
        });
        p.showEntity(plugin, d);
        return d;
    }

    /** Toggle one block. Returns true if it is now selected. */
    public boolean toggle(Player p, World w, int x, int y, int z, Set<Long> blocks) {
        State s = st(p);
        long key = Portal.pack(x, y, z);
        if (blocks.contains(key)) {
            blocks.remove(key);
            BlockDisplay old = s.singles.remove(key);
            if (old != null) {
                old.remove();
            } else {
                Hole h = new Hole();
                h.x = x; h.y = y; h.z = z;
                h.d = spawn(p, w, x, y, z, 1, 1, 1, Material.RED_STAINED_GLASS, RED);
                s.holes.put(key, h);
            }
            return false;
        }
        blocks.add(key);
        Hole h = s.holes.remove(key);
        if (h != null) {
            h.d.remove();
        } else {
            s.singles.put(key, spawn(p, w, x, y, z, 1, 1, 1, Material.LIGHT_BLUE_STAINED_GLASS, BLUE));
        }
        return true;
    }

    public void cornerMark(Player p, World w, int x, int y, int z) {
        State s = st(p);
        clearCorner(p);
        s.corner = spawn(p, w, x, y, z, 1, 1, 1, Material.YELLOW_STAINED_GLASS, YELLOW);
    }

    public void clearCorner(Player p) {
        State s = states.get(p.getUniqueId());
        if (s != null && s.corner != null) {
            s.corner.remove();
            s.corner = null;
        }
    }

    /** Fills the box between two corners (inclusive). Returns number of blocks added. */
    public int addBox(Player p, World w, int x0, int y0, int z0, int x1, int y1, int z1, Set<Long> blocks) {
        State s = st(p);
        int ax = Math.min(x0, x1), bx = Math.max(x0, x1);
        int ay = Math.min(y0, y1), by = Math.max(y0, y1);
        int az = Math.min(z0, z1), bz = Math.max(z0, z1);
        int added = 0;
        for (int x = ax; x <= bx; x++)
            for (int y = ay; y <= by; y++)
                for (int z = az; z <= bz; z++) {
                    if (blocks.add(Portal.pack(x, y, z))) added++;
                }
        var it = s.holes.entrySet().iterator();
        while (it.hasNext()) {
            Hole h = it.next().getValue();
            if (h.x >= ax && h.x <= bx && h.y >= ay && h.y <= by && h.z >= az && h.z <= bz) {
                h.d.remove();
                it.remove();
            }
        }
        s.boxes.add(spawn(p, w, ax, ay, az, bx - ax + 1, by - ay + 1, bz - az + 1,
                Material.LIGHT_BLUE_STAINED_GLASS, BLUE));
        return added;
    }

    public void clear(Player p) {
        clear(p.getUniqueId());
    }

    public void clear(UUID id) {
        State s = states.remove(id);
        if (s == null) return;
        s.singles.values().forEach(BlockDisplay::remove);
        s.holes.values().forEach(h -> h.d.remove());
        s.boxes.forEach(BlockDisplay::remove);
        if (s.corner != null) s.corner.remove();
    }

    public void clearAll() {
        for (UUID id : new ArrayList<>(states.keySet())) clear(id);
    }
}
