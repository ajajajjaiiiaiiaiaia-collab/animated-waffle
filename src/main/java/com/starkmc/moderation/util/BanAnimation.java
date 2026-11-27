package com.starkmc.moderation.util;

import com.starkmc.moderation.ModerationPlugin;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

public final class BanAnimation {
   private static final MiniMessage MM = MiniMessage.miniMessage();
   private static final Set<UUID> ACTIVE = ConcurrentHashMap.newKeySet();
   private static final Set<Display> DISPLAYS = ConcurrentHashMap.newKeySet();

   private BanAnimation() {
   }

   public static boolean isActive(Player p) {
      return ACTIVE.contains(p.getUniqueId());
   }

   public static void cleanup() {
      for (Display d : DISPLAYS) {
         d.remove();
      }
      DISPLAYS.clear();
      ACTIVE.clear();
   }

   public static void preloadHead(ModerationPlugin plugin) {
   }

   public static void play(ModerationPlugin plugin, Player target, CommandSender admin, Runnable onFinish) {
      play(plugin, target, admin.getName(), onFinish);
   }

   public static void play(ModerationPlugin plugin, Player target, String adminName, Runnable onFinish) {
      FileConfiguration cfg = plugin.getConfig();
      if (!cfg.getBoolean("ban-animation.enabled", true)) {
         onFinish.run();
         return;
      }
      if (!ACTIVE.add(target.getUniqueId())) {
         return;
      }

      int beamTicks = Math.max(10, cfg.getInt("ban-animation.beam-ticks", 30));
      int fallTicks = Math.max(6, cfg.getInt("ban-animation.fall-ticks", 14));
      int titleTicks = Math.max(20, cfg.getInt("ban-animation.title-ticks", 60));
      double height = Math.max(4.0, cfg.getDouble("ban-animation.hammer-height", 9.0));
      float hammerScale = (float) cfg.getDouble("ban-animation.hammer-scale", 4.0);
      boolean everyone = cfg.getBoolean("ban-animation.visible-to-all", false);
      int impactAt = beamTicks + fallTicks;
      World world = target.getWorld();

      target.playSound(target.getLocation(), "entity.lightning_bolt.thunder", SoundCategory.MASTER, 1.0f, 1.4f);
      target.playSound(target.getLocation(), "block.beacon.activate", SoundCategory.MASTER, 1.0f, 1.2f);

      BlockDisplay beam = world.spawn(target.getLocation(), BlockDisplay.class, e -> {
         e.setBlock(Material.LIGHT_BLUE_STAINED_GLASS.createBlockData());
         e.setBrightness(new Display.Brightness(15, 15));
         e.setPersistent(false);
         e.setVisibleByDefault(everyone);
         e.setTeleportDuration(1);
         e.setViewRange(2.0f);
         e.setTransformation(new Transformation(
            new Vector3f(-0.2f, 0.0f, -0.2f), new AxisAngle4f(), new Vector3f(0.4f, 0.1f, 0.4f), new AxisAngle4f()
         ));
         e.setInterpolationDuration(2);
      });
      BlockDisplay core = world.spawn(target.getLocation(), BlockDisplay.class, e -> {
         e.setBlock(Material.WHITE_STAINED_GLASS.createBlockData());
         e.setBrightness(new Display.Brightness(15, 15));
         e.setPersistent(false);
         e.setVisibleByDefault(everyone);
         e.setTeleportDuration(1);
         e.setViewRange(2.0f);
         e.setTransformation(new Transformation(
            new Vector3f(-0.1f, 0.0f, -0.1f), new AxisAngle4f(), new Vector3f(0.2f, 0.1f, 0.2f), new AxisAngle4f()
         ));
         e.setInterpolationDuration(2);
      });
      ItemDisplay hammer = world.spawn(target.getLocation().add(0, height, 0), ItemDisplay.class, e -> {
         e.setItemStack(new ItemStack(Material.MACE));
         e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
         e.setBillboard(Display.Billboard.CENTER);
         e.setBrightness(new Display.Brightness(15, 15));
         e.setPersistent(false);
         e.setVisibleByDefault(everyone);
         e.setGlowing(true);
         e.setGlowColorOverride(Color.fromRGB(255, 40, 60));
         e.setTeleportDuration(1);
         e.setViewRange(2.0f);
         e.setTransformation(new Transformation(
            new Vector3f(), new AxisAngle4f(), new Vector3f(hammerScale, hammerScale, hammerScale), new AxisAngle4f()
         ));
      });
      DISPLAYS.add(beam);
      DISPLAYS.add(core);
      DISPLAYS.add(hammer);
      if (!everyone) {
         target.showEntity(plugin, beam);
         target.showEntity(plugin, core);
         target.showEntity(plugin, hammer);
      }
      hammer.setInterpolationDelay(0);

      new BukkitRunnable() {
         int tick = 0;

         private void clear() {
            beam.remove();
            core.remove();
            hammer.remove();
            DISPLAYS.remove(beam);
            DISPLAYS.remove(core);
            DISPLAYS.remove(hammer);
         }

         @Override
         public void run() {
            if (!target.isOnline()) {
               clear();
               ACTIVE.remove(target.getUniqueId());
               cancel();
               return;
            }

            Location base = target.getLocation();
            Location eyeLoc = target.getEyeLocation();

            if (tick <= impactAt) {
               double grow = Math.min(1.0, tick / 10.0);
               float h = (float) (40.0 * grow);
               beam.teleport(base);
               core.teleport(base);
               float pulse = 1.0f + 0.12f * (float) Math.sin(tick * 0.9);
               beam.setTransformation(new Transformation(
                  new Vector3f(-0.4f * pulse, 0.0f, -0.4f * pulse), new AxisAngle4f(),
                  new Vector3f(0.8f * pulse, h, 0.8f * pulse), new AxisAngle4f()
               ));
               core.setTransformation(new Transformation(
                  new Vector3f(-0.15f, 0.0f, -0.15f), new AxisAngle4f(), new Vector3f(0.3f, h, 0.3f), new AxisAngle4f()
               ));
               if (tick % 2 == 0) {
                  target.spawnParticle(Particle.END_ROD, base.clone().add(0, 1.0, 0), 4, 0.35, 1.0, 0.35, 0.02);
                  target.spawnParticle(Particle.ELECTRIC_SPARK, base.clone().add(0, 0.2, 0), 3, 0.4, 0.1, 0.4, 0.1);
               }
            }

            if (tick >= beamTicks && tick < impactAt) {
               double f = (double) (tick - beamTicks) / fallTicks;
               double eased = f * f;
               double y = height - (height - 1.6) * eased;
               hammer.teleport(base.clone().add(0, y, 0));
               if (tick == beamTicks) {
                  target.playSound(base, "item.mace.smash_air", SoundCategory.MASTER, 1.0f, 0.8f);
               }
            }

            if (tick == impactAt) {
               hammer.teleport(base.clone().add(0, 1.6, 0));
               target.playSound(base, "item.mace.smash_ground_heavy", SoundCategory.MASTER, 1.5f, 0.8f);
               target.playSound(base, "entity.generic.explode", SoundCategory.MASTER, 1.0f, 1.0f);
               target.spawnParticle(Particle.EXPLOSION, eyeLoc, 1, 0, 0, 0, 0);
               target.spawnParticle(
                  Particle.BLOCK, eyeLoc, 60, 0.5, 0.6, 0.5, 0.25, Material.RED_CONCRETE.createBlockData()
               );
               target.spawnParticle(
                  Particle.BLOCK, eyeLoc, 30, 0.5, 0.6, 0.5, 0.25, Material.LIGHT_BLUE_CONCRETE.createBlockData()
               );
               target.spawnParticle(Particle.CRIT, eyeLoc, 40, 0.5, 0.6, 0.5, 0.4);

               Component main = MM.deserialize(cfg.getString("ban-animation.title", "<red><bold>BANNED"));
               Component sub = MM.deserialize(cfg.getString("ban-animation.subtitle", "<gray>The hammer has spoken."));
               Title.Times times = Title.Times.times(
                  Duration.ofMillis(100), Duration.ofMillis(Math.max(500, (titleTicks - 6) * 50L)), Duration.ofMillis(300)
               );
               target.showTitle(Title.title(main, sub, times));
               String bar = cfg.getString("ban-animation.actionbar", "<dark_gray>by <red><admin>");
               target.sendActionBar(MM.deserialize(bar.replace("<admin>", MM.escapeTags(adminName))));
            }

            if (tick == impactAt + 6) {
               hammer.remove();
               DISPLAYS.remove(hammer);
            }
            if (tick == impactAt + 10) {
               beam.remove();
               core.remove();
               DISPLAYS.remove(beam);
               DISPLAYS.remove(core);
            }

            if (tick >= impactAt + titleTicks) {
               clear();
               ACTIVE.remove(target.getUniqueId());
               cancel();
               onFinish.run();
               return;
            }
            tick++;
         }
      }.runTaskTimer(plugin, 0L, 1L);
   }
}
