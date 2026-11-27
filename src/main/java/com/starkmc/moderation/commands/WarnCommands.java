package com.starkmc.moderation.commands;

import com.starkmc.moderation.ModerationPlugin;
import com.starkmc.moderation.data.DataManager;
import com.starkmc.moderation.data.WarnEntry;
import com.starkmc.moderation.util.RankGuard;
import com.starkmc.moderation.util.SoundUtil;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

public class WarnCommands implements CommandExecutor, TabCompleter {
   private final ModerationPlugin plugin;
   private final DataManager data;
   private final RankGuard rankGuard;
   private final AlltsCommand allts;
   private final MiniMessage mm = MiniMessage.miniMessage();
   private final java.util.Map<String, List<String>> warnIdCache = new java.util.concurrent.ConcurrentHashMap<>();
   private final java.util.Map<String, Long> warnIdCacheAt = new java.util.concurrent.ConcurrentHashMap<>();
   private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

   public WarnCommands(ModerationPlugin plugin, DataManager data, RankGuard rankGuard, AlltsCommand allts) {
      this.plugin = plugin;
      this.data = data;
      this.rankGuard = rankGuard;
      this.allts = allts;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      String var5 = command.getName().toLowerCase();
      switch (var5) {
         case "warn":
            this.handleWarn(sender, args);
            break;
         case "unwarn":
            this.handleUnwarn(sender, args);
            break;
         case "warnings":
            this.handleWarnings(sender, args);
            break;
         case "clearwarnings":
            this.handleClearWarnings(sender, args);
      }

      return true;
   }

   private void handleWarn(CommandSender sender, String[] args) {
      if (!sender.hasPermission("starkmod.warn")) {
         this.noPermission(sender);
      } else if (args.length < 1) {
         this.msg(sender, "messages.usage.warn");
      } else {
         Player target = this.plugin.getServer().getPlayerExact(args[0]);
         if (target == null) {
            this.msgOffline(sender, args[0]);
         } else if (!this.rankGuard.canPunish(sender, target)) {
            this.rankDenied(sender, target);
         } else {
            String reason = args.length > 1
               ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
               : this.cfg().getString("default-reasons.warn", "ɴᴏ ʀᴇᴀꜱᴏɴ");
            String warnedName = target.getName();
            String warnedBy = sender.getName();
            this.data.async(this.plugin, () -> this.data.warn(warnedName, reason, warnedBy), countBoxed -> {
            if (countBoxed == null) {
               sender.sendMessage(this.mm.deserialize("<red>Database error, try again."));
               return;
            }
            int count = countBoxed;
            this.warnIdCache.remove(warnedName.toLowerCase());
            this.allts.invalidateCache(warnedName);
            String notifyMsg = this.cfg()
               .getString(
                  "warn.notify", "<yellow>⚠ You have been warned by <white><admin></white>. <gray>Reason: <white><reason> <gray>| Total: <white><count>"
               )
               .replace("<admin>", sender.getName())
               .replace("<reason>", reason)
               .replace("<count>", String.valueOf(count));
            target.sendMessage(this.mm.deserialize(notifyMsg));
            String successMsg = this.cfg()
               .getString("messages.warn-success", "<green>✔ <player> has been warned. <gray>(<count> total)")
               .replace("<player>", target.getName())
               .replace("<count>", String.valueOf(count));
            sender.sendMessage(this.mm.deserialize(successMsg));
            if (sender instanceof Player p) {
               SoundUtil.play(p, this.cfg(), "warn-admin");
            }

            SoundUtil.play(target, this.cfg(), "warn-target");
            this.broadcast("broadcasts.warn.message", target.getName(), null, reason, String.valueOf(count));
            });
         }
      }
   }

   private void handleUnwarn(CommandSender sender, String[] args) {
      if (!sender.hasPermission("starkmod.warn")) {
         this.noPermission(sender);
      } else if (args.length < 2) {
         this.msg(sender, "messages.usage.unwarn");
      } else {
         String targetName = args[0];

         int id;
         try {
            id = Integer.parseInt(args[1]);
         } catch (NumberFormatException var8) {
            sender.sendMessage(this.mm.deserialize("<red>Invalid warn ID."));
            return;
         }

         final int fid = id;
         this.data.async(this.plugin, () -> this.data.removeWarn(targetName, fid), removed -> {
         if (removed == null) {
            sender.sendMessage(this.mm.deserialize("<red>Database error, try again."));
            return;
         }
         if (!removed) {
            sender.sendMessage(
               this.mm
                  .deserialize(
                     this.cfg()
                        .getString("messages.warn-not-found", "<red>No warning #<id> found for <player>.")
                        .replace("<player>", targetName)
                        .replace("<id>", String.valueOf(fid))
                  )
            );
         } else {
            this.warnIdCache.remove(targetName.toLowerCase());
            this.allts.invalidateCache(targetName);
            String successMsg = this.cfg()
               .getString("messages.unwarn-success", "<green>✔ Warning #<id> removed from <player>.")
               .replace("<player>", targetName)
               .replace("<id>", String.valueOf(fid));
            sender.sendMessage(this.mm.deserialize(successMsg));
            Player target = this.plugin.getServer().getPlayerExact(targetName);
            if (target != null) {
               String notify = this.cfg()
                  .getString("messages.unwarn-notify", "<green>⚠ Warning #<id> has been removed from your record.")
                  .replace("<id>", String.valueOf(fid));
               target.sendMessage(this.mm.deserialize(notify));
            }
         }
         });
      }
   }

   private void handleWarnings(CommandSender sender, String[] args) {
      if (!sender.hasPermission("starkmod.warn.view")) {
         this.noPermission(sender);
      } else {
         String targetName;
         if (args.length < 1) {
            if (!(sender instanceof Player p)) {
               this.msg(sender, "messages.usage.warnings");
               return;
            }

            targetName = p.getName();
         } else {
            targetName = args[0];
         }

         final String fTarget = targetName;
         this.data.async(this.plugin, () -> this.data.getWarns(fTarget), list -> {
         if (list == null) {
            sender.sendMessage(this.mm.deserialize("<red>Database error, try again."));
            return;
         }
         String header = this.cfg()
            .getString("warn.list-header", "<gradient:#FFD700:#FFA500>⚠ Warnings for <white><player></white> <gray>(<count> total)")
            .replace("<player>", fTarget)
            .replace("<count>", String.valueOf(list.size()));
         sender.sendMessage(this.mm.deserialize(header));
         if (list.isEmpty()) {
            sender.sendMessage(this.mm.deserialize(this.cfg().getString("warn.list-empty", "<gray>  No warnings on record.")));
         } else {
            String lineTemplate = this.cfg().getString("warn.list-line", "<gray>  #<id> <white><reason> <gray>| By: <white><by> <gray>| <white><date>");

            for (WarnEntry w : list) {
               String line = lineTemplate.replace("<id>", String.valueOf(w.id()))
                  .replace("<reason>", w.reason())
                  .replace("<by>", w.by())
                  .replace("<date>", DATE_FMT.format(Instant.ofEpochMilli(w.at())));
               sender.sendMessage(this.mm.deserialize(line));
            }
         }
         });
      }
   }

   private void handleClearWarnings(CommandSender sender, String[] args) {
      if (!sender.hasPermission("starkmod.warn")) {
         this.noPermission(sender);
      } else if (args.length < 1) {
         this.msg(sender, "messages.usage.clearwarnings");
      } else {
         String targetName = args[0];
         this.data.async(this.plugin, () -> this.data.clearWarns(targetName), clearedBoxed -> {
         if (clearedBoxed == null) {
            sender.sendMessage(this.mm.deserialize("<red>Database error, try again."));
            return;
         }
         int cleared = clearedBoxed;
         if (cleared == 0) {
            sender.sendMessage(
               this.mm.deserialize(this.cfg().getString("messages.no-warnings", "<red><player> has no warnings.").replace("<player>", targetName))
            );
         } else {
            this.warnIdCache.remove(targetName.toLowerCase());
            this.allts.invalidateCache(targetName);
            String successMsg = this.cfg()
               .getString("messages.clearwarnings-success", "<green>✔ Cleared <count> warning(s) from <player>.")
               .replace("<player>", targetName)
               .replace("<count>", String.valueOf(cleared));
            sender.sendMessage(this.mm.deserialize(successMsg));
            Player target = this.plugin.getServer().getPlayerExact(targetName);
            if (target != null) {
               target.sendMessage(this.mm.deserialize(this.cfg().getString("messages.clearwarnings-notify", "<green>✔ All your warnings have been cleared.")));
            }
         }
         });
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
      String cmd = command.getName().toLowerCase();
      if (args.length == 1) {
         return switch (cmd) {
            case "warn", "warnings", "clearwarnings" -> this.filterOnlinePlayers(args[0]);
            case "unwarn" -> this.filterWarnedPlayers(args[0]);
            default -> List.of();
         };
      } else {
         return args.length == 2 && cmd.equals("unwarn")
            ? this.cachedWarnIds(args[0]).stream().filter(id -> id.startsWith(args[1])).collect(Collectors.toList())
            : List.of();
      }
   }

   private FileConfiguration cfg() {
      return this.plugin.getConfig();
   }

   private void msg(CommandSender sender, String key) {
      sender.sendMessage(this.mm.deserialize(this.cfg().getString(key, "<red>Missing message: " + key)));
   }

   private void msgOffline(CommandSender sender, String name) {
      sender.sendMessage(this.mm.deserialize(this.cfg().getString("messages.player-offline", "<red>Player <player> is not online.").replace("<player>", name)));
   }

   private void noPermission(CommandSender sender) {
      sender.sendMessage(this.mm.deserialize("<red>You don't have permission."));
   }

   private void rankDenied(CommandSender sender, Player target) {
      sender.sendMessage(
         this.mm
            .deserialize(
               this.cfg().getString("messages.rank-guard-denied", "<red>You cannot punish <white><player></white>.").replace("<player>", target.getName())
            )
      );
   }

   private void broadcast(String cfgKey, String player, String duration, String reason, String count) {
      String parentKey = cfgKey.substring(0, cfgKey.lastIndexOf(46));
      if (this.cfg().getBoolean(parentKey + ".enabled", true)) {
         String raw = this.cfg().getString(cfgKey, "");
         if (!raw.isBlank()) {
            raw = raw.replace("<player>", player != null ? player : "")
               .replace("<duration>", duration != null ? duration : "")
               .replace("<reason>", reason != null ? reason : "")
               .replace("<count>", count != null ? count : "");
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

   private List<String> filterWarnedPlayers(String typed) {
      return this.plugin
         .getServer()
         .getOnlinePlayers()
         .stream()
         .<String>map(Player::getName)
         .filter(n -> n.toLowerCase().startsWith(typed.toLowerCase()) && !this.cachedWarnIds(n).isEmpty())
         .collect(Collectors.toList());
   }

   /** Tab-complete must not hit MySQL on the main thread: return cached ids and refresh them in the background (max every 3s). */
   private List<String> cachedWarnIds(String name) {
      String key = name.toLowerCase();
      long now = System.currentTimeMillis();
      Long last = this.warnIdCacheAt.get(key);
      if (last == null || now - last > 3000L) {
         this.warnIdCacheAt.put(key, now);
         this.data.async(this.plugin, () -> this.data.getWarns(name), list -> {
            if (list != null) {
               this.warnIdCache.put(key, list.stream().map(w -> String.valueOf(w.id())).collect(Collectors.toList()));
            }
         });
      }
      return this.warnIdCache.getOrDefault(key, List.of());
   }
}
