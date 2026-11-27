package com.starkmc.moderation.util;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.starkmc.moderation.ModerationPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.entity.Player;

/**
 * يطرد اللاعب من الشبكة كاملة (من البروكسي) بدل ما Velocity يرجعه للهوب.
 * يرسل KickPlayerRaw عبر قناة BungeeCord (Velocity يدعمها)، وإذا ما نفذ يطرده محلياً بعد فترة قصيرة.
 */
public final class NetworkKick {
   public static final String CHANNEL = "BungeeCord";

   private NetworkKick() {
   }

   public static void register(ModerationPlugin plugin) {
      plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
   }

   public static void kick(ModerationPlugin plugin, Player target, Component screen) {
      if (!target.isOnline()) {
         return;
      }
      sendRaw(plugin, target, target.getName(), screen);
      long delay = Math.max(2L, plugin.getConfig().getLong("network.fallback-kick-ticks", 10L));
      plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
         if (target.isOnline()) {
            target.kick(screen);
         }
      }, delay);
   }

   private static boolean sendRaw(ModerationPlugin plugin, Player carrier, String targetName, Component screen) {
      if (!plugin.getConfig().getBoolean("network.kick-from-proxy", true)) {
         return false;
      }
      try {
         ByteArrayDataOutput out = ByteStreams.newDataOutput();
         out.writeUTF("KickPlayerRaw");
         out.writeUTF(targetName);
         out.writeUTF(GsonComponentSerializer.gson().serialize(screen));
         carrier.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
         return true;
      } catch (Exception ex) {
         plugin.getLogger().warning("Proxy kick failed: " + ex.getMessage());
         return false;
      }
   }

   /**
    * يطرد لاعب (ممكن يكون على سيرفر ثاني في الشبكة) عن طريق البروكسي، بأي لاعب أونلاين هنا كناقل للرسالة.
    * يرجع false لو ما فيه أحد أونلاين على هذا السيرفر أو الطرد من البروكسي مطفي.
    */
   public static boolean kickByName(ModerationPlugin plugin, String targetName, Component screen) {
      Player carrier = plugin.getServer().getOnlinePlayers().stream().findFirst().orElse(null);
      return carrier != null && sendRaw(plugin, carrier, targetName, screen);
   }
}
