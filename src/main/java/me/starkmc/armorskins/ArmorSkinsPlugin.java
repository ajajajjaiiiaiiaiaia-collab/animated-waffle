package me.starkmc.armorskins;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.event.player.PlayerArmorChangeEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ArmorSkinsPlugin extends JavaPlugin implements Listener {

    private static final String[] PIECES = {"helmet", "chestplate", "leggings", "boots"};
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private NamespacedKey keySet;
    private NamespacedKey keyPiece;
    private final Map<String, SkinSet> sets = new LinkedHashMap<>();
    private final Map<UUID, String> applied = new HashMap<>();
    private final Map<UUID, ProfileProperty> originals = new HashMap<>();
    private final Set<UUID> disabled = new HashSet<>();
    private File dataFile;
    private boolean defaultEnabled;
    private boolean hideModel;

    private record SkinSet(String id, String displayName, String value, String signature,
                           Map<String, ConfigurationSection> pieces) {}

    @Override
    public void onEnable() {
        saveDefaultConfig();
        keySet = new NamespacedKey(this, "set");
        keyPiece = new NamespacedKey(this, "piece");
        dataFile = new File(getDataFolder(), "players.yml");
        loadAll();
        getServer().getPluginManager().registerEvents(this, this);
        int interval = Math.max(2, getConfig().getInt("settings.check-interval-ticks", 10));
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) update(p);
        }, interval, interval);
    }

    @Override
    public void onDisable() {
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) restore(p);
        saveData();
    }

    private void loadAll() {
        reloadConfig();
        defaultEnabled = getConfig().getBoolean("settings.default-enabled", true);
        hideModel = getConfig().getBoolean("settings.hide-armor-model", true);
        sets.clear();
        ConfigurationSection root = getConfig().getConfigurationSection("sets");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(id);
                if (s == null) continue;
                ConfigurationSection ps = s.getConfigurationSection("pieces");
                if (ps == null) continue;
                Map<String, ConfigurationSection> pieces = new LinkedHashMap<>();
                for (String piece : PIECES) {
                    ConfigurationSection c = ps.getConfigurationSection(piece);
                    if (c != null) pieces.put(piece, c);
                }
                if (pieces.size() != 4) {
                    getLogger().warning("Set '" + id + "' must define all 4 pieces; skipped.");
                    continue;
                }
                sets.put(id, new SkinSet(id, s.getString("display-name", id),
                        s.getString("texture-value", ""), s.getString("texture-signature", ""), pieces));
            }
        }
        disabled.clear();
        if (dataFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
            for (String u : y.getStringList("disabled")) {
                try { disabled.add(UUID.fromString(u)); } catch (IllegalArgumentException ignored) {}
            }
            if (!defaultEnabled) {
                disabled.clear();
                for (String u : y.getStringList("enabled")) {
                    try { disabled.add(UUID.fromString(u)); } catch (IllegalArgumentException ignored) {}
                }
            }
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> l = new ArrayList<>();
        for (UUID u : disabled) l.add(u.toString());
        y.set(defaultEnabled ? "disabled" : "enabled", l);
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save players.yml: " + e.getMessage());
        }
    }

    // when default-enabled is false the set holds the players who opted IN
    private boolean isEnabled(Player p) {
        boolean listed = disabled.contains(p.getUniqueId());
        return defaultEnabled ? !listed : listed;
    }

    private Component msg(String key, Map<String, String> vars) {
        String s = getConfig().getString("messages." + key, key);
        for (Map.Entry<String, String> e : vars.entrySet()) s = s.replace("%" + e.getKey() + "%", e.getValue());
        return LegacyComponentSerializer.legacyAmpersand()
                .deserialize(getConfig().getString("messages.prefix", "") + s);
    }

    private String wornSet(Player p) {
        String found = null;
        for (int i = 0; i < 4; i++) {
            ItemStack it = p.getInventory().getItem(SLOTS[i]);
            if (it == null || !it.hasItemMeta()) return null;
            var pdc = it.getItemMeta().getPersistentDataContainer();
            String id = pdc.get(keySet, PersistentDataType.STRING);
            String piece = pdc.get(keyPiece, PersistentDataType.STRING);
            if (id == null || piece == null || !piece.equals(PIECES[i])) return null;
            if (found == null) found = id;
            else if (!found.equals(id)) return null;
        }
        return sets.containsKey(found) ? found : null;
    }

    private void update(Player p) {
        String set = wornSet(p);
        String want = (set != null && isEnabled(p)) ? set : null;
        String cur = applied.get(p.getUniqueId());
        if (Objects.equals(want, cur)) return;
        if (want == null) {
            restore(p);
            p.sendMessage(msg("restored", Map.of()));
            return;
        }
        SkinSet s = sets.get(want);
        if (s.value().isBlank()) return;
        PlayerProfile prof = p.getPlayerProfile();
        if (cur == null) {
            ProfileProperty orig = null;
            for (ProfileProperty pp : prof.getProperties()) {
                if (pp.getName().equals("textures")) { orig = pp; break; }
            }
            originals.put(p.getUniqueId(), orig);
        }
        String sig = s.signature().isBlank() ? null : s.signature();
        prof.setProperty(new ProfileProperty("textures", s.value(), sig));
        p.setPlayerProfile(prof);
        applied.put(p.getUniqueId(), want);
        p.sendMessage(msg("applied", Map.of("set", s.displayName().replaceAll("&.", ""))));
    }

    private void restore(Player p) {
        UUID id = p.getUniqueId();
        if (!applied.containsKey(id)) return;
        PlayerProfile prof = p.getPlayerProfile();
        ProfileProperty orig = originals.remove(id);
        if (orig != null) prof.setProperty(orig); else prof.removeProperty("textures");
        p.setPlayerProfile(prof);
        applied.remove(id);
    }

    @EventHandler
    public void onArmor(PlayerArmorChangeEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> { if (p.isOnline()) update(p); });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) update(p); }, 20L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) update(p); }, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        applied.remove(e.getPlayer().getUniqueId());
        originals.remove(e.getPlayer().getUniqueId());
    }

    private ItemStack build(SkinSet s, String piece) {
        ConfigurationSection c = s.pieces().get(piece);
        Material m = Material.matchMaterial(c.getString("material", "LEATHER_HELMET"));
        if (m == null) m = Material.LEATHER_HELMET;
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        LegacyComponentSerializer ser = LegacyComponentSerializer.legacyAmpersand();
        meta.displayName(ser.deserialize(c.getString("name", piece)).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String l : c.getStringList("lore")) lore.add(ser.deserialize(l).decoration(TextDecoration.ITALIC, false));
        if (!lore.isEmpty()) meta.lore(lore);
        String col = c.getString("color", "");
        if (meta instanceof LeatherArmorMeta lm && col.matches("#[0-9a-fA-F]{6}")) {
            lm.setColor(Color.fromRGB(Integer.parseInt(col.substring(1), 16)));
        }
        if (hideModel) {
            EquippableComponent eq = meta.getEquippable();
            eq.setSlot(SLOTS[indexOf(piece)]);
            eq.setModel((NamespacedKey) null);
            meta.setEquippable(eq);
        }
        meta.getPersistentDataContainer().set(keySet, PersistentDataType.STRING, s.id());
        meta.getPersistentDataContainer().set(keyPiece, PersistentDataType.STRING, piece);
        it.setItemMeta(meta);
        return it;
    }

    private static int indexOf(String piece) {
        for (int i = 0; i < PIECES.length; i++) if (PIECES[i].equals(piece)) return i;
        return 0;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("setting")) {
            if (!(sender instanceof Player p)) return true;
            boolean now;
            if (args.length > 0 && args[0].equalsIgnoreCase("on")) now = true;
            else if (args.length > 0 && args[0].equalsIgnoreCase("off")) now = false;
            else now = !isEnabled(p);
            boolean listedWhenOn = !defaultEnabled;
            if (now == listedWhenOn) disabled.add(p.getUniqueId()); else disabled.remove(p.getUniqueId());
            saveData();
            p.sendMessage(msg(now ? "enabled" : "disabled", Map.of()));
            update(p);
            return true;
        }
        if (!sender.hasPermission("armorskins.admin")) {
            sender.sendMessage(msg("no-permission", Map.of()));
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "list";
        switch (sub) {
            case "reload" -> {
                for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) restore(p);
                loadAll();
                sender.sendMessage(msg("reloaded", Map.of()));
            }
            case "give" -> {
                if (args.length < 3) { sender.sendMessage("/armorskin give <player> <set> [piece]"); return true; }
                Player t = Bukkit.getPlayerExact(args[1]);
                SkinSet s = sets.get(args[2]);
                if (t == null) { sender.sendMessage("Player not found"); return true; }
                if (s == null) { sender.sendMessage(msg("unknown-set", Map.of())); return true; }
                for (String piece : PIECES) {
                    if (args.length > 3 && !args[3].equalsIgnoreCase(piece)) continue;
                    t.getInventory().addItem(build(s, piece)).values()
                            .forEach(r -> t.getWorld().dropItemNaturally(t.getLocation(), r));
                }
                sender.sendMessage(msg("given", Map.of("player", t.getName(), "set", s.id())));
            }
            default -> sender.sendMessage("Sets: " + String.join(", ", sets.keySet()));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("setting")) {
            if (args.length == 1) { out.add("on"); out.add("off"); }
            return out;
        }
        if (args.length == 1) { out.add("give"); out.add("reload"); out.add("list"); }
        else if (args.length == 2 && args[0].equalsIgnoreCase("give")) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        else if (args.length == 3 && args[0].equalsIgnoreCase("give")) out.addAll(sets.keySet());
        else if (args.length == 4 && args[0].equalsIgnoreCase("give")) out.addAll(List.of(PIECES));
        return out;
    }
}
