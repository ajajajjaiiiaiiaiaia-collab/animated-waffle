package com.stark.sportal;

import org.bukkit.World;

import java.util.HashSet;
import java.util.Set;

public class SelectionSession {
    private final String portalName;
    private final String world;
    private final Set<Long> blocks = new HashSet<>();

    public SelectionSession(String portalName, World world) {
        this.portalName = portalName;
        this.world = world.getName();
    }

    public String getPortalName() {
        return portalName;
    }

    public String getWorld() {
        return world;
    }

    public Set<Long> getBlocks() {
        return blocks;
    }

    public void addBlock(int x, int y, int z) {
        blocks.add(Portal.pack(x, y, z));
    }

    private boolean boxMode = false;
    private int[] corner = null;

    public boolean isBoxMode() { return boxMode; }
    public void setBoxMode(boolean b) { this.boxMode = b; }
    public int[] getCorner() { return corner; }
    public void setCorner(int[] c) { this.corner = c; }

    public int size() {
        return blocks.size();
    }
}
