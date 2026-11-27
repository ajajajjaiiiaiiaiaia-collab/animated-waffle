package com.starkmc.moderation.util;

import java.util.concurrent.CompletableFuture;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class RankGuard {
   private final JavaPlugin plugin;
   private LuckPerms luckPerms;
   private final boolean enabled;

   public RankGuard(JavaPlugin plugin) {
      this.plugin = plugin;
      this.enabled = plugin.getConfig().getBoolean("rank-guard.enabled", true);
      if (this.enabled) {
         RegisteredServiceProvider<LuckPerms> provider = plugin.getServer().getServicesManager().getRegistration(LuckPerms.class);
         if (provider != null) {
            this.luckPerms = (LuckPerms)provider.getProvider();
         }
      }
   }

   public boolean canPunish(CommandSender executor, Player target) {
      if (!this.enabled || this.luckPerms == null) {
         return true;
      } else if (executor instanceof Player p) {
         if (p.hasPermission("starkmod.rank-guard.bypass")) {
            return true;
         } else {
            return target.hasPermission("starkmod.rank-guard.bypass") ? false : this.weightOf(p) > this.weightOf(target);
         }
      } else {
         return true;
      }
   }

   public CompletableFuture<Boolean> canPunishOffline(CommandSender executor, String targetName) {
      if (!this.enabled || this.luckPerms == null || !(executor instanceof Player p)) {
         return CompletableFuture.completedFuture(true);
      } else if (p.hasPermission("starkmod.rank-guard.bypass")) {
         return CompletableFuture.completedFuture(true);
      } else {
         int executorWeight = this.weightOf(p);
         return this.luckPerms.getUserManager().lookupUniqueId(targetName).thenCompose(uuid -> {
            return uuid == null ? CompletableFuture.completedFuture((User)null) : this.luckPerms.getUserManager().loadUser(uuid);
         }).thenApply(user -> {
            if (user == null) {
               return true;
            } else if (user.getCachedData().getPermissionData().checkPermission("starkmod.rank-guard.bypass").asBoolean()) {
               return false;
            } else {
               QueryOptions opts = this.luckPerms.getContextManager().getStaticQueryOptions();
               int targetWeight = user.getInheritedGroups(opts).stream().mapToInt(g -> g.getWeight().orElse(0)).max().orElse(0);
               return executorWeight > targetWeight;
            }
         }).exceptionally(t -> {
            this.plugin.getLogger().warning("RankGuard offline check failed for " + targetName + ": " + t);
            return false;
         });
      }
   }

   private int weightOf(Player player) {
      User user = this.luckPerms.getUserManager().getUser(player.getUniqueId());
      if (user == null) {
         return 0;
      } else {
         QueryOptions opts = this.luckPerms.getContextManager().getQueryOptions(player);
         return user.getInheritedGroups(opts).stream().mapToInt(g -> g.getWeight().orElse(0)).max().orElse(0);
      }
   }
}
