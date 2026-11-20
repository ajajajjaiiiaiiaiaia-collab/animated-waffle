package com.stark.sportal;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SPortalPlugin extends JavaPlugin {

    private PortalManager portalManager;

    // holds a finished (but not yet created) selection per player, between
    // "/s portal finish" and "/s portal create <name> <type>"
    private final Map<UUID, SelectionSession> pendingSelections = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        portalManager = new PortalManager(this);
        portalManager.load();

        SCommand sCommand = new SCommand(this, portalManager);
        getCommand("s").setExecutor(sCommand);
        getCommand("s").setTabCompleter(sCommand);

        getServer().getPluginManager().registerEvents(new PortalListener(this, portalManager), this);

        getLogger().info("SPortal enabled.");
    }

    @Override
    public void onDisable() {
        if (portalManager != null) {
            portalManager.save();
        }
        getLogger().info("SPortal disabled.");
    }

    public PortalManager getPortalManager() {
        return portalManager;
    }

    public Map<UUID, SelectionSession> getPendingSelections() {
        return pendingSelections;
    }
}
