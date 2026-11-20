package com.stark.sportal;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.HashSet;
import java.util.Set;

public class Portal {

    private final String name;
    private final String world;
    private final Set<Long> blocks; // packed block coordinates for O(1) lookup
    private PortalType type;

    // destination
    private String destWorld;
    private double destX, destY, destZ;
    private float destYaw, destPitch;
    private boolean destSet = false;

    public Portal(String name, String world, Set<Long> blocks, PortalType type) {
        this.name = name;
        this.world = world;
        this.blocks = new HashSet<>(blocks);
        this.type = type;
    }

    public static long pack(int x, int y, int z) {
        // pack into a long: 26 bits x, 12 bits y, 26 bits z (enough range for normal worlds)
        long lx = ((long) (x) & 0x3FFFFFFL) << 38;
        long ly = ((long) (y) & 0xFFFL) << 26;
        long lz = ((long) (z) & 0x3FFFFFFL);
        return lx | ly | lz;
    }

    public String getName() {
        return name;
    }

    public String getWorld() {
        return world;
    }

    public Set<Long> getBlocks() {
        return blocks;
    }

    public PortalType getType() {
        return type;
    }

    public void setType(PortalType type) {
        this.type = type;
    }

    public boolean isDestSet() {
        return destSet;
    }

    public void setDestination(Location loc) {
        this.destWorld = loc.getWorld().getName();
        this.destX = loc.getX();
        this.destY = loc.getY();
        this.destZ = loc.getZ();
        this.destYaw = loc.getYaw();
        this.destPitch = loc.getPitch();
        this.destSet = true;
    }

    public Location getDestination() {
        if (!destSet) return null;
        World w = org.bukkit.Bukkit.getWorld(destWorld);
        if (w == null) return null;
        return new Location(w, destX, destY, destZ, destYaw, destPitch);
    }

    public boolean containsBlock(int x, int y, int z) {
        return blocks.contains(pack(x, y, z));
    }
}
