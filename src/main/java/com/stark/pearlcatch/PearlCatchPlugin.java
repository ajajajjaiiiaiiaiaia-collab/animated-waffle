package com.stark.pearlcatch;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PearlCatchPlugin extends JavaPlugin implements Listener {

    private boolean enabled, ownOnly;
    private double radius, damage;
    private Set<String> disabledWorlds = new HashSet<>();

    /** Wind charges in flight -> position on the previous tick. */
    private final Map<UUID, Location> tracked = new HashMap<>();
    /** Ender pearls in flight (only these are ever checked, so there are no world-wide entity searches). */
    private final Set<UUID> pearls = new HashSet<>();
    private BukkitTask task;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        load();
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void load() {
        reloadConfig();
        enabled = getConfig().getBoolean("enabled", true);
        radius = getConfig().getDouble("assist-radius", 2.5);
        damage = getConfig().getDouble("teleport-damage", 0.0);
        ownOnly = getConfig().getBoolean("own-only", true);
        disabledWorlds = new HashSet<>(getConfig().getStringList("disabled-worlds"));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("pearlcatch.admin")) return true;
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            load();
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    getConfig().getString("messages.reloaded", "&aReloaded.")));
        } else {
            sender.sendMessage(ChatColor.YELLOW + "/pearlcatch reload");
        }
        return true;
    }

    @EventHandler
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!enabled) return;
        if (disabledWorlds.contains(e.getEntity().getWorld().getName())) return;
        if (e.getEntity() instanceof EnderPearl pearl) {
            pearls.add(pearl.getUniqueId());
            startTask();
        } else if (e.getEntity() instanceof WindCharge wc) {
            tracked.put(wc.getUniqueId(), wc.getLocation());
            startTask();
        }
    }

    private void startTask() {
        if (task != null) return;
        task = getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
    }

    private void tick() {
        pearls.removeIf(id -> {
            Entity en = getServer().getEntity(id);
            return en == null || !en.isValid();
        });
        if (pearls.isEmpty()) {
            tracked.entrySet().removeIf(en -> !(getServer().getEntity(en.getKey()) instanceof WindCharge w) || !w.isValid());
            if (tracked.isEmpty() && task != null) {
                task.cancel();
                task = null;
            }
            return;
        }
        Iterator<Map.Entry<UUID, Location>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Location> en = it.next();
            Entity ent = getServer().getEntity(en.getKey());
            if (!(ent instanceof WindCharge wc) || !wc.isValid() || wc.isDead()) {
                it.remove();
                continue;
            }
            Location now = wc.getLocation();
            Location prev = en.getValue();
            en.setValue(now.clone());
            if (wc.getTicksLived() < 2) continue;

            Location mid = prev.getWorld() == now.getWorld() ? now.clone().add(prev).multiply(0.5) : now;
            EnderPearl hit = findPearl(wc, now, mid);
            if (hit == null) continue;

            ProjectileSource src = hit.getShooter();
            if (!(src instanceof Player p) || !p.isOnline()) continue;
            catchPearl(p, hit, wc);
            it.remove();
        }
        if (tracked.isEmpty() && pearls.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    private EnderPearl findPearl(WindCharge wc, Location now, Location mid) {
        EnderPearl best = null;
        double bestD = radius * radius;
        for (UUID id : pearls) {
            if (!(getServer().getEntity(id) instanceof EnderPearl pearl) || pearl.getWorld() != wc.getWorld()) continue;
            if (!pearl.isValid()) continue;
            if (ownOnly && !(wc.getShooter() instanceof Player owner && pearl.getShooter() instanceof Player po
                    && owner.getUniqueId().equals(po.getUniqueId()))) continue;
            double d = Math.min(pearl.getLocation().distanceSquared(now), pearl.getLocation().distanceSquared(mid));
            if (d <= bestD) {
                bestD = d;
                best = pearl;
            }
        }
        return best;
    }

    private void catchPearl(Player p, EnderPearl pearl, WindCharge wc) {
        Location dest = pearl.getLocation().clone();
        dest.setYaw(p.getLocation().getYaw());
        dest.setPitch(p.getLocation().getPitch());
        Vector keep = p.getVelocity();
        pearl.remove();
        p.teleport(dest, PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
        p.setVelocity(keep);
        p.setFallDistance(0f);
        if (damage > 0 && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
            p.damage(damage);
        }
        // Burst at the player's new position so the wind charge launches them upward.
        wc.teleport(dest);
        wc.explode();
    }
}
