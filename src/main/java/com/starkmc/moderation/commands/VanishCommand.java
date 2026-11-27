package com.starkmc.moderation.commands;

import com.starkmc.moderation.ModerationPlugin;
import com.starkmc.moderation.util.SoundUtil;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class VanishCommand implements CommandExecutor, TabCompleter, Listener {
   private static final String PERM = "starkmod.vanish";
   private static final String PERM_SEE = "starkmod.vanish.see";
   private final ModerationPlugin plugin;
   private final MiniMessage mm = MiniMessage.miniMessage();
   private final Map<UUID, Location> vanished = new ConcurrentHashMap<>();

   public VanishCommand(ModerationPlugin plugin) {
      this.plugin = plugin;
      var cfg = plugin.getConfig();
      cfg.addDefault("vanish.actionbar", "<#7CFC00><bold>Vanish on");
      cfg.addDefault("vanish.enabled", "<green>✔ Vanish enabled. You are now hidden.");
      cfg.addDefault("vanish.disabled", "<red>✘ Vanish disabled. You are visible again.");
      cfg.addDefault("vanish.already-on", "<yellow>Vanish is already on.");
      cfg.addDefault("vanish.already-off", "<yellow>Vanish is already off.");
      cfg.addDefault("vanish.usage", "<red>Usage: /vanish [on|off]");
      cfg.addDefault("vanish.players-only", "<red>This command is for players only.");
      cfg.addDefault("sounds.vanish-on.enabled", true);
      cfg.addDefault("sounds.vanish-on.sound", "entity.enderman.teleport");
      cfg.addDefault("sounds.vanish-on.volume", 0.8);
      cfg.addDefault("sounds.vanish-on.pitch", 0.7);
      cfg.addDefault("sounds.vanish-off.enabled", true);
      cfg.addDefault("sounds.vanish-off.sound", "block.beacon.activate");
      cfg.addDefault("sounds.vanish-off.volume", 0.8);
      cfg.addDefault("sounds.vanish-off.pitch", 1.4);
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
      Bukkit.getScheduler().runTaskTimer(plugin, () -> {
         if (!this.vanished.isEmpty()) {
            var bar = this.mm.deserialize(plugin.getConfig().getString("vanish.actionbar", "Vanish on"));
            for (UUID id : this.vanished.keySet()) {
               Player p = Bukkit.getPlayer(id);
               if (p != null) {
                  p.sendActionBar(bar);
               }
            }
         }
      }, 20L, 20L);
   }

   public boolean isVanished(Player p) {
      return this.vanished.containsKey(p.getUniqueId());
   }

   public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
      if (!(sender instanceof Player p)) {
         this.send(sender, "vanish.players-only");
         return true;
      } else if (!p.hasPermission(PERM)) {
         sender.sendMessage(this.mm.deserialize("<red>You don't have permission."));
         return true;
      } else {
         boolean isOn = this.isVanished(p);
         boolean want = !isOn;
         if (args.length > 0) {
            if (args[0].equalsIgnoreCase("on")) {
               want = true;
            } else {
               if (!args[0].equalsIgnoreCase("off")) {
                  this.send(sender, "vanish.usage");
                  return true;
               }

               want = false;
            }
         }

         if (want == isOn) {
            this.send(sender, want ? "vanish.already-on" : "vanish.already-off");
         } else if (want) {
            this.enable(p);
         } else {
            this.disable(p, true, true);
         }

         return true;
      }
   }

   private void enable(Player p) {
      this.vanished.put(p.getUniqueId(), p.getLocation().clone());

      for (Player o : Bukkit.getOnlinePlayers()) {
         if (!o.equals(p) && !o.hasPermission(PERM_SEE)) {
            o.hidePlayer(this.plugin, p);
         }
      }

      p.setAllowFlight(true);
      p.setFlying(true);
      p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false, false));
      p.setCollidable(false);
      p.setSleepingIgnored(true);
      SoundUtil.play(p, this.plugin.getConfig(), "vanish-on");
      this.send(p, "vanish.enabled");
   }

   private void disable(Player p, boolean teleportBack, boolean notify) {
      Location back = this.vanished.remove(p.getUniqueId());

      for (Player o : Bukkit.getOnlinePlayers()) {
         if (!o.equals(p)) {
            o.showPlayer(this.plugin, p);
         }
      }

      p.removePotionEffect(PotionEffectType.NIGHT_VISION);
      p.setCollidable(true);
      p.setSleepingIgnored(false);
      if (teleportBack && back != null) {
         p.teleport(back);
      }

      GameMode gm = p.getGameMode();
      if (gm != GameMode.CREATIVE && gm != GameMode.SPECTATOR) {
         p.setFlying(false);
         p.setAllowFlight(false);
      }

      p.setFallDistance(0.0F);
      if (notify) {
         SoundUtil.play(p, this.plugin.getConfig(), "vanish-off");
         this.send(p, "vanish.disabled");
      }
   }

   public void disableAll() {
      for (UUID id : List.copyOf(this.vanished.keySet())) {
         Player p = Bukkit.getPlayer(id);
         if (p != null) {
            this.disable(p, true, false);
         } else {
            this.vanished.remove(id);
         }
      }
   }

   private void send(CommandSender to, String key) {
      to.sendMessage(this.mm.deserialize(this.plugin.getConfig().getString(key, "")));
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      Player joiner = e.getPlayer();
      if (!joiner.hasPermission(PERM_SEE)) {
         for (UUID id : this.vanished.keySet()) {
            Player v = Bukkit.getPlayer(id);
            if (v != null) {
               joiner.hidePlayer(this.plugin, v);
            }
         }
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      Player p = e.getPlayer();
      if (this.isVanished(p)) {
         e.quitMessage(null);
         this.disable(p, true, false);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onBreak(BlockBreakEvent e) {
      if (this.isVanished(e.getPlayer())) {
         e.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onPlace(BlockPlaceEvent e) {
      if (this.isVanished(e.getPlayer())) {
         e.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onAttack(EntityDamageByEntityEvent e) {
      Entity damager = e.getDamager();
      if (damager instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) {
         damager = shooter;
      }

      if (damager instanceof Player p && this.isVanished(p)) {
         e.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onInteract(PlayerInteractEvent e) {
      if (e.getAction() == Action.PHYSICAL && this.isVanished(e.getPlayer())) {
         e.setCancelled(true);
         e.setUseInteractedBlock(Event.Result.DENY);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onBucketEmpty(PlayerBucketEmptyEvent e) {
      if (this.isVanished(e.getPlayer())) {
         e.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onBucketFill(PlayerBucketFillEvent e) {
      if (this.isVanished(e.getPlayer())) {
         e.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onHangingBreak(HangingBreakByEntityEvent e) {
      if (e.getRemover() instanceof Player p && this.isVanished(p)) {
         e.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onPickup(PlayerAttemptPickupItemEvent e) {
      if (this.isVanished(e.getPlayer())) {
         e.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onMobTarget(EntityTargetLivingEntityEvent e) {
      if (e.getTarget() instanceof Player p && this.isVanished(p)) {
         e.setCancelled(true);
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
      return args.length == 1 ? List.of("on", "off").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList() : List.of();
   }
}
