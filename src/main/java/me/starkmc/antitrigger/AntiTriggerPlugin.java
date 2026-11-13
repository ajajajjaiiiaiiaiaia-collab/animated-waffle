package me.starkmc.antitrigger;

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

            state.lastYaw = eye.getYaw();
            state.lastPitch = eye.getPitch();

            RayTraceResult result = player.getWorld().rayTraceEntities(eye, eye.getDirection(), this.range, this.raySize, entity -> entity instanceof Player other && !other.equals(player) && other.getGameMode() != GameMode.SPECTATOR && !other.isDead());
            UUID hit = result != null && result.getHitEntity() != null ? result.getHitEntity().getUniqueId() : null;

            if (hit != null && hit.equals(state.target))
            {
                state.onTargetTicks++;
            }
            else
            {
                state.target = hit;
                state.onTargetTicks = hit == null ? 0 : 1;
            }
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

        if (!this.canBeJudged(attacker))
        {
            return;
        }

        // Only judge hits where our own look-ray agrees the crosshair was on the victim.
        if (!victim.getUniqueId().equals(state.target) || state.onTargetTicks < 1)
        {
            return;
        }

        long sinceRotation = System.currentTimeMillis() - this.lastRotation.getOrDefault(attacker.getUniqueId(), 0L);
        state.samples.addLast(new PlayerState.Sample(state.onTargetTicks, now, sinceRotation >= this.passiveMs));
        state.trim(now, this.windowTicks, this.windowSize);
        this.evaluate(attacker, state);
    }

    private boolean canBeJudged(Player player)
    {
        if (player.hasPermission(this.bypassPermission))
        {
            return false;
        }

        if (!this.bedrockPrefix.isEmpty() && player.getName().startsWith(this.bedrockPrefix))
        {
            return false;
        }

        if (player.getPing() > this.maxPing)
        {
            return false;
        }

        return Bukkit.getTPS()[0] >= this.minTps;
    }

    private void evaluate(Player player, PlayerState state)
    {
        if (state.samples.size() < this.minSamples)
        {
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
                for (String command : action.commands())
                {
                    String ready = command.replace("%player%", player.getName()).replace("%vl%", vlText);
                    this.writeLog(player.getName() + " running action at VL " + action.vl() + ": " + ready);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), ready);
                }
            }
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
            case "reload":
                this.loadSettings();
                this.hookPacketEvents();
                sender.sendMessage(this.legacy.deserialize(this.prefix + "&aReloaded. Mouse timing: " + (this.packetHook != null ? "PacketEvents" : "tick-based")));
                return true;
            default:
                sender.sendMessage(this.legacy.deserialize("&cUsage: /antitrigger <alerts|info <player>|reset <player>|reload>"));
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
            for (String s : List.of("alerts", "info", "reset", "reload"))
            {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                {
                    out.add(s);
                }
            }
        }
        else if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("reset")))
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
