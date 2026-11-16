package com.duel;

import com.duel.commands.DuelCommand;
import com.duel.commands.DuelRespondCommand;
import com.duel.commands.SpectateCommand;
import com.duel.commands.GUICommand;
import com.duel.commands.SetSpawnCommand;
import com.duel.commands.SettingsCommand;
import com.duel.listeners.ChatListener;
import com.duel.listeners.PlayerSettingsListener;
import com.duel.listeners.DuelListener;
import com.duel.listeners.InventoryListener;
import com.duel.listeners.PlayerJoinListener;
import com.duel.managers.ArenaManager;
import com.duel.managers.ChunkPreloader;
import com.duel.managers.RoundPullManager;
import com.duel.managers.WorldPoolManager;
import com.duel.managers.ConversationManager;
import com.duel.DuelPlaceholders;
import com.duel.managers.DuelManager;
import com.duel.managers.DuelRequestManager;
import com.duel.managers.KitManager;
import com.duel.managers.PlayerKitManager;
import com.duel.managers.QueueManager;
import com.duel.managers.SettingsManager;
import com.duel.database.DatabaseManager;
import com.duel.managers.HologramManager;
import com.duel.managers.StatsManager;
import com.duel.party.PartyManager;
import com.duel.party.PartyListener;
import com.duel.party.command.PartyCommand;
import com.duel.managers.PartyDuelManager;
import com.duel.managers.TabIsolationManager;
import com.duel.managers.DuelScoreboardManager;
import com.duel.managers.SpawnManager;
import com.duel.managers.SpawnFlightManager;
import com.duel.managers.CountryManager;
import com.duel.listeners.CountryFlagListener;
import com.duel.managers.HotbarManager;
import com.duel.gui.SpectateGUI;
import com.duel.listeners.SpectateGUIListener;
import com.duel.listeners.PartyDuelListener;
import com.duel.listeners.ShiftClickDuelListener;
import com.duel.trim.TrimManager;
import com.duel.trim.TrimListener;
import com.duel.managers.RematchManager;
import org.bukkit.plugin.java.JavaPlugin;

public class DuelPlugin extends JavaPlugin {

    private static DuelPlugin instance;

    private ArenaManager        arenaManager;
    private WorldPoolManager    worldPoolManager;
    private ChunkPreloader      chunkPreloader;
    private RoundPullManager    roundPullManager;
    private KitManager          kitManager;
    private PlayerKitManager    playerKitManager;
    private QueueManager        queueManager;
    private DuelManager         duelManager;
    private DuelRequestManager  duelRequestManager;
    private ConversationManager conversationManager;
    private DatabaseManager     databaseManager;
    private SettingsManager     settingsManager;
    private StatsManager        statsManager;
    private HologramManager     hologramManager;
    private PlayerJoinListener  playerJoinListener;
    private SpectateCommand     spectateCommand;
    private PartyManager        partyManager;
    private PartyDuelManager    partyDuelManager;
    private TabIsolationManager tabIsolationManager;
    private DuelScoreboardManager duelScoreboardManager;
    private com.duel.managers.PartyScoreboardManager partyScoreboardManager;
    private SpawnManager        spawnManager;
    private SpawnFlightManager  spawnFlightManager;
    private CountryManager      countryManager;
    private com.duel.managers.TabFlagManager tabFlagManager;
    private HotbarManager       hotbarManager;
    private SpectateGUI         spectateGUI;
    private com.duel.managers.FlySpectatorManager flySpectatorManager;
    private TrimManager         trimManager;
    private com.duel.managers.WeeklyResetManager weeklyResetManager;
    private RematchManager      rematchManager;
    private com.duel.tier.TierManager tierManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // Init database (must be first)
        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.init();

        // Init managers
        this.arenaManager        = new ArenaManager(this);
        this.worldPoolManager    = new WorldPoolManager(this);
        this.chunkPreloader      = new ChunkPreloader(this);
        this.roundPullManager    = new RoundPullManager(this);
        this.kitManager          = new KitManager(this);
        this.playerKitManager    = new PlayerKitManager(this);
        this.queueManager        = new QueueManager(this);
        this.duelManager         = new DuelManager(this);
        this.duelRequestManager  = new DuelRequestManager(this);
        this.conversationManager = new ConversationManager(this);
        this.settingsManager     = new SettingsManager(this);
        this.statsManager        = new StatsManager(this);
        this.tierManager         = new com.duel.tier.TierManager(this);
        getServer().getPluginManager().registerEvents(tierManager, this);
        this.hologramManager     = new HologramManager(this);
        this.weeklyResetManager  = new com.duel.managers.WeeklyResetManager(this);
        getCommand("weeklyreset").setExecutor((sender, cmd, label, args) -> {
            if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
                weeklyResetManager.resetNow();
                sender.sendMessage("§aWeekly reset started.");
            } else sender.sendMessage("§cThis wipes ALL tops and gives the ranks now. Use: /weeklyreset confirm");
            return true;
        });

        // Init party manager
        this.partyManager         = new PartyManager(this);
        this.partyDuelManager     = new PartyDuelManager(this);
        this.tabIsolationManager  = new TabIsolationManager(this);
        this.tabIsolationManager.setEnabled(getConfig().getBoolean("tab-isolation.enabled", true));
        this.tabIsolationManager.setHideFromOutsiders(getConfig().getBoolean("tab-isolation.hide-from-outsiders", false));
        this.duelScoreboardManager = new DuelScoreboardManager(this);
        this.partyScoreboardManager = new com.duel.managers.PartyScoreboardManager(this);
        this.spawnManager         = new SpawnManager(this);
        this.spawnFlightManager   = new SpawnFlightManager(this);
        this.countryManager       = new CountryManager(this);
        this.tabFlagManager       = new com.duel.managers.TabFlagManager(this);
        getServer().getPluginManager().registerEvents(new CountryFlagListener(this), this);
        getServer().getPluginManager().registerEvents(new com.duel.listeners.BorderGuardListener(this), this);
        this.hotbarManager        = new HotbarManager(this);
        this.spectateGUI          = new SpectateGUI(this);
        this.flySpectatorManager  = new com.duel.managers.FlySpectatorManager(this);

        // Init trim manager
        this.trimManager    = new TrimManager(this);
        this.rematchManager = new RematchManager(this);

        // Register commands
        GUICommand guiCmd = new GUICommand(this);
        getCommand("GUI").setExecutor(guiCmd);
        getCommand("GUI").setTabCompleter(guiCmd);

        DuelCommand duelCmd = new DuelCommand(this);
        com.duel.commands.BarrierCommand barrierCmd = new com.duel.commands.BarrierCommand(this);
        com.duel.commands.RoundSceneCommand roundSceneCmd = new com.duel.commands.RoundSceneCommand(this);
        getCommand("roundscene").setExecutor(roundSceneCmd);
        getCommand("roundscene").setTabCompleter(roundSceneCmd);
        getCommand("duelbarrier").setExecutor(barrierCmd);
        getCommand("duelbarrier").setTabCompleter(barrierCmd);
        getCommand("duel").setExecutor(duelCmd);
        getCommand("duel").setTabCompleter(duelCmd);

        getCommand("duelaccept").setExecutor(new DuelRespondCommand(this, true));
        getCommand("dueldecline").setExecutor(new DuelRespondCommand(this, false));

        this.spectateCommand = new SpectateCommand(this);
        getCommand("spectate").setExecutor(spectateCommand);
        getCommand("spectate").setTabCompleter(spectateCommand);

        getCommand("leave").setExecutor(new com.duel.commands.LeaveCommand(this));

        getCommand("settings").setExecutor(new SettingsCommand(this));

        com.duel.commands.TierCommand tierCmd = new com.duel.commands.TierCommand(this);
        getCommand("tier").setExecutor(tierCmd);
        getCommand("tier").setTabCompleter(tierCmd);
        com.duel.commands.TierTopCommand tierTopCmd = new com.duel.commands.TierTopCommand(this);
        getCommand("tiertop").setExecutor(tierTopCmd);
        getCommand("tiertop").setTabCompleter(tierTopCmd);

        getCommand("setspawn").setExecutor(new SetSpawnCommand(this));
        getServer().getPluginManager().registerEvents(new SpectateGUIListener(this), this);
        getServer().getPluginManager().registerEvents(new com.duel.gui.TierTopGUI(this), this);
        getServer().getPluginManager().registerEvents(new com.duel.listeners.SpectateListener(this), this);
        getServer().getPluginManager().registerEvents(new com.duel.listeners.FlySpectatorListener(this), this);
        if (getServer().getPluginManager().getPlugin("packetevents") != null) {
            try {
                com.duel.listeners.ExplosionPacketGuard.register(this);
                flySpectatorManager.setPacketGuard(true);
                getLogger().info("PacketEvents found: spectator explosion knockback blocked at packet level.");
            } catch (Throwable t) {
                getLogger().warning("PacketEvents hook failed, using fallback: " + t);
            }
        }
        getServer().getPluginManager().registerEvents(new com.duel.listeners.AdvancementListener(), this);

        PartyCommand partyCmd = new PartyCommand(this);
        getCommand("party").setExecutor(partyCmd);
        getCommand("party").setTabCompleter(partyCmd);

        com.duel.commands.TeamCommand teamCmd = new com.duel.commands.TeamCommand(this);
        getCommand("team").setExecutor(teamCmd);
        getCommand("team").setTabCompleter(teamCmd);

        // Register listeners
        this.playerJoinListener = new PlayerJoinListener(this);
        getServer().getPluginManager().registerEvents(playerJoinListener, this);
        getServer().getPluginManager().registerEvents(new PartyListener(this), this);
        getServer().getPluginManager().registerEvents(new PartyDuelListener(this), this);
        getServer().getPluginManager().registerEvents(new InventoryListener(this), this);
        getServer().getPluginManager().registerEvents(new com.duel.listeners.RoundsAnvilListener(this), this);
        getServer().getPluginManager().registerEvents(new DuelListener(this), this);
        getServer().getPluginManager().registerEvents(new com.duel.listeners.FrozenItemListener(), this);
        getServer().getPluginManager().registerEvents(new com.duel.listeners.WorldDirtyListener(this), this);
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerSettingsListener(this), this);
        getServer().getPluginManager().registerEvents(new TrimListener(this), this);
        getServer().getPluginManager().registerEvents(new ShiftClickDuelListener(this), this);

        // PlaceholderAPI
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new DuelPlaceholders(this).register();
            getLogger().info("[DuelPlugin] PlaceholderAPI hooked — %duel_active% / %duel_queued% ready.");
        }

        worldPoolManager.start();

        getLogger().info("DuelPlugin v1.0 enabled — STARK NETWORK");
    }

    @Override
    public void onDisable() {
        if (weeklyResetManager != null) weeklyResetManager.stop();
        try { if (flySpectatorManager != null && flySpectatorManager.hasPacketGuard()) com.duel.listeners.ExplosionPacketGuard.unregister(); } catch (Throwable ignored) {}
        if (flySpectatorManager != null) flySpectatorManager.shutdown();
        if (spawnFlightManager != null) spawnFlightManager.stop();
        if (tabFlagManager != null) tabFlagManager.resetAll();
        hologramManager.removeAll();
        duelManager.cleanupAll();
        partyDuelManager.cleanupAll();
        if (tabIsolationManager != null) tabIsolationManager.restoreEveryone();
        if (worldPoolManager != null) worldPoolManager.shutdown();
        // إصلاح 1: تنظيف الـ static party list عند الإيقاف
        partyManager.shutdown();
        databaseManager.close();
        getLogger().info("DuelPlugin disabled.");
    }

    // ── Getters ───────────────────────────────
    public static DuelPlugin getInstance()               { return instance; }
    public ArenaManager getArenaManager()                { return arenaManager; }
    public WorldPoolManager getWorldPoolManager()        { return worldPoolManager; }
    public ChunkPreloader getChunkPreloader()            { return chunkPreloader; }
    public RoundPullManager getRoundPullManager()        { return roundPullManager; }
    public KitManager getKitManager()                    { return kitManager; }
    public PlayerKitManager getPlayerKitManager()        { return playerKitManager; }
    public QueueManager getQueueManager()                { return queueManager; }
    public DuelManager getDuelManager()                  { return duelManager; }
    public DuelRequestManager getDuelRequestManager()    { return duelRequestManager; }
    public ConversationManager getConversationManager()  { return conversationManager; }
    public SettingsManager getSettingsManager()          { return settingsManager; }
    public StatsManager getStatsManager()                { return statsManager; }
    public HologramManager getHologramManager()          { return hologramManager; }
    public DatabaseManager getDatabaseManager()          { return databaseManager; }
    public PlayerJoinListener getPlayerJoinListener()    { return playerJoinListener; }
    public PartyManager getPartyManager()                { return partyManager; }
    public PartyDuelManager getPartyDuelManager()        { return partyDuelManager; }
    public SpectateCommand getSpectateCommand()          { return spectateCommand; }
    public TabIsolationManager getTabIsolationManager()          { return tabIsolationManager; }
    public DuelScoreboardManager getDuelScoreboardManager()      { return duelScoreboardManager; }
    public com.duel.managers.PartyScoreboardManager getPartyScoreboardManager() { return partyScoreboardManager; }
    public SpawnManager getSpawnManager()                        { return spawnManager; }
    public SpawnFlightManager getSpawnFlightManager()            { return spawnFlightManager; }
    public CountryManager getCountryManager()                    { return countryManager; }
    public com.duel.managers.TabFlagManager getTabFlagManager()  { return tabFlagManager; }
    public HotbarManager getHotbarManager()                      { return hotbarManager; }
    public SpectateGUI getSpectateGUI()                          { return spectateGUI; }
    public com.duel.managers.FlySpectatorManager getFlySpectatorManager() { return flySpectatorManager; }
    public TrimManager getTrimManager()                          { return trimManager; }
    public RematchManager getRematchManager()                    { return rematchManager; }
    public com.duel.tier.TierManager getTierManager()            { return tierManager; }
}
