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
    private static final long COOLDOWN_MS = 250;

    public PortalListener(SPortalPlugin plugin, PortalManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    private static final int MAX_BOX = 100000;

    private void single(Player p, SelectionSession session, org.bukkit.block.Block b) {
        plugin.getHighlighter().toggle(p, b.getWorld(), b.getX(), b.getY(), b.getZ(), session.getBlocks());
        p.sendActionBar(net.kyori.adventure.text.Component.text(
                "§aSelected blocks: §f" + session.size() + " §7for portal '" + session.getPortalName() + "'"));
    }

    private void corner(Player p, SelectionSession session, org.bukkit.block.Block b) {
        int[] c = session.getCorner();
        if (c == null) {
            session.setCorner(new int[]{b.getX(), b.getY(), b.getZ()});
            plugin.getHighlighter().cornerMark(p, b.getWorld(), b.getX(), b.getY(), b.getZ());
            p.sendActionBar(net.kyori.adventure.text.Component.text("§eCorner 1 set. Click the opposite corner."));
            return;
        }
        long vol = (long) (Math.abs(b.getX() - c[0]) + 1) * (Math.abs(b.getY() - c[1]) + 1) * (Math.abs(b.getZ() - c[2]) + 1);
        if (vol > MAX_BOX) {
            session.setCorner(null);
            plugin.getHighlighter().clearCorner(p);
            p.sendMessage("§cBox too big (" + vol + " blocks, max " + MAX_BOX + "). Corner reset.");
            return;
        }
        plugin.getHighlighter().addBox(p, b.getWorld(), c[0], c[1], c[2], b.getX(), b.getY(), b.getZ(), session.getBlocks());
        session.setCorner(null);
        plugin.getHighlighter().clearCorner(p);
        p.sendActionBar(net.kyori.adventure.text.Component.text(
                "§aBox filled. Total blocks: §f" + session.size() + " §7for portal '" + session.getPortalName() + "'"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onSelectionHit(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!manager.hasSession(p)) return;
        SelectionSession session = manager.getSession(p);
        if (!session.getWorld().equals(e.getBlock().getWorld().getName())) return;
        e.setCancelled(true);
        single(p, session, e.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSelectionInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!manager.hasSession(p)) return;
        if (e.getClickedBlock() == null) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        SelectionSession session = manager.getSession(p);
        if (!session.getWorld().equals(e.getClickedBlock().getWorld().getName())) return;
        e.setCancelled(true);
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (session.isBoxMode()) corner(p, session, e.getClickedBlock());
        else single(p, session, e.getClickedBlock());
    }

    // ---------- Activation: movement covers walk / touch / jump / collision in one check ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        plugin.getHighlighter().clear(e.getPlayer());
        plugin.getPendingTp().remove(e.getPlayer().getUniqueId());
        plugin.getPendingSelections().remove(e.getPlayer().getUniqueId());
        manager.cancelSelection(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        check(e.getPlayer(), e.getFrom(), e.getTo());
    }

    private Portal scan(Location from, Location to) {
        String world = to.getWorld().getName();
        double dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = (from.getWorld() == to.getWorld()) ? (int) Math.min(40, Math.ceil(len / 0.25)) : 0;
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 1.0 : (double) i / steps;
            double px = from.getX() + dx * t, py = from.getY() + dy * t, pz = from.getZ() + dz * t;
            int x0 = (int) Math.floor(px - 0.35), x1 = (int) Math.floor(px + 0.35);
            int z0 = (int) Math.floor(pz - 0.35), z1 = (int) Math.floor(pz + 0.35);
            int y0 = (int) Math.floor(py - 0.2), y1 = (int) Math.floor(py + 1.9);
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++)
                    for (int y = y0; y <= y1; y++) {
                        Portal pt = checkCell(world, x, y, z);
                        if (pt != null) return pt;
                    }
        }
        return null;
    }

    public void check(Player p, Location to) {
        check(p, to, to);
    }

    public void check(Player p, Location from, Location to) {
        if (manager.hasSession(p)) return;
        if (to.getWorld() == null) return;

        Long last = lastTeleport.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (last != null && now - last < COOLDOWN_MS) return;

        Portal matched = scan(from, to);
        if (matched == null) return;
        if (!matched.isDestSet()) return;

        Location dest = matched.getDestination();
        if (dest == null) return;

        UUID id = p.getUniqueId();
        if (plugin.getDelayTicks() <= 0) {
            lastTeleport.put(id, now);
            if (p.isInsideVehicle()) p.leaveVehicle();
            p.teleport(dest);
            return;
        }
        if (!plugin.getPendingTp().add(id)) return;
        final String portalName = matched.getName();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            plugin.getPendingTp().remove(id);
            if (!p.isOnline()) return;
            Location now2 = p.getLocation();
            Portal still = scan(now2, now2);
            if (still == null || !still.getName().equals(portalName)) return;
            Location d2 = still.getDestination();
            if (d2 == null) return;
            lastTeleport.put(id, System.currentTimeMillis());
            if (p.isInsideVehicle()) p.leaveVehicle();
            p.teleport(d2);
        }, plugin.getDelayTicks());
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
