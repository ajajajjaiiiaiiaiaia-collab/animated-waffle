package me.starkmc.zonearmor;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ZoneArmorPlugin extends JavaPlugin implements Listener {

    private static final int CHEST_SLOT = 38;

    private record Zone(String name, String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        boolean contains(Location l) {
            if (l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
            int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
            return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
        }
    }

    private final Map<String, Zone> zones = new LinkedHashMap<>();
    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();
    private NamespacedKey kForced, kActive, kItem;
    private File zonesFile;

    @Override
    public void onEnable() {
        kForced = new NamespacedKey(this, "forced");
        kActive = new NamespacedKey(this, "active");
        kItem = new NamespacedKey(this, "item");
        zonesFile = new File(getDataFolder(), "zones.yml");
        loadZones();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) check(p);
        }, 5L, 5L);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) leave(p);
    }

    private void loadZones() {
        zones.clear();
        if (!zonesFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(zonesFile);
        for (String n : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(n);
            if (s == null) continue;
            zones.put(n, new Zone(n, s.getString("world", "world"),
                    s.getInt("x1"), s.getInt("y1"), s.getInt("z1"),
                    s.getInt("x2"), s.getInt("y2"), s.getInt("z2")));
        }
    }

    private void saveZones() {
        YamlConfiguration y = new YamlConfiguration();
        for (Zone z : zones.values()) {
            String p = z.name() + ".";
            y.set(p + "world", z.world());
            y.set(p + "x1", z.x1()); y.set(p + "y1", z.y1()); y.set(p + "z1", z.z1());
            y.set(p + "x2", z.x2()); y.set(p + "y2", z.y2()); y.set(p + "z2", z.z2());
        }
        try {
            getDataFolder().mkdirs();
            y.save(zonesFile);
        } catch (IOException e) {
            getLogger().warning("Could not save zones.yml");
        }
    }

    private boolean inZone(Location l) {
        for (Zone z : zones.values()) if (z.contains(l)) return true;
        return false;
    }

    private boolean exempt(Player p) {
        return false;
    }

    private boolean isForced(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(kForced, PersistentDataType.BYTE);
    }

    private ItemStack forcedItem() {
        ItemStack it = new ItemStack(Material.DIAMOND_CHESTPLATE);
        ItemMeta m = it.getItemMeta();
        m.setUnbreakable(true);
        m.getPersistentDataContainer().set(kForced, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private boolean isActive(Player p) {
        return p.getPersistentDataContainer().has(kActive, PersistentDataType.BYTE);
    }

    private void check(Player p) {
        if (!p.isOnline()) return;
        boolean should = !exempt(p) && inZone(p.getLocation());
        boolean active = isActive(p);
        if (should && !active) enter(p);
        else if (should && !isForced(p.getInventory().getChestplate())) enter(p);
        else if (!should && (active || hasForcedAnywhere(p))) leave(p);
    }

    private boolean hasForcedAnywhere(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (isForced(it)) return true;
        return false;
    }

    private void give(Player p, ItemStack it) {
        p.getInventory().addItem(it).values()
                .forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
    }

    private void enter(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack cur = inv.getChestplate();
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (isForced(cur)) {
            if (!isActive(p)) pdc.set(kActive, PersistentDataType.BYTE, (byte) 1);
            return;
        }
        if (!isActive(p)) {
            pdc.set(kActive, PersistentDataType.BYTE, (byte) 1);
            if (cur != null && cur.getType() != Material.AIR) {
                pdc.set(kItem, PersistentDataType.BYTE_ARRAY, cur.serializeAsBytes());
            } else {
                pdc.remove(kItem);
            }
        } else if (cur != null && cur.getType() != Material.AIR) {
            give(p, cur);
        }
        if (p.isGliding()) p.setGliding(false);
        inv.setChestplate(forcedItem());
    }

    private void leave(Player p) {
        PlayerInventory inv = p.getInventory();
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        ItemStack orig = null;
        byte[] raw = pdc.get(kItem, PersistentDataType.BYTE_ARRAY);
        if (raw != null) {
            try { orig = ItemStack.deserializeBytes(raw); } catch (Exception ignored) {}
        }
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isForced(contents[i])) inv.setItem(i, null);
        }
        if (isForced(p.getItemOnCursor())) p.setItemOnCursor(null);
        if (orig != null) {
            ItemStack now = inv.getChestplate();
            if (now == null || now.getType() == Material.AIR) inv.setChestplate(orig);
            else give(p, orig);
        }
        pdc.remove(kActive);
        pdc.remove(kItem);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.hasChangedBlock()) check(e.getPlayer());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        Location to = e.getTo();
        Bukkit.getScheduler().runTask(this, () -> check(p));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> check(p), 1L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> check(p), 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        leave(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!isActive(p) && !hasForcedAnywhere(p)) return;
        e.getDrops().removeIf(this::isForced);
        if (e.getKeepInventory()) {
            leave(p);
            return;
        }
        byte[] raw = p.getPersistentDataContainer().get(kItem, PersistentDataType.BYTE_ARRAY);
        if (raw != null) {
            try { e.getDrops().add(ItemStack.deserializeBytes(raw)); } catch (Exception ignored) {}
        }
        p.getPersistentDataContainer().remove(kActive);
        p.getPersistentDataContainer().remove(kItem);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isForced(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
        }
    }

    private boolean locked(Player p) {
        return isActive(p) && !exempt(p);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !locked(p)) return;
        boolean chestClicked = e.getClickedInventory() instanceof PlayerInventory && e.getSlot() == CHEST_SLOT;
        boolean forcedMoved = isForced(e.getCurrentItem()) || isForced(e.getCursor());
        if (chestClicked || forcedMoved) {
            e.setCancelled(true);
            return;
        }
        if (e.getClick().isShiftClick() && wearableChest(e.getCurrentItem())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !locked(p)) return;
        for (int raw : e.getRawSlots()) {
            if (e.getView().getInventory(raw) instanceof PlayerInventory
                    && e.getView().convertSlot(raw) == CHEST_SLOT) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!locked(p) || !wearableChest(e.getItem())) return;
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDispense(BlockDispenseArmorEvent e) {
        if (e.getTargetEntity() instanceof Player p && locked(p)) e.setCancelled(true);
    }

    private boolean wearableChest(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) return false;
        String n = it.getType().name();
        if (n.endsWith("_CHESTPLATE") || it.getType() == Material.ELYTRA) return true;
        if (it.hasItemMeta() && it.getItemMeta().hasEquippable()) {
            return it.getItemMeta().getEquippable().getSlot() == EquipmentSlot.CHEST;
        }
        return false;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("zonearmor.admin")) return true;
        String sub = a.length > 0 ? a[0].toLowerCase() : "list";
        switch (sub) {
            case "pos1", "pos2" -> {
                if (!(s instanceof Player p)) return true;
                (sub.equals("pos1") ? pos1 : pos2).put(p.getUniqueId(), p.getLocation());
                s.sendMessage("ok");
            }
            case "create" -> {
                if (!(s instanceof Player p) || a.length < 2) { s.sendMessage("/zonearmor create <name>"); return true; }
                Location l1 = pos1.get(p.getUniqueId()), l2 = pos2.get(p.getUniqueId());
                if (l1 == null || l2 == null || !l1.getWorld().equals(l2.getWorld())) {
                    s.sendMessage("set pos1 and pos2 first (same world)");
                    return true;
                }
                zones.put(a[1], new Zone(a[1], l1.getWorld().getName(),
                        Math.min(l1.getBlockX(), l2.getBlockX()), Math.min(l1.getBlockY(), l2.getBlockY()),
                        Math.min(l1.getBlockZ(), l2.getBlockZ()), Math.max(l1.getBlockX(), l2.getBlockX()),
                        Math.max(l1.getBlockY(), l2.getBlockY()), Math.max(l1.getBlockZ(), l2.getBlockZ())));
                saveZones();
                s.sendMessage("ok");
            }
            case "delete" -> {
                if (a.length < 2 || zones.remove(a[1]) == null) { s.sendMessage("no such zone"); return true; }
                saveZones();
                s.sendMessage("ok");
            }
            default -> {
                List<String> out = new ArrayList<>();
                for (Zone z : zones.values()) {
                    out.add(z.name() + " " + z.world() + " " + z.x1() + "," + z.y1() + "," + z.z1()
                            + " -> " + z.x2() + "," + z.y2() + "," + z.z2());
                }
                s.sendMessage(out.isEmpty() ? "no zones" : String.join("\n", out));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return List.of("pos1", "pos2", "create", "delete", "list");
        if (a.length == 2 && a[0].equalsIgnoreCase("delete")) return new ArrayList<>(zones.keySet());
        return List.of();
    }
}
