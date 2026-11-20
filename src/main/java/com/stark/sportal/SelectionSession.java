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

    public int size() {
        return blocks.size();
    }
}
