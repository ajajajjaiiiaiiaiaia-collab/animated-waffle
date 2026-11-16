package com.duel.trim;

import com.duel.DuelPlugin;
import com.duel.utils.ItemUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;

import java.util.*;

/**
 * TrimGUI — shown when a player left-clicks an armor piece in the Kit Editor.
 *
 * Layout (54 slots):
 *   Rows 0-4 (slots 0-44): one button per trim pattern
 *   Slot 45: RESET (remove trim)
 *   Slot 49: BACK button
 *   Rest:    filler glass
 *
 */
public class TrimGUI implements InventoryHolder {

    // ── All vanilla trim patterns as of 1.21 ─────────────────────────────
    // All patterns shown to every player.
    public static final List<String> ALL_PATTERNS = List.of(
        "sentry", "dune", "coast", "wild", "ward", "eye",
        "vex", "tide", "snout", "rib", "spire", "wayfinder",
        "shaper", "raiser", "host", "silence", "bolt", "flow"
    );

    // Default material used for preview when none is chosen yet
    public static final String DEFAULT_MATERIAL = "amethyst";

    // The material names that exist in vanilla (used in MaterialSelectorGUI)
    public static final List<String> ALL_MATERIALS = List.of(
        "iron", "copper", "gold", "lapis", "emerald", "diamond",
        "netherite", "redstone", "amethyst", "quartz"
    );

    // Map pattern → a representative base armor piece for preview
    private static final Material PREVIEW_BASE = Material.DIAMOND_CHESTPLATE;

    // ── State ─────────────────────────────────────────────────────────────
    public static final Map<UUID, TrimGUI.State> OPEN_GUI = new HashMap<>();

    public record State(TrimManager.ArmorSlot slot, String arenaId) {}

    private final DuelPlugin              plugin;
    private final Inventory               inventory;
    private final TrimManager.ArmorSlot   armorSlot;
    private final String                  arenaId;
    private final Player                  player;
    // slot index → pattern key
    private final Map<Integer, String>    slotToPattern = new LinkedHashMap<>();

    public TrimGUI(DuelPlugin plugin, Player player, TrimManager.ArmorSlot armorSlot, String arenaId) {
        this.plugin    = plugin;
        this.player    = player;
        this.armorSlot = armorSlot;
        this.arenaId   = arenaId;

        this.inventory = Bukkit.createInventory(this, 54,
            Component.text("Armor Trim — " + titleCase(armorSlot.name()))
                .color(NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.BOLD, false));

        populate();
    }

    private void populate() {
        // Filler
        ItemStack filler = ItemUtils.buildFiller(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 54; i++) inventory.setItem(i, filler);

        // Current trim for this player + arena + slot
        TrimManager.TrimEntry current = plugin.getTrimManager().getTrim(player.getUniqueId(), arenaId, armorSlot);
        String currentPattern  = current != null ? current.pattern()  : null;
        String currentMaterial = current != null ? current.material() : DEFAULT_MATERIAL;

        // Build one button per pattern
        int slot = 0;
        for (String patternKey : ALL_PATTERNS) {
            if (slot >= 45) break; // only 45 content slots (rows 0-4)

            boolean selected = patternKey.equals(currentPattern);
            ItemStack btn = buildPatternButton(patternKey, currentMaterial, selected,
                    TrimManager.canUsePattern(player, patternKey));
            inventory.setItem(slot, btn);
            slotToPattern.put(slot, patternKey);
            slot++;
        }

        // RESET button (slot 45)
        inventory.setItem(45, ItemUtils.buildGlow(Material.BARRIER,
            "§c✖ Remove Trim",
            NamedTextColor.RED,
            "§7Removes any trim from",
            "§7this armor piece."));

        // BACK button (slot 49)
        inventory.setItem(49, ItemUtils.buildFiller(Material.ARROW));
        ItemStack back = new ItemStack(Material.ARROW);
        var backMeta = back.getItemMeta();
        backMeta.displayName(Component.text("« Back").color(NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false));
        back.setItemMeta(backMeta);
        inventory.setItem(49, back);
    }

    private ItemStack buildPatternButton(String patternKey, String materialKey, boolean selected, boolean unlocked) {
        // Create a diamond chestplate with the trim applied for preview
        ItemStack preview = new ItemStack(PREVIEW_BASE);
        TrimPattern pattern  = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft(patternKey));
        TrimMaterial material = Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft(materialKey));

        if (preview.getItemMeta() instanceof ArmorMeta meta && pattern != null && material != null) {
            meta.setTrim(new ArmorTrim(material, pattern));
            String display = "§d" + titleCase(patternKey) + " Trim";
            if (selected) display = "§a✔ " + display;
            if (!unlocked) display = "§c🔒 " + titleCase(patternKey) + " Trim";
            meta.displayName(Component.text(display.replace("§", "\u00A7"))
                .decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("§7Pattern: §f" + titleCase(patternKey))
                .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("§7Material: §f" + titleCase(materialKey))
                .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text(" ").decoration(TextDecoration.ITALIC, false));
            if (!unlocked) {
                lore.add(Component.text("§c🔒 Locked").decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("§7You don't have permission").decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("§7to use this trim.").decoration(TextDecoration.ITALIC, false));
            } else if (selected) {
                lore.add(Component.text("§a● Currently selected")
                    .decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("§7Left-click to change material")
                    .decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(Component.text("§eLeft-click to select")
                    .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
            preview.setItemMeta(meta);
        }
        return preview;
    }

    /** Returns the pattern key for a given inventory slot (null if not a pattern slot). */
    public String getPatternForSlot(int slot) {
        return slotToPattern.get(slot);
    }

    public static void open(DuelPlugin plugin, Player player, TrimManager.ArmorSlot armorSlot, String arenaId) {
        TrimGUI gui = new TrimGUI(plugin, player, armorSlot, arenaId);
        OPEN_GUI.put(player.getUniqueId(), new State(armorSlot, arenaId));
        // Store GUI reference for click handling
        OPEN_GUI_INSTANCE.put(player.getUniqueId(), gui);
        player.openInventory(gui.getInventory());
    }

    // Stores the live GUI instance so the listener can call getPatternForSlot()
    public static final Map<UUID, TrimGUI> OPEN_GUI_INSTANCE = new HashMap<>();

    @Override
    public Inventory getInventory() { return inventory; }
    public TrimManager.ArmorSlot getArmorSlot() { return armorSlot; }
    public String getArenaId() { return arenaId; }

    public static String titleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
