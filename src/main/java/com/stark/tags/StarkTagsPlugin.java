package com.stark.tags;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.bukkit.util.Transformation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StarkTagsPlugin extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern BRACES = Pattern.compile("\\{(\\w+)}");
    private static final String TEAM_PREFIX = "stk_";
    private static final String BELOW = "stk_below";

    private record VarDef(String text, String color, boolean bold) {}

    private static final class Profile {
        String id, permission, prefix, suffix, below;
        int priority;
        NamedTextColor nameColor = NamedTextColor.WHITE;
        List<String> above = new ArrayList<>();
    }

    private record Built(Component prefix, Component suffix, List<Component> above, Component below, NamedTextColor nameColor) {
        String key() {
            return MM.serialize(prefix) + "|" + MM.serialize(suffix) + "|" + MM.serialize(below) + "|" + nameColor;
        }
    }

    private final Map<UUID, TextDisplay> displays = new HashMap<>();
    private final Map<UUID, String> aboveKey = new HashMap<>();
    private final Map<Scoreboard, Map<UUID, String>> applied = new IdentityHashMap<>();
    private final Map<String, VarDef> vars = new LinkedHashMap<>();
    private final List<Profile> profiles = new ArrayList<>();
    private Set<String> disabledWorlds = new HashSet<>();
    private boolean hideOwn, leaveOthers, shadow, seeThrough;
    private float offset;
    private Color background;
    private int period;
    private NamespacedKey markKey;
    private Method papi;
    private boolean usesBelow;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        markKey = new NamespacedKey(this, "display");
        try {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI").getMethod("setPlaceholders", Player.class, String.class);
            }
        } catch (ReflectiveOperationException ignored) {
            papi = null;
        }
        purgeLeftovers();
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 1L);
    }

    @Override
    public void onDisable() {
        for (TextDisplay d : displays.values()) if (d != null && d.isValid()) d.remove();
        displays.clear();
        purgeLeftovers();
        for (Scoreboard sb : boards()) cleanBoard(sb);
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        cleanBoard(main);
    }

    private void purgeLeftovers() {
        for (World w : Bukkit.getWorlds()) {
            for (TextDisplay d : w.getEntitiesByClass(TextDisplay.class)) {
                if (d.getPersistentDataContainer().has(markKey, PersistentDataType.BYTE)) d.remove();
            }
        }
    }

    private void cleanBoard(Scoreboard sb) {
        for (Team t : new ArrayList<>(sb.getTeams())) if (t.getName().startsWith(TEAM_PREFIX)) t.unregister();
        Objective o = sb.getObjective(BELOW);
        if (o != null) o.unregister();
    }

    private void load() {
        reloadConfig();
        period = Math.max(1, getConfig().getInt("update-ticks", 20));
        hideOwn = getConfig().getBoolean("hide-own-above", true);
        leaveOthers = getConfig().getBoolean("leave-other-teams-alone", true);
        disabledWorlds = new HashSet<>(getConfig().getStringList("disabled-worlds"));
        offset = (float) getConfig().getDouble("above.offset", 0.38);
        shadow = getConfig().getBoolean("above.shadow", true);
        seeThrough = getConfig().getBoolean("above.see-through", false);
        background = parseColor(getConfig().getString("above.background", "#00000000"));

        vars.clear();
        vars.put("name", new VarDef("{name}", "", false));
        vars.put("displayname", new VarDef("{displayname}", "", false));
        vars.put("ping", new VarDef("{ping}", "", false));
        vars.put("health", new VarDef("{health}", "", false));
        vars.put("max_health", new VarDef("{max_health}", "", false));
        vars.put("world", new VarDef("{world}", "", false));
        vars.put("level", new VarDef("{level}", "", false));
        vars.put("food", new VarDef("{food}", "", false));
        ConfigurationSection vs = getConfig().getConfigurationSection("variables");
        if (vs != null) {
            for (String k : vs.getKeys(false)) {
                ConfigurationSection s = vs.getConfigurationSection(k);
                if (s == null) continue;
                String old = vars.containsKey(k.toLowerCase()) ? vars.get(k.toLowerCase()).text() : "";
                vars.put(k.toLowerCase(), new VarDef(s.getString("text", old), s.getString("color", ""), s.getBoolean("bold", false)));
            }
        }

        profiles.clear();
        usesBelow = false;
        ConfigurationSection ps = getConfig().getConfigurationSection("profiles");
        if (ps != null) {
            for (String id : ps.getKeys(false)) {
                ConfigurationSection s = ps.getConfigurationSection(id);
                if (s == null) continue;
                Profile p = new Profile();
                p.id = id;
                p.permission = s.getString("permission", "");
                p.priority = s.getInt("priority", 0);
                p.prefix = s.getString("prefix", "");
                p.suffix = s.getString("suffix", "");
                p.below = s.getString("below", "");
                p.above = new ArrayList<>(s.getStringList("above"));
                try {
                    p.nameColor = NamedTextColor.NAMES.valueOrThrow(s.getString("name-color", "white").toLowerCase());
                } catch (RuntimeException e) {
                    p.nameColor = NamedTextColor.WHITE;
                }
                if (!p.below.isBlank()) usesBelow = true;
                profiles.add(p);
            }
        }
        profiles.sort((a, b) -> Integer.compare(b.priority, a.priority));
        for (Scoreboard sb : boards()) cleanBoard(sb);
        applied.clear();
        aboveKey.clear();
        for (TextDisplay d : displays.values()) if (d != null && d.isValid()) d.remove();
        displays.clear();
    }

    private static Color parseColor(String s) {
        try {
            String h = s.startsWith("#") ? s.substring(1) : s;
            if (h.length() == 6) return Color.fromRGB(Integer.parseInt(h, 16));
            long v = Long.parseLong(h, 16);
            return Color.fromARGB((int) (v & 0xFFFFFFFFL));
        } catch (RuntimeException e) {
            return Color.fromARGB(0);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("starktags.admin")) return true;
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            load();
            sender.sendMessage(MM.deserialize("<green>StarkTags reloaded."));
        } else {
            sender.sendMessage(MM.deserialize("<yellow>/starktags reload"));
        }
        return true;
    }

    private Set<Scoreboard> boards() {
        Set<Scoreboard> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.add(Bukkit.getScoreboardManager().getMainScoreboard());
        for (Player p : Bukkit.getOnlinePlayers()) set.add(p.getScoreboard());
        return set;
    }

    private int counter;

    private void tick() {
        counter++;
        // keep displays glued to players every tick-ish, rebuild text on the configured period
        boolean rebuild = counter % period == 0;
        Set<Scoreboard> boards = rebuild ? boards() : Set.of();
        for (Player t : Bukkit.getOnlinePlayers()) {
            if (disabledWorlds.contains(t.getWorld().getName()) || !shown(t)) {
                dropDisplay(t.getUniqueId());
                continue;
            }
            TextDisplay d = displays.get(t.getUniqueId());
            if (d != null && (!d.isValid() || !t.getPassengers().contains(d) || d.getWorld() != t.getWorld())) {
                if (d.isValid()) d.remove();
                displays.remove(t.getUniqueId());
                aboveKey.remove(t.getUniqueId());
                d = null;
            }
            if (!rebuild && d != null) continue;
            Built b = build(t);
            if (b == null) continue;
            updateDisplay(t, b);
            for (Scoreboard sb : boards) applyBoard(sb, t, b);
        }
        if (rebuild) {
            Set<Scoreboard> live = boards;
            applied.keySet().removeIf(sb -> !live.contains(sb));
        }
    }

    private boolean shown(Player p) {
        return p.isOnline() && !p.isDead() && p.getGameMode() != GameMode.SPECTATOR
                && !p.hasPotionEffect(PotionEffectType.INVISIBILITY) && !p.hasMetadata("vanished");
    }

    private Profile profileFor(Player p) {
        for (Profile pr : profiles) {
            if (pr.permission == null || pr.permission.isBlank() || p.hasPermission(pr.permission)) return pr;
        }
        return null;
    }

    private String papi(Player p, String s) {
        if (papi == null || s.indexOf('%') < 0) return s;
        try {
            String out = (String) papi.invoke(null, p, s);
            return out == null ? s : out;
        } catch (ReflectiveOperationException e) {
            return s;
        }
    }

    private String builtin(Player p, String text) {
        Matcher m = BRACES.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = switch (m.group(1).toLowerCase()) {
                case "name" -> p.getName();
                case "displayname" -> PlainTextComponentSerializer.plainText().serialize(p.displayName());
                case "ping" -> String.valueOf(p.getPing());
                case "health" -> String.valueOf((int) Math.ceil(p.getHealth()));
                case "max_health" -> String.valueOf((int) Math.ceil(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null ? 20 : p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()));
                case "world" -> p.getWorld().getName();
                case "level" -> String.valueOf(p.getLevel());
                case "food" -> String.valueOf(p.getFoodLevel());
                default -> m.group(0);
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Component varComponent(Player p, VarDef def) {
        String raw = builtin(p, def.text());
        String res = papi(p, raw);
        if (res.equals(raw) && raw.contains("%") && raw.matches(".*%[^%\\s]+%.*")) return Component.empty();
        if (res.isBlank()) return Component.empty();
        boolean legacy = res.indexOf('&') >= 0 || res.indexOf('§') >= 0;
        Component c;
        String col = def.color() == null ? "" : def.color().trim();
        if (legacy) {
            c = LegacyComponentSerializer.legacyAmpersand().deserialize(res.replace('§', '&'));
            TextColor tc = plainColor(col);
            if (tc != null) c = c.colorIfAbsent(tc);
        } else if (col.toLowerCase().startsWith("gradient:")) {
            c = MM.deserialize("<" + col + ">" + MM.escapeTags(res) + "</gradient>");
        } else {
            c = Component.text(res);
            TextColor tc = plainColor(col);
            if (tc != null) c = c.color(tc);
        }
        if (def.bold()) c = c.decoration(TextDecoration.BOLD, true);
        return c;
    }

    private static TextColor plainColor(String s) {
        if (s == null || s.isBlank()) return null;
        if (s.startsWith("#")) return TextColor.fromHexString(s);
        return NamedTextColor.NAMES.value(s.toLowerCase());
    }

    private Component parse(Player p, TagResolver res, String s) {
        if (s == null || s.isBlank()) return Component.empty();
        s = papi(p, s);
        s = BRACES.matcher(s).replaceAll("<$1>");
        return MM.deserialize(s, res);
    }

    private Built build(Player p) {
        Profile pr = profileFor(p);
        if (pr == null) return null;
        List<TagResolver> list = new ArrayList<>();
        for (Map.Entry<String, VarDef> e : vars.entrySet()) {
            if (!e.getKey().matches("[a-z0-9_-]+")) continue;
            list.add(Placeholder.component(e.getKey(), varComponent(p, e.getValue())));
        }
        TagResolver res = TagResolver.resolver(list);
        List<Component> above = new ArrayList<>();
        for (String line : pr.above) {
            Component c = parse(p, res, line);
            if (!PlainTextComponentSerializer.plainText().serialize(c).isBlank()) above.add(c);
        }
        return new Built(parse(p, res, pr.prefix), parse(p, res, pr.suffix), above, parse(p, res, pr.below), pr.nameColor);
    }

    private void dropDisplay(UUID id) {
        TextDisplay d = displays.remove(id);
        aboveKey.remove(id);
        if (d != null && d.isValid()) d.remove();
    }

    private void updateDisplay(Player t, Built b) {
        UUID id = t.getUniqueId();
        if (b.above().isEmpty()) {
            dropDisplay(id);
            return;
        }
        Component text = Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), b.above());
        TextDisplay d = displays.get(id);
        if (d == null || !d.isValid()) {
            d = t.getWorld().spawn(t.getLocation(), TextDisplay.class, e -> {
                e.setPersistent(false);
                e.setBillboard(Display.Billboard.CENTER);
                e.setShadowed(shadow);
                e.setSeeThrough(seeThrough);
                e.setBackgroundColor(background);
                e.setViewRange(0.6f);
                e.setInterpolationDuration(0);
                e.getPersistentDataContainer().set(markKey, PersistentDataType.BYTE, (byte) 1);
                e.setTransformation(new Transformation(new Vector3f(0, offset, 0), new AxisAngle4f(), new Vector3f(1, 1, 1), new AxisAngle4f()));
            });
            t.addPassenger(d);
            if (hideOwn) t.hideEntity(this, d);
            displays.put(id, d);
            aboveKey.remove(id);
        }
        String key = MM.serialize(text);
        if (!key.equals(aboveKey.get(id))) {
            d.text(text);
            aboveKey.put(id, key);
        }
    }

    private void applyBoard(Scoreboard sb, Player t, Built b) {
        Map<UUID, String> cache = applied.computeIfAbsent(sb, k -> new HashMap<>());
        String key = b.key();
        if (key.equals(cache.get(t.getUniqueId()))) return;
        String entry = t.getName();
        Team cur = sb.getEntryTeam(entry);
        String tn = TEAM_PREFIX + t.getUniqueId().toString().replace("-", "").substring(0, 12);
        boolean ours = cur == null || cur.getName().startsWith(TEAM_PREFIX);
        if (ours || !leaveOthers) {
            Team team = sb.getTeam(tn);
            if (team == null) team = sb.registerNewTeam(tn);
            if (!team.hasEntry(entry)) team.addEntry(entry);
            team.prefix(b.prefix());
            team.suffix(b.suffix());
            team.color(b.nameColor());
        }
        if (usesBelow) {
            Objective cur2 = sb.getObjective(DisplaySlot.BELOW_NAME);
            Objective o = sb.getObjective(BELOW);
            if (cur2 == null || (o != null && cur2 == o)) {
                if (o == null) o = sb.registerNewObjective(BELOW, Criteria.DUMMY, Component.empty());
                o.setDisplaySlot(DisplaySlot.BELOW_NAME);
                Score s = o.getScore(entry);
                if (PlainTextComponentSerializer.plainText().serialize(b.below()).isBlank()) {
                    s.resetScore();
                } else {
                    s.setScore(0);
                    s.numberFormat(NumberFormat.fixed(b.below()));
                }
            }
        }
        cache.put(t.getUniqueId(), key);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        dropDisplay(id);
        String tn = TEAM_PREFIX + id.toString().replace("-", "").substring(0, 12);
        for (Scoreboard sb : boards()) {
            Team t = sb.getTeam(tn);
            if (t != null) t.unregister();
            sb.resetScores(e.getPlayer().getName());
            Map<UUID, String> c = applied.get(sb);
            if (c != null) c.remove(id);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        counter = (counter / period) * period + period - 5;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        dropDisplay(e.getPlayer().getUniqueId());
        getServer().getScheduler().runTaskLater(this, () -> counter = (counter / period) * period + period - 1, 2L);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        dropDisplay(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        dropDisplay(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        TextDisplay d = displays.get(e.getPlayer().getUniqueId());
        if (d != null && d.isValid()) d.setTextOpacity(e.isSneaking() ? (byte) 0x60 : (byte) 0xFF);
    }
}
