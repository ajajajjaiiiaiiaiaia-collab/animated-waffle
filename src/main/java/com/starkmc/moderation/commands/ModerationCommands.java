package com.starkmc.moderation.commands;

import com.starkmc.moderation.ModerationPlugin;
import com.starkmc.moderation.data.DataManager;
import com.starkmc.moderation.util.BanAnimation;
import com.starkmc.moderation.util.NetworkKick;
import com.starkmc.moderation.util.DurationUtil;
import com.starkmc.moderation.util.RankGuard;
import com.starkmc.moderation.util.Screens;
import com.starkmc.moderation.util.SoundUtil;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

public class ModerationCommands implements CommandExecutor, TabCompleter {
   private final ModerationPlugin plugin;
   private final DataManager data;
   private final RankGuard rankGuard;
   private final AlltsCommand allts;
   private final MiniMessage mm = MiniMessage.miniMessage();

   public ModerationCommands(ModerationPlugin plugin, DataManager data, RankGuard rankGuard, AlltsCommand allts) {
      this.plugin = plugin;
      this.data = data;
      this.rankGuard = rankGuard;
      this.allts = allts;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      String name = command.getName().toLowerCase();
      switch (name) {
         case "starkmod":
            this.handleReload(sender, args);
            break;
         case "kick":
            this.handleKick(sender, args);
            break;
         case "tempban":
            this.handleTempBan(sender, args);
            break;
         case "unban":
            this.handleUnban(sender, args);
            break;
         case "unbanall":
            this.handleUnbanAll(sender, args);
            break;
         case "blacklist":
            this.handleBlacklist(sender, args);
            break;
         case "tempmute":
            this.handleTempMute(sender, args);
            break;
         case "unmute":
            this.handleUnmute(sender, args);
            break;
         case "freeze":
            this.handleFreeze(sender, args);
            break;
         case "unfreeze":
            this.handleUnfreeze(sender, args);
      }

      return true;
   }

   private void handleReload(CommandSender sender, String[] args) {
      if (args.length != 0 && args[0].equalsIgnoreCase("reload")) {
         this.plugin.reloadConfig();
         this.data.load();
         this.msg(sender, "messages.reload-success");
      } else {
         this.msg(sender, "messages.usage.starkmod");
      }
   }

   private void handleKick(CommandSender sender, String[] args) {
      if (!sender.hasPermission("starkmod.kick")) {
         this.noPermission(sender);
      } else if (args.length < 1) {
         this.msg(sender, "messages.usage.kick");
      } else {
         Player target = this.plugin.getServer().getPlayerExact(args[0]);
         if (target == null) {
            this.msgPlayer(sender, args[0]);
         } else if (!this.rankGuard.canPunish(sender, target)) {
            this.rankDenied(sender, target);
         } else {
            String reason = args.length > 1
               ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
               : this.cfg().getString("default-reasons.kick", "ɴᴏ ʀᴇᴀꜱᴏɴ");
            target.kick(Screens.buildKickScreen(this.cfg(), target.getName(), reason, sender.getName()));
            String kickedName = target.getName();
            String kickedBy = sender.getName();
            this.data.async(this.plugin, () -> this.data.kick(kickedName, reason, kickedBy), id -> this.allts.invalidateCache(kickedName));
            if (sender instanceof Player p) {
               SoundUtil.play(p, this.cfg(), "kick-admin");
            }

            this.broadcast("broadcasts.kick.message", target.getName(), "n/a", reason);
         }
      }
   }

   private void handleTempBan(CommandSender sender, String[] args) {
      if (!sender.hasPermission("starkmod.ban")) {
         this.noPermission(sender);
      } else if (args.length < 2) {
         this.msg(sender, "messages.usage.tempban");
      } else {
         Player target = this.plugin.getServer().getPlayerExact(args[0]);
         String targetName = args[0];
         if (this.data.isBanned(targetName)) {
            sender.sendMessage(
               this.mm
                  .deserialize(
                     this.cfg().getString("messages.already-banned", "<red><player> is already banned. Unban them first.").replace("<player>", targetName)
                  )
            );
         } else if (target != null && !this.rankGuard.canPunish(sender, target)) {
            this.rankDenied(sender, target);
         } else {
            long durationMs = DurationUtil.parse(args[1]);
            if (durationMs == -2L) {
               this.msg(sender, "messages.invalid-duration");
            } else {
               String reason = args.length > 2
                  ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                  : this.cfg().getString("default-reasons.ban", "ɴᴏ ʀᴇᴀꜱᴏɴ");
               this.data.ban(targetName, durationMs, reason, sender.getName());
               this.allts.invalidateCache(targetName);
               if (target != null) {
                  BanAnimation.play(this.plugin, target, sender, () -> {
                     if (target.isOnline()) {
                        NetworkKick.kick(
                           this.plugin,
                           target,
                           Screens.buildBanScreen(
                              this.cfg(), target.getName(), reason, sender.getName(), this.data.getBanAt(targetName), this.data.getBanExpiry(targetName)
                           )
                        );
                     }
                  });
               }

               String durStr = DurationUtil.format(durationMs);
               this.sendSuccess(sender, "messages.ban-success", targetName, durStr);
               if (sender instanceof Player p) {
                  SoundUtil.play(p, this.cfg(), "ban-admin");
               }

               this.broadcast("broadcasts.ban.message", targetName, durStr, reason);
            }
         }
      }
   }

   private void handleUnban(CommandSender sender, String[] args) {
      if (!sender.hasPermission("starkmod.unban")) {
         this.noPermission(sender);
      } else if (args.length < 1) {
         this.msg(sender, "messages.usage.unban");
      } else {
         String targetName = args[0];
         if (!this.data.isBanned(targetName)) {
            sender.sendMessage(this.mm.deserialize(this.cfg().getString("messages.not-banned", "<red><player> is not banned.").replace("<player>", targetName)));
         } else {
            this.data.unban(targetName, sender.getName());
            this.allts.invalidateCache(targetName);
            this.sendSuccess(sender, "messages.unban-success", targetName, null);
            if (sender instanceof Player p) {
               SoundUtil.play(p, this.cfg(), "unban-admin");
            }
         }
      }
   }

   private void handleBlacklist(CommandSender sender, String[] args) {
      String sub = args.length > 0 ? args[0].toLowerCase() : "";
      if (sub.equals("add")) {
         if (!sender.hasPermission("starkmod.blacklist")) {
            this.noPermission(sender);
         } else if (args.length < 2) {
            this.msg(sender, "messages.usage.blacklist");
         } else {
            String targetName = args[1];
            Player target = this.plugin.getServer().getPlayerExact(targetName);
            if (this.data.isBlacklisted(targetName)) {
               sender.sendMessage(
                  this.mm.deserialize(this.cfg().getString("messages.already-blacklisted", "<red><player> is already blacklisted.").replace("<player>", targetName))
               );
            } else if (target != null && !this.rankGuard.canPunish(sender, target)) {
               this.rankDenied(sender, target);
            } else {
               String reason = args.length > 2
                  ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                  : this.cfg().getString("default-reasons.blacklist", "ɴᴏ ʀᴇᴀꜱᴏɴ");
               this.data.blacklist(targetName, reason, sender.getName());
               this.allts.invalidateCache(targetName);
               if (target != null) {
                  NetworkKick.kick(
                     this.plugin,
                     target,
                     Screens.buildBlacklistScreen(this.cfg(), target.getName(), reason, sender.getName(), this.data.getBlacklistAt(targetName))
                  );
               }

               this.sendSuccess(sender, "messages.blacklist-success", targetName, null);
               if (sender instanceof Player p) {
                  SoundUtil.play(p, this.cfg(), "ban-admin");
               }

               this.broadcast("broadcasts.blacklist.message", targetName, "permanent", reason);
            }
         }
      } else if (sub.equals("remove")) {
         if (!sender.hasPermission("starkmod.blacklist.remove")) {
            this.noPermission(sender);
         } else if (args.length < 2) {
            this.msg(sender, "messages.usage.blacklist");
         } else if (!this.data.isBlacklisted(args[1])) {
            sender.sendMessage(
               this.mm.deserialize(this.cfg().getString("messages.not-blacklisted", "<red><player> is not blacklisted.").replace("<player>", args[1]))
            );
         } else {
            this.data.unblacklist(args[1]);
            this.allts.invalidateCache(args[1]);
            this.sendSuccess(sender, "messages.unblacklist-success", args[1], null);
         }
      } else if (sub.equals("list")) {
         if (!sender.hasPermission("starkmod.blacklist")) {
            this.noPermission(sender);
         } else {
            java.util.Set<String> names = this.data.getBlacklistedPlayers();
            sender.sendMessage(
               this.mm.deserialize(
                  this.cfg()
                     .getString("messages.blacklist-list", "<gray>Blacklisted (<count>): <white><players>")
                     .replace("<count>", String.valueOf(names.size()))
                     .replace("<players>", names.isEmpty() ? "-" : String.join(", ", names))
               )
            );
         }
      } else {
         this.msg(sender, "messages.usage.blacklist");
      }
   }

   private void handleUnbanAll(CommandSender sender, String[] args) {
      if (!sender.hasPermission("starkmod.unbanall")) {
         this.noPermission(sender);
      } else if (args.length < 1 || !args[0].equalsIgnoreCase("confirm")) {
         int active = this.data.getActiveBanCount();
         sender.sendMessage(
            this.mm.deserialize(
               this.cfg()
                  .getString("messages.unbanall-confirm", "<yellow>⚠ This will unban <red><count></red> player(s). Run <white>/unbanall confirm</white> to proceed.")
                  .replace("<count>", String.valueOf(active))
            )
         );
      } else {
         int count = this.data.unbanAll();
         this.allts.invalidateAll();
         sender.sendMessage(
            this.mm.deserialize(
               this.cfg().getString("messages.unbanall-success", "<green>✔ Unbanned <white><count></white> player(s).").replace("<count>", String.valueOf(count))
            )
         );
         if (sender instanceof Player p) {
            SoundUtil.play(p, this.cfg(), "unban-admin");
         }

         this.plugin.getLogger().warning(sender.getName() + " unbanned ALL players (" + count + ").");
      }
   }

   private void handleTempMute(CommandSender sender, String[] args) {
      if (!sender.hasPermission("starkmod.mute")) {
         this.noPermission(sender);
      } else if (args.length < 2) {
         this.msg(sender, "messages.usage.tempmute");
      } else {
         Player target = this.plugin.getServer().getPlayerExact(args[0]);
         String targetName = target != null ? target.getName() : args[0];
         long durationMs = DurationUtil.parse(args[1]);
         if (durationMs == -2L) {
            this.msg(sender, "messages.invalid-duration");
         } else {
            String reason = args.length > 2
               ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
               : this.cfg().getString("default-reasons.mute", "ɴᴏ ʀᴇᴀꜱᴏɴ");
            if (target != null) {
               if (!this.rankGuard.canPunish(sender, target)) {
                  this.rankDenied(sender, targetName);
               } else {
                  this.applyMute(sender, target, targetName, durationMs, reason);
               }
            } else {
               this.rankGuard.canPunishOffline(sender, targetName).thenAccept(allowed -> this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
                  if (!allowed) {
                     this.rankDenied(sender, targetName);
                  } else {
                     this.applyMute(sender, this.plugin.getServer().getPlayerExact(targetName), targetName, durationMs, reason);
                  }
               }));
            }
         }
      }
   }

   private void applyMute(CommandSender sender, Player target, String targetName, long durationMs, String reason) {
      this.data.mute(targetName, durationMs, reason, sender.getName());
      this.allts.invalidateCache(targetName);
      if (target != null) {
         target.sendMessage(Screens.buildMuteNotice(this.cfg(), reason, this.data.getMuteExpiry(targetName)));
      }

      String durStr = DurationUtil.format(durationMs);
      this.sendSuccess(sender, "messages.mute-success", targetName, durStr);
      if (sender instanceof Player p) {
         SoundUtil.play(p, this.cfg(), "mute-admin");
      }

      if (target != null) {
         SoundUtil.play(target, this.cfg(), "mute-target");
      }

      this.broadcast("broadcasts.mute.message", targetName, durStr, reason);
   }

   private void handleUnmute(CommandSender sender, String[] args) {
      if (!sender.hasPermission("starkmod.mute")) {
         this.noPermission(sender);
      } else if (args.length < 1) {
         this.msg(sender, "messages.usage.unmute");
      } else {
         Player target = this.plugin.getServer().getPlayerExact(args[0]);
         String targetName = args[0];
         if (!this.data.isMuted(targetName)) {
            sender.sendMessage(this.mm.deserialize(this.cfg().getString("messages.not-muted", "<red><player> is not muted.").replace("<player>", targetName)));
         } else {
            this.data.unmute(targetName, sender.getName());
            this.allts.invalidateCache(targetName);
            this.sendSuccess(sender, "messages.unmute-success", targetName, null);
            if (sender instanceof Player p) {
               SoundUtil.play(p, this.cfg(), "unmute-admin");
            }

            if (target != null) {
               target.sendMessage(this.mm.deserialize(this.cfg().getString("messages.unmute-notify", "<green>You have been unmuted.")));
               SoundUtil.play(target, this.cfg(), "unmute-target");
            }
         }
      }
   }

   private void handleFreeze(CommandSender sender, String[] args) {
      if (!sender.hasPermission("starkmod.freeze")) {
         this.noPermission(sender);
      } else if (args.length < 1) {
         this.msg(sender, "messages.usage.freeze");
      } else {
         Player target = this.plugin.getServer().getPlayerExact(args[0]);
         if (target == null) {
            this.msgPlayer(sender, args[0]);
         } else if (!this.rankGuard.canPunish(sender, target)) {
            this.rankDenied(sender, target);
         } else {
            long durationMs = -1L;
            if (args.length >= 2) {
               durationMs = DurationUtil.parse(args[1]);
               if (durationMs == -2L) {
                  this.msg(sender, "messages.invalid-duration");
                  return;
               }
            }

            this.data.freeze(target.getName(), durationMs, sender.getName());
            long expiry = this.data.getFreezeExpiry(target.getName());
            target.sendMessage(Screens.buildFreezeNotice(this.cfg(), sender.getName(), expiry));
            if (sender instanceof Player p) {
               SoundUtil.play(p, this.cfg(), "freeze-admin");
            }

            SoundUtil.play(target, this.cfg(), "freeze-target");
            if (durationMs == -1L) {
               this.sendSuccess(sender, "messages.freeze-success-permanent", target.getName(), null);
            } else {
               this.sendSuccess(sender, "messages.freeze-success-timed", target.getName(), DurationUtil.format(durationMs));
            }
         }
      }
   }

   private void handleUnfreeze(CommandSender sender, String[] args) {
      if (!sender.hasPermission("starkmod.freeze")) {
         this.noPermission(sender);
      } else if (args.length < 1) {
         this.msg(sender, "messages.usage.unfreeze");
      } else {
         Player target = this.plugin.getServer().getPlayerExact(args[0]);
         String targetName = args[0];
         if (!this.data.isFrozen(targetName)) {
            sender.sendMessage(this.mm.deserialize(this.cfg().getString("messages.not-frozen", "<red><player> is not frozen.").replace("<player>", targetName)));
         } else {
            this.data.unfreeze(targetName);
            this.sendSuccess(sender, "messages.unfreeze-success", targetName, null);
            if (sender instanceof Player p) {
               SoundUtil.play(p, this.cfg(), "unfreeze-admin");
            }

            if (target != null) {
               target.sendMessage(this.mm.deserialize(this.cfg().getString("messages.unfreeze-notify", "<green>You have been unfrozen.")));
               SoundUtil.play(target, this.cfg(), "unfreeze-target");
            }
         }
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
      String cmd = command.getName().toLowerCase();
      if (args.length == 1) {
         return switch (cmd) {
            case "starkmod" -> List.of("reload");
            case "kick", "freeze" -> this.filterOnlinePlayers(args[0]);
            case "tempban", "tempmute" -> this.filterOnlinePlayers(args[0]);
            case "unban" -> List.of("<player>");
            case "blacklist" -> List.of("add", "remove", "list");
            case "unbanall" -> sender.hasPermission("starkmod.unbanall") ? List.of("confirm") : List.of();
            case "unmute", "unfreeze" -> this.filterMutedOrFrozen(cmd, args[0]);
            default -> List.of();
         };
      } else if (args.length == 2 && cmd.equals("blacklist")) {
         if (args[0].equalsIgnoreCase("add")) {
            return this.filterOnlinePlayers(args[1]);
         } else {
            return args[0].equalsIgnoreCase("remove")
               ? this.data.getBlacklistedPlayers().stream().filter(n -> n.startsWith(args[1].toLowerCase())).collect(Collectors.toList())
               : List.of();
         }
      } else {
         return args.length == 2 && List.of("tempban", "tempmute", "freeze").contains(cmd)
            ? this.cfg().getStringList("durations").stream().filter(d -> d.startsWith(args[1])).collect(Collectors.toList())
            : List.of();
      }
   }

   private FileConfiguration cfg() {
      return this.plugin.getConfig();
   }

   private void msg(CommandSender sender, String key) {
      String raw = this.cfg().getString(key, "<red>Missing message: " + key);
      sender.sendMessage(this.mm.deserialize(raw));
   }

   private void msgPlayer(CommandSender sender, String playerName) {
      String raw = this.cfg().getString("messages.player-offline", "<red>Player <player> is not online.").replace("<player>", playerName);
      sender.sendMessage(this.mm.deserialize(raw));
   }

   private void noPermission(CommandSender sender) {
      sender.sendMessage(this.mm.deserialize("<red>You don't have permission."));
   }

   private void rankDenied(CommandSender sender, Player target) {
      this.rankDenied(sender, target.getName());
   }

   private void rankDenied(CommandSender sender, String targetName) {
      String raw = this.cfg()
         .getString("messages.rank-guard-denied", "<red>You cannot punish <white><player></white>, their rank is equal or higher than yours.")
         .replace("<player>", targetName);
      sender.sendMessage(this.mm.deserialize(raw));
   }

   private void sendSuccess(CommandSender sender, String key, String playerName, String duration) {
      String raw = this.cfg()
         .getString(key, "<green>Done.")
         .replace("<player>", playerName != null ? playerName : "")
         .replace("<duration>", duration != null ? duration : "");
      sender.sendMessage(this.mm.deserialize(raw));
   }

   private void broadcast(String cfgKey, String playerName, String duration, String reason) {
      if (this.cfg().getBoolean(cfgKey.replace(".message", ".enabled"), true)) {
         String raw = this.cfg()
            .getString(cfgKey, "")
            .replace("<player>", playerName != null ? playerName : "")
            .replace("<duration>", duration != null ? duration : "")
            .replace("<reason>", reason != null ? reason : "");
         if (!raw.isBlank()) {
            Component component = this.mm.deserialize(raw);
            this.plugin.getServer().getOnlinePlayers().forEach(p -> p.sendMessage(component));
         }
      }
   }

   private List<String> filterOnlinePlayers(String typed) {
      return this.plugin
         .getServer()
         .getOnlinePlayers()
         .stream()
         .<String>map(Player::getName)
         .filter(n -> n.toLowerCase().startsWith(typed.toLowerCase()))
         .collect(Collectors.toList());
   }

   private List<String> filterMutedOrFrozen(String cmd, String typed) {
      return this.plugin.getServer().getOnlinePlayers().stream().<String>map(Player::getName).filter(n -> {
         boolean match = n.toLowerCase().startsWith(typed.toLowerCase());
         if (!match) {
            return false;
         } else {
            return cmd.equals("unmute") ? this.data.isMuted(n) : this.data.isFrozen(n);
         }
      }).collect(Collectors.toList());
   }
}
