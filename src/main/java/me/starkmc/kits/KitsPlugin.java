package me.starkmc.kits;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KitsPlugin extends JavaPlugin implements Listener {

    private static final String PERM_PREFIX = "starkkits.kit.";
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_\\-]{2,24}");
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)([dhm])");
    private static final int EDITOR_ICON = 13;
    private static final int EDITOR_CONTINUE = 11;
    private static final int EDITOR_CANCEL = 15;
    private static final int EDITOR_DELETE = 22;
    private static final int CONTENTS_CONTINUE = 47;
    private static final int CONTENTS_LOAD = 49;
    private static final int CONTENTS_CANCEL = 51;
    private static final long SESSION_TIMEOUT_MS = 120_000L;

    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<String, Kit> kits = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Long>> claims = new HashMap<>();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private File kitsFile;
    private File dataFile;
    private boolean dirty;

    static final class Kit {
        String id;
        String name;
        int slot;
        long cooldown;
        ItemStack icon;
        ItemStack[] items = new ItemStack[41];
    }

    enum Stage { ICON, CONTENTS, NAME, COOLDOWN }

    static final class Session {
        final int slot;
        final Kit existing;
        ItemStack icon;
        ItemStack[] items = new ItemStack[41];
        volatile Stage stage = Stage.ICON;
        volatile boolean transition;
        boolean deleteArmed;
        String name;
        long expires;

        Session(int slot, Kit existing) {
            this.slot = slot;
            this.existing = existing;
        }
    }

    abstract static class Holder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    static final class ManageHolder extends Holder {}

    static final class EditorHolder extends Holder {
        final Session session;

        EditorHolder(Session s) {
            this.session = s;
        }
    }

    static final class ContentsHolder extends Holder {
        final Session session;

        ContentsHolder(Session s) {
            this.session = s;
        }
    }

    static final class PlayerHolder extends Holder {
        final Map<Integer, String> slots = new HashMap<>();
    }

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        kitsFile = new File(getDataFolder(), "kits.yml");
        dataFile = new File(getDataFolder(), "data.yml");
        loadKits();
        loadClaims();
        getServer().getPluginManager().registerEvents(this, this);
        takeOverCommand();
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 100L, 100L);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) p.closeInventory();
        }
        saveClaims();
    }

    private void takeOverCommand() {
        var cmd = getCommand("kit");
        if (cmd == null) return;
        cmd.setExecutor(this);
        cmd.setTabCompleter((s, c, l, a) -> a.length == 1 && s.hasPermission("starkkits.admin") && "manage".startsWith(a[0].toLowerCase(Locale.ROOT))
                ? List.of("manage") : List.of());
        Bukkit.getCommandMap().getKnownCommands().put("kit", cmd);
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent e) {
        takeOverCommand();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Session> en : sessions.entrySet()) {
            Session s = en.getValue();
            if ((s.stage == Stage.NAME || s.stage == Stage.COOLDOWN) && s.expires < now) {
                sessions.remove(en.getKey());
                Player p = Bukkit.getPlayer(en.getKey());
                if (p != null) p.sendMessage(msg("<red>Kit setup timed out."));
            }
        }
        if (dirty) saveClaims();
    }

    private Component c(String s) {
        return mm.deserialize("<!italic>" + s);
    }

    private Component msg(String s) {
        return mm.deserialize("<dark_gray>[<aqua>Kits<dark_gray>] <gray>" + s);
    }

    private ItemStack glass() {
        ItemStack it = new ItemStack(Material.GLASS_PANE);
        ItemMeta m = it.getItemMeta();
        m.setHideTooltip(true);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack button(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(c(name));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(c(s));
            m.lore(l);
        }
        it.setItemMeta(m);
        return it;
    }

    private void fill(Inventory inv) {
        ItemStack g = glass();
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, g);
    }

    static String fmt(long seconds) {
        if (seconds <= 0) return "None";
        long d = seconds / 86400;
        long h = seconds % 86400 / 3600;
        long m = seconds % 3600 / 60;
        long s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (sb.length() == 0) sb.append(s).append("s");
        return sb.toString().trim();
    }

    static long parseDuration(String raw) {
        String t = raw.toLowerCase(Locale.ROOT).replace(" ", "");
        if (t.equals("0") || t.equals("none") || t.equals("off")) return 0;
        Matcher m = DURATION_PART.matcher(t);
        long total = 0;
        int consumed = 0;
        while (m.find()) {
            if (m.start() != consumed) return -1;
            consumed = m.end();
            long n = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "d" -> total += n * 86400;
                case "h" -> total += n * 3600;
                default -> total += n * 60;
            }
        }
        if (consumed != t.length() || consumed == 0 || total <= 0 || total > 3650L * 86400) return -1;
        return total;
    }

    private String node(Kit k) {
        return PERM_PREFIX + k.id;
    }

    private void registerPerm(Kit k) {
        if (Bukkit.getPluginManager().getPermission(node(k)) == null) {
            Bukkit.getPluginManager().addPermission(new Permission(node(k), "Access to kit " + k.name, PermissionDefault.FALSE));
        }
    }

    private void unregisterPerm(Kit k) {
        Bukkit.getPluginManager().removePermission(node(k));
    }

    private void loadKits() {
        kits.clear();
        if (!kitsFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(kitsFile);
        ConfigurationSection root = y.getConfigurationSection("kits");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Kit k = new Kit();
            k.id = id;
            k.name = s.getString("name", id);
            k.slot = s.getInt("slot");
            k.cooldown = s.getLong("cooldown");
            k.icon = s.getItemStack("icon");
            ConfigurationSection items = s.getConfigurationSection("items");
            if (items != null) {
                for (String key : items.getKeys(false)) {
                    try {
                        int i = Integer.parseInt(key);
                        if (i >= 0 && i < 41) k.items[i] = items.getItemStack(key);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (k.icon == null) k.icon = new ItemStack(Material.CHEST);
            kits.put(id, k);
            registerPerm(k);
        }
    }

    private void saveKits() {
        YamlConfiguration y = new YamlConfiguration();
        for (Kit k : kits.values()) {
            String p = "kits." + k.id + ".";
            y.set(p + "name", k.name);
            y.set(p + "slot", k.slot);
            y.set(p + "cooldown", k.cooldown);
            y.set(p + "icon", k.icon);
            for (int i = 0; i < 41; i++) {
                if (k.items[i] != null && k.items[i].getType() != Material.AIR) y.set(p + "items." + i, k.items[i]);
            }
        }
        try {
            y.save(kitsFile);
        } catch (IOException e) {
            getLogger().warning("Could not save kits.yml: " + e.getMessage());
        }
    }

    private void loadClaims() {
        claims.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = y.getConfigurationSection("players");
        if (root == null) return;
        for (String u : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(u);
                ConfigurationSection s = root.getConfigurationSection(u);
                if (s == null) continue;
                Map<String, Long> m = new HashMap<>();
                for (String kit : s.getKeys(false)) m.put(kit, s.getLong(kit));
                claims.put(id, m);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveClaims() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Long>> e : claims.entrySet()) {
            for (Map.Entry<String, Long> k : e.getValue().entrySet()) y.set("players." + e.getKey() + "." + k.getKey(), k.getValue());
        }
        try {
            y.save(dataFile);
            dirty = false;
        } catch (IOException e) {
            getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }

    private long remaining(Player p, Kit k) {
        if (k.cooldown <= 0) return 0;
        Long last = claims.getOrDefault(p.getUniqueId(), Map.of()).get(k.id);
        if (last == null) return 0;
        long left = (last + k.cooldown * 1000L - System.currentTimeMillis()) / 1000L;
        return Math.max(0, left);
    }

    private List<Component> summary(Kit k) {
        Map<String, Object[]> agg = new LinkedHashMap<>();
        for (ItemStack it : k.items) {
            if (it == null || it.getType() == Material.AIR) continue;
            ItemMeta meta = it.getItemMeta();
            boolean named = meta != null && meta.hasDisplayName();
            String key = named ? "n:" + PlainTextComponentSerializer.plainText().serialize(meta.displayName()) : "t:" + it.getType().name();
            Object[] e = agg.get(key);
            if (e == null) {
                Component name = named ? meta.displayName() : Component.translatable(it.getType().translationKey());
                e = new Object[]{name, 0, false};
                agg.put(key, e);
            }
            e[1] = (int) e[1] + it.getAmount();
            if (it.getEnchantments().size() > 0 || (meta != null && meta.hasEnchants())) e[2] = true;
        }
        List<Component> out = new ArrayList<>();
        int shown = 0;
        for (Object[] e : agg.values()) {
            if (shown >= 10) break;
            Component line = c("<dark_gray>▪ <white>" + e[1] + "x ").append(((Component) e[0]).colorIfAbsent(net.kyori.adventure.text.format.NamedTextColor.WHITE));
            if ((boolean) e[2]) line = line.append(c(" <light_purple>✦"));
            out.add(line);
            shown++;
        }
        if (agg.size() > shown) out.add(c("<dark_gray>▪ <gray>+" + (agg.size() - shown) + " more..."));
        if (out.isEmpty()) out.add(c("<gray>Empty kit"));
        return out;
    }

    private ItemStack kitItem(Kit k, Player viewer, boolean admin) {
        ItemStack it = k.icon.clone();
        it.setAmount(1);
        ItemMeta m = it.getItemMeta();
        m.displayName(c("<aqua><bold>" + k.name.toUpperCase(Locale.ROOT)));
        m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        String line = "<dark_gray><st>                    </st>";
        List<Component> lore = new ArrayList<>();
        lore.add(c(line));
        lore.add(c("<gray>Contents:"));
        lore.addAll(summary(k));
        lore.add(c(line));
        lore.add(c("<gray>Cooldown: <white>" + fmt(k.cooldown)));
        if (admin) {
            lore.add(c("<gray>Permission: <white>" + node(k)));
            lore.add(c(line));
            lore.add(c("<yellow>▶ Click to edit"));
        } else {
            long left = remaining(viewer, k);
            lore.add(left > 0 ? c("<gray>Status: <red>✘ <white>" + fmt(left)) : c("<gray>Status: <green>✔ Ready"));
            lore.add(c(line));
            lore.add(left > 0 ? c("<red>Cooldown active") : c("<yellow>▶ Click to claim"));
        }
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    private void openManage(Player p) {
        ManageHolder h = new ManageHolder();
        Inventory inv = Bukkit.createInventory(h, 36, c("<dark_gray>Kit Manager"));
        h.inv = inv;
        fill(inv);
        for (Kit k : kits.values()) {
            if (k.slot >= 0 && k.slot < 36) inv.setItem(k.slot, kitItem(k, p, true));
        }
        p.openInventory(inv);
    }

    private void openPlayerMenu(Player p) {
        PlayerHolder h = new PlayerHolder();
        Inventory inv = Bukkit.createInventory(h, 36, c("<dark_gray>Kits"));
        h.inv = inv;
        fill(inv);
        int count = 0;
        for (Kit k : kits.values()) {
            if (!p.hasPermission(node(k)) || k.slot < 0 || k.slot >= 36) continue;
            inv.setItem(k.slot, kitItem(k, p, false));
            h.slots.put(k.slot, k.id);
            count++;
        }
        if (count == 0) {
            p.sendMessage(msg("<red>You don't have access to any kits."));
            return;
        }
        p.openInventory(inv);
    }

    private void openEditor(Player p, Session s) {
        EditorHolder h = new EditorHolder(s);
        Inventory inv = Bukkit.createInventory(h, 27, c("<dark_gray>Kit Editor"));
        h.inv = inv;
        fill(inv);
        inv.setItem(EDITOR_ICON, s.icon == null ? null : s.icon.clone());
        inv.setItem(EDITOR_CONTINUE, button(Material.LIME_STAINED_GLASS_PANE, "<green><bold>CONTINUE", "<gray>Go to the kit contents"));
        inv.setItem(EDITOR_CANCEL, button(Material.RED_STAINED_GLASS_PANE, "<red><bold>CANCEL", "<gray>Discard and go back"));
        if (s.existing != null) {
            inv.setItem(EDITOR_DELETE, button(Material.BARRIER, "<red><bold>DELETE KIT", "<gray>Click twice to confirm"));
        }
        s.stage = Stage.ICON;
        switchTo(p, s, inv);
        p.sendMessage(msg("Click any item in <white>your inventory</white> to use it as the kit icon. Click the icon again to clear it."));
    }

    private int guiToKit(int g) {
        if (g >= 0 && g <= 26) return 9 + g;
        if (g >= 27 && g <= 35) return g - 27;
        return switch (g) {
            case 37 -> 39;
            case 38 -> 38;
            case 39 -> 37;
            case 40 -> 36;
            case 42 -> 40;
            default -> -1;
        };
    }

    private int kitToGui(int k) {
        if (k >= 9 && k <= 35) return k - 9;
        if (k >= 0 && k <= 8) return 27 + k;
        return switch (k) {
            case 39 -> 37;
            case 38 -> 38;
            case 37 -> 39;
            case 36 -> 40;
            case 40 -> 42;
            default -> -1;
        };
    }

    private void openContents(Player p, Session s) {
        ContentsHolder h = new ContentsHolder(s);
        Inventory inv = Bukkit.createInventory(h, 54, c("<dark_gray>Kit Contents"));
        h.inv = inv;
        ItemStack g = glass();
        for (int i = 0; i < 54; i++) {
            if (guiToKit(i) < 0) inv.setItem(i, g);
        }
        inv.setItem(36, button(Material.GRAY_STAINED_GLASS_PANE, "<gray>Armor →"));
        inv.setItem(41, button(Material.GRAY_STAINED_GLASS_PANE, "<gray>Offhand →"));
        for (int k = 0; k < 41; k++) {
            int gi = kitToGui(k);
            if (gi >= 0 && s.items[k] != null) inv.setItem(gi, s.items[k].clone());
        }
        inv.setItem(CONTENTS_CONTINUE, button(Material.LIME_STAINED_GLASS_PANE, "<green><bold>CONTINUE", "<gray>Save contents and name the kit"));
        inv.setItem(CONTENTS_LOAD, button(Material.CHEST, "<aqua><bold>LOAD MY INVENTORY", "<gray>Copy your current inventory", "<gray>into this kit"));
        inv.setItem(CONTENTS_CANCEL, button(Material.RED_STAINED_GLASS_PANE, "<red><bold>CANCEL", "<gray>Discard and go back"));
        s.stage = Stage.CONTENTS;
        switchTo(p, s, inv);
    }

    private void switchTo(Player p, Session s, Inventory inv) {
        s.transition = true;
        p.openInventory(inv);
        s.transition = false;
    }

    private ItemStack[] readContents(Inventory inv) {
        ItemStack[] out = new ItemStack[41];
        for (int k = 0; k < 41; k++) {
            int gi = kitToGui(k);
            if (gi < 0) continue;
            ItemStack it = inv.getItem(gi);
            if (it != null && it.getType() != Material.AIR) out[k] = it.clone();
        }
        return out;
    }

    private void giveBack(Player p, Inventory inv) {
        for (int k = 0; k < 41; k++) {
            int gi = kitToGui(k);
            if (gi < 0) continue;
            ItemStack it = inv.getItem(gi);
            if (it == null || it.getType() == Material.AIR) continue;
            inv.setItem(gi, null);
            for (ItemStack left : p.getInventory().addItem(it).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        }
    }

    private void sound(Player p, Sound s) {
        p.playSound(p.getLocation(), s, 1f, 1f);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder holder)) return;

        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }

        boolean inTop = e.getClickedInventory() != null && e.getClickedInventory().equals(top);

        if (holder instanceof PlayerHolder ph) {
            e.setCancelled(true);
            if (!inTop) return;
            String id = ph.slots.get(e.getSlot());
            if (id == null) return;
            Kit k = kits.get(id);
            if (k != null) claim(p, k, top, e.getSlot());
            return;
        }

        if (holder instanceof ManageHolder) {
            e.setCancelled(true);
            if (!inTop || !p.hasPermission("starkkits.admin")) return;
            int slot = e.getSlot();
            Kit existing = null;
            for (Kit k : kits.values()) if (k.slot == slot) existing = k;
            Session s = new Session(slot, existing);
            if (existing != null) {
                s.icon = existing.icon.clone();
                for (int i = 0; i < 41; i++) s.items[i] = existing.items[i] == null ? null : existing.items[i].clone();
            }
            sessions.put(p.getUniqueId(), s);
            sound(p, Sound.UI_BUTTON_CLICK);
            openEditor(p, s);
            return;
        }

        if (holder instanceof EditorHolder eh) {
            e.setCancelled(true);
            Session s = eh.session;
            if (!inTop) {
                ItemStack clicked = e.getCurrentItem();
                if (clicked == null || clicked.getType() == Material.AIR) return;
                ItemStack icon = clicked.clone();
                icon.setAmount(1);
                s.icon = icon;
                top.setItem(EDITOR_ICON, icon.clone());
                sound(p, Sound.UI_BUTTON_CLICK);
                return;
            }
            switch (e.getSlot()) {
                case EDITOR_ICON -> {
                    s.icon = null;
                    top.setItem(EDITOR_ICON, null);
                    sound(p, Sound.UI_BUTTON_CLICK);
                }
                case EDITOR_CONTINUE -> {
                    if (s.icon == null) {
                        sound(p, Sound.ENTITY_VILLAGER_NO);
                        p.sendMessage(msg("<red>Pick an icon first."));
                        return;
                    }
                    sound(p, Sound.UI_BUTTON_CLICK);
                    openContents(p, s);
                }
                case EDITOR_CANCEL -> {
                    sessions.remove(p.getUniqueId());
                    sound(p, Sound.UI_BUTTON_CLICK);
                    s.transition = true;
                    openManage(p);
                    s.transition = false;
                }
                case EDITOR_DELETE -> {
                    if (s.existing == null) return;
                    if (!s.deleteArmed) {
                        s.deleteArmed = true;
                        top.setItem(EDITOR_DELETE, button(Material.TNT, "<red><bold>CLICK AGAIN TO DELETE", "<gray>This cannot be undone"));
                        sound(p, Sound.ENTITY_VILLAGER_NO);
                        return;
                    }
                    kits.remove(s.existing.id);
                    unregisterPerm(s.existing);
                    for (Map<String, Long> m : claims.values()) m.remove(s.existing.id);
                    dirty = true;
                    saveKits();
                    sessions.remove(p.getUniqueId());
                    p.sendMessage(msg("Kit <white>" + s.existing.name + "</white> deleted."));
                    s.transition = true;
                    openManage(p);
                    s.transition = false;
                }
                default -> {
                }
            }
            return;
        }

        if (holder instanceof ContentsHolder ch) {
            Session s = ch.session;
            if (inTop) {
                int raw = e.getSlot();
                if (raw == CONTENTS_CONTINUE) {
                    e.setCancelled(true);
                    s.items = readContents(top);
                    boolean empty = true;
                    for (ItemStack it : s.items) if (it != null) empty = false;
                    if (empty) {
                        sound(p, Sound.ENTITY_VILLAGER_NO);
                        p.sendMessage(msg("<red>The kit is empty. Add at least one item."));
                        return;
                    }
                    s.stage = Stage.NAME;
                    s.expires = System.currentTimeMillis() + SESSION_TIMEOUT_MS;
                    s.transition = true;
                    p.closeInventory();
                    s.transition = false;
                    promptName(p, s);
                    return;
                }
                if (raw == CONTENTS_CANCEL) {
                    e.setCancelled(true);
                    sessions.remove(p.getUniqueId());
                    s.transition = true;
                    p.closeInventory();
                    s.transition = false;
                    Bukkit.getScheduler().runTask(this, () -> openManage(p));
                    return;
                }
                if (raw == CONTENTS_LOAD) {
                    e.setCancelled(true);
                    giveBack(p, top);
                    PlayerInventory pi = p.getInventory();
                    for (int k = 0; k < 41; k++) {
                        int gi = kitToGui(k);
                        if (gi < 0) continue;
                        ItemStack it = pi.getItem(k);
                        top.setItem(gi, it == null || it.getType() == Material.AIR ? null : it.clone());
                    }
                    sound(p, Sound.UI_BUTTON_CLICK);
                    return;
                }
                if (guiToKit(raw) < 0) e.setCancelled(true);
            }
            return;
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder holder)) return;
        boolean touchesTop = false;
        for (int raw : e.getRawSlots()) if (raw < top.getSize()) touchesTop = true;
        if (!touchesTop) return;
        if (holder instanceof ContentsHolder) {
            for (int raw : e.getRawSlots()) {
                if (raw < top.getSize() && guiToKit(raw) < 0) {
                    e.setCancelled(true);
                    return;
                }
            }
            return;
        }
        e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Inventory inv = e.getInventory();
        if (inv.getHolder() instanceof ContentsHolder ch) {
            giveBack(p, inv);
            if (!ch.session.transition && ch.session.stage == Stage.CONTENTS) sessions.remove(p.getUniqueId());
        } else if (inv.getHolder() instanceof EditorHolder eh) {
            if (!eh.session.transition && eh.session.stage == Stage.ICON) sessions.remove(p.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        sessions.remove(e.getPlayer().getUniqueId());
    }

    private void promptName(Player p, Session s) {
        p.sendMessage(mm.deserialize("<dark_gray><st>      </st> <aqua><bold>KIT SETUP</bold> <dark_gray><st>      </st>"));
        p.sendMessage(mm.deserialize("<gray>Type the <white>kit name</white> in chat <dark_gray>(letters, numbers, _ and -, 2-24 chars)"));
        if (s.existing != null) p.sendMessage(mm.deserialize("<gray>Type <white>keep</white> to keep <aqua>" + s.existing.name));
        p.sendMessage(mm.deserialize("<gray>Type <red>cancel</red> to abort."));
    }

    private void promptCooldown(Player p, Session s) {
        p.sendMessage(mm.deserialize("<dark_gray><st>      </st> <aqua><bold>COOLDOWN</bold> <dark_gray><st>      </st>"));
        p.sendMessage(mm.deserialize("<gray>Type the cooldown: <white>30m</white>, <white>2h</white>, <white>1d</white>, <white>1d12h</white> or <white>0</white> for none."));
        if (s.existing != null) p.sendMessage(mm.deserialize("<gray>Type <white>keep</white> to keep <aqua>" + fmt(s.existing.cooldown)));
        p.sendMessage(mm.deserialize("<gray>Type <red>cancel</red> to abort."));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || (s.stage != Stage.NAME && s.stage != Stage.COOLDOWN)) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> handleChat(p, s, text));
    }

    private void handleChat(Player p, Session s, String text) {
        if (sessions.get(p.getUniqueId()) != s) return;
        if (text.equalsIgnoreCase("cancel")) {
            sessions.remove(p.getUniqueId());
            p.sendMessage(msg("Kit setup cancelled."));
            return;
        }
        s.expires = System.currentTimeMillis() + SESSION_TIMEOUT_MS;

        if (s.stage == Stage.NAME) {
            String name;
            if (text.equalsIgnoreCase("keep") && s.existing != null) {
                name = s.existing.name;
            } else if (NAME_PATTERN.matcher(text).matches()) {
                name = text;
            } else {
                p.sendMessage(msg("<red>Invalid name. Use letters, numbers, _ and - (2-24 chars)."));
                return;
            }
            String id = name.toLowerCase(Locale.ROOT);
            Kit other = kits.get(id);
            if (other != null && other != s.existing) {
                p.sendMessage(msg("<red>A kit with that name already exists. Type another name."));
                return;
            }
            s.name = name;
            s.stage = Stage.COOLDOWN;
            promptCooldown(p, s);
            return;
        }

        long cooldown;
        if (text.equalsIgnoreCase("keep") && s.existing != null) {
            cooldown = s.existing.cooldown;
        } else {
            cooldown = parseDuration(text);
            if (cooldown < 0) {
                p.sendMessage(msg("<red>Invalid cooldown. Examples: 30m, 2h, 1d, 1d12h, 0"));
                return;
            }
        }

        Kit k = s.existing != null ? s.existing : new Kit();
        if (s.existing != null) {
            kits.remove(s.existing.id);
            if (!s.existing.id.equals(s.name.toLowerCase(Locale.ROOT))) {
                unregisterPerm(s.existing);
                for (Map<String, Long> m : claims.values()) {
                    Long v = m.remove(s.existing.id);
                    if (v != null) m.put(s.name.toLowerCase(Locale.ROOT), v);
                }
                dirty = true;
            }
        }
        k.id = s.name.toLowerCase(Locale.ROOT);
        k.name = s.name;
        k.slot = s.slot;
        k.cooldown = cooldown;
        k.icon = s.icon;
        k.items = s.items;
        kits.put(k.id, k);
        registerPerm(k);
        saveKits();
        sessions.remove(p.getUniqueId());

        p.sendMessage(mm.deserialize("<dark_gray><st>      </st> <green><bold>KIT SAVED</bold> <dark_gray><st>      </st>"));
        p.sendMessage(mm.deserialize("<gray>Kit: <aqua>" + k.name));
        p.sendMessage(mm.deserialize("<gray>Cooldown: <white>" + fmt(k.cooldown)));
        p.sendMessage(mm.deserialize("<gray>Permission: <white>" + node(k)));
        sound(p, Sound.ENTITY_PLAYER_LEVELUP);
        openManage(p);
    }

    private void claim(Player p, Kit k, Inventory view, int slot) {
        if (!p.hasPermission(node(k))) {
            sound(p, Sound.ENTITY_VILLAGER_NO);
            p.sendMessage(msg("<red>You don't have access to this kit."));
            return;
        }
        long left = remaining(p, k);
        if (left > 0) {
            sound(p, Sound.ENTITY_VILLAGER_NO);
            p.sendMessage(msg("<red>You can claim <white>" + k.name + "</white> again in <white>" + fmt(left) + "</white>."));
            return;
        }

        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 41; i++) {
            ItemStack it = k.items[i];
            if (it == null || it.getType() == Material.AIR) continue;
            ItemStack give = it.clone();
            ItemStack current = inv.getItem(i);
            if (current == null || current.getType() == Material.AIR) {
                inv.setItem(i, give);
            } else {
                for (ItemStack rest : inv.addItem(give).values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
        }

        if (k.cooldown > 0) {
            claims.computeIfAbsent(p.getUniqueId(), x -> new HashMap<>()).put(k.id, System.currentTimeMillis());
            dirty = true;
        }
        sound(p, Sound.ENTITY_PLAYER_LEVELUP);
        p.sendMessage(msg("You claimed kit <aqua>" + k.name + "</aqua>."));
        view.setItem(slot, kitItem(k, p, false));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("manage")) {
            if (!p.hasPermission("starkkits.admin")) {
                p.sendMessage(msg("<red>You don't have permission."));
                return true;
            }
            openManage(p);
            return true;
        }
        if (!p.hasPermission("starkkits.use")) {
            p.sendMessage(msg("<red>You don't have permission."));
            return true;
        }
        openPlayerMenu(p);
        return true;
    }
}
