package com.stark.pearlbounce;

import org.bukkit.ChatColor;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PearlBouncePlugin extends JavaPlugin implements Listener {

    private boolean enabled;
    private int maxBounces;
    private double restitution;
    private double minSpeed;
    private double clutchRadius;
    private int clutchTicks;
    private boolean walls, floor, ceiling, sound, particles;
    private Set<String> disabledWorlds = new HashSet<>();

    private final Map<UUID, Integer> bounces = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        load();
        getServer().getPluginManager().registerEvents(this, this);
        // تنظيف العدّادات لبيرلات اختفت
        getServer().getScheduler().runTaskTimer(this, () ->
                bounces.keySet().removeIf(id -> getServer().getEntity(id) == null), 200L, 200L);
    }

    private void load() {
        reloadConfig();
        enabled = getConfig().getBoolean("enabled", true);
        maxBounces = getConfig().getInt("max-bounces", 2);
        restitution = getConfig().getDouble("restitution", 0.7);
        minSpeed = getConfig().getDouble("min-speed", 0.25);
        clutchRadius = getConfig().getDouble("clutch.radius", 6.0);
        clutchTicks = getConfig().getInt("clutch.min-flight-ticks", 10);
        walls = getConfig().getBoolean("bounce.walls", true);
        floor = getConfig().getBoolean("bounce.floor", false);
        ceiling = getConfig().getBoolean("bounce.ceiling", true);
        disabledWorlds = new HashSet<>(getConfig().getStringList("disabled-worlds"));
        sound = getConfig().getBoolean("effects.sound", true);
        particles = getConfig().getBoolean("effects.particles", true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("pearlbounce.admin")) return true;
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            load();
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    getConfig().getString("messages.reloaded", "&aReloaded.")));
        } else {
            sender.sendMessage(ChatColor.YELLOW + "/pearlbounce reload");
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(ProjectileHitEvent e) {
        if (!enabled) return;
        if (!(e.getEntity() instanceof EnderPearl pearl)) return;
        if (e.getHitEntity() != null) return;
        BlockFace face = e.getHitBlockFace();
        if (e.getHitBlock() == null || face == null) return;
        if (disabledWorlds.contains(pearl.getWorld().getName())) return;

        ProjectileSource src = pearl.getShooter();
        if (src instanceof Player p && p.hasPermission("pearlbounce.bypass")) return;

        // Pearl clutch: a pearl that lands close to the thrower (or hits very early) must teleport instantly, never bounce.
        if (src instanceof Player thrower) {
            if (pearl.getTicksLived() < clutchTicks) return;
            if (thrower.getWorld().equals(pearl.getWorld())
                    && thrower.getLocation().distanceSquared(pearl.getLocation()) <= clutchRadius * clutchRadius) return;
        }

        boolean vertical = face == BlockFace.UP || face == BlockFace.DOWN;
        if (face == BlockFace.UP && !floor) return;
        if (face == BlockFace.DOWN && !ceiling) return;
        if (!vertical && !walls) return;

        int used = bounces.getOrDefault(pearl.getUniqueId(), 0);
        if (used >= maxBounces) return;

        Vector n = face.getDirection();
        Vector v = pearl.getVelocity();
        double dot = v.dot(n);
        if (dot >= 0) return; // ما تتجه للسطح فعلاً
        Vector reflected = v.clone().subtract(n.clone().multiply(2 * dot)).multiply(restitution);
        if (reflected.length() < minSpeed) return;

        bounces.put(pearl.getUniqueId(), used + 1);
        e.setCancelled(true);
        pearl.teleport(pearl.getLocation().add(n.clone().multiply(0.35)));
        pearl.setVelocity(reflected);

        if (sound) pearl.getWorld().playSound(pearl.getLocation(), Sound.ENTITY_SLIME_SQUISH_SMALL, 0.8f, 1.4f);
        if (particles) pearl.getWorld().spawnParticle(Particle.PORTAL, pearl.getLocation(), 10, 0.1, 0.1, 0.1, 0.4);
    }
}
