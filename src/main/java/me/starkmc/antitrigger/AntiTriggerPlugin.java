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
import org.bukkit.event.player.PlayerAnimationType;
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
    private boolean evidenceOnAlert;
    private String evidenceFolder;
    private int evidenceKeepFiles;
    private boolean reviewEnabled;
    private String reviewApiKey;
    private String reviewProvider;
    private String reviewModel;
    private double reviewMinConfidence;
    private int reviewTimeout;
    private long reviewCooldownMs;
    private boolean reviewPunishOnFailure;
    private boolean reviewPrimary;
    private long primaryCooldownMs;
    private int primaryConfirmations;
    private long primaryWindowMs;
    private final List<String> primaryCommands = new ArrayList<>();
    private double gateOff;
    private int gateSnaps;
    private int gateCps;
    private double gateReach;
    private double gateInstant;

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
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tickAll, 1L, 1L);
        this.hookPacketEvents();
        this.getLogger().info("AntiTrigger enabled. Automatic punishment: " + (this.punishEnabled ? "ON (" + this.punishActions.size() + " actions)" : "off (alerts only)") + ". Mouse timing: " + (this.packetHook != null ? "PacketEvents (ms)" : "tick-based"));
    }

    @Override
    public void onDisable()
    {
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
        this.evidenceOnAlert = c.getBoolean("evidence.save-on-alert", true);
        this.evidenceFolder = c.getString("evidence.folder", "evidence");
        this.evidenceKeepFiles = Math.max(10, c.getInt("evidence.keep-files", 300));
        this.reviewEnabled = c.getBoolean("review.enabled", false);
        this.reviewApiKey = c.getString("review.api-key", "");
        this.reviewProvider = "gemini".equalsIgnoreCase(c.getString("review.provider", "anthropic")) ? "gemini" : "anthropic";
        String configuredModel = c.getString("review.model", "");
        this.reviewModel = configuredModel == null || configuredModel.isBlank() ? ("gemini".equals(this.reviewProvider) ? "gemini-3.6-flash" : "claude-sonnet-5-5") : configuredModel.trim();
        this.reviewMinConfidence = c.getDouble("review.min-confidence", 0.85);
        this.reviewTimeout = Math.max(3, c.getInt("review.timeout-seconds", 12));
        this.reviewCooldownMs = Math.max(0, c.getInt("review.cooldown-seconds", 20)) * 1000L;
        this.reviewPunishOnFailure = "punish".equalsIgnoreCase(c.getString("review.on-failure", "alert"));
        this.reviewPrimary = "primary".equalsIgnoreCase(c.getString("review.mode", "confirm"));
        this.primaryCooldownMs = Math.max(5, c.getInt("review.primary.cooldown-seconds", 30)) * 1000L;
        this.primaryConfirmations = Math.max(1, c.getInt("review.primary.confirmations", 1));
        this.primaryWindowMs = Math.max(10, c.getInt("review.primary.confirm-window-seconds", 180)) * 1000L;
        this.primaryCommands.clear();
        this.primaryCommands.addAll(c.getStringList("review.primary.commands"));
        this.gateOff = c.getDouble("review.primary.gate.off-crosshair-ratio", 0.4);
        this.gateSnaps = Math.max(1, c.getInt("review.primary.gate.snap-attacks", 2));
        this.gateCps = Math.max(5, c.getInt("review.primary.gate.cps", 18));
        this.gateReach = c.getDouble("review.primary.gate.reach", 3.6);
        this.gateInstant = c.getDouble("review.primary.gate.instant-ratio", 0.6);
    }

    private PlayerState state(Player player)
    {
        return this.states.computeIfAbsent(player.getUniqueId(), id -> new PlayerState());
    }

    /** Once per tick: remember how long each player's crosshair has been on the same enemy. */
    private void tickAll()
    {
        for (Player player : Bukkit.getOnlinePlayers())
        {
            PlayerState state = this.state(player);
            GameMode mode = player.getGameMode();

            if (player.isDead() || (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE))
            {
                state.target = null;
                state.onTargetTicks = 0;
                continue;
            }

            Location eye = player.getEyeLocation();

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

        if (now - state.lastAttackTick <= 1)
        {
            return; // the swing that belongs to a hit we already counted
        }

        state.airSwings.addLast(now);
        state.trim(now, this.windowTicks, this.windowSize);
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
        state.samples.addLast(new PlayerState.Sample(state.onTargetTicks, now, sinceRotation >= this.passiveMs));
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
        boolean on = onTarget || (victim.getUniqueId().equals(state.seenVictim) && now - state.seenTick <= this.offGraceTicks) || this.wideRayHits(attacker, victim);

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
        String key = this.reviewApiKey == null ? "" : this.reviewApiKey.trim();

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

    private void recordAttack(Player attacker, PlayerState state, Player victim, int now, double damage)
    {
        if (!this.evidenceEnabled)
        {
            return;
        }

        double[] aim = Evidence.aim(attacker.getEyeLocation(), victim);
        boolean on = victim.getUniqueId().equals(state.target) && state.onTargetTicks >= 1;
        long still = System.currentTimeMillis() - this.lastRotation.getOrDefault(attacker.getUniqueId(), 0L);
        state.attackLog.addLast(new Evidence.AttackRec(now, victim.getName(), aim[0], on, this.wideRayHits(attacker, victim), on ? state.onTargetTicks : 0, still, state.cps(now), aim[1], aim[2], aim[3], damage, attacker.getAttackCooldown(), this.skipReason(attacker)));

        while (state.attackLog.size() > 40)
        {
            state.attackLog.pollFirst();
        }

        this.maybeScreen(attacker, state);
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

        if (state.attackLog.size() < 4 || this.skipReason(player) != null)
        {
            return;
        }

        JsonObject st = Evidence.stats(state.frames, state.attackLog);
        int n = st.get("attacks_recorded").getAsInt();
        double offFraction = st.get("attacks_off_victim_server_ray").getAsDouble() / n;
        int instant = 0;

        for (Evidence.AttackRec attack : state.attackLog)
        {
            if (attack.onCrosshair() && attack.reaction() <= this.instantTicks)
            {
                instant++;
            }
        }

        List<String> why = new ArrayList<>();

        if (offFraction >= this.gateOff)
        {
            why.add("off-crosshair " + Math.round(offFraction * 100.0) + "%");
        }

        if (st.get("attacks_after_fast_snap_that_ended_on_target").getAsInt() >= this.gateSnaps)
        {
            why.add("fast snaps onto target");
        }

        if (st.get("max_cps").getAsInt() >= this.gateCps)
        {
            why.add("CPS " + st.get("max_cps").getAsInt());
        }

        if (st.get("max_attack_distance").getAsDouble() > this.gateReach)
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
        String evidence = this.buildEvidence(player, state, "ai-screen", String.join(", ", why)).toString();

        Bukkit.getScheduler().runTaskAsynchronously(this, () ->
        {
            AiReviewer.Verdict verdict = this.reviewer.review(this.reviewProvider, this.apiKey(), this.reviewModel, this.reviewTimeout, evidence);
            Bukkit.getScheduler().runTask(this, () -> this.finishScreen(name, state, verdict));
        });
    }

    private void finishScreen(String name, PlayerState state, AiReviewer.Verdict verdict)
    {
        state.reviewPending = false;
        String reasons = String.join("; ", verdict.reasons());
        this.writeLog(name + " AI screen: verdict=" + verdict.verdict() + " confidence=" + verdict.confidence() + " type=" + verdict.cheatType() + " error=" + verdict.error() + " reasons=" + reasons);

        if (verdict.error() != null)
        {
            return;
        }

        long ms = System.currentTimeMillis();

        if (ms - state.lastAiHitMillis > this.primaryWindowMs)
        {
            state.aiHits = 0;
        }

        if (!"cheating".equalsIgnoreCase(verdict.verdict()) || verdict.confidence() < this.reviewMinConfidence)
        {
            return;
        }

        state.aiHits++;
        state.lastAiHitMillis = ms;
        String summary = "&f" + name + " &8| &7AI &f" + verdict.verdict() + " &8| &7confidence &f" + Math.round(verdict.confidence() * 100.0) + "% &8| &7type &f" + verdict.cheatType() + " &8| &7" + reasons;

        if (state.aiHits < this.primaryConfirmations)
        {
            this.alertStaff("&eAI suspects (" + state.aiHits + "/" + this.primaryConfirmations + "), checking again soon: " + summary);
            state.lastScreenMillis = ms - this.primaryCooldownMs + 8000L;
            return;
        }

        Player online = Bukkit.getPlayerExact(name);

        if (online != null && this.evidenceEnabled)
        {
            this.saveEvidence(online, state, "ai-ban", reasons);
        }

        this.alertStaff("&aAI banned: " + summary);
        state.aiHits = 0;

        for (String command : this.primaryCommands)
        {
            String ready = command.replace("%player%", name).replace("%vl%", "0");
            this.writeLog(name + " running AI action: " + ready);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), ready);
        }
    }

    private JsonObject buildEvidence(Player player, PlayerState state, String type, String detail)
    {
        JsonObject root = new JsonObject();
        root.addProperty("plugin_version", this.getPluginMeta().getVersion());
        root.addProperty("player", player.getName());
        root.addProperty("uuid", player.getUniqueId().toString());
        root.addProperty("time", LocalDateTime.now().withNano(0).toString());
        root.addProperty("flag_type", type);
        root.addProperty("flag_detail", detail);
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

        root.add("stats", Evidence.stats(state.frames, state.attackLog));

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

        for (Evidence.AttackRec attack : state.attackLog)
        {
            attacks.add(attack.toJson());
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

        if (state.reviewPending || now - state.lastReviewMillis < this.reviewCooldownMs)
        {
            state.firedActions.remove(index);
            return;
        }

        state.reviewPending = true;
        state.lastReviewMillis = now;
        String name = player.getName();
        String evidence = this.buildEvidence(player, state, "punish-review", "action at VL " + action.vl()).toString();
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

        if ("cheating".equalsIgnoreCase(verdict.verdict()) && verdict.confidence() >= this.reviewMinConfidence)
        {
            this.alertStaff("&aAI confirmed: " + summary);
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
            case "reload":
                this.loadSettings();
                this.hookPacketEvents();
                sender.sendMessage(this.legacy.deserialize(this.prefix + "&aReloaded. Mouse timing: " + (this.packetHook != null ? "PacketEvents" : "tick-based")));
                return true;
            default:
                sender.sendMessage(this.legacy.deserialize("&cUsage: /antitrigger <alerts|info <player>|reset <player>|debug <player>|evidence <player>|review <player>|reload>"));
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
            for (String s : List.of("alerts", "info", "reset", "debug", "evidence", "review", "reload"))
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
