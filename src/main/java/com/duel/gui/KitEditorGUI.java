package com.duel.gui;

import com.duel.DuelPlugin;
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
 * Kit Editor GUI — 54 slots
 *
 * Layout:
 *   Rows 1-4 (slots 0-35):  hotbar + inventory items
 *   Slot 37: Helmet
 *   Slot 38: Chestplate
 *   Slot 39: Leggings
 *   Slot 40: Boots
 *   Slot 41: Off-hand
 *   Slot 49: SAVE button
 *   Rest: filler glass
 *
 * Internal slot mapping (Kit.contents[]):
 *   0-35  = hotbar/inventory
 *   36    = boots
 *   37    = leggings
 *   38    = chestplate
 *   39    = helmet
 *   40    = offhand
 */
public class KitEditorGUI implements InventoryHolder {

    // GUI slot → kit slot
    public static final Map<Integer, Integer> GUI_TO_KIT = new HashMap<>();
    // Kit slot → GUI slot
    public static final Map<Integer, Integer> KIT_TO_GUI = new HashMap<>();

    public static final int GUI_HELMET      = 37;
    public static final int GUI_CHESTPLATE  = 38;
    public static final int GUI_LEGGINGS    = 39;
    public static final int GUI_BOOTS       = 40;
    public static final int GUI_OFFHAND     = 41;
    public static final int GUI_SAVE        = 49;
    public static final int SIZE            = 54;

    static {
        // Items: GUI slots 0-35 → kit slots 0-35
        for (int i = 0; i <= 35; i++) { GUI_TO_KIT.put(i, i); KIT_TO_GUI.put(i, i); }
        // Armor / offhand
        GUI_TO_KIT.put(GUI_HELMET,     39);
        GUI_TO_KIT.put(GUI_CHESTPLATE, 38);
        GUI_TO_KIT.put(GUI_LEGGINGS,   37);
        GUI_TO_KIT.put(GUI_BOOTS,      36);
        GUI_TO_KIT.put(GUI_OFFHAND,    40);
        KIT_TO_GUI.put(39, GUI_HELMET);
        KIT_TO_GUI.put(38, GUI_CHESTPLATE);
        KIT_TO_GUI.put(37, GUI_LEGGINGS);
        KIT_TO_GUI.put(36, GUI_BOOTS);
        KIT_TO_GUI.put(40, GUI_OFFHAND);
    }

    // Track open editors
    public static final Map<UUID, String> OPEN_EDITORS = new HashMap<>();

    private final DuelPlugin plugin;
    private final Inventory inventory;
    private final String kitName;

    public KitEditorGUI(DuelPlugin plugin, String kitName) {
        this.plugin  = plugin;
        this.kitName = kitName;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("Kit Editor — " + kitName)
                        .color(NamedTextColor.LIGHT_PURPLE)
                        .decoration(TextDecoration.BOLD, false));
        populate();
    }

    private void populate() {
        // Filler for bottom row
        ItemStack filler = ItemUtils.buildFiller(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 36; i < 54; i++) inventory.setItem(i, filler);

        // Armor label slots (plain — no trim in admin kit editor)
        inventory.setItem(GUI_HELMET,     buildArmorButton(Material.IRON_HELMET,     "§eHelmet"));
        inventory.setItem(GUI_CHESTPLATE, buildArmorButton(Material.IRON_CHESTPLATE, "§eChestplate"));
        inventory.setItem(GUI_LEGGINGS,   buildArmorButton(Material.IRON_LEGGINGS,   "§eLeggings"));
        inventory.setItem(GUI_BOOTS,      buildArmorButton(Material.IRON_BOOTS,      "§eBoots"));
        inventory.setItem(GUI_OFFHAND,    ItemUtils.buildFiller(Material.SHIELD));

        // Labels above armor slots (row 4, cols 1-5)
        setLabel(36, "§7◄ Items ►");
        setLabel(GUI_HELMET,     "§eHelmet");
        setLabel(GUI_CHESTPLATE, "§eChestplate");
        setLabel(GUI_LEGGINGS,   "§eLeggings");
        setLabel(GUI_BOOTS,      "§eBoots");
        setLabel(GUI_OFFHAND,    "§bOff-hand");

        // SAVE button
        ItemStack save = ItemUtils.buildGlow(Material.LIME_DYE, "✔ SAVE Kit",
                NamedTextColor.GREEN,
                "Click to save this kit as §e" + kitName);
        inventory.setItem(GUI_SAVE, save);

        // Load existing kit items
        Kit existing = plugin.getKitManager().getKit(kitName);
        if (existing != null) {
            ItemStack[] contents = existing.getContents();
            for (int kitSlot = 0; kitSlot <= 40; kitSlot++) {
                Integer guiSlot = KIT_TO_GUI.get(kitSlot);
                if (guiSlot == null) guiSlot = kitSlot; // 0-35 are 1:1
                if (contents[kitSlot] != null && guiSlot < SIZE) {
                    inventory.setItem(guiSlot, contents[kitSlot].clone());
                }
            }
        }
    }

    private ItemStack buildArmorButton(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        var meta = item.getItemMeta();
        meta.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private void setLabel(int slot, String text) {
        // Labels are just named glass panes placed near actual slots
        // We skip this to avoid overwriting editable slots — labels are in GUI names only
    }

    public static void onSave(DuelPlugin plugin, Player admin, Inventory inv, String kitName) {
        Kit kit = plugin.getKitManager().kitExists(kitName)
                ? plugin.getKitManager().getKit(kitName)
                : plugin.getKitManager().createKit(kitName);

        for (Map.Entry<Integer, Integer> entry : GUI_TO_KIT.entrySet()) {
            int guiSlot = entry.getKey();
            int kitSlot = entry.getValue();
            ItemStack item = inv.getItem(guiSlot);
            // Skip filler glass
            if (item != null && !isFiller(item)) {
                kit.setSlot(kitSlot, item.clone());
            } else {
                kit.setSlot(kitSlot, null);
            }
        }

        plugin.getKitManager().saveKit(kit);
        admin.closeInventory();
        SoundUtils.playSave(admin);
        admin.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&a✔ Kit &e" + kitName + " &asaved successfully!"));
    }

    public static boolean isFillerItem(ItemStack item) {
        if (item == null) return true;
        var meta = item.getItemMeta();
        if (meta == null) return false;
        var name = meta.displayName();
        return name != null && name.equals(Component.text(" ").decoration(TextDecoration.ITALIC, false));
    }

    private static boolean isFiller(ItemStack item) {
        return isFillerItem(item);
    }

    public static void open(DuelPlugin plugin, Player admin, String kitName) {
        KitEditorGUI gui = new KitEditorGUI(plugin, kitName);
        OPEN_EDITORS.put(admin.getUniqueId(), kitName);
        admin.openInventory(gui.getInventory());
    }

    @Override
    public Inventory getInventory() { return inventory; }
    public String getKitName()      { return kitName; }
}
