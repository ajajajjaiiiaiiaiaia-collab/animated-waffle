package com.starkmc.moderation.util;

import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

public final class SoundUtil {
   private SoundUtil() {
   }

   public static void play(Player target, FileConfiguration cfg, String path) {
      String key = "sounds." + path;
      if (cfg.getBoolean(key + ".enabled", true)) {
         String soundName = cfg.getString(key + ".sound", "");
         if (!soundName.isBlank()) {
            double volume = cfg.getDouble(key + ".volume", 1.0);
            double pitch = cfg.getDouble(key + ".pitch", 1.0);

            try {
               Sound sound = Sound.valueOf(soundName.toUpperCase().replace(".", "_"));
               target.playSound(target.getLocation(), sound, (float)volume, (float)pitch);
            } catch (IllegalArgumentException var10) {
            }
         }
      }
   }
}
