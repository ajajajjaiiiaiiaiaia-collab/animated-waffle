package me.starkmc.antitrigger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

/**
 * Conservative triggerbot detection.
 *
 * A triggerbot attacks the very moment the crosshair touches an enemy and never swings at nothing.
 * A human reacts slower than that and wastes swings. A player is flagged only when BOTH signals
 * appear together across a whole window of hits. Nobody is punished automatically unless the owner enables it.
 */
public final class AntiTriggerPlugin extends JavaPlugin implements Listener
{
    private final Map<UUID, PlayerState> states = new HashMap<>();
    private final Set<UUID> alertsOff = new HashSet<>();
    /** Last time (ms) each player moved the mouse. Written by PacketEvents (netty threads) or by the tick task. */
    private final Map<UUID, Long> lastRotation = new ConcurrentHashMap<>();
    private PacketHook packetHook;
    private final Set<UUID> debugTargets = new HashSet<>();
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();

    // settings
    private double range;
    private double raySize;
    private int windowSize;
    private int minSamples;
    private int windowTicks;
    private int instantTicks;
    private double minInstantRatio;
    private boolean fastTrigEnabled = true;
    private int fastTrigSamples = 6;
    private double fastTrigRatio = 0.85;
    private int maxAirSwings;
    private boolean requirePassive;
    private long passiveMs;
    private double minPassiveRatio;
    private int maxPing;
    private double minTps;
    private String bedrockPrefix;
    private String bypassPermission;
    private double alertVl;
    private double decayPerMinute;
    private long resetAfterMillis;
    private boolean punishEnabled;
    private final List<PunishAction> punishActions = new ArrayList<>();
    private boolean packetEventsWanted;
    private String prefix;
    private String alertFormat;
    private String offAlertFormat;

    // off-target check: attacks where the crosshair was away from the victim
    private boolean offEnabled;
    private int offWindowSize;
    private int offMinHits;
    private double offMinRatio;
    private int offGraceTicks;
    private double offRaySize;
    private int offWindowTicks;
    private double offVl;

    // evidence recording and AI review
    private final Gson gson = new GsonBuilder().create();
    private final AiReviewer reviewer = new AiReviewer();
    private boolean evidenceEnabled;
    private int evidenceTicks;
    private int evidenceAttackTicks;
    private boolean evidenceOnAlert;
    private String evidenceFolder;
    private int evidenceKeepFiles;
    private boolean reviewEnabled;
    private String reviewApiKey;
    private List<String> reviewApiKeys = new ArrayList<>();
    private String reviewProvider;
    private String reviewModel;
    private double reviewMinConfidence;
    private int reviewTimeout;
    private long reviewCooldownMs;
    private boolean reviewPunishOnFailure;
    private boolean reviewPrimary;
    private boolean preventEnabled = true;
    private boolean pNoSwingEnabled = true;
    private int pNoSwingMisses = 2;
    private int pNoSwingWindow = 100;
    private boolean pWallEnabled = true;
    private boolean pInvEnabled = true;
    private boolean pUseEnabled = true;
    private int pUseTicks = 3;
    private double preventFlagVl = 1.0;
    private int preventMaxPing = 250;
    private boolean pReachEnabled = true;
    private double pReachMax = 4.2;
    private double reachPingMargin = 0.3;
    private boolean pMultiEnabled = true;
    private int pMultiMax = 2;
    private int pMultiTicks = 6;
    private boolean pCpsEnabled = true;
    private int pCpsMax = 24;
    private boolean pBehindEnabled = true;
    private double pBehindAngle = 100.0;
    private boolean pTrigEnabled = true;
    private int pTrigStreak = 3;
    private boolean pTrigFlag = false;
    private boolean pTrigKeys = false;
    private double pTrigJitter = 1.5;
    private boolean pHitEnabled = true;
    private boolean pHitFlag = false;
    private double pHitMargin = 0.15;
    private double pHitPingMargin = 0.04;
    private int pTrigWindow = 100;
    private boolean multiEnabled = true;
    private int multiTargets = 3;
    private int multiTicks = 10;
    private int multiCooldownTicks = 160;
    private double multiVl = 1.5;
    private List<String> multiCommands = new ArrayList<>();
    private boolean reachEnabled = true;
    private double reachLimit = 3.5;
    private int reachTicks = 80;
    private int reachHits = 3;
    private int reachCooldownTicks = 160;
    private double reachVl = 1.5;
    private List<String> reachCommands = new ArrayList<>();
    private boolean cpsEnabled = true;
    private int cpsLimit = 26;
    private int cpsCooldownTicks = 200;
    private double cpsVl = 1.0;
    private List<String> cpsCommands = new ArrayList<>();
    private boolean corrEnabled = true;
    private boolean corrBanOnAiFail = true;
    private long corrWindowMs = 180000L;
    private int corrTypes = 2;
    private int corrRepeat = 4;
    private long corrRepeatWindowMs = 600000L;
    private long corrCooldownMs = 60000L;
    private List<String> corrCommands = new ArrayList<>();
    private long primaryCooldownMs;
    private int primaryConfirmations;
    private long primaryWindowMs;
    private final List<String> primaryCommands = new ArrayList<>();
    private double gateOff;
    private int gateSnaps;
    private int gateCps;
    private double gateReach;
    private double gateInstant;
    private History history;
    private int maxPingSwing;

    // ElytraTarget check
    private boolean elytraEnabled;
    private double elytraMaxDistance;
    private double elytraLockAngle;
    private int elytraLockTicks;
    private int elytraBoostCount;
    private int elytraWindowTicks;
    private double elytraMaxStd;
    private double elytraMaxMean;
    private double elytraMinMean;
    private int elytraSwapCycles;
    private double elytraVl;
    private boolean elytraRequireLock = true;
    private int elytraCooldownTicks;
    private List<String> elytraCommands = new ArrayList<>();
    private String elytraAlertFormat;
    private boolean aimEnabled;
    private int aimMinSamples;
    private double aimMaxCv;
    private double aimMinToward;
    private double aimMinMean;
    private double aimMaxMean;
    private double aimVl;
    private int aimCooldownTicks;
    private String aimAlertFormat;
    private List<String> aimCommands = new ArrayList<>();

    /** One tier of the punish ladder. */
    private record PunishAction(double vl, List<String> commands)
    {
    }

    private File logFile;

    @Override
    public void onEnable()
    {
        this.saveDefaultConfig();
        this.loadSettings();
        this.logFile = new File(this.getDataFolder(), "flags.log");
        this.history = new History(new File(this.getDataFolder(), "history.json"));
        this.history.load();
        Bukkit.getScheduler().runTaskTimer(this, this.history::saveIfDirty, 6000L, 6000L);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tickAll, 1L, 1L);
        this.hookPacketEvents();
        this.getLogger().info("AntiTrigger enabled. Automatic punishment: " + (this.punishEnabled ? "ON (" + this.punishActions.size() + " actions)" : "off (alerts only)") + ". Mouse timing: " + (this.packetHook != null ? "PacketEvents (ms)" : "tick-based"));
    }

    @Override
    public void onDisable()
    {
        this.history.saveIfDirty();
        this.unhookPacketEvents();
    }

    private void hookPacketEvents()
    {
        this.unhookPacketEvents();

        if (!this.packetEventsWanted || Bukkit.getPluginManager().getPlugin("packetevents") == null)
        {
            return;
        }

        try
        {
            PacketHook hook = new PacketHook(this);
            hook.register();
            this.packetHook = hook;
        }
        catch (Throwable ex)
        {
            this.packetHook = null;
            this.getLogger().warning("PacketEvents is installed but could not be used, falling back to tick timing: " + ex);
        }
    }

    private void unhookPacketEvents()
    {
        if (this.packetHook != null)
        {
            try
            {
                this.packetHook.unregister();
            }
            catch (Throwable ignored)
            {
            }

            this.packetHook = null;
        }
    }

    /** Called from PacketEvents (any thread). */
    void recordRotation(UUID id, long millis)
    {
        this.lastRotation.put(id, millis);
    }

    private void loadSettings()
    {
        this.reloadConfig();
        FileConfiguration c = this.getConfig();
        this.range = c.getDouble("detection.range", 4.0);
        this.raySize = c.getDouble("detection.ray-size", 0.1);
        this.windowSize = Math.max(4, c.getInt("detection.window-size", 12));
        this.minSamples = Math.min(this.windowSize, Math.max(4, c.getInt("detection.min-samples", 10)));
        this.windowTicks = Math.max(20, c.getInt("detection.window-seconds", 40) * 20);
        this.instantTicks = Math.max(1, c.getInt("detection.instant-ticks", 2));
        this.minInstantRatio = c.getDouble("detection.min-instant-ratio", 0.8);
        this.fastTrigEnabled = c.getBoolean("detection.fast-path.enabled", true);
        this.fastTrigSamples = Math.max(4, c.getInt("detection.fast-path.samples", 6));
        this.fastTrigRatio = Math.min(1.0, Math.max(0.5, c.getDouble("detection.fast-path.ratio", 0.85)));
        this.maxAirSwings = Math.max(0, c.getInt("detection.max-air-swings", 1));
        this.requirePassive = c.getBoolean("detection.require-passive", true);
        this.passiveMs = Math.max(0, c.getLong("detection.passive-ms", 100L));
        this.minPassiveRatio = c.getDouble("detection.min-passive-ratio", 0.5);
        this.maxPing = c.getInt("safety.max-ping", 150);
        this.minTps = c.getDouble("safety.min-tps", 18.0);
        this.bedrockPrefix = c.getString("safety.bedrock-prefix", ".");
        this.bypassPermission = c.getString("safety.bypass-permission", "antitrigger.bypass");
        this.alertVl = c.getDouble("violations.alert-vl", 2.0);
        this.decayPerMinute = c.getDouble("violations.decay-per-minute", 0.0);
        this.resetAfterMillis = Math.max(0L, c.getLong("violations.reset-after-seconds", 300L)) * 1000L;
        this.punishEnabled = c.getBoolean("punish.enabled", false);
        this.packetEventsWanted = c.getBoolean("packetevents.enabled", true);
        this.punishActions.clear();

        for (Map<?, ?> raw : c.getMapList("punish.actions"))
        {
            Object vl = raw.get("vl");
            Object cmds = raw.get("commands");

            if (vl instanceof Number number && cmds instanceof List<?> list)
            {
                List<String> commands = new ArrayList<>();

                for (Object o : list)
                {
                    commands.add(String.valueOf(o));
                }

                this.punishActions.add(new PunishAction(number.doubleValue(), commands));
            }
        }

        this.punishActions.sort((a, b) -> Double.compare(a.vl(), b.vl()));
        this.prefix = c.getString("messages.prefix", "&c[AntiTrigger] ");
        this.alertFormat = c.getString("messages.alert", "&f%player% &7flagged");
        this.offAlertFormat = c.getString("messages.offtarget-alert", "&f%player% &7hits off crosshair &8| &7off-target &f%ratio%% &8| &7attacks &f%samples% &8| &7VL &f%vl% &8| &7ping &f%ping%ms");
        this.offEnabled = c.getBoolean("offtarget.enabled", true);
        this.offWindowSize = Math.max(3, c.getInt("offtarget.window-size", 6));
        this.offMinHits = Math.min(this.offWindowSize, Math.max(3, c.getInt("offtarget.min-hits", 4)));
        this.offMinRatio = c.getDouble("offtarget.min-ratio", 0.6);
        this.offGraceTicks = Math.max(0, c.getInt("offtarget.grace-ticks", 3));
        this.offRaySize = Math.max(0.1, c.getDouble("offtarget.ray-size", 0.5));
        this.offWindowTicks = Math.max(20, c.getInt("offtarget.window-seconds", 20) * 20);
        this.offVl = c.getDouble("offtarget.vl", 1.0);
        this.evidenceEnabled = c.getBoolean("evidence.enabled", true);
        this.evidenceTicks = Math.max(40, Math.min(200, c.getInt("evidence.seconds", 6) * 20));
        this.evidenceAttackTicks = Math.max(100, Math.min(1200, c.getInt("evidence.attack-seconds", 30) * 20));
        this.evidenceOnAlert = c.getBoolean("evidence.save-on-alert", true);
        this.evidenceFolder = c.getString("evidence.folder", "evidence");
        this.evidenceKeepFiles = Math.max(10, c.getInt("evidence.keep-files", 300));
        this.reviewEnabled = c.getBoolean("review.enabled", false);
        this.reviewApiKey = c.getString("review.api-key", "");
        List<String> pool = new ArrayList<>();
        String single = this.reviewApiKey == null ? "" : this.reviewApiKey.trim();

        if (!single.isEmpty())
        {
            pool.add(single);
        }

        for (String k : c.getStringList("review.api-keys"))
        {
            String t = k == null ? "" : k.trim();

            if (!t.isEmpty() && !pool.contains(t))
            {
                pool.add(t);
            }
        }

        this.reviewApiKeys = pool;
        this.reviewer.setKeys(pool.size() > 1 || !c.getStringList("review.api-keys").isEmpty() ? pool : List.of(), c.getLong("review.key-cooldown-minutes", 30L));
        this.reviewer.notice = line ->
        {
            this.getLogger().warning(line);
            this.writeLog(line);
        };
        this.reviewProvider = "gemini".equalsIgnoreCase(c.getString("review.provider", "anthropic")) ? "gemini" : "anthropic";
        String configuredModel = c.getString("review.model", "");
        this.reviewModel = configuredModel == null || configuredModel.isBlank() ? ("gemini".equals(this.reviewProvider) ? "gemini-3.6-flash" : "claude-sonnet-5-5") : configuredModel.trim();
        this.reviewMinConfidence = c.getDouble("review.min-confidence", 0.85);
        this.reviewTimeout = Math.max(3, c.getInt("review.timeout-seconds", 12));
        this.reviewCooldownMs = Math.max(0, c.getInt("review.cooldown-seconds", 20)) * 1000L;
        this.reviewPunishOnFailure = "punish".equalsIgnoreCase(c.getString("review.on-failure", "alert"));
        this.reviewPrimary = "primary".equalsIgnoreCase(c.getString("review.mode", "confirm"));
        this.preventEnabled = c.getBoolean("prevent.enabled", true);
        this.pNoSwingEnabled = c.getBoolean("prevent.noswing.enabled", true);
        this.pNoSwingMisses = Math.max(1, c.getInt("prevent.noswing.min-misses", 2));
        this.pNoSwingWindow = Math.max(20, c.getInt("prevent.noswing.window-ticks", 100));
        this.pWallEnabled = c.getBoolean("prevent.wall.enabled", true);
        this.pInvEnabled = c.getBoolean("prevent.inventory.enabled", true);
        this.pUseEnabled = c.getBoolean("prevent.useitem.enabled", true);
        this.pUseTicks = Math.max(1, c.getInt("prevent.useitem.min-ticks", 3));
        this.preventFlagVl = c.getDouble("prevent.flag-vl", 1.0);
        this.preventMaxPing = Math.max(50, c.getInt("prevent.max-ping", 250));
        this.pReachEnabled = c.getBoolean("prevent.reach.enabled", true);
        this.pReachMax = c.getDouble("prevent.reach.max", 4.2);
        this.reachPingMargin = Math.max(0.0, c.getDouble("prevent.reach.ping-margin", 0.3));
        this.pMultiEnabled = c.getBoolean("prevent.multitarget.enabled", true);
        this.pMultiMax = Math.max(1, c.getInt("prevent.multitarget.max-targets", 2));
        this.pMultiTicks = Math.max(2, c.getInt("prevent.multitarget.ticks", 6));
        this.pCpsEnabled = c.getBoolean("prevent.cps.enabled", true);
        this.pCpsMax = Math.max(10, c.getInt("prevent.cps.max", 24));
        this.pBehindEnabled = c.getBoolean("prevent.behind.enabled", true);
        this.pBehindAngle = c.getDouble("prevent.behind.angle", 100.0);
        this.pTrigEnabled = c.getBoolean("prevent.trigger.enabled", true);
        this.pTrigStreak = Math.max(2, c.getInt("prevent.trigger.streak", 3));
        this.pTrigKeys = c.getBoolean("prevent.trigger.require-no-keyboard", false);
        this.pTrigJitter = Math.max(0.0, c.getDouble("prevent.trigger.jitter-degrees", 1.5));
        this.pTrigFlag = c.getBoolean("prevent.trigger.flag", false);
        this.pHitEnabled = c.getBoolean("prevent.hitbox.enabled", true);
        this.pHitFlag = c.getBoolean("prevent.hitbox.flag", false);
        this.pHitMargin = Math.max(0.1, c.getDouble("prevent.hitbox.margin", 0.15));
        this.pHitPingMargin = Math.max(0.0, c.getDouble("prevent.hitbox.ping-margin", 0.04));
        this.pTrigWindow = Math.max(20, c.getInt("prevent.trigger.window-ticks", 100));
        this.multiEnabled = c.getBoolean("fast.multiaura.enabled", true);
        this.multiTargets = Math.max(2, c.getInt("fast.multiaura.targets", 3));
        this.multiTicks = Math.max(2, c.getInt("fast.multiaura.ticks", 10));
        this.multiCooldownTicks = Math.max(1, c.getInt("fast.multiaura.cooldown-seconds", 8)) * 20;
        this.multiVl = c.getDouble("fast.multiaura.vl", 1.5);
        this.multiCommands = new ArrayList<>(c.getStringList("fast.multiaura.commands"));
        this.reachEnabled = c.getBoolean("fast.reach.enabled", true);
        this.reachLimit = c.getDouble("fast.reach.limit", 3.5);
        this.reachTicks = Math.max(10, c.getInt("fast.reach.window-ticks", 80));
        this.reachHits = Math.max(2, c.getInt("fast.reach.hits", 3));
        this.reachCooldownTicks = Math.max(1, c.getInt("fast.reach.cooldown-seconds", 8)) * 20;
        this.reachVl = c.getDouble("fast.reach.vl", 1.5);
        this.reachCommands = new ArrayList<>(c.getStringList("fast.reach.commands"));
        this.cpsEnabled = c.getBoolean("fast.highcps.enabled", true);
        this.cpsLimit = Math.max(10, c.getInt("fast.highcps.limit", 26));
        this.cpsCooldownTicks = Math.max(1, c.getInt("fast.highcps.cooldown-seconds", 10)) * 20;
        this.cpsVl = c.getDouble("fast.highcps.vl", 1.0);
        this.cpsCommands = new ArrayList<>(c.getStringList("fast.highcps.commands"));
        this.corrEnabled = c.getBoolean("corroboration.enabled", true);
        this.corrBanOnAiFail = c.getBoolean("corroboration.ban-if-ai-fails", true);
        this.corrWindowMs = Math.max(30, c.getInt("corroboration.window-seconds", 180)) * 1000L;
        this.corrTypes = Math.max(2, c.getInt("corroboration.distinct-types", 2));
        this.corrRepeat = Math.max(2, c.getInt("corroboration.repeat-flags", 4));
        this.corrRepeatWindowMs = Math.max(60, c.getInt("corroboration.repeat-window-seconds", 600)) * 1000L;
        this.corrCooldownMs = Math.max(10, c.getInt("corroboration.cooldown-seconds", 60)) * 1000L;
        this.corrCommands = new ArrayList<>(c.getStringList("corroboration.commands"));
        this.primaryCooldownMs = Math.max(5, c.getInt("review.primary.cooldown-seconds", 15)) * 1000L;
        this.primaryConfirmations = Math.max(1, c.getInt("review.primary.confirmations", 1));
        this.primaryWindowMs = Math.max(10, c.getInt("review.primary.confirm-window-seconds", 180)) * 1000L;
        this.primaryCommands.clear();
        this.primaryCommands.addAll(c.getStringList("review.primary.commands"));
        this.gateOff = c.getDouble("review.primary.gate.off-crosshair-ratio", 0.35);
        this.gateSnaps = Math.max(1, c.getInt("review.primary.gate.snap-attacks", 2));
        this.gateCps = Math.max(5, c.getInt("review.primary.gate.cps", 18));
        this.gateReach = c.getDouble("review.primary.gate.reach", 3.6);
        this.gateInstant = c.getDouble("review.primary.gate.instant-ratio", 0.6);
        this.maxPingSwing = Math.max(20, c.getInt("review.primary.max-ping-swing", 70));
        this.elytraEnabled = c.getBoolean("elytratarget.enabled", true);
        this.elytraMaxDistance = Math.max(10.0, c.getDouble("elytratarget.max-distance", 100.0));
        this.elytraLockAngle = Math.max(1.0, c.getDouble("elytratarget.lock-angle", 6.0));
        this.elytraLockTicks = Math.max(5, c.getInt("elytratarget.lock-ticks", 20));
        this.elytraBoostCount = Math.max(3, c.getInt("elytratarget.boost-count", 4));
        this.elytraWindowTicks = Math.max(40, c.getInt("elytratarget.window-seconds", 8) * 20);
        this.elytraMaxStd = Math.max(0.2, c.getDouble("elytratarget.max-stddev-ticks", 1.5));
        this.elytraMaxMean = Math.max(4.0, c.getDouble("elytratarget.max-mean-ticks", 18.0));
        this.elytraMinMean = Math.max(0.0, c.getDouble("elytratarget.min-mean-ticks", 6.0));
        this.elytraSwapCycles = Math.max(2, c.getInt("elytratarget.swap-cycles", 3));
        this.elytraVl = c.getDouble("elytratarget.vl", 2.0);
        this.elytraRequireLock = c.getBoolean("elytratarget.require-lock", true);
        this.elytraCooldownTicks = Math.max(20, c.getInt("elytratarget.cooldown-seconds", 6) * 20);
        this.elytraCommands = new ArrayList<>(c.getStringList("elytratarget.commands"));
        this.aimEnabled = c.getBoolean("aimassist.enabled", true);
        this.aimMinSamples = Math.max(10, c.getInt("aimassist.min-samples", 25));
        this.aimMaxCv = Math.max(0.05, c.getDouble("aimassist.max-ratio-cv", 0.35));
        this.aimMinToward = Math.min(1.0, Math.max(0.5, c.getDouble("aimassist.min-toward-fraction", 0.9)));
        this.aimMinMean = Math.max(0.0, c.getDouble("aimassist.min-ratio-mean", 0.04));
        this.aimMaxMean = Math.max(this.aimMinMean + 0.01, c.getDouble("aimassist.max-ratio-mean", 0.7));
        this.aimVl = c.getDouble("aimassist.vl", 1.5);
        this.aimCooldownTicks = Math.max(20, c.getInt("aimassist.cooldown-seconds", 8) * 20);
        this.aimCommands = new ArrayList<>(c.getStringList("aimassist.commands"));
        this.aimAlertFormat = c.getString("messages.aimassist-alert", "&f%player% &7AimAssist &8| &7pull &f%pull% &8(variation &f%cv%&8) &8| &7samples &f%samples% &8| &7VL &f%vl% &8| &7ping &f%ping%ms");
        this.elytraAlertFormat = c.getString("messages.elytra-alert", "&f%player% &7ElytraTarget &8| &7rockets &f%boosts% &8(every &f%mean% &8ticks, &7+-&f%sd%&8) &8| &7swap cycles &f%swaps% &8| &7aim lock &f%lock% &8| &7VL &f%vl% &8| &7ping &f%ping%ms");
    }

    private PlayerState state(Player player)
    {
        return this.states.computeIfAbsent(player.getUniqueId(), id ->
        {
            PlayerState created = new PlayerState();
            created.uuid = id;
            return created;
        });
    }

    /** Once per tick: remember how long each player's crosshair has been on the same enemy. */
    private void tickAll()
    {
        for (Player player : Bukkit.getOnlinePlayers())
        {
            PlayerState state = this.state(player);
            state.boxes.addLast(new PlayerState.BoxSnap(Bukkit.getCurrentTick(), player.getBoundingBox()));

            while (state.boxes.size() > 16)
            {
                state.boxes.pollFirst();
            }

            GameMode mode = player.getGameMode();

            if (player.isDead() || (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE))
            {
                state.target = null;
                state.onTargetTicks = 0;
                continue;
            }

            Location eye = player.getEyeLocation();
            Location feet = player.getLocation();

            if (state.hasPos && (Math.abs(feet.getX() - state.lastPx) > 0.002 || Math.abs(feet.getY() - state.lastPy) > 0.002 || Math.abs(feet.getZ() - state.lastPz) > 0.002))
            {
                state.lastMoveMs = System.currentTimeMillis();
            }

            state.lastPx = feet.getX();
            state.lastPy = feet.getY();
            state.lastPz = feet.getZ();
            state.hasPos = true;

            if (this.packetHook == null && (Math.abs(eye.getYaw() - state.lastYaw) > 0.001F || Math.abs(eye.getPitch() - state.lastPitch) > 0.001F))
            {
                this.lastRotation.put(player.getUniqueId(), System.currentTimeMillis());
            }

            float dYaw = state.frames.isEmpty() ? 0.0F : Evidence.wrap(eye.getYaw() - state.lastYaw);
            float dPitch = state.frames.isEmpty() ? 0.0F : eye.getPitch() - state.lastPitch;
            state.lastYaw = eye.getYaw();
            state.lastPitch = eye.getPitch();

            RayTraceResult result = player.getWorld().rayTraceEntities(eye, eye.getDirection(), this.range, this.raySize, entity -> entity instanceof Player other && !other.equals(player) && other.getGameMode() != GameMode.SPECTATOR && !other.isDead());
            UUID hit = result != null && result.getHitEntity() != null ? result.getHitEntity().getUniqueId() : null;

            if (hit == null)
            {
                hit = this.lagTarget(player, eye, Bukkit.getCurrentTick());
            }

            if (hit != null)
            {
                state.seenVictim = hit;
                state.seenTick = Bukkit.getCurrentTick();
            }

            if (hit != null && hit.equals(state.target))
            {
                state.onTargetTicks++;
            }
            else
            {
                state.target = hit;
                state.onTargetTicks = hit == null ? 0 : 1;
            }

            this.recordFrame(player, state, eye, dYaw, dPitch, hit);
            this.trackElytra(player, state, eye);
        }
    }

    /** A swing that is not part of a hit means the player swung at nothing. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent event)
    {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING)
        {
            return;
        }

        PlayerState state = this.state(event.getPlayer());
        int now = Bukkit.getCurrentTick();
        state.click(now);
        state.lastSwingTick = now;

        if (state.pendingSwing)
        {
            state.pendingHadSwing = true;
        }

        if (now - state.lastAttackTick <= 1)
        {
            return; // the swing that belongs to a hit we already counted
        }

        state.airSwings.addLast(now);
        state.trim(now, this.windowTicks, this.windowSize);
    }

    /**
     * Impossible hits never land: a hit from beyond any lag-excused reach, a third different victim inside a blink,
     * a hit above the click-speed ceiling, or a hit on someone behind the attacker is cancelled. The attack is still
     * recorded so the detectors keep flagging the player.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHitPrevent(EntityDamageByEntityEvent event)
    {
        if (!this.preventEnabled || !(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof Player victim)
            || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK)
        {
            return;
        }

        if (attacker.hasPermission(this.bypassPermission) || attacker.getGameMode() == org.bukkit.GameMode.CREATIVE
            || attacker.getGameMode() == org.bukkit.GameMode.SPECTATOR
            || (!this.bedrockPrefix.isEmpty() && attacker.getName().startsWith(this.bedrockPrefix))
            || attacker.getPing() > this.preventMaxPing)
        {
            return;
        }

        PlayerState state = this.state(attacker);
        int now = Bukkit.getCurrentTick();

        while (!state.hitTicks.isEmpty() && now - state.hitTicks.peekFirst() > 20)
        {
            state.hitTicks.pollFirst();
            state.hitVictims.pollFirst();
        }

        if (state.pendingSwing && !state.pendingHadSwing)
        {
            state.swingMisses.addLast(now);
        }

        while (!state.swingMisses.isEmpty() && now - state.swingMisses.peekFirst() > this.pNoSwingWindow)
        {
            state.swingMisses.pollFirst();
        }

        state.pendingSwing = true;
        state.pendingHadSwing = now - state.lastSwingTick <= 1;

        Location eye = attacker.getEyeLocation();
        String reason = null;
        String rule = null;
        boolean hitBurst = false;
        double dist = this.lagReach(attacker, victim, eye, now, Evidence.aim(eye, victim)[0]);

        if (this.pReachEnabled && dist > this.pReachMax + this.reachExtra(attacker) + this.reachPingMargin * attacker.getPing() / 100.0)
        {
            reason = "reach " + String.format(Locale.ROOT, "%.2f", dist);
        }

        if (reason == null && this.pMultiEnabled)
        {
            java.util.Set<UUID> others = new java.util.HashSet<>();
            java.util.Iterator<Integer> ti = state.hitTicks.iterator();
            java.util.Iterator<UUID> vi = state.hitVictims.iterator();

            while (ti.hasNext() && vi.hasNext())
            {
                int t = ti.next();
                UUID v = vi.next();

                if (now - t <= this.pMultiTicks && !v.equals(victim.getUniqueId()))
                {
                    others.add(v);
                }
            }

            if (others.size() >= this.pMultiMax)
            {
                reason = "third target in " + this.pMultiTicks + " ticks";
            }
        }

        if (reason == null && this.pCpsEnabled && state.cps(now) > this.pCpsMax)
        {
            reason = "clicks " + state.cps(now) + "/s";
        }

        if (reason == null && this.pBehindEnabled && dist > Evidence.MIN_RELIABLE_DISTANCE)
        {
            double lagAngle = this.lagCompensated(attacker, victim, eye, now)[1];

            if (lagAngle > Math.min(175.0, this.pBehindAngle + attacker.getPing() / 4.0))
            {
                reason = "victim behind, " + Math.round(lagAngle) + " deg off";
            }
        }

        if (reason == null && this.pNoSwingEnabled && state.swingMisses.size() >= this.pNoSwingMisses)
        {
            reason = "no swing animation (" + state.swingMisses.size() + " hits without a swing)";
            rule = "noswing";
        }

        if (reason == null && this.pInvEnabled)
        {
            boolean container = attacker.getOpenInventory().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING;
            boolean own = state.invOpen && now - state.lastInvClickTick <= 100;

            if (container || own)
            {
                reason = "attacking with an inventory open";
                rule = "invhit";
            }
        }

        if (reason == null && this.pUseEnabled && attacker.isHandRaised() && attacker.getActiveItemUsedTime() >= this.pUseTicks)
        {
            Material held = attacker.getActiveItem().getType();

            if (held.isEdible() || held == Material.POTION || held == Material.MILK_BUCKET || held == Material.HONEY_BOTTLE || held == Material.SHIELD)
            {
                reason = "attacking while using " + held.name().toLowerCase(Locale.ROOT);
                rule = "useitem";
            }
        }

        if (reason == null && this.pTrigEnabled)
        {
            long nowMs = System.currentTimeMillis();
            boolean onTarget = victim.getUniqueId().equals(state.target) && state.onTargetTicks >= 1;
            long sinceRot = nowMs - this.lastRotation.getOrDefault(attacker.getUniqueId(), 0L);
            long sinceMove = nowMs - state.lastMoveMs;
            boolean idle = dist >= Evidence.MIN_RELIABLE_DISTANCE && onTarget && state.onTargetTicks <= this.instantTicks && this.mouseStill(state, sinceRot) && (!this.pTrigKeys || sinceMove >= this.passiveMs);

            if (idle)
            {
                if (now - state.lastTrigTick > this.pTrigWindow)
                {
                    state.trigStreak = 0;
                }

                state.trigStreak++;
                state.lastTrigTick = now;

                if (state.trigStreak >= this.pTrigStreak)
                {
                    reason = "no mouse or keyboard input before " + state.trigStreak + " hits in a row";
                    rule = "triggerlock";
                }
            }
            else
            {
                state.trigStreak = 0;
            }
        }

        if (reason == null && this.pHitEnabled && dist >= 1.0 && this.missesHitbox(attacker, victim, eye, now))
        {
            reason = "crosshair outside the hitbox";
            rule = "hitbox";
            state.hitboxMisses.addLast(now);

            while (!state.hitboxMisses.isEmpty() && now - state.hitboxMisses.peekFirst() > 200)
            {
                state.hitboxMisses.pollFirst();
            }

            hitBurst = state.hitboxMisses.size() >= 3;
        }

        if (reason == null && this.pWallEnabled && this.throughWall(eye, victim))
        {
            reason = "hit through a solid block";
            rule = "wallhit";
        }

        state.hitTicks.addLast(now);
        state.hitVictims.addLast(victim.getUniqueId());

        if (reason == null)
        {
            return;
        }

        if (rule != null && (!"triggerlock".equals(rule) || this.pTrigFlag) && (!"hitbox".equals(rule) || this.pHitFlag || hitBurst))
        {
            Integer lastRule = state.lastRuleTick.get(rule);

            if (lastRule == null || now - lastRule >= 60)
            {
                state.lastRuleTick.put(rule, now);
                this.flagFast(attacker, state, rule, reason, this.preventFlagVl, new ArrayList<>());
            }
        }

        event.setCancelled(true);
        state.lastAttackTick = now;

        if ("triggerlock".equals(rule) && this.skipReason(attacker) == null)
        {
            state.samples.addLast(new PlayerState.Sample(state.onTargetTicks, now, true));
            state.trim(now, this.windowTicks, this.windowSize);
            this.evaluate(attacker, state);
        }

        if ("hitbox".equals(rule) && this.offEnabled && this.skipReason(attacker) == null)
        {
            this.recordAttempt(attacker, state, victim, now, false);
        }

        this.recordAttack(attacker, state, victim, now, event.getDamage());
        this.fastChecks(attacker, state, now);
        state.blockedCount++;
        long logMs = System.currentTimeMillis();
        state.blockedLog.addLast(reason + " vs " + victim.getName() + " ping " + attacker.getPing());
        state.blockedLogMs.addLast(logMs);

        while (state.blockedLog.size() > 40 || (!state.blockedLogMs.isEmpty() && logMs - state.blockedLogMs.peekFirst() > 120000L))
        {
            state.blockedLog.pollFirst();
            state.blockedLogMs.pollFirst();
        }

        this.debug(attacker, "hit blocked: " + reason);
        long ms = System.currentTimeMillis();

        if (ms - state.lastBlockAlert >= 10000L)
        {
            this.alertStaff("&f" + attacker.getName() + " &7hit blocked &8| &7" + reason + " &8| &7blocked &f" + state.blockedCount + " &8| &7ping &f" + attacker.getPing() + "ms");
            this.writeLog(attacker.getName() + " hit blocked: " + reason + " total=" + state.blockedCount + " ping=" + attacker.getPing());
            state.lastBlockAlert = ms;
            state.blockedCount = 0;
        }
    }

    /**
     * Extra reach a player legitimately has right now: a spear (1.21.11+ attack_range component) or a raised
     * entity-interaction-range attribute. Returns the extra blocks to add, or 100 when the held item is a spear.
     */
    private double reachExtra(Player player)
    {
        String held = player.getInventory().getItemInMainHand().getType().name();

        if (held.endsWith("_SPEAR") || held.equals("SPEAR"))
        {
            return 100.0;
        }

        org.bukkit.attribute.AttributeInstance range = player.getAttribute(org.bukkit.attribute.Attribute.ENTITY_INTERACTION_RANGE);
        return range == null ? 0.0 : Math.max(0.0, range.getValue() - 3.0);
    }

    /** True when every line from the eye to the victim (nearest point, centre, head) is blocked by a solid block. */
    private boolean mouseStill(PlayerState state, long sinceRotationMs)
    {
        if (sinceRotationMs >= this.passiveMs)
        {
            return true;
        }

        double sum = 0.0;
        int n = 0;
        java.util.Iterator<Evidence.Frame> it = state.frames.descendingIterator();

        while (it.hasNext() && n < 3)
        {
            Evidence.Frame f = it.next();
            sum += Math.abs(f.dYaw()) + Math.abs(f.dPitch());
            n++;
        }

        return n == 3 && sum < this.pTrigJitter;
    }

    private boolean missesHitbox(Player attacker, Player victim, Location eye, int now)
    {
        org.bukkit.util.Vector origin = eye.toVector();
        org.bukkit.util.Vector dir = eye.getDirection();
        double pingTicks = attacker.getPing() / 50.0;
        double margin = this.pHitMargin + this.pHitPingMargin * pingTicks;
        PlayerState attackerState = this.states.get(attacker.getUniqueId());

        if (attackerState != null && attackerState.vl >= 1.0)
        {
            margin *= 0.5;
        }

        java.util.List<org.bukkit.util.BoundingBox> boxes = new ArrayList<>();
        boxes.add(victim.getBoundingBox());
        PlayerState victimState = this.states.get(victim.getUniqueId());

        if (victimState != null)
        {
            int lo = Math.max(0, (int)Math.floor(pingTicks - 0.5));
            int hi = Math.min(15, (int)Math.ceil(pingTicks + 2.5));

            for (PlayerState.BoxSnap snap : victimState.boxes)
            {
                int age = now - snap.tick();

                if (age >= lo && age <= hi)
                {
                    boxes.add(snap.box());
                }
            }
        }

        for (org.bukkit.util.BoundingBox bb : boxes)
        {
            org.bukkit.util.BoundingBox grown = bb.clone().expand(margin);

            if (grown.contains(origin) || grown.rayTrace(origin, dir, 12.0) != null)
            {
                return false;
            }
        }

        return true;
    }

    private UUID lagTarget(Player player, Location eye, int now)
    {
        double pingTicks = player.getPing() / 50.0;
        int lo = Math.max(0, (int)Math.floor(pingTicks - 1.0));
        int hi = Math.min(15, (int)Math.ceil(pingTicks + 2.0));
        org.bukkit.util.Vector origin = eye.toVector();
        org.bukkit.util.Vector dir = eye.getDirection();
        double reach = this.range + 1.0;
        UUID best = null;
        double bestDist = Double.MAX_VALUE;

        for (Player other : player.getWorld().getNearbyPlayers(eye, reach))
        {
            if (other.equals(player) || other.getGameMode() == GameMode.SPECTATOR || other.isDead())
            {
                continue;
            }

            PlayerState otherState = this.states.get(other.getUniqueId());

            if (otherState == null)
            {
                continue;
            }

            for (PlayerState.BoxSnap snap : otherState.boxes)
            {
                int age = now - snap.tick();

                if (age < lo || age > hi)
                {
                    continue;
                }

                RayTraceResult r = snap.box().clone().expand(this.raySize).rayTrace(origin, dir, reach);

                if (r != null)
                {
                    double d = r.getHitPosition().distance(origin);

                    if (d < bestDist)
                    {
                        bestDist = d;
                        best = other.getUniqueId();
                    }

                    break;
                }
            }
        }

        return best;
    }

    private boolean webBetween(org.bukkit.World world, org.bukkit.util.Vector origin, org.bukkit.util.Vector dir, double len, org.bukkit.util.BoundingBox victimBox)
    {
        org.bukkit.util.BoundingBox near = victimBox.clone().expand(0.2);

        for (double d = 0.3; d < len - 0.1; d += 0.25)
        {
            int x = (int)Math.floor(origin.getX() + dir.getX() * d);
            int y = (int)Math.floor(origin.getY() + dir.getY() * d);
            int z = (int)Math.floor(origin.getZ() + dir.getZ() * d);

            if (!world.isChunkLoaded(x >> 4, z >> 4))
            {
                continue;
            }

            org.bukkit.block.Block block = world.getBlockAt(x, y, z);

            if (block.getType() == org.bukkit.Material.COBWEB && !org.bukkit.util.BoundingBox.of(block).overlaps(near))
            {
                return true;
            }
        }

        return false;
    }

    private boolean throughWall(Location eye, Player victim)
    {
        org.bukkit.World world = eye.getWorld();

        if (world == null || victim.getWorld() != world || eye.getBlock().getType().isSolid())
        {
            return false;
        }

        org.bukkit.util.Vector origin = eye.toVector();
        java.util.List<org.bukkit.util.BoundingBox> boxes = new ArrayList<>();
        boxes.add(victim.getBoundingBox());
        PlayerState victimState = this.states.get(victim.getUniqueId());

        if (victimState != null)
        {
            int i = 0;

            for (PlayerState.BoxSnap snap : victimState.boxes)
            {
                if (i++ % 3 == 0)
                {
                    boxes.add(snap.box());
                }
            }
        }

        for (org.bukkit.util.BoundingBox bb : boxes)
        {
            if (bb.clone().expand(0.3).contains(origin))
            {
                return false;
            }

            org.bukkit.util.Vector center = bb.getCenter();
            double[] ys = {bb.getMinY() + 0.1, center.getY(), bb.getMaxY() - 0.2};
            double[] xs = {bb.getMinX() + 0.05, bb.getMaxX() - 0.05};
            double[] zs = {bb.getMinZ() + 0.05, bb.getMaxZ() - 0.05};

            for (double y : ys)
            {
                for (double x : xs)
                {
                    for (double z : zs)
                    {
                        org.bukkit.util.Vector dir = new org.bukkit.util.Vector(x, y, z).subtract(origin);
                        double len = dir.length();

                        if (len < 0.9)
                        {
                            return false;
                        }

                        if (world.rayTraceBlocks(eye, dir.normalize(), len - 0.1, org.bukkit.FluidCollisionMode.NEVER, true) == null && !this.webBetween(world, origin, dir, len, bb))
                        {
                            return false;
                        }
                    }
                }
            }
        }

        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInvClick(org.bukkit.event.inventory.InventoryClickEvent event)
    {
        if (event.getWhoClicked() instanceof Player p)
        {
            PlayerState state = this.state(p);
            state.invOpen = true;
            state.lastInvClickTick = Bukkit.getCurrentTick();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInvClose(org.bukkit.event.inventory.InventoryCloseEvent event)
    {
        if (event.getPlayer() instanceof Player p)
        {
            this.state(p).invOpen = false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event)
    {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof Player victim))
        {
            return;
        }

        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK)
        {
            return;
        }

        PlayerState state = this.state(attacker);
        int now = Bukkit.getCurrentTick();
        state.lastAttackTick = now;
        this.recordAttack(attacker, state, victim, now, event.getFinalDamage());

        String skip = this.skipReason(attacker);

        if (skip != null)
        {
            this.debug(attacker, "hit ignored: " + skip);
            return;
        }

        this.fastChecks(attacker, state, now);

        boolean onTarget = victim.getUniqueId().equals(state.target) && state.onTargetTicks >= 1;

        if (this.offEnabled)
        {
            this.recordAttempt(attacker, state, victim, now, onTarget);
        }

        // Only judge hits where our own look-ray agrees the crosshair was on the victim.
        if (!onTarget)
        {
            this.debug(attacker, "hit ignored: server look-ray was not on " + victim.getName() + " (lag or hitbox mismatch)");
            return;
        }

        long sinceRotation = System.currentTimeMillis() - this.lastRotation.getOrDefault(attacker.getUniqueId(), 0L);
        state.samples.addLast(new PlayerState.Sample(state.onTargetTicks, now, this.mouseStill(state, sinceRotation)));
        this.debug(attacker, "hit on " + victim.getName() + ": crosshair on target for " + state.onTargetTicks + " tick(s) (instant if <= " + this.instantTicks + "), mouse still for " + sinceRotation + "ms (passive if >= " + this.passiveMs + "), air swings in window " + state.airSwings.size());
        state.trim(now, this.windowTicks, this.windowSize);
        this.evaluate(attacker, state);
    }

    /**
     * Every attack counts, hits that landed normally and hits where the crosshair was away from the victim.
     * A wide ray (and a short grace period after the ray last touched the victim) absorbs lag and hitbox rounding,
     * so only attacks that are clearly away from the victim count as "off".
     */
    private void recordAttempt(Player attacker, PlayerState state, Player victim, int now, boolean onTarget)
    {
        if (Evidence.aim(attacker.getEyeLocation(), victim)[0] < Evidence.MIN_RELIABLE_DISTANCE)
        {
            this.debug(attacker, "attack on " + victim.getName() + " not counted for off-target: closer than " + Evidence.MIN_RELIABLE_DISTANCE + " blocks, the aim angle is meaningless there");
            return;
        }

        boolean on = onTarget || (victim.getUniqueId().equals(state.seenVictim) && now - state.seenTick <= this.offGraceTicks) || this.wideRayHits(attacker, victim) || this.lagCompensated(attacker, victim, attacker.getEyeLocation(), now)[0] > 0.5;

        state.attempts.addLast(new PlayerState.Attempt(now, !on));
        state.trimAttempts(now, this.offWindowTicks, this.offWindowSize);

        int off = 0;

        for (PlayerState.Attempt attempt : state.attempts)
        {
            if (attempt.off())
            {
                off++;
            }
        }

        double ratio = (double)off / state.attempts.size();
        this.debug(attacker, "attack on " + victim.getName() + ": " + (on ? "on crosshair" : "OFF crosshair") + " | window off-target " + off + "/" + state.attempts.size() + " (" + Math.round(ratio * 100.0) + "%, need " + Math.round(this.offMinRatio * 100.0) + "% from " + this.offMinHits + " attacks)");

        if (state.attempts.size() >= this.offMinHits && ratio >= this.offMinRatio)
        {
            this.flagOffTarget(attacker, state, ratio, state.attempts.size());
            state.attempts.clear();
        }
    }

    private boolean wideRayHits(Player attacker, Player victim)
    {
        Location eye = attacker.getEyeLocation();
        RayTraceResult result = attacker.getWorld().rayTraceEntities(eye, eye.getDirection(), this.range + 1.0, this.offRaySize, entity -> entity.equals(victim));
        return result != null && victim.equals(result.getHitEntity());
    }

    private void flagOffTarget(Player player, PlayerState state, double ratio, int samples)
    {
        state.refreshVl(this.decayPerMinute, this.resetAfterMillis);
        state.vl += this.offVl;
        state.totalFlags++;
        state.lastFlagMillis = System.currentTimeMillis();

        this.history.flag(player.getUniqueId(), player.getName(), "offtarget");
        String ratioText = String.valueOf(Math.round(ratio * 100.0));
        String vlText = String.format(Locale.ROOT, "%.1f", state.vl);
        String line = this.offAlertFormat.replace("%player%", player.getName()).replace("%ratio%", ratioText).replace("%samples%", String.valueOf(samples)).replace("%vl%", vlText).replace("%ping%", String.valueOf(player.getPing()));

        this.writeLog(player.getName() + " vl=" + vlText + " OFF-TARGET=" + ratioText + "% attacks=" + samples + " ping=" + player.getPing() + " tps=" + String.format(Locale.ROOT, "%.1f", Bukkit.getTPS()[0]) + (state.vl >= this.alertVl ? " [ALERTED]" : ""));

        if (state.vl >= this.alertVl)
        {
            Component message = this.legacy.deserialize(this.prefix + line);

            for (Player staff : Bukkit.getOnlinePlayers())
            {
                if (staff.hasPermission("antitrigger.alerts") && !this.alertsOff.contains(staff.getUniqueId()))
                {
                    staff.sendMessage(message);
                }
            }

            Bukkit.getConsoleSender().sendMessage(message);
        }

        this.afterFlag(player, state, "offtarget", "off=" + ratioText + "% attacks=" + samples);

        if (this.punishEnabled)
        {
            this.runPunishActions(player, state, vlText);
        }
    }

    /** Cheap, strong checks on the attack list: several players hit in a blink, hits beyond vanilla reach, inhuman click speed. */
    private void fastChecks(Player player, PlayerState state, int now)
    {
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE || player.getGameMode() == org.bukkit.GameMode.SPECTATOR)
        {
            return;
        }

        if (this.pingSwing(state) > this.maxPingSwing)
        {
            return;
        }

        Evidence.AttackRec[] log = state.attackLog.toArray(new Evidence.AttackRec[0]);

        if (this.multiEnabled && now - state.lastMultiTick >= this.multiCooldownTicks)
        {
            java.util.Set<String> victims = new java.util.HashSet<>();

            for (Evidence.AttackRec a : log)
            {
                if (now - a.tick() <= this.multiTicks)
                {
                    victims.add(a.victim());
                }
            }

            if (victims.size() >= this.multiTargets)
            {
                state.lastMultiTick = now;
                this.flagFast(player, state, "multiaura", victims.size() + " different players hit within " + this.multiTicks + " ticks", this.multiVl, this.multiCommands);
            }
        }

        if (this.reachEnabled && now - state.lastReachTick >= this.reachCooldownTicks)
        {
            double extra = this.reachExtra(player) + this.reachPingMargin * player.getPing() / 100.0;
            int over = 0;
            double max = 0.0;

            for (Evidence.AttackRec a : log)
            {
                if (now - a.tick() <= this.reachTicks && a.dist() > this.reachLimit + extra)
                {
                    over++;
                    max = Math.max(max, a.dist());
                }
            }

            if (over >= this.reachHits)
            {
                state.lastReachTick = now;
                this.flagFast(player, state, "reach", over + " attacks beyond " + this.reachLimit + " blocks, max " + String.format(Locale.ROOT, "%.2f", max), this.reachVl, this.reachCommands);
            }
        }

        if (this.cpsEnabled && now - state.lastCpsTick >= this.cpsCooldownTicks)
        {
            int cps = state.cps(now);

            if (cps >= this.cpsLimit)
            {
                state.lastCpsTick = now;
                this.flagFast(player, state, "highcps", cps + " clicks in one second", this.cpsVl, this.cpsCommands);
            }
        }
    }

    private void flagFast(Player player, PlayerState state, String type, String detail, double vlAdd, List<String> commands)
    {
        state.refreshVl(this.decayPerMinute, this.resetAfterMillis);
        state.vl += vlAdd;
        state.totalFlags++;
        state.lastFlagMillis = System.currentTimeMillis();
        this.history.flag(player.getUniqueId(), player.getName(), type);
        String vlText = String.format(Locale.ROOT, "%.1f", state.vl);
        this.writeLog(player.getName() + " vl=" + vlText + " " + type + " " + detail + " ping=" + player.getPing());
        this.alertStaff("&f" + player.getName() + " &7" + type + " &8| &7" + detail + " &8| &7VL &f" + vlText + " &8| &7ping &f" + player.getPing() + "ms");
        this.afterFlag(player, state, type, detail);

        if (!this.punishEnabled)
        {
            return;
        }

        if (!commands.isEmpty())
        {
            if (this.forceScreen(player, state, type + ": " + detail))
            {
                return;
            }

            this.history.ban(player.getUniqueId(), player.getName());

            for (String command : commands)
            {
                String ready = command.replace("%player%", player.getName()).replace("%vl%", vlText).replace("%type%", type);
                this.writeLog(player.getName() + " running " + type + " action: " + ready);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), ready);
            }

            return;
        }

        this.runPunishActions(player, state, vlText);
    }

    /** Returns why a player's hits are not judged, or null when they are. */
    private String skipReason(Player player)
    {
        if (player.hasPermission(this.bypassPermission))
        {
            return "player has the bypass permission (" + this.bypassPermission + "); a '*' permission also grants it";
        }

        if (!this.bedrockPrefix.isEmpty() && player.getName().startsWith(this.bedrockPrefix))
        {
            return "Bedrock name prefix";
        }

        if (player.getPing() > this.maxPing)
        {
            return "ping " + player.getPing() + "ms is above max-ping " + this.maxPing;
        }

        double tps = Bukkit.getTPS()[0];

        if (tps < this.minTps)
        {
            return "server TPS " + String.format(Locale.ROOT, "%.1f", tps) + " is below min-tps " + this.minTps;
        }

        return null;
    }

    /** Sends a diagnostic line to staff (and console) when /antitrigger debug is on for this player. */
    private void debug(Player target, String text)
    {
        if (this.debugTargets.isEmpty() || !this.debugTargets.contains(target.getUniqueId()))
        {
            return;
        }

        Component message = this.legacy.deserialize("&8[AT-debug] &7" + target.getName() + ": &f" + text);

        for (Player staff : Bukkit.getOnlinePlayers())
        {
            if (staff.hasPermission("antitrigger.alerts") && !this.alertsOff.contains(staff.getUniqueId()))
            {
                staff.sendMessage(message);
            }
        }

        Bukkit.getConsoleSender().sendMessage(message);
    }

    private void evaluate(Player player, PlayerState state)
    {
        if (this.fastTrigEnabled && state.samples.size() >= this.fastTrigSamples)
        {
            int fast = 0;

            for (PlayerState.Sample sample : state.samples)
            {
                if (sample.reaction() <= this.instantTicks + 1 && sample.passive())
                {
                    fast++;
                }
            }

            double fastRatio = (double)fast / state.samples.size();

            if (fast >= this.fastTrigSamples && fastRatio >= this.fastTrigRatio)
            {
                this.flag(player, state, fastRatio, fastRatio, 0, state.samples.size());
                state.samples.clear();
                return;
            }
        }

        if (state.samples.size() < this.minSamples)
        {
            this.debug(player, "collecting hits: " + state.samples.size() + "/" + this.minSamples);
            return;
        }

        int instant = 0;
        int passive = 0;

        for (PlayerState.Sample sample : state.samples)
        {
            if (sample.reaction() <= this.instantTicks)
            {
                instant++;
            }

            if (sample.passive())
            {
                passive++;
            }
        }

        double ratio = (double)instant / state.samples.size();
        double passiveRatio = (double)passive / state.samples.size();
        int oldest = state.samples.peekFirst().tick();
        int air = 0;

        for (int tick : state.airSwings)
        {
            if (tick >= oldest)
            {
                air++;
            }
        }

        this.debug(player, "window: instant " + instant + "/" + state.samples.size() + " (" + Math.round(ratio * 100.0) + "%, need " + Math.round(this.minInstantRatio * 100.0) + "%), passive " + passive + "/" + state.samples.size() + " (" + Math.round(passiveRatio * 100.0) + "%, need " + (this.requirePassive ? Math.round(this.minPassiveRatio * 100.0) + "%" : "off") + "), air swings " + air + " (max " + this.maxAirSwings + ")");

        // All signals together: near-instant hits, almost no swings at nothing, and (optionally) a still mouse.
        if (ratio >= this.minInstantRatio && air <= this.maxAirSwings && (!this.requirePassive || passiveRatio >= this.minPassiveRatio))
        {
            this.flag(player, state, ratio, passiveRatio, air, state.samples.size());
            state.samples.clear();
        }
    }

    private void flag(Player player, PlayerState state, double ratio, double passiveRatio, int air, int samples)
    {
        state.refreshVl(this.decayPerMinute, this.resetAfterMillis);
        state.vl += 1.0;
        state.totalFlags++;
        state.lastFlagMillis = System.currentTimeMillis();

        this.history.flag(player.getUniqueId(), player.getName(), "instant");
        String ratioText = String.valueOf(Math.round(ratio * 100.0));
        String passiveText = String.valueOf(Math.round(passiveRatio * 100.0));
        String vlText = String.format(Locale.ROOT, "%.1f", state.vl);
        String line = this.alertFormat.replace("%player%", player.getName()).replace("%ratio%", ratioText).replace("%passive%", passiveText).replace("%air%", String.valueOf(air)).replace("%samples%", String.valueOf(samples)).replace("%vl%", vlText).replace("%ping%", String.valueOf(player.getPing()));

        this.writeLog(player.getName() + " vl=" + vlText + " instant=" + ratioText + "% passive=" + passiveText + "% airSwings=" + air + " hits=" + samples + " ping=" + player.getPing() + " tps=" + String.format(Locale.ROOT, "%.1f", Bukkit.getTPS()[0]) + (state.vl >= this.alertVl ? " [ALERTED]" : ""));

        if (state.vl >= this.alertVl)
        {
            Component message = this.legacy.deserialize(this.prefix + line);

            for (Player staff : Bukkit.getOnlinePlayers())
            {
                if (staff.hasPermission("antitrigger.alerts") && !this.alertsOff.contains(staff.getUniqueId()))
                {
                    staff.sendMessage(message);
                }
            }

            Bukkit.getConsoleSender().sendMessage(message);
        }

        this.afterFlag(player, state, "triggerbot", "instant=" + ratioText + "% passive=" + passiveText + "% air=" + air + " hits=" + samples);

        if (this.punishEnabled)
        {
            this.runPunishActions(player, state, vlText);
        }
    }

    private void runPunishActions(Player player, PlayerState state, String vlText)
    {
        for (int i = 0; i < this.punishActions.size(); i++)
        {
            PunishAction action = this.punishActions.get(i);

            if (state.vl >= action.vl() && state.firedActions.add(i))
            {
                if (this.reviewEnabled && !this.apiKey().isEmpty())
                {
                    this.requestReview(player, state, i, action, vlText);
                    continue;
                }

                this.history.ban(player.getUniqueId(), player.getName());
                this.executeAction(player.getName(), action, vlText);
            }
        }
    }

    private void executeAction(String name, PunishAction action, String vlText)
    {
        for (String command : action.commands())
        {
            String ready = command.replace("%player%", name).replace("%vl%", vlText);
            this.writeLog(name + " running action at VL " + action.vl() + ": " + ready);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), ready);
        }
    }

    private String apiKey()
    {
        String key = this.reviewApiKeys.isEmpty() ? "" : this.reviewApiKeys.get(0);

        if (key.isEmpty())
        {
            String env = System.getenv("gemini".equals(this.reviewProvider) ? "GEMINI_API_KEY" : "ANTHROPIC_API_KEY");
            key = env == null ? "" : env.trim();
        }

        return key;
    }

    private void alertStaff(String legacyLine)
    {
        Component message = this.legacy.deserialize(this.prefix + legacyLine);

        for (Player staff : Bukkit.getOnlinePlayers())
        {
            if (staff.hasPermission("antitrigger.alerts") && !this.alertsOff.contains(staff.getUniqueId()))
            {
                staff.sendMessage(message);
            }
        }

        Bukkit.getConsoleSender().sendMessage(message);
    }

    /** Remembers the flag and saves the evidence file once staff are alerted. */
    private void afterFlag(Player player, PlayerState state, String type, String detail)
    {
        state.addHistory("[" + LocalDateTime.now().withNano(0) + "] " + type + " vl=" + String.format(Locale.ROOT, "%.1f", state.vl) + " " + detail);

        if (this.evidenceEnabled && this.evidenceOnAlert && state.vl >= this.alertVl)
        {
            this.saveEvidence(player, state, type, detail);
        }

        this.corroborate(player, state, type, false);
    }

    /**
     * Strong signal without waiting for the long ladder: two different detectors agreeing on the same player
     * inside a few minutes, or one detector firing again and again, runs the corroboration commands.
     */
    private void corroborate(Player player, PlayerState state, String type, boolean aiConfirmed)
    {
        if (player == null)
        {
            return;
        }

        String name = player.getName();

        if (!this.corrEnabled || !this.punishEnabled || this.corrCommands.isEmpty())
        {
            return;
        }

        long ms = System.currentTimeMillis();
        java.util.ArrayDeque<Long> times = state.flagTimes.computeIfAbsent(type, k -> new java.util.ArrayDeque<>());
        times.addLast(ms);
        long keep = Math.max(this.corrWindowMs, this.corrRepeatWindowMs);

        while (!times.isEmpty() && ms - times.peekFirst() > keep)
        {
            times.pollFirst();
        }

        int distinct = 0;

        for (java.util.ArrayDeque<Long> q : state.flagTimes.values())
        {
            if (!q.isEmpty() && ms - q.peekLast() <= this.corrWindowMs)
            {
                distinct++;
            }
        }

        int repeats = 0;

        for (long t : times)
        {
            if (ms - t <= this.corrRepeatWindowMs)
            {
                repeats++;
            }
        }

        String why = null;

        if (distinct >= this.corrTypes)
        {
            why = distinct + " different detectors agree";
        }
        else if (repeats >= this.corrRepeat)
        {
            why = type + " flagged " + repeats + " times";
        }

        if (why == null || ms - state.lastCorroborateMillis < this.corrCooldownMs)
        {
            return;
        }

        state.lastCorroborateMillis = ms;
        state.flagTimes.clear();
        if (!aiConfirmed && this.forceScreen(player, state, "corroboration: " + why + " (" + type + ")"))
        {
            this.alertStaff("&e" + name + " &7corroborated: &f" + why + " &8(" + type + ") &7- AI is checking before any ban");
            return;
        }

        this.alertStaff("&c" + name + " &7corroborated: &f" + why + " &8(" + type + ")");
        this.history.ban(state.uuid, name);

        for (String command : this.corrCommands)
        {
            String ready = command.replace("%player%", name).replace("%vl%", "0").replace("%type%", type);
            this.writeLog(name + " running corroboration action (" + why + "): " + ready);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), ready);
        }
    }

    /** One frame per tick for the last few seconds. The focus is the player being fought, so no extra searching is needed. */
    private void recordFrame(Player player, PlayerState state, Location eye, float dYaw, float dPitch, UUID rayHit)
    {
        if (!this.evidenceEnabled)
        {
            return;
        }

        int now = Bukkit.getCurrentTick();

        if (rayHit != null)
        {
            state.focus = rayHit;
            state.focusUntil = now + 100;
        }

        String targetName = null;
        double dist = -1.0;
        double angle = -1.0;
        double yawErr = 0.0;
        double pitchErr = 0.0;

        if (state.focus != null && now <= state.focusUntil)
        {
            Player victim = Bukkit.getPlayer(state.focus);

            if (victim != null && victim.isOnline() && !victim.isDead() && victim.getWorld().equals(player.getWorld()))
            {
                double[] aim = Evidence.aim(eye, victim);
                targetName = victim.getName();
                dist = aim[0];
                angle = aim[1];
                yawErr = aim[2];
                pitchErr = aim[3];
            }
        }

        state.frames.addLast(new Evidence.Frame(now, eye.getX(), eye.getY(), eye.getZ(), eye.getYaw(), eye.getPitch(), dYaw, dPitch, player.isSprinting(), player.isSneaking(), player.isOnGround(), player.getPing(), targetName, dist, angle, yawErr, pitchErr, rayHit != null));

        while (state.frames.size() > this.evidenceTicks)
        {
            state.frames.pollFirst();
        }
    }

    /** The crosshair counts as on the victim when it is within this many degrees of the hitbox. */
    private static final double LAG_ON_ANGLE = 1.0;

    /**
     * What the attacker's client most likely saw: the victim's hitbox as it was about one ping ago (plus the few ticks the
     * client takes to smooth movement). Returns {1 when the crosshair was on that hitbox else 0, smallest angle to it in degrees}.
     */
    private double[] lagCompensated(Player attacker, Player victim, Location eye, int now)
    {
        PlayerState victimState = this.states.get(victim.getUniqueId());
        double best = Evidence.angleToBox(eye, victim.getBoundingBox());

        if (victimState != null && !victimState.boxes.isEmpty())
        {
            double pingTicks = attacker.getPing() / 50.0;
            int lo = Math.max(0, (int)Math.floor(pingTicks - 0.5));
            int hi = Math.min(14, (int)Math.ceil(pingTicks + 3.0));
            best = 180.0;

            for (PlayerState.BoxSnap snap : victimState.boxes)
            {
                int age = now - snap.tick();

                if (age >= lo && age <= hi)
                {
                    best = Math.min(best, Evidence.angleToBox(eye, snap.box()));
                }
            }

            if (best >= 180.0)
            {
                best = Evidence.angleToBox(eye, victim.getBoundingBox());
            }
        }

        return new double[] {best <= LAG_ON_ANGLE ? 1.0 : 0.0, best};
    }

    /**
     * Distance from the eye to the victim as the attacker's screen showed it: the smallest eye-to-hitbox distance over the
     * victim's recent positions (about one ping ago, plus a little for interpolation). The current server position alone
     * overstates reach whenever the victim moves away between the attacker's click and the server tick.
     */
    private double lagReach(Player attacker, Player victim, Location eye, int now, double currentDist)
    {
        PlayerState victimState = this.states.get(victim.getUniqueId());

        if (victimState == null || victimState.boxes.isEmpty())
        {
            return currentDist;
        }

        double pingTicks = attacker.getPing() / 50.0;
        int lo = Math.max(0, (int)Math.floor(pingTicks - 0.5));
        int hi = Math.min(14, (int)Math.ceil(pingTicks + 3.0));
        double best = currentDist;

        for (PlayerState.BoxSnap snap : victimState.boxes)
        {
            int age = now - snap.tick();

            if (age >= lo && age <= hi)
            {
                org.bukkit.util.BoundingBox bb = snap.box();
                double ex = Math.max(bb.getMinX() - eye.getX(), Math.max(0.0, eye.getX() - bb.getMaxX()));
                double ey = Math.max(bb.getMinY() - eye.getY(), Math.max(0.0, eye.getY() - bb.getMaxY()));
                double ez = Math.max(bb.getMinZ() - eye.getZ(), Math.max(0.0, eye.getZ() - bb.getMaxZ()));
                best = Math.min(best, Math.sqrt(ex * ex + ey * ey + ez * ez));
            }
        }

        return best;
    }

    private int crowd(Player attacker, Player victim)
    {
        int count = 0;

        for (org.bukkit.entity.Entity entity : attacker.getNearbyEntities(6.0, 6.0, 6.0))
        {
            if (entity instanceof Player other && !other.equals(victim) && other.getGameMode() != GameMode.SPECTATOR)
            {
                count++;
            }
        }

        return count;
    }

    /** Name of another player standing between the crosshair and the victim, or null. */
    private String inFront(Player attacker, Player victim, Location eye, double victimDistance)
    {
        RayTraceResult result = attacker.getWorld().rayTraceEntities(eye, eye.getDirection(), Math.max(1.0, victimDistance), 0.1, entity -> entity instanceof Player other && !other.equals(attacker) && !other.equals(victim) && other.getGameMode() != GameMode.SPECTATOR);
        return result != null && result.getHitEntity() != null ? result.getHitEntity().getName() : null;
    }

    private double victimSpeed(Player victim)
    {
        PlayerState victimState = this.states.get(victim.getUniqueId());

        if (victimState == null || victimState.boxes.size() < 2)
        {
            return 0.0;
        }

        PlayerState.BoxSnap[] last = victimState.boxes.toArray(new PlayerState.BoxSnap[0]);
        org.bukkit.util.Vector a = last[last.length - 1].box().getCenter();
        org.bukkit.util.Vector b = last[last.length - 2].box().getCenter();
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void recordAttack(Player attacker, PlayerState state, Player victim, int now, double damage)
    {
        if (!this.evidenceEnabled)
        {
            return;
        }

        Location eye = attacker.getEyeLocation();
        double[] aim = Evidence.aim(eye, victim);
        aim[0] = this.lagReach(attacker, victim, eye, now, aim[0]);
        boolean on = victim.getUniqueId().equals(state.target) && state.onTargetTicks >= 1;
        long still = System.currentTimeMillis() - this.lastRotation.getOrDefault(attacker.getUniqueId(), 0L);
        double hitboxAngle = Evidence.angleToBox(eye, victim.getBoundingBox());
        double[] lag = this.lagCompensated(attacker, victim, eye, now);
        state.attackLog.addLast(new Evidence.AttackRec(now, victim.getName(), aim[0], on, this.wideRayHits(attacker, victim), on ? state.onTargetTicks : 0, still, state.cps(now), aim[1], aim[2], aim[3], damage, attacker.getAttackCooldown(), this.skipReason(attacker), hitboxAngle, lag[0] > 0.5, lag[1], this.crowd(attacker, victim), this.inFront(attacker, victim, eye, aim[0]), this.victimSpeed(victim)));

        while (!state.attackLog.isEmpty() && (state.attackLog.size() > 40 || now - state.attackLog.peekFirst().tick() > this.evidenceAttackTicks))
        {
            state.attackLog.pollFirst();
        }

        this.checkAimAssist(attacker, state, now);
        this.maybeScreen(attacker, state);
    }

    /**
     * Returns a short description when the recent tracking looks like aim assist, otherwise null.
     * Aim assist turns the view toward the target by a nearly constant share of the remaining error every tick.
     * Every axis that has enough samples must show it, and the pull must be steady, toward the target and of a plausible size.
     */
    private String aimAssistReason(JsonObject st)
    {
        double[] yaw = Evidence.yawTrack(st);
        double[] pitch = Evidence.pitchTrack(st);
        boolean yawEnough = yaw[0] >= this.aimMinSamples;
        boolean pitchEnough = pitch[0] >= this.aimMinSamples;

        if (!yawEnough && !pitchEnough)
        {
            return null;
        }

        if (yawEnough && !this.aimAxisPasses(yaw))
        {
            return null;
        }

        if (pitchEnough && !this.aimAxisPasses(pitch))
        {
            return null;
        }

        double[] used = yawEnough ? yaw : pitch;
        return "aim assist pull " + String.format(Locale.ROOT, "%.2f", used[2]) + " cv " + String.format(Locale.ROOT, "%.2f", used[3]) + " samples " + (int)used[0];
    }

    private boolean aimAxisPasses(double[] axis)
    {
        return axis[1] >= this.aimMinToward && axis[2] >= this.aimMinMean && axis[2] <= this.aimMaxMean && axis[3] <= this.aimMaxCv;
    }

    private void checkAimAssist(Player player, PlayerState state, int now)
    {
        if (!this.aimEnabled || now - state.lastAimCheckTick < 20 || now - state.lastAimFlagTick < this.aimCooldownTicks)
        {
            return;
        }

        state.lastAimCheckTick = now;

        if (this.skipReason(player) != null)
        {
            return;
        }

        Evidence.AttackRec last = state.attackLog.peekLast();

        if (last == null || state.frames.size() < this.aimMinSamples)
        {
            return;
        }

        JsonObject st = new JsonObject();
        Evidence.addAimStats(st, new ArrayList<>(state.frames));
        String why = this.aimAssistReason(st);

        if (why == null)
        {
            return;
        }

        state.lastAimFlagTick = now;
        double[] yaw = Evidence.yawTrack(st);
        double[] pitch = Evidence.pitchTrack(st);
        double[] used = yaw[0] >= this.aimMinSamples ? yaw : pitch;
        this.flagAimAssist(player, state, used[2], used[3], (int)used[0]);
    }

    private void flagAimAssist(Player player, PlayerState state, double pull, double cv, int samples)
    {
        state.refreshVl(this.decayPerMinute, this.resetAfterMillis);
        state.vl += this.aimVl;
        state.totalFlags++;
        state.lastFlagMillis = System.currentTimeMillis();

        this.history.flag(player.getUniqueId(), player.getName(), "aimassist");
        String vlText = String.format(Locale.ROOT, "%.1f", state.vl);
        String pullText = String.format(Locale.ROOT, "%.2f", pull);
        String cvText = String.format(Locale.ROOT, "%.2f", cv);
        String line = this.aimAlertFormat.replace("%player%", player.getName()).replace("%pull%", pullText).replace("%cv%", cvText).replace("%samples%", String.valueOf(samples)).replace("%vl%", vlText).replace("%ping%", String.valueOf(player.getPing()));
        this.writeLog(player.getName() + " vl=" + vlText + " AIM-ASSIST pull=" + pullText + " cv=" + cvText + " samples=" + samples + " ping=" + player.getPing());

        if (state.vl >= this.alertVl)
        {
            this.alertStaff(line);
        }

        this.afterFlag(player, state, "aimassist", "pull=" + pullText + " cv=" + cvText + " samples=" + samples);

        if (this.punishEnabled && !this.aimCommands.isEmpty())
        {
            this.alertStaff("&cAimAssist punished: &f" + player.getName());
            this.history.ban(player.getUniqueId(), player.getName());

            for (String command : this.aimCommands)
            {
                String ready = command.replace("%player%", player.getName()).replace("%vl%", vlText);
                this.writeLog(player.getName() + " running AimAssist action: " + ready);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), ready);
            }

            return;
        }

        this.elytraScreen(player, state, "aim assist pull " + pullText + " cv " + cvText);

        if (this.punishEnabled)
        {
            this.runPunishActions(player, state, vlText);
        }
    }

    /** Every tick while gliding: how long has the crosshair stayed on the nearest player? ElytraTarget locks on automatically. */
    private void trackElytra(Player player, PlayerState state, Location eye)
    {
        if (!this.elytraEnabled)
        {
            return;
        }

        if (!player.isGliding())
        {
            state.lockTicks = 0;
            state.lockMiss = 0;
            return;
        }

        Player nearest = null;
        double best = this.elytraMaxDistance * this.elytraMaxDistance;

        for (Player other : player.getWorld().getPlayers())
        {
            if (other.equals(player) || other.isDead() || other.getGameMode() == GameMode.SPECTATOR)
            {
                continue;
            }

            double d = other.getLocation().distanceSquared(player.getLocation());

            if (d < best)
            {
                best = d;
                nearest = other;
            }
        }

        if (nearest == null)
        {
            state.lockTicks = 0;
            state.lockMiss = 0;
            return;
        }

        double[] aim = Evidence.aim(eye, nearest);
        state.elytraTarget = nearest.getName();
        state.elytraDist = aim[0];
        state.elytraAngle = aim[1];

        if (aim[1] <= this.elytraLockAngle)
        {
            state.lockTicks++;
            state.lockMiss = 0;

            if (state.lockTicks >= this.elytraLockTicks)
            {
                state.lastLockTick = Bukkit.getCurrentTick();
            }
        }
        else if (++state.lockMiss > 2)
        {
            state.lockTicks = 0;
        }
    }

    /** A firework rocket used while gliding. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRocket(PlayerInteractEvent event)
    {
        if (!this.elytraEnabled)
        {
            return;
        }

        Action action = event.getAction();

        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK)
        {
            return;
        }

        if (event.getItem() == null || event.getItem().getType() != Material.FIREWORK_ROCKET)
        {
            return;
        }

        Player player = event.getPlayer();

        if (!player.isGliding())
        {
            return;
        }

        PlayerState state = this.state(player);
        int now = Bukkit.getCurrentTick();
        state.boosts.addLast(now);
        this.evaluateElytra(player, state, now);
    }

    /** Slot swap -> rocket -> swap back within a few ticks is ElytraTarget's visible firework routine. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSlotChange(PlayerItemHeldEvent event)
    {
        if (!this.elytraEnabled)
        {
            return;
        }

        Player player = event.getPlayer();

        if (!player.isGliding())
        {
            return;
        }

        PlayerState state = this.state(player);
        int now = Bukkit.getCurrentTick();
        Integer lastBoost = state.boosts.peekLast();

        if (lastBoost != null && now - lastBoost >= 1 && now - lastBoost <= 8 && lastBoost - state.lastSwapTick >= 0 && lastBoost - state.lastSwapTick <= 6)
        {
            state.swapCycles.addLast(now);
        }

        state.lastSwapTick = now;
        this.evaluateElytra(player, state, now);
    }

    private double[] boostStats(PlayerState state)
    {
        List<Integer> list = new ArrayList<>(state.boosts);

        if (list.size() < 2)
        {
            return new double[] {list.size(), 0.0, 0.0};
        }

        int m = list.size() - 1;
        double sum = 0.0;

        for (int i = 0; i < m; i++)
        {
            sum += list.get(i + 1) - list.get(i);
        }

        double mean = sum / m;
        double var = 0.0;

        for (int i = 0; i < m; i++)
        {
            double diff = (list.get(i + 1) - list.get(i)) - mean;
            var += diff * diff;
        }

        return new double[] {list.size(), mean, Math.sqrt(var / m)};
    }

    private void evaluateElytra(Player player, PlayerState state, int now)
    {
        while (!state.boosts.isEmpty() && now - state.boosts.peekFirst() > this.elytraWindowTicks)
        {
            state.boosts.pollFirst();
        }

        while (!state.swapCycles.isEmpty() && now - state.swapCycles.peekFirst() > this.elytraWindowTicks)
        {
            state.swapCycles.pollFirst();
        }

        if (now - state.lastElytraFlagTick < this.elytraCooldownTicks || this.skipReason(player) != null)
        {
            return;
        }

        double[] bs = this.boostStats(state);
        boolean regular = bs[0] >= this.elytraBoostCount && bs[1] >= this.elytraMinMean && bs[1] <= this.elytraMaxMean && bs[2] <= this.elytraMaxStd;
        boolean swapPattern = state.swapCycles.size() >= this.elytraSwapCycles;
        boolean lock = now - state.lastLockTick <= this.elytraWindowTicks;

        // A machine-regular rocket rhythm is required. Locking on an enemy and swapping slots are things skilled players do too.
        if (regular && (lock || (swapPattern && !this.elytraRequireLock)))
        {
            state.lastElytraFlagTick = now;
            this.flagElytra(player, state, (int)bs[0], bs[1], bs[2], state.swapCycles.size(), lock);
        }
    }

    private void flagElytra(Player player, PlayerState state, int boosts, double mean, double sd, int swaps, boolean lock)
    {
        state.refreshVl(this.decayPerMinute, this.resetAfterMillis);
        state.vl += this.elytraVl;
        state.totalFlags++;
        state.lastFlagMillis = System.currentTimeMillis();

        this.history.flag(player.getUniqueId(), player.getName(), "elytratarget");
        String vlText = String.format(Locale.ROOT, "%.1f", state.vl);
        String meanText = String.format(Locale.ROOT, "%.1f", mean);
        String sdText = String.format(Locale.ROOT, "%.1f", sd);
        String line = this.elytraAlertFormat.replace("%player%", player.getName()).replace("%boosts%", String.valueOf(boosts)).replace("%mean%", meanText).replace("%sd%", sdText).replace("%swaps%", String.valueOf(swaps)).replace("%lock%", lock ? "yes" : "no").replace("%vl%", vlText).replace("%ping%", String.valueOf(player.getPing()));
        this.writeLog(player.getName() + " vl=" + vlText + " ELYTRA-TARGET rockets=" + boosts + " mean=" + meanText + " sd=" + sdText + " swapCycles=" + swaps + " lock=" + lock + " ping=" + player.getPing());

        if (state.vl >= this.alertVl)
        {
            this.alertStaff(line);
        }

        this.afterFlag(player, state, "elytratarget", "rockets=" + boosts + " mean=" + meanText + " sd=" + sdText + " swaps=" + swaps + " lock=" + lock);

        if (this.punishEnabled && !this.elytraCommands.isEmpty())
        {
            this.alertStaff("&cElytraTarget punished: &f" + player.getName());
            this.history.ban(player.getUniqueId(), player.getName());

            for (String command : this.elytraCommands)
            {
                String ready = command.replace("%player%", player.getName()).replace("%vl%", vlText);
                this.writeLog(player.getName() + " running ElytraTarget action: " + ready);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), ready);
            }

            return;
        }

        this.elytraScreen(player, state, "elytra target pattern");

        if (this.punishEnabled)
        {
            this.runPunishActions(player, state, vlText);
        }
    }

    /** AI-primary mode: send an ElytraTarget flag straight to the AI. */
    private void elytraScreen(Player player, PlayerState state, String why)
    {
        if (!this.reviewEnabled || !this.reviewPrimary || state.reviewPending || this.apiKey().isEmpty())
        {
            return;
        }

        long ms = System.currentTimeMillis();

        if (ms - state.lastScreenMillis < this.primaryCooldownMs)
        {
            return;
        }

        state.reviewPending = true;
        state.lastScreenMillis = ms;
        state.lastReviewMillis = ms;
        String name = player.getName();
        String evidence = this.buildEvidence(player, state, "ai-screen", why).toString();

        Bukkit.getScheduler().runTaskAsynchronously(this, () ->
        {
            AiReviewer.Verdict verdict = this.reviewer.review(this.reviewProvider, this.apiKey(), this.reviewModel, this.reviewTimeout, evidence);
            Bukkit.getScheduler().runTask(this, () -> this.finishScreen(name, state, verdict));
        });
    }

    private JsonObject elytraInfo(Player player, PlayerState state, int now)
    {
        JsonObject o = new JsonObject();
        double[] bs = this.boostStats(state);
        o.addProperty("gliding_now", player.isGliding());
        o.addProperty("rockets_in_window", (int)bs[0]);
        o.addProperty("rocket_interval_mean_ticks", Evidence.r(bs[1], 1));
        o.addProperty("rocket_interval_stddev_ticks", Evidence.r(bs[2], 2));
        o.addProperty("slot_swap_rocket_swap_cycles", state.swapCycles.size());
        o.addProperty("aim_lock_ticks_now", state.lockTicks);
        o.addProperty("aim_lock_reached_seconds_ago", state.lastLockTick < -1000 ? -1.0 : Evidence.r((now - state.lastLockTick) / 20.0, 1));
        o.addProperty("nearest_player_while_gliding", state.elytraTarget == null ? "" : state.elytraTarget);
        o.addProperty("nearest_distance", Evidence.r(state.elytraDist, 1));
        o.addProperty("angle_to_nearest_deg", Evidence.r(state.elytraAngle, 1));
        JsonArray ticks = new JsonArray();

        for (int t : state.boosts)
        {
            ticks.add(Evidence.r((now - t) / 20.0, 2));
        }

        o.add("rocket_seconds_ago", ticks);
        return o;
    }

    /** Attacks that are recent enough to describe the current fight. */
    private List<Evidence.AttackRec> recentAttacks(PlayerState state, int nowTick)
    {
        List<Evidence.AttackRec> recent = new ArrayList<>();

        for (Evidence.AttackRec attack : state.attackLog)
        {
            if (nowTick - attack.tick() <= this.evidenceAttackTicks)
            {
                recent.add(attack);
            }
        }

        return recent;
    }

    /**
     * Same as recentAttacks, but restricted to one victim. Mixing attacks against different
     * victims into one statistics blob is what let the AI see things like "23 of 40 hits" that
     * were actually several unrelated fights stitched together — a single continuous fight is the
     * only thing the evidence (and the AI reading it) should ever be scored against.
     */
    private List<Evidence.AttackRec> recentAttacksAgainst(PlayerState state, int nowTick, String victimName)
    {
        List<Evidence.AttackRec> recent = new ArrayList<>();

        for (Evidence.AttackRec attack : state.attackLog)
        {
            if (nowTick - attack.tick() <= this.evidenceAttackTicks && attack.victim().equals(victimName))
            {
                recent.add(attack);
            }
        }

        return recent;
    }

    /**
     * AI-primary mode: loose, cheap gates decide whether a fight looks suspicious at all.
     * Only then is the AI asked, so ordinary fights cost nothing. The AI decides, not the VL.
     */
    private void maybeScreen(Player player, PlayerState state)
    {
        if (!this.reviewEnabled || !this.reviewPrimary || state.reviewPending || this.apiKey().isEmpty())
        {
            return;
        }

        long ms = System.currentTimeMillis();

        if (ms - state.lastScreenMillis < this.primaryCooldownMs || ms - state.lastScreenCheck < 1000L)
        {
            return;
        }

        state.lastScreenCheck = ms;
        int nowTick = Bukkit.getCurrentTick();
        Evidence.AttackRec last = state.attackLog.peekLast();

        if (last == null)
        {
            return;
        }

        // Scope to the victim of the most recent attack, so a player who fought several people in
        // this window is judged on one continuous fight at a time, never on a blended total.
        List<Evidence.AttackRec> recent = this.recentAttacksAgainst(state, nowTick, last.victim());

        if (recent.size() < 3 || this.skipReason(player) != null)
        {
            return;
        }

        if (this.pingSwing(state) > this.maxPingSwing)
        {
            return;
        }

        JsonObject st = Evidence.stats(state.frames, recent);
        int n = st.get("attacks_recorded").getAsInt();

        if (n < 3)
        {
            return;
        }

        double offFraction = st.get("attacks_crosshair_off_hitbox_lag_compensated").getAsDouble() / n;
        int instant = 0;

        for (Evidence.AttackRec attack : recent)
        {
            if (attack.dist() >= Evidence.MIN_RELIABLE_DISTANCE && attack.onCrosshair() && attack.reaction() <= this.instantTicks)
            {
                instant++;
            }
        }

        List<String> why = new ArrayList<>();

        if (offFraction >= this.gateOff)
        {
            why.add("off-crosshair " + Math.round(offFraction * 100.0) + "%");
        }

        String aimWhy = this.aimAssistReason(st);

        if (aimWhy != null)
        {
            why.add(aimWhy);
        }

        if (st.get("attacks_after_fast_snap_that_ended_on_target").getAsInt() >= this.gateSnaps)
        {
            why.add("fast snaps onto target");
        }

        if (st.get("max_cps").getAsInt() >= this.gateCps)
        {
            why.add("CPS " + st.get("max_cps").getAsInt());
        }

        if (st.get("max_attack_distance").getAsDouble() > this.gateReach + Math.min(this.reachExtra(player), 3.0) && this.reachExtra(player) < 50.0)
        {
            why.add("distance " + st.get("max_attack_distance").getAsDouble());
        }

        if ((double)instant / n >= this.gateInstant && st.get("avg_mouse_still_ms_before_hit").getAsDouble() >= this.passiveMs)
        {
            why.add("instant hits with still mouse");
        }

        if (why.isEmpty())
        {
            return;
        }

        state.reviewPending = true;
        state.lastScreenMillis = ms;
        state.lastReviewMillis = ms;
        String name = player.getName();
        String evidence = this.buildEvidence(player, state, "ai-screen", String.join(", ", why), last.victim()).toString();

        Bukkit.getScheduler().runTaskAsynchronously(this, () ->
        {
            AiReviewer.Verdict verdict = this.reviewer.review(this.reviewProvider, this.apiKey(), this.reviewModel, this.reviewTimeout, evidence);
            Bukkit.getScheduler().runTask(this, () -> this.finishScreen(name, state, verdict));
        });
    }

    /** Sends the evidence to the AI right now (no gate, no cooldown). The ban only happens if the AI confirms. Returns false when no AI is available. */
    private boolean forceScreen(Player player, PlayerState state, String why)
    {
        if (!this.reviewEnabled || this.apiKey().isEmpty())
        {
            return false;
        }

        if (state.reviewPending)
        {
            return true;
        }

        long ms = System.currentTimeMillis();
        state.reviewPending = true;
        state.forceBan = true;
        state.lastScreenMillis = ms;
        state.lastReviewMillis = ms;
        String name = player.getName();
        String evidence = this.buildEvidence(player, state, "ai-screen", why).toString();

        Bukkit.getScheduler().runTaskAsynchronously(this, () ->
        {
            AiReviewer.Verdict verdict = this.reviewer.review(this.reviewProvider, this.apiKey(), this.reviewModel, this.reviewTimeout, evidence);
            Bukkit.getScheduler().runTask(this, () -> this.finishScreen(name, state, verdict));
        });
        return true;
    }

    private void finishScreen(String name, PlayerState state, AiReviewer.Verdict verdict)
    {
        state.reviewPending = false;
        boolean force = state.forceBan;
        state.forceBan = false;
        String reasons = String.join("; ", verdict.reasons());
        this.writeLog(name + " AI screen: verdict=" + verdict.verdict() + " confidence=" + verdict.confidence() + " type=" + verdict.cheatType() + " error=" + verdict.error() + " reasons=" + reasons);

        if (verdict.error() != null)
        {
            if (force && this.corrBanOnAiFail && this.punishEnabled && !this.corrCommands.isEmpty())
            {
                this.alertStaff("&e" + name + " &7AI unavailable (" + verdict.error() + "), corroborated evidence is enough: banning");
                this.history.ban(state.uuid, name);

                for (String command : this.corrCommands)
                {
                    String ready = command.replace("%player%", name).replace("%vl%", "0").replace("%type%", "Cheating");
                    this.writeLog(name + " running corroboration action (AI unavailable): " + ready);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), ready);
                }
            }

            return;
        }

        this.history.verdict(state.uuid, name, verdict.verdict());
        long ms = System.currentTimeMillis();

        if (ms - state.lastAiHitMillis > this.primaryWindowMs)
        {
            state.aiHits = 0;
        }

        if (!"cheating".equalsIgnoreCase(verdict.verdict()) || verdict.confidence() < this.reviewMinConfidence)
        {
            return;
        }

        if (this.pingSwing(state) > this.maxPingSwing)
        {
            this.writeLog(name + " AI verdict dropped, unstable connection: ping moved by " + this.pingSwing(state) + "ms");
            return;
        }

        Player stillThere = Bukkit.getPlayerExact(name);

        if (stillThere != null)
        {
            String skip = this.skipReason(stillThere);

            if (skip != null)
            {
                this.writeLog(name + " AI verdict dropped, conditions changed: " + skip);
                return;
            }
        }

        state.aiHits++;
        state.lastAiHitMillis = ms;
        String summary = "&f" + name + " &8| &7AI &f" + verdict.verdict() + " &8| &7confidence &f" + Math.round(verdict.confidence() * 100.0) + "% &8| &7type &f" + verdict.cheatType() + " &8| &7" + reasons;

        if (!force && state.aiHits < this.primaryConfirmations)
        {
            this.alertStaff("&eAI suspects (" + state.aiHits + "/" + this.primaryConfirmations + "), checking again soon: " + summary);
            this.corroborate(Bukkit.getPlayerExact(name), state, "ai-suspect", true);
            state.lastScreenMillis = ms - this.primaryCooldownMs + 4000L;
            return;
        }

        Player online = Bukkit.getPlayerExact(name);

        if (online != null && this.evidenceEnabled)
        {
            this.saveEvidence(online, state, "ai-ban", reasons);
        }

        this.alertStaff("&aAI banned: " + summary);
        this.history.ban(state.uuid, name);
        state.aiHits = 0;

        for (String command : this.primaryCommands)
        {
            String ready = command.replace("%player%", name).replace("%vl%", "0").replace("%type%", this.cheatLabel(verdict.cheatType()));
            this.writeLog(name + " running AI action: " + ready);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), ready);
        }
    }

    /** How much the player's ping moved inside the recorded window. A big swing means a lag spike or a bad connection. */
    private int pingSwing(PlayerState state)
    {
        int min = Integer.MAX_VALUE;
        int max = 0;

        for (Evidence.Frame f : state.frames)
        {
            min = Math.min(min, f.ping());
            max = Math.max(max, f.ping());
        }

        return max == 0 ? 0 : max - min;
    }

    /** The cheat name for a ban reason. Anything unknown becomes the neutral "Hacks". */
    private String cheatLabel(String type)
    {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);

        return switch (t)
        {
            case "triggerbot" -> "Triggerbot";
            case "killaura" -> "Killaura";
            case "aim_assist" -> "AimAssist";
            case "autoclicker" -> "Autoclicker";
            case "reach" -> "Reach";
            default -> "Hacks";
        };
    }

    private JsonObject buildEvidence(Player player, PlayerState state, String type, String detail)
    {
        return this.buildEvidence(player, state, type, detail, null);
    }

    /**
     * victimFilter, when non-null, restricts the attacks (and the stats computed from them) to that
     * one victim, so the evidence describes a single continuous fight instead of silently combining
     * separate fights against different people into one blended attack count.
     */
    private JsonObject buildEvidence(Player player, PlayerState state, String type, String detail, String victimFilter)
    {
        JsonObject root = new JsonObject();
        root.addProperty("plugin_version", this.getPluginMeta().getVersion());
        root.addProperty("player", player.getName());
        root.addProperty("uuid", player.getUniqueId().toString());
        root.addProperty("time", LocalDateTime.now().withNano(0).toString());
        root.addProperty("flag_type", type);
        root.addProperty("flag_detail", detail);
        JsonArray prevented = new JsonArray();
        long evNow = System.currentTimeMillis();
        java.util.Iterator<String> pi = state.blockedLog.iterator();
        java.util.Iterator<Long> pt = state.blockedLogMs.iterator();

        while (pi.hasNext() && pt.hasNext())
        {
            String entry = pi.next();
            long at = pt.next();

            if (evNow - at <= 120000L)
            {
                prevented.add(Math.round((evNow - at) / 1000.0) + "s ago: " + entry);
            }
        }

        root.add("hits_cancelled_by_server_rules_last_2_min", prevented);
        root.addProperty("held_item", player.getInventory().getItemInMainHand().getType().name());
        root.addProperty("interaction_range_attribute", player.getAttribute(org.bukkit.attribute.Attribute.ENTITY_INTERACTION_RANGE) == null ? 3.0 : player.getAttribute(org.bukkit.attribute.Attribute.ENTITY_INTERACTION_RANGE).getValue());
        root.addProperty("vl", Evidence.r(state.vl, 2));
        root.addProperty("total_flags", state.totalFlags);

        JsonObject env = new JsonObject();
        env.addProperty("ping_ms", player.getPing());
        env.addProperty("tps", Evidence.r(Bukkit.getTPS()[0], 1));
        String brand = player.getClientBrandName();
        env.addProperty("client_brand", brand == null ? "unknown" : brand);
        env.addProperty("mouse_timing", this.packetHook != null ? "packetevents_ms" : "tick_based");
        env.addProperty("game_mode", player.getGameMode().name());
        root.add("environment", env);

        JsonObject th = new JsonObject();
        th.addProperty("instant_ticks", this.instantTicks);
        th.addProperty("min_instant_ratio", this.minInstantRatio);
        th.addProperty("max_air_swings", this.maxAirSwings);
        th.addProperty("require_passive", this.requirePassive);
        th.addProperty("passive_ms", this.passiveMs);
        th.addProperty("min_passive_ratio", this.minPassiveRatio);
        th.addProperty("offtarget_min_ratio", this.offMinRatio);
        th.addProperty("offtarget_min_hits", this.offMinHits);
        th.addProperty("offtarget_ray_size", this.offRaySize);
        th.addProperty("max_ping", this.maxPing);
        th.addProperty("alert_vl", this.alertVl);
        root.add("thresholds", th);

        int nowTick = Bukkit.getCurrentTick();
        List<Evidence.AttackRec> recent = victimFilter == null
            ? this.recentAttacks(state, nowTick)
            : this.recentAttacksAgainst(state, nowTick, victimFilter);
        root.addProperty("frame_window_seconds", this.evidenceTicks / 20);
        root.addProperty("attack_window_seconds", this.evidenceAttackTicks / 20);
        root.addProperty("attacks_scoped_to_single_victim", victimFilter != null);

        if (victimFilter != null)
        {
            root.addProperty("victim_filter", victimFilter);
        }

        root.add("player_history", this.history.toJson(player.getUniqueId()));
        root.add("elytra", this.elytraInfo(player, state, nowTick));
        root.add("stats", Evidence.stats(state.frames, recent));

        JsonArray history = new JsonArray();

        for (String h : state.flagHistory)
        {
            history.add(h);
        }

        root.add("flag_history", history);

        JsonArray columns = new JsonArray();

        for (String column : Evidence.FRAME_COLUMNS)
        {
            columns.add(column);
        }

        root.add("frame_columns", columns);

        JsonArray rows = new JsonArray();

        for (Evidence.Frame frame : state.frames)
        {
            rows.add(frame.toRow());
        }

        root.add("frames", rows);

        JsonArray attacks = new JsonArray();

        for (Evidence.AttackRec attack : recent)
        {
            JsonObject entry = attack.toJson();
            entry.addProperty("seconds_ago", Evidence.r((nowTick - attack.tick()) / 20.0, 1));
            entry.addProperty("angle_reliable", attack.dist() >= Evidence.MIN_RELIABLE_DISTANCE);
            attacks.add(entry);
        }

        root.add("attacks", attacks);
        return root;
    }

    /** Writes the evidence file (off the main thread) and returns its name. */
    private String saveEvidence(Player player, PlayerState state, String type, String detail)
    {
        String json = this.gson.toJson(this.buildEvidence(player, state, type, detail));
        String stamp = LocalDateTime.now().withNano(0).toString().replace(':', '-').replace('T', '_');
        String fileName = player.getName() + "_" + stamp + "_" + type + ".json";
        File dir = new File(this.getDataFolder(), this.evidenceFolder);
        int keep = this.evidenceKeepFiles;

        Bukkit.getScheduler().runTaskAsynchronously(this, () ->
        {
            try
            {
                dir.mkdirs();
                Files.writeString(new File(dir, fileName).toPath(), json, StandardCharsets.UTF_8);
                File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));

                if (files != null && files.length > keep)
                {
                    java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));

                    for (int i = 0; i < files.length - keep; i++)
                    {
                        files[i].delete();
                    }
                }
            }
            catch (IOException ex)
            {
                this.getLogger().warning("Could not write evidence file: " + ex.getMessage());
            }
        });

        return fileName;
    }

    /** Holds a punish action and asks the AI first. The action only runs if the AI confirms with enough confidence. */
    private void requestReview(Player player, PlayerState state, int index, PunishAction action, String vlText)
    {
        long now = System.currentTimeMillis();

        if (state.reviewPending || now - state.lastLadderMillis < this.reviewCooldownMs)
        {
            state.firedActions.remove(index);
            String waiting = player.getName();
            this.writeLog(waiting + " AI review postponed (another review is running or cooldown), retrying in 5s");
            Bukkit.getScheduler().runTaskLater(this, () ->
            {
                Player again = Bukkit.getPlayerExact(waiting);

                if (again != null && again.isOnline() && this.punishEnabled)
                {
                    this.runPunishActions(again, state, vlText);
                }
            }, 100L);
            return;
        }

        state.reviewPending = true;
        state.lastReviewMillis = now;
        state.lastLadderMillis = now;
        String name = player.getName();
        Evidence.AttackRec lastAttack = state.attackLog.peekLast();
        String victim = lastAttack == null ? null : lastAttack.victim();
        String evidence = this.buildEvidence(player, state, "punish-review", "action at VL " + action.vl(), victim).toString();
        this.alertStaff("&7AI is reviewing &f" + name + " &7before the action at VL " + action.vl() + "...");

        Bukkit.getScheduler().runTaskAsynchronously(this, () ->
        {
            AiReviewer.Verdict verdict = this.reviewer.review(this.reviewProvider, this.apiKey(), this.reviewModel, this.reviewTimeout, evidence);
            Bukkit.getScheduler().runTask(this, () -> this.finishReview(name, state, index, action, vlText, verdict));
        });
    }

    private void finishReview(String name, PlayerState state, int index, PunishAction action, String vlText, AiReviewer.Verdict verdict)
    {
        state.reviewPending = false;
        String reasons = String.join("; ", verdict.reasons());
        this.writeLog(name + " AI review: verdict=" + verdict.verdict() + " confidence=" + verdict.confidence() + " type=" + verdict.cheatType() + " error=" + verdict.error() + " reasons=" + reasons);

        if (verdict.error() != null)
        {
            if (this.reviewPunishOnFailure)
            {
                this.alertStaff("&eAI review of &f" + name + " &efailed (" + verdict.error() + "), running the action anyway");
                this.executeAction(name, action, vlText);
            }
            else
            {
                this.alertStaff("&eAI review of &f" + name + " &efailed (" + verdict.error() + "), no action taken");
                state.firedActions.remove(index);
            }

            return;
        }

        String summary = "&f" + name + " &8| &7AI &f" + verdict.verdict() + " &8| &7confidence &f" + Math.round(verdict.confidence() * 100.0) + "% &8| &7type &f" + verdict.cheatType() + " &8| &7" + reasons;
        this.history.verdict(state.uuid, name, verdict.verdict());

        if ("cheating".equalsIgnoreCase(verdict.verdict()) && verdict.confidence() >= this.reviewMinConfidence)
        {
            this.alertStaff("&aAI confirmed: " + summary);
            this.history.ban(state.uuid, name);
            this.executeAction(name, action, vlText);
        }
        else
        {
            this.alertStaff("&eAI did not confirm, no action: " + summary);
            state.firedActions.remove(index);
        }
    }

    private void writeLog(String text)
    {
        String entry = "[" + LocalDateTime.now().withNano(0) + "] " + text + System.lineSeparator();
        Bukkit.getScheduler().runTaskAsynchronously(this, () ->
        {
            try
            {
                this.getDataFolder().mkdirs();
                Files.writeString(this.logFile.toPath(), entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            catch (IOException ex)
            {
                this.getLogger().warning("Could not write flags.log: " + ex.getMessage());
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event)
    {
        this.states.remove(event.getPlayer().getUniqueId());
        this.debugTargets.remove(event.getPlayer().getUniqueId());
        this.lastRotation.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args)
    {
        if (!sender.hasPermission("antitrigger.admin"))
        {
            sender.sendMessage(this.legacy.deserialize("&cNo permission."));
            return true;
        }

        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";

        switch (sub)
        {
            case "alerts":
                if (!(sender instanceof Player player))
                {
                    sender.sendMessage("Players only.");
                    return true;
                }

                boolean nowOff = this.alertsOff.add(player.getUniqueId());

                if (!nowOff)
                {
                    this.alertsOff.remove(player.getUniqueId());
                }

                sender.sendMessage(this.legacy.deserialize(this.prefix + "&7Alerts " + (nowOff ? "&cOFF" : "&aON")));
                return true;
            case "info":
            {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;

                if (target == null)
                {
                    sender.sendMessage(this.legacy.deserialize("&cUsage: /antitrigger info <online player>"));
                    return true;
                }

                PlayerState state = this.state(target);
                state.refreshVl(this.decayPerMinute, this.resetAfterMillis);
                sender.sendMessage(this.legacy.deserialize(this.prefix + "&f" + target.getName() + " &7VL &f" + String.format(Locale.ROOT, "%.1f", state.vl) + " &7flags &f" + state.totalFlags + " &7window hits &f" + state.samples.size() + " &7ping &f" + target.getPing() + "ms"));
                return true;
            }
            case "reset":
            {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;

                if (target == null)
                {
                    sender.sendMessage(this.legacy.deserialize("&cUsage: /antitrigger reset <online player>"));
                    return true;
                }

                this.states.remove(target.getUniqueId());
                sender.sendMessage(this.legacy.deserialize(this.prefix + "&7Reset data of &f" + target.getName()));
                return true;
            }
            case "debug":
            {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;

                if (target == null)
                {
                    sender.sendMessage(this.legacy.deserialize("&cUsage: /antitrigger debug <online player>"));
                    return true;
                }

                boolean on = this.debugTargets.add(target.getUniqueId());

                if (!on)
                {
                    this.debugTargets.remove(target.getUniqueId());
                }

                sender.sendMessage(this.legacy.deserialize(this.prefix + "&7Debug for &f" + target.getName() + " " + (on ? "&aON &7(every hit is explained to staff with alerts on)" : "&cOFF")));
                return true;
            }
            case "evidence":
            {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;

                if (target == null)
                {
                    sender.sendMessage(this.legacy.deserialize("&cUsage: /antitrigger evidence <online player>"));
                    return true;
                }

                String file = this.saveEvidence(target, this.state(target), "manual", "requested by " + sender.getName());
                sender.sendMessage(this.legacy.deserialize(this.prefix + "&7Saved &f" + this.evidenceFolder + "/" + file + " &7(last " + this.evidenceTicks / 20 + "s)"));
                return true;
            }
            case "review":
            {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;

                if (target == null)
                {
                    sender.sendMessage(this.legacy.deserialize("&cUsage: /antitrigger review <online player>"));
                    return true;
                }

                if (this.apiKey().isEmpty())
                {
                    sender.sendMessage(this.legacy.deserialize(this.prefix + "&cNo API key. Set review.api-key in config.yml (or the ANTHROPIC_API_KEY / GEMINI_API_KEY environment variable) and /antitrigger reload."));
                    return true;
                }

                String evidence = this.buildEvidence(target, this.state(target), "manual-review", "requested by " + sender.getName()).toString();
                String name = target.getName();
                sender.sendMessage(this.legacy.deserialize(this.prefix + "&7Asking the AI about &f" + name + "&7 (no punishment will be run)..."));

                Bukkit.getScheduler().runTaskAsynchronously(this, () ->
                {
                    AiReviewer.Verdict v = this.reviewer.review(this.reviewProvider, this.apiKey(), this.reviewModel, this.reviewTimeout, evidence);
                    Bukkit.getScheduler().runTask(this, () ->
                    {
                        if (v.error() != null)
                        {
                            sender.sendMessage(this.legacy.deserialize(this.prefix + "&cAI review failed: " + v.error()));
                            return;
                        }

                        sender.sendMessage(this.legacy.deserialize(this.prefix + "&f" + name + " &8| &7AI &f" + v.verdict() + " &8| &7confidence &f" + Math.round(v.confidence() * 100.0) + "% &8| &7type &f" + v.cheatType()));

                        for (String reason : v.reasons())
                        {
                            sender.sendMessage(this.legacy.deserialize("&8 - &7" + reason));
                        }
                    });
                });
                return true;
            }
            case "apitest":
            {
                String key = this.apiKey();
                sender.sendMessage(this.legacy.deserialize(this.prefix + "&7Provider &f" + this.reviewProvider + " &8| &7model &f" + this.reviewModel + " &8| &7key &f" + (key.isEmpty() ? "&cmissing" : key.substring(0, Math.min(4, key.length())) + "****") + " &8| &7review enabled &f" + this.reviewEnabled + " &8| &7mode &f" + (this.reviewPrimary ? "primary" : "confirm")));

                if (key.isEmpty())
                {
                    sender.sendMessage(this.legacy.deserialize(this.prefix + "&cNo API key. Set review.api-key in config.yml (or the ANTHROPIC_API_KEY / GEMINI_API_KEY environment variable), then /antitrigger reload."));
                    return true;
                }

                sender.sendMessage(this.legacy.deserialize(this.prefix + "&7Sending a test request..."));
                String testEvidence = "{\"connectivity_test\":true,\"instruction\":\"There is no real player. Reply only with verdict uncertain, confidence 0, cheat_type none and reasons [\\\"api ok\\\"].\"}";
                String provider = this.reviewProvider;
                String model = this.reviewModel;
                int timeout = this.reviewTimeout;

                Bukkit.getScheduler().runTaskAsynchronously(this, () ->
                {
                    long t0 = System.currentTimeMillis();
                    boolean ok1 = false;
                    List<String> testKeys = this.reviewApiKeys.isEmpty() ? List.of(key) : new ArrayList<>(this.reviewApiKeys);

                    for (int ki = 0; ki < testKeys.size(); ki++)
                    {
                        String k = testKeys.get(ki);
                        String shown = "key #" + (ki + 1) + " (" + k.substring(0, Math.min(4, k.length())) + "****)";
                        String step1;
                        long tk = System.currentTimeMillis();

                        try
                        {
                            AiReviewer.Check check = this.reviewer.checkAccess(provider, k, model, 10);
                            ok1 = true;
                            step1 = "&a\u2714 " + shown + " OK (" + (System.currentTimeMillis() - tk) + "ms, " + check.count() + " models listed, model '" + model + "' " + (check.modelFound() ? "found" : "NOT in the list - check review.model") + ")";
                        }
                        catch (Exception ex)
                        {
                            step1 = "&c\u2716 " + shown + " failed after " + (System.currentTimeMillis() - tk) + "ms: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
                        }

                        String line1 = step1;
                        Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(this.legacy.deserialize(this.prefix + line1)));
                    }

                    boolean proceed = ok1;

                    if (!proceed)
                    {
                        return;
                    }

                    long t1 = System.currentTimeMillis();
                    AiReviewer.Verdict v = this.reviewer.review(provider, key, model, timeout, testEvidence);
                    long took = System.currentTimeMillis() - t1;
                    Bukkit.getScheduler().runTask(this, () ->
                    {
                        if (v.error() != null)
                        {
                            sender.sendMessage(this.legacy.deserialize(this.prefix + "&c\u2716 step 2 (real request) FAILED after " + took + "ms: " + v.error()));
                            return;
                        }

                        sender.sendMessage(this.legacy.deserialize(this.prefix + "&a\u2714 step 2: API works &8| &7replied in &f" + took + "ms &8| &7verdict &f" + v.verdict() + " &8| &7" + String.join("; ", v.reasons())));
                    });
                });
                return true;
            }
            case "reload":
                this.loadSettings();
                this.hookPacketEvents();
                sender.sendMessage(this.legacy.deserialize(this.prefix + "&aReloaded. Mouse timing: " + (this.packetHook != null ? "PacketEvents" : "tick-based")));
                return true;
            default:
                sender.sendMessage(this.legacy.deserialize("&cUsage: /antitrigger <alerts|info <player>|reset <player>|debug <player>|evidence <player>|review <player>|apitest|reload>"));
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args)
    {
        List<String> out = new ArrayList<>();

        if (!sender.hasPermission("antitrigger.admin"))
        {
            return out;
        }

        if (args.length == 1)
        {
            for (String s : List.of("alerts", "info", "reset", "debug", "evidence", "review", "apitest", "reload"))
            {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                {
                    out.add(s);
                }
            }
        }
        else if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("debug") || args[0].equalsIgnoreCase("evidence") || args[0].equalsIgnoreCase("review")))
        {
            for (Player p : Bukkit.getOnlinePlayers())
            {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                {
                    out.add(p.getName());
                }
            }
        }

        return out;
    }
}
