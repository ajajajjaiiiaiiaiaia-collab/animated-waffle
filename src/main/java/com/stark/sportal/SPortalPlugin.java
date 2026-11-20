package com.stark.sportal;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SPortalPlugin extends JavaPlugin {

    private PortalManager portalManager;
    private Highlighter highlighter;
    private long delayTicks = 10;
    private final java.util.Set<UUID> pendingTp = ConcurrentHashMap.newKeySet();

    // holds a finished (but not yet created) selection per player, between
    // "/s portal finish" and "/s portal create <name> <type>"
    private final Map<UUID, SelectionSession> pendingSelections = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        saveDefaultConfig();
        delayTicks = Math.max(0, getConfig().getLong("teleport-delay-ticks", 0));
        highlighter = new Highlighter(this);
        portalManager = new PortalManager(this);
        portalManager.load();

        SCommand sCommand = new SCommand(this, portalManager);
        getCommand("s").setExecutor(sCommand);
        getCommand("s").setTabCompleter(sCommand);

        PortalListener listener = new PortalListener(this, portalManager);
        getServer().getPluginManager().registerEvents(listener, this);
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (org.bukkit.entity.Player pl : getServer().getOnlinePlayers()) {
                listener.check(pl, pl.getLocation());
            }
        }, 10L, 2L);

        getLogger().info("SPortal enabled.");
    }

    @Override
    public void onDisable() {
        if (highlighter != null) highlighter.clearAll();
        if (portalManager != null) {
            portalManager.save();
        }
        getLogger().info("SPortal disabled.");
    }

    public Highlighter getHighlighter() { return highlighter; }
    public long getDelayTicks() { return delayTicks; }
    public java.util.Set<UUID> getPendingTp() { return pendingTp; }

    public PortalManager getPortalManager() {
        return portalManager;
    }

    public Map<UUID, SelectionSession> getPendingSelections() {
        return pendingSelections;
    }
}
