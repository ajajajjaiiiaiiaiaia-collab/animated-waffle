package com.starkmc.moderation;

import com.starkmc.moderation.api.UnpunishApi;
import com.starkmc.moderation.commands.AlltsCommand;
import com.starkmc.moderation.commands.ModerationCommands;
import com.starkmc.moderation.commands.WarnCommands;
import com.starkmc.moderation.data.DataManager;
import com.starkmc.moderation.listeners.PunishmentListener;
import com.starkmc.moderation.util.RankGuard;
import java.util.List;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

public class ModerationPlugin extends JavaPlugin {
   private DataManager dataManager;
   private RankGuard rankGuard;
   private ModerationCommands commands;
   private WarnCommands warnCommands;
   private AlltsCommand alltsCommand;
   private com.starkmc.moderation.commands.VanishCommand vanishCommand;

   public void onEnable() {
      this.saveDefaultConfig();

      try {
         this.dataManager = new DataManager(this.getConfig());
      } catch (Exception var5) {
         this.getLogger().severe("Failed to connect to MySQL: " + var5.getMessage());
         this.getLogger().severe("Check config.yml mysql section.");
         this.getServer().getPluginManager().disablePlugin(this);
         return;
      }

      this.rankGuard = new RankGuard(this);
      this.alltsCommand = new AlltsCommand(this, this.dataManager);
      this.commands = new ModerationCommands(this, this.dataManager, this.rankGuard, this.alltsCommand);
      this.warnCommands = new WarnCommands(this, this.dataManager, this.rankGuard, this.alltsCommand);
      List<String> modCmds = List.of("kick", "tempban", "unban", "unbanall", "blacklist", "tempmute", "unmute", "freeze", "unfreeze", "starkmod");
      List<String> warnCmds = List.of("warn", "unwarn", "warnings", "clearwarnings");

      for (String cmd : modCmds) {
         this.registerCmd(cmd, this.commands, this.commands);
      }

      for (String cmd : warnCmds) {
         this.registerCmd(cmd, this.warnCommands, this.warnCommands);
      }

      this.registerCmd("allts", this.alltsCommand, this.alltsCommand);
      this.vanishCommand = new com.starkmc.moderation.commands.VanishCommand(this);
      this.registerCmd("vanish", this.vanishCommand, this.vanishCommand);
      this.getServer().getPluginManager().registerEvents(new PunishmentListener(this, this.dataManager), this);
      com.starkmc.moderation.util.NetworkKick.register(this);
      com.starkmc.moderation.util.ProxyPlayers.start(this);
      com.starkmc.moderation.util.BanAnimation.preloadHead(this);
      UnpunishApi.start(this, this.dataManager);
      com.starkmc.moderation.web.WebPanel.start(this, this.dataManager);
      this.getLogger().info("StarkModeration v" + this.getPluginMeta().getVersion() + " enabled! (MySQL | conflict-safe)");
   }

   private void registerCmd(String name, CommandExecutor exec, TabCompleter tab) {
      PluginCommand pc = this.getCommand(name);
      if (pc != null) {
         pc.setExecutor(exec);
         pc.setTabCompleter(tab);
         this.getServer().getCommandMap().register("starkmod", pc);
      }
   }

   public void onDisable() {
      UnpunishApi.shutdown();
      com.starkmc.moderation.util.ProxyPlayers.shutdown();
      com.starkmc.moderation.web.WebPanel.shutdown();
      com.starkmc.moderation.util.BanAnimation.cleanup();
      if (this.vanishCommand != null) {
         this.vanishCommand.disableAll();
      }

      if (this.dataManager != null) {
         this.dataManager.shutdown();
      }

      this.getLogger().info("StarkModeration disabled.");
   }

   public DataManager getDataManager() {
      return this.dataManager;
   }

   public RankGuard getRankGuard() {
      return this.rankGuard;
   }

   public AlltsCommand getAlltsCommand() {
      return this.alltsCommand;
   }
}
