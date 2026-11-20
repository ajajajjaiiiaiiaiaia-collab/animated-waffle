package com.stark.sportal;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class PortalListener implements Listener {

    private final SPortalPlugin plugin;
    private final PortalManager manager;

    // simple per-player cooldown (in ticks via System.currentTimeMillis) to avoid repeat-teleport spam
    // on the same block while standing/jumping in place. Short enough to feel instant, long enough
    // to prevent re-trigger loops if destination lands back near the source.
    private final Map<UUID, Long> lastTeleport = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 500;

    public PortalListener(SPortalPlugin plugin, PortalManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ---------- Selection: left-click (break) marks a block during startselect ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onSelectionHit(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!manager.hasSession(p)) return;

        SelectionSession session = manager.getSession(p);
        if (!session.getWorld().equals(e.getBlock().getWorld().getName())) {
            return;
        }

        e.setCancelled(true); // never actually break the block during selection

        session.addBlock(e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ());
        p.sendActionBar(net.kyori.adventure.text.Component.text(
                "§aSelected block §f#" + session.size() + " §7for portal '" + session.getPortalName() + "'"));
    }

    // Right-click as an alternate, non-destructive way to mark a block during selection.
    @EventHandler(priority = EventPriority.HIGH)
    public void onSelectionInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!manager.hasSession(p)) return;
        if (e.getClickedBlock() == null) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        SelectionSession session = manager.getSession(p);
        if (!session.getWorld().equals(e.getClickedBlock().getWorld().getName())) {
            return;
        }

        e.setCancelled(true);

        session.addBlock(e.getClickedBlock().getX(), e.getClickedBlock().getY(), e.getClickedBlock().getZ());
        p.sendActionBar(net.kyori.adventure.text.Component.text(
                "§aSelected block §f#" + session.size() + " §7for portal '" + session.getPortalName() + "'"));
    }

    // ---------- Activation: movement covers walk / touch / jump / collision in one check ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        check(e.getPlayer(), e.getTo());
    }

    public void check(Player p, Location to) {
        if (manager.hasSession(p)) return;
        if (to.getWorld() == null) return;

        Long last = lastTeleport.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (last != null && now - last < COOLDOWN_MS) return;

        String world = to.getWorld().getName();
        int x = to.getBlockX();
        int yFeet = to.getBlockY();
        int z = to.getBlockZ();

        Portal matched = findMatch(world, x, yFeet, yFeet - 1, yFeet + 1, z);
        if (matched == null) return;
        if (!matched.isDestSet()) return;

        Location dest = matched.getDestination();
        if (dest == null) return;

        lastTeleport.put(p.getUniqueId(), now);
        p.teleport(dest);
    }

    private Portal findMatch(String world, int x, int yFeet, int yBelow, int yHead, int z) {
        Portal portal = checkCell(world, x, yFeet, z);
        if (portal != null) return portal;
        portal = checkCell(world, x, yBelow, z);
        if (portal != null) return portal;
        portal = checkCell(world, x, yHead, z);
        return portal;
    }

    private Portal checkCell(String world, int x, int y, int z) {
        List<String> names = manager.getPortalsAt(world, x, y, z);
        if (names.isEmpty()) return null;
        for (String name : names) {
            Portal portal = manager.get(name);
            if (portal != null) {
                return portal; // type is ALL/COLLISION/TOUCH/JUMP but detection is unified by design
            }
        }
        return null;
    }
}
