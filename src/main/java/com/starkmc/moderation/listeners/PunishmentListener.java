package com.starkmc.moderation.listeners;

import com.starkmc.moderation.ModerationPlugin;
import com.starkmc.moderation.data.DataManager;
import com.starkmc.moderation.util.BanAnimation;
import com.starkmc.moderation.util.Screens;
import com.starkmc.moderation.util.SoundUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;

public class PunishmentListener implements Listener {
   private final ModerationPlugin plugin;
   private final DataManager data;
   private final MiniMessage mm = MiniMessage.miniMessage();
   private static final long FREEZE_NOTIFY_COOLDOWN_MS = 3000L;
   private final Map<String, Long> lastFreezeNotify = new ConcurrentHashMap<>();

   public PunishmentListener(ModerationPlugin plugin, DataManager data) {
      this.plugin = plugin;
      this.data = data;
   }

   @EventHandler(
      priority = EventPriority.LOWEST
   )
   public void onLogin(AsyncPlayerPreLoginEvent event) {
      String name = event.getName().toLowerCase();
      if (this.data.isBlacklisted(name)) {
         Component screen = Screens.buildBlacklistScreen(
            this.plugin.getConfig(), event.getName(), this.data.getBlacklistReason(name), this.data.getBlacklistBy(name), this.data.getBlacklistAt(name)
         );
         event.disallow(Result.KICK_BANNED, screen);
         return;
      }

      if (this.data.isBanned(name)) {
         Component screen = Screens.buildBanScreen(
            this.plugin.getConfig(),
            event.getName(),
            this.data.getBanReason(name),
            this.data.getBanBy(name),
            this.data.getBanAt(name),
            this.data.getBanExpiry(name)
         );
         event.disallow(Result.KICK_BANNED, screen);
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST
   )
   public void onChat(AsyncChatEvent event) {
      if (this.data.anyMuted()) {
         String name = event.getPlayer().getName();
         if (this.data.isMuted(name)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Screens.buildMuteNotice(this.plugin.getConfig(), this.data.getMuteReason(name), this.data.getMuteExpiry(name)));
         }
      }
   }

   private static final Set<String> DEFAULT_MUTE_BLOCKED = Set.of("msg", "tell", "w", "whisper", "t", "m", "pm", "dm", "reply", "r", "me");

   private Set<String> muteBlockedCommands() {
      List<String> list = this.plugin.getConfig().getStringList("mute.blocked-commands");
      return list.isEmpty() ? DEFAULT_MUTE_BLOCKED : list.stream().map(c -> c.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
   }

   /** Muted players cannot use private-message commands (/msg, /tell, /r ...) to get around the mute. */
   @EventHandler(
      priority = EventPriority.LOWEST,
      ignoreCancelled = true
   )
   public void onMutedCommand(PlayerCommandPreprocessEvent event) {
      if (!this.data.anyMuted()) {
         return;
      }
      String name = event.getPlayer().getName();
      if (!this.data.isMuted(name)) {
         return;
      }
      String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
      int colon = label.indexOf(':');
      if (colon >= 0) {
         label = label.substring(colon + 1);
      }
      if (this.muteBlockedCommands().contains(label)) {
         event.setCancelled(true);
         event.getPlayer().sendMessage(Screens.buildMuteNotice(this.plugin.getConfig(), this.data.getMuteReason(name), this.data.getMuteExpiry(name)));
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH,
      ignoreCancelled = true
   )
   public void onMove(PlayerMoveEvent event) {
      if (this.data.anyFrozen()) {
         String name = event.getPlayer().getName();
         if (this.data.isFrozen(name)) {
            Location from = event.getFrom();
            Location to = event.getTo();
            if (to != null) {
               if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
                  event.setTo(from);
                  long now = System.currentTimeMillis();
                  long last = this.lastFreezeNotify.getOrDefault(name, 0L);
                  if (now - last >= 3000L) {
                     this.lastFreezeNotify.put(name, now);
                     event.getPlayer()
                        .sendMessage(Screens.buildFreezeNotice(this.plugin.getConfig(), this.data.getFreezeBy(name), this.data.getFreezeExpiry(name)));
                     SoundUtil.play(event.getPlayer(), this.plugin.getConfig(), "freeze-notify");
                  }
               }
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST
   )
   public void onCommand(PlayerCommandPreprocessEvent event) {
      if (this.data.anyFrozen()) {
         String name = event.getPlayer().getName();
         if (this.data.isFrozen(name)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(this.mm.deserialize("<red>❄ You cannot use commands while frozen."));
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onMoveBanAnim(PlayerMoveEvent event) {
      if (BanAnimation.isActive(event.getPlayer())) {
         Location from = event.getFrom();
         Location to = event.getTo();
         if (to != null && (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ())) {
            Location fixed = from.clone();
            fixed.setYaw(to.getYaw());
            fixed.setPitch(to.getPitch());
            event.setTo(fixed);
         }
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST
   )
   public void onCommandBanAnim(PlayerCommandPreprocessEvent event) {
      if (BanAnimation.isActive(event.getPlayer())) {
         event.setCancelled(true);
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.lastFreezeNotify.remove(event.getPlayer().getName());
   }
}
