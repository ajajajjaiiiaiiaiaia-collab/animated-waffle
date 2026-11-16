package com.duel.gui;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import com.duel.utils.ItemUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Map picker opened from the Grass Block in {@link DuelConfirmGUI}.
 *
 *   Row 1 : gray glass
 *   Row 2 : the maps (worlds) of the chosen arena — icon + display name from the DB
 *   Row 3 : gray glass
 *
 * Clicking a map goes back to the Duel confirm GUI with that map selected.
 * Click handling lives in {@link com.duel.listeners.InventoryListener}.
 */
public class MapSelectorGUI implements InventoryHolder {

    public static final int SIZE = 27;
    /** Middle row has 9 slots (9..17) — that's the max number of maps shown. */
    public static final int MAX_MAPS = 9;

    private final DuelPlugin plugin;
    private final Player     sender;
    private final Player     target;
    private final Arena      arena;
    private final int        rounds;
    private final String     selectedWorld;   // currently selected map (nullable)
    private final java.util.function.Consumer<String> onPick; // party mode: called with the chosen world (nullable)
    private final Inventory  inventory;

    /** slot → real world name */
    private final Map<Integer, String> slotWorldMap = new HashMap<>();

    public MapSelectorGUI(DuelPlugin plugin, Player sender, Player target, Arena arena,
                          int rounds, String selectedWorld) {
        this(plugin, sender, target, arena, rounds, selectedWorld, null);
    }

    private MapSelectorGUI(DuelPlugin plugin, Player sender, Player target, Arena arena,
                           int rounds, String selectedWorld, java.util.function.Consumer<String> onPick) {
        this.onPick = onPick;
        this.plugin = plugin;
        this.sender = sender;
        this.target = target;
        this.arena = arena;
        this.rounds = rounds;
        this.selectedWorld = selectedWorld;
        this.inventory = Bukkit.createInventory(this, SIZE, Component.text("Select Map"));
        populate();
    }

    private void populate() {
        ItemStack filler = ItemUtils.buildFiller(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < SIZE; i++) inventory.setItem(i, filler);

        List<String> worlds = new ArrayList<>(arena.getWorlds());
        if (worlds.size() > MAX_MAPS) {
            plugin.getLogger().warning("[MapSelector] Arena '" + arena.getId() + "' has " + worlds.size()
                    + " maps — only the first " + MAX_MAPS + " fit in the middle row.");
            worlds = worlds.subList(0, MAX_MAPS);
        }

        if (worlds.isEmpty()) {
            inventory.setItem(13, ItemUtils.build(Material.BARRIER, "No Maps Available",
                    NamedTextColor.RED, "Ask an admin to configure maps."));
            return;
        }

        // Centered in the middle row (slots 9..17)
        int start = 9 + (9 - worlds.size()) / 2;
        for (int i = 0; i < worlds.size(); i++) {
            int slot = start + i;
            String world = worlds.get(i);
            inventory.setItem(slot, buildMapItem(world));
            slotWorldMap.put(slot, world);
        }
    }

    private ItemStack buildMapItem(String world) {
        ItemStack item = new ItemStack(arena.getWorldIcon(world));
        ItemMeta meta = item.getItemMeta();
        boolean selected = world.equals(selectedWorld);

        meta.displayName(Component.text(arena.getWorldDisplayName(world))
                .color(selected ? NamedTextColor.GREEN : NamedTextColor.AQUA)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(selected ? "✔ Selected" : "Click to select this map")
                .color(selected ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        if (selected) meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    /** Real world name mapped to this GUI slot, or null (glass / empty). */
    public String getWorldAt(int slot) { return slotWorldMap.get(slot); }

    public static void open(DuelPlugin plugin, Player sender, Player target, Arena arena,
                            int rounds, String selectedWorld) {
        MapSelectorGUI gui = new MapSelectorGUI(plugin, sender, target, arena, rounds, selectedWorld);
        sender.openInventory(gui.getInventory());
    }

    /** Party mode: opens the picker for the party leader; {@code onPick} receives the chosen world. */
    public static void openForParty(DuelPlugin plugin, Player leader, Arena arena, String selectedWorld,
                                    java.util.function.Consumer<String> onPick) {
        MapSelectorGUI gui = new MapSelectorGUI(plugin, leader, null, arena, 1, selectedWorld, onPick);
        leader.openInventory(gui.getInventory());
    }

    public java.util.function.Consumer<String> getOnPick() { return onPick; }

    public Player getSender() { return sender; }
    public Player getTarget() { return target; }
    public Arena  getArena()  { return arena; }
    public int    getRounds() { return rounds; }

    @Override
    public Inventory getInventory() { return inventory; }
}
