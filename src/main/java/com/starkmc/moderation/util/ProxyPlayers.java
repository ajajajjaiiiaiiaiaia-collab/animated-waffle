package com.starkmc.moderation.util;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.starkmc.moderation.ModerationPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * يجيب أونلاين الشبكة كاملة من البروكسي (Velocity) عبر قناة BungeeCord: GetServers ثم PlayerList لكل سيرفر.
 * ملاحظة: البروكسي يرد فقط عبر لاعب أونلاين على هذا السيرفر، فلو السيرفر فاضي تبقى آخر بيانات (وتتعلم قديمة).
 */
public final class ProxyPlayers implements PluginMessageListener {
   private static final String CHANNEL = NetworkKick.CHANNEL;
   private static final long STALE_MS = 30_000L;
   private static ProxyPlayers instance;

   private final ModerationPlugin plugin;
   private final Map<String, List<String>> byServer = new ConcurrentHashMap<>();
   private final int taskId;
   private volatile long updated;

   private ProxyPlayers(ModerationPlugin plugin) {
      this.plugin = plugin;
      plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
      this.taskId = plugin.getServer().getScheduler().runTaskTimer(plugin, this::poll, 40L, 100L).getTaskId();
   }

   public static synchronized void start(ModerationPlugin plugin) {
      shutdown();
      if (plugin.getConfig().getBoolean("web.enabled", false) && plugin.getConfig().getBoolean("web.proxy-players", true)) {
         instance = new ProxyPlayers(plugin);
      }
   }

   public static synchronized void shutdown() {
      if (instance != null) {
         instance.plugin.getServer().getScheduler().cancelTask(instance.taskId);
         instance.plugin.getServer().getMessenger().unregisterIncomingPluginChannel(instance.plugin, CHANNEL, instance);
         instance = null;
      }
   }

   public static ProxyPlayers get() {
      return instance;
   }

   public boolean hasData() {
      return this.updated > 0L;
   }

   public boolean stale() {
      return System.currentTimeMillis() - this.updated > STALE_MS;
   }

   public Map<String, List<String>> snapshot() {
      return new TreeMap<>(this.byServer);
   }

   /** يرجع الاسم الصحيح لو اللاعب أونلاين في أي سيرفر بالشبكة، وإلا null. */
   public String find(String name) {
      for (List<String> l : this.byServer.values()) {
         for (String n : l) {
            if (n.equalsIgnoreCase(name)) {
               return n;
            }
         }
      }
      return null;
   }

   private void send(Player carrier, String... parts) {
      ByteArrayDataOutput out = ByteStreams.newDataOutput();
      for (String p : parts) {
         out.writeUTF(p);
      }
      carrier.sendPluginMessage(this.plugin, CHANNEL, out.toByteArray());
   }

   private void poll() {
      Player carrier = this.plugin.getServer().getOnlinePlayers().stream().findFirst().orElse(null);
      if (carrier != null) {
         send(carrier, "GetServers");
      }
   }

   @Override
   public void onPluginMessageReceived(String channel, Player player, byte[] message) {
      if (!CHANNEL.equals(channel)) {
         return;
      }
      try {
         ByteArrayDataInput in = ByteStreams.newDataInput(message);
         String sub = in.readUTF();
         if ("GetServers".equals(sub)) {
            List<String> servers = split(in.readUTF());
            this.byServer.keySet().removeIf(s -> !servers.contains(s));
            for (String s : servers) {
               send(player, "PlayerList", s);
            }
         } else if ("PlayerList".equals(sub)) {
            String server = in.readUTF();
            this.byServer.put(server, split(in.readUTF()));
            this.updated = System.currentTimeMillis();
         }
      } catch (Exception ignored) {
      }
   }

   private static List<String> split(String csv) {
      List<String> out = new ArrayList<>();
      for (String s : csv.split(",")) {
         if (!s.isBlank()) {
            out.add(s.trim());
         }
      }
      return out;
   }
}
