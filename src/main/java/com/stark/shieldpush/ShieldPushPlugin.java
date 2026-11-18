package com.stark.shieldpush;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ShieldPushPlugin extends JavaPlugin implements Listener {

    private boolean enabled;
    private boolean requireAxe;
    private double horizontal;
    private double vertical;
    private boolean additive;
    private int internalCooldown;
    private Set<String> disabledWorlds = new HashSet<>();
    private boolean sound;
    private boolean particles;

    private final Map<UUID, Integer> lastPushTick = new HashMap<>();
    private int tickCounter;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, () -> tickCounter++, 1L, 1L);
    }

    private void load() {
        reloadConfig();
        enabled = getConfig().getBoolean("enabled", true);
        requireAxe = getConfig().getBoolean("require-axe", true);
        horizontal = getConfig().getDouble("push.horizontal", 0.9);
        vertical = getConfig().getDouble("push.vertical", 0.35);
        additive = getConfig().getBoolean("push.additive", false);
        internalCooldown = getConfig().getInt("internal-cooldown-ticks", 10);
        disabledWorlds = new HashSet<>(getConfig().getStringList("disabled-worlds"));
        sound = getConfig().getBoolean("effects.sound", true);
        particles = getConfig().getBoolean("effects.particles", true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("shieldpush.admin")) return true;
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            load();
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    getConfig().getString("messages.reloaded", "&aReloaded.")));
        } else {
            sender.sendMessage(ChatColor.YELLOW + "/shieldpush reload");
        }
        return true;
    }

    private static boolean isAxe(ItemStack item) {
        return item != null && item.getType().name().endsWith("_AXE");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!enabled) return;
        if (!(e.getEntity() instanceof Player victim)) return;
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (disabledWorlds.contains(victim.getWorld().getName())) return;
        if (victim.hasPermission("shieldpush.bypass")) return;
        if (!victim.isBlocking()) return;
        if (requireAxe && !isAxe(attacker.getInventory().getItemInMainHand())) return;

        // الدرع يتعطل داخل نفس الضربة؛ نتحقق بعدها بتك إن صار عليه كول داون فعلاً.
        final boolean wasOnCooldown = victim.hasCooldown(Material.SHIELD);
        if (wasOnCooldown) return;
        getServer().getScheduler().runTask(this, () -> {
            if (!victim.isOnline() || !attacker.isOnline()) return;
            if (!victim.hasCooldown(Material.SHIELD)) return;
            push(attacker, victim);
        });
    }

    private void push(Player attacker, Player victim) {
        Integer last = lastPushTick.get(victim.getUniqueId());
        if (last != null && tickCounter - last < internalCooldown) return;
        lastPushTick.put(victim.getUniqueId(), tickCounter);

        Vector dir = victim.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() < 1.0E-6) dir = attacker.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-6) dir = new Vector(0, 0, 1);
        dir.normalize().multiply(horizontal).setY(vertical);

        Vector v = additive ? victim.getVelocity().add(dir) : dir;
        victim.setVelocity(v);

        if (sound) victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_SHIELD_BREAK, 1f, 1f);
        if (particles) victim.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, 1, 0), 12, 0.3, 0.4, 0.3, 0.1);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastPushTick.remove(e.getPlayer().getUniqueId());
    }
}
