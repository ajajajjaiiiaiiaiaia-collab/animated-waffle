package com.duel.gui;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import com.duel.models.Kit;
import com.duel.utils.ItemUtils;
import com.duel.utils.SoundUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player kit editor.
 *
 * Layout (54 slots):
 *   Slots  0-35 : hotbar + inventory items  (editable — reorder only)
 *   Slot   37   : Helmet
 *   Slot   38   : Chestplate
 *   Slot   39   : Leggings
 *   Slot   40   : Boots
 *   Slot   41   : Off-hand
 *   Slot   49   : SAVE button
 *   Rest        : filler glass (blocked)
 *
 * Kit slot mapping is identical to KitEditorGUI.
 */
public class PlayerKitEditorGUI implements InventoryHolder {

    // GUI slot → kit slot (same as KitEditorGUI)
    public static final Map<Integer, Integer> GUI_TO_KIT = new HashMap<>();
    public static final Map<Integer, Integer> KIT_TO_GUI = new HashMap<>();

    public static final int GUI_HELMET     = 37;
    public static final int GUI_CHESTPLATE = 38;
    public static final int GUI_LEGGINGS   = 39;
    public static final int GUI_BOOTS      = 40;
    public static final int GUI_OFFHAND    = 41;
    public static final int GUI_SAVE       = 49;
    public static final int SIZE           = 54;

    static {
        for (int i = 0; i <= 35; i++) { GUI_TO_KIT.put(i, i); KIT_TO_GUI.put(i, i); }
        GUI_TO_KIT.put(GUI_HELMET,     39); KIT_TO_GUI.put(39, GUI_HELMET);
        GUI_TO_KIT.put(GUI_CHESTPLATE, 38); KIT_TO_GUI.put(38, GUI_CHESTPLATE);
        GUI_TO_KIT.put(GUI_LEGGINGS,   37); KIT_TO_GUI.put(37, GUI_LEGGINGS);
        GUI_TO_KIT.put(GUI_BOOTS,      36); KIT_TO_GUI.put(36, GUI_BOOTS);
        GUI_TO_KIT.put(GUI_OFFHAND,    40); KIT_TO_GUI.put(40, GUI_OFFHAND);
    }

    // Slots the player is NOT allowed to move items into/out of player inventory
    // (all GUI slots are fine within the GUI, but bottom player inventory is blocked)
    public static final java.util.Set<Integer> BLOCKED_BOTTOM = new java.util.HashSet<>();

    // track open editors: playerUUID → arenaId
    public static final Map<UUID, String> OPEN_EDITORS = new HashMap<>();

    private final DuelPlugin  plugin;
    private final Inventory   inventory;
    private final String      arenaId;

    public PlayerKitEditorGUI(DuelPlugin plugin, Player player, String arenaId) {
        this.plugin  = plugin;
        this.arenaId = arenaId;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("Kit — " + arenaId)
                        .color(NamedTextColor.LIGHT_PURPLE)
                        .decoration(TextDecoration.BOLD, false));
        populate(player);
    }

    private void populate(Player player) {
        // Filler for bottom row
        ItemStack filler = ItemUtils.buildFiller(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 36; i < SIZE; i++) inventory.setItem(i, filler);

        // Save button
        inventory.setItem(GUI_SAVE, ItemUtils.buildGlow(Material.LIME_DYE,
                "✔ SAVE My Kit", NamedTextColor.GREEN,
                "Saves your personal item arrangement",
                "§7for arena §e" + arenaId));

        // Load items: player's custom kit first, fallback to arena default
        ItemStack[] contents = getEffectiveContents(player);

        // Load player's saved trims so we can display them on armor pieces
        java.util.Map<com.duel.trim.TrimManager.ArmorSlot, com.duel.trim.TrimManager.TrimEntry> trims =
                plugin.getTrimManager().getAllTrims(player.getUniqueId(), arenaId);

        for (int kitSlot = 0; kitSlot <= 40; kitSlot++) {
            Integer guiSlot = KIT_TO_GUI.get(kitSlot);
            if (guiSlot == null) guiSlot = kitSlot;
            if (contents[kitSlot] != null && guiSlot < SIZE) {
                ItemStack item = contents[kitSlot].clone();
                // Armor slots: apply saved trim + append Trim Editor hint to lore
                if (guiSlot == GUI_HELMET || guiSlot == GUI_CHESTPLATE
                        || guiSlot == GUI_LEGGINGS || guiSlot == GUI_BOOTS) {

                    // Map GUI slot → ArmorSlot enum to look up trim
                    com.duel.trim.TrimManager.ArmorSlot trimSlot = switch (guiSlot) {
                        case GUI_HELMET     -> com.duel.trim.TrimManager.ArmorSlot.HELMET;
                        case GUI_CHESTPLATE -> com.duel.trim.TrimManager.ArmorSlot.CHESTPLATE;
                        case GUI_LEGGINGS   -> com.duel.trim.TrimManager.ArmorSlot.LEGGINGS;
                        case GUI_BOOTS      -> com.duel.trim.TrimManager.ArmorSlot.BOOTS;
                        default             -> null;
                    };

                    // Apply trim visually if the player has one saved for this slot
                    com.duel.trim.TrimManager.TrimEntry entry = trimSlot != null ? trims.get(trimSlot) : null;
                    if (entry != null) {
                        item = com.duel.trim.TrimManager.applyTrimToItem(item, entry);
                    }

                    // Append trim hint + current trim info to lore
                    var meta = item.getItemMeta();
                    if (meta != null) {
                        java.util.List<net.kyori.adventure.text.Component> lore =
                            new java.util.ArrayList<>(
                                meta.lore() != null ? meta.lore() : java.util.List.of());
                        lore.add(net.kyori.adventure.text.Component.text(" ")
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                        if (entry != null) {
                            lore.add(net.kyori.adventure.text.Component.text(
                                    "§dTrim: §f" + com.duel.trim.TrimGUI.titleCase(entry.pattern())
                                    + " §8[§f" + com.duel.trim.TrimGUI.titleCase(entry.material()) + "§8]")
                                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                        }
                        lore.add(net.kyori.adventure.text.Component.text("§7Left-click §8\u2192 §dArmor Trim")
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                        meta.lore(lore);
                        item.setItemMeta(meta);
                    }
                }
                inventory.setItem(guiSlot, item);
            }
        }
    }

    /** Returns player's saved kit, or the arena default kit if not customised yet. */
    private ItemStack[] getEffectiveContents(Player player) {
        ItemStack[] saved = plugin.getPlayerKitManager()
                .getPlayerKit(player.getUniqueId(), arenaId);
        if (saved != null) return saved;

        // Fall back to arena kit
        Arena arena = plugin.getArenaManager().getArena(arenaId);
        if (arena != null) {
            Kit kit = plugin.getKitManager().getKit(
                    arena.getKitName() != null ? arena.getKitName() : arenaId);
            if (kit != null) return kit.getContents();
        }
        return new ItemStack[41];
    }

    // ─────────────────────────────────────────
    //  Save
    // ─────────────────────────────────────────

    public static void onSave(DuelPlugin plugin, Player player, Inventory inv, String arenaId) {
        ItemStack[] contents = new ItemStack[41];

        for (Map.Entry<Integer, Integer> e : GUI_TO_KIT.entrySet()) {
            int guiSlot = e.getKey();
            int kitSlot = e.getValue();
            ItemStack item = inv.getItem(guiSlot);
            if (item != null && !isFiller(item)) {
                contents[kitSlot] = item.clone();
            }
        }

        plugin.getPlayerKitManager().savePlayerKit(player.getUniqueId(), arenaId, contents);
        player.closeInventory();
        SoundUtils.playSave(player);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&a✔ Kit arrangement saved for arena &e" + arenaId + "&a!"));
    }

    // ─────────────────────────────────────────
    //  Open
    // ─────────────────────────────────────────

    public static void open(DuelPlugin plugin, Player player, String arenaId) {
        PlayerKitEditorGUI gui = new PlayerKitEditorGUI(plugin, player, arenaId);
        OPEN_EDITORS.put(player.getUniqueId(), arenaId);
        player.openInventory(gui.getInventory());
    }

    // ─────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────

    public static boolean isEditableSlot(int slot) {
        return GUI_TO_KIT.containsKey(slot);
    }

    private static boolean isFiller(ItemStack item) {
        if (item == null) return true;
        var meta = item.getItemMeta();
        if (meta == null) return false;
        var name = meta.displayName();
        return name != null && name.equals(
                Component.text(" ").decoration(TextDecoration.ITALIC, false));
    }

    @Override public Inventory getInventory() { return inventory; }
    public String getArenaId()               { return arenaId; }
}
