package com.duel.trim;

import com.duel.DuelPlugin;
import com.duel.utils.ItemUtils;
import com.duel.utils.SoundUtils;
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
 * MaterialSelectorGUI — shown after a player picks a pattern in TrimGUI.
 *
 * Layout (54 slots):
 *   Slots 10-19, 19-26: one button per material (bordered layout)
 *   Slot 45:   BACK to TrimGUI
 *   Slot 49:   CONFIRM / SAVE
 *   Rest:      filler
 *
 * FIX: Material selection no longer saves immediately. The chosen material
 * is kept in {@link #selectedMaterial} and only persisted when the player
 * clicks the Confirm button (slot 49).
 */
public class MaterialSelectorGUI implements InventoryHolder {

    // material key → the item to display
    private static final Map<String, Material> MATERIAL_ICONS = Map.ofEntries(
        Map.entry("iron",       Material.IRON_INGOT),
        Map.entry("copper",     Material.COPPER_INGOT),
        Map.entry("gold",       Material.GOLD_INGOT),
        Map.entry("lapis",      Material.LAPIS_LAZULI),
        Map.entry("emerald",    Material.EMERALD),
        Map.entry("diamond",    Material.DIAMOND),
        Map.entry("netherite",  Material.NETHERITE_INGOT),
        Map.entry("redstone",   Material.REDSTONE),
        Map.entry("amethyst",   Material.AMETHYST_SHARD),
        Map.entry("quartz",     Material.QUARTZ)
    );

    // Track open instances
    public static final Map<UUID, MaterialSelectorGUI> OPEN_INSTANCES = new HashMap<>();

    private final DuelPlugin             plugin;
    private final Inventory              inventory;
    private final Player                 player;
    private final TrimManager.ArmorSlot  armorSlot;
    private final String                 arenaId;
    private final String                 patternKey;
    private final Map<Integer, String>   slotToMaterial = new LinkedHashMap<>();

    /**
     * Currently highlighted material. NOT yet saved — only written to DB on Confirm.
     * Initialised from the player's existing trim for this slot+pattern, or DEFAULT_MATERIAL.
     */
    private String selectedMaterial;

    public MaterialSelectorGUI(DuelPlugin plugin, Player player,
                               TrimManager.ArmorSlot armorSlot, String arenaId, String patternKey) {
        this(plugin, player, armorSlot, arenaId, patternKey, null);
    }

    public MaterialSelectorGUI(DuelPlugin plugin, Player player,
                               TrimManager.ArmorSlot armorSlot, String arenaId, String patternKey,
                               String overrideMaterial) {
        this.plugin      = plugin;
        this.player      = player;
        this.armorSlot   = armorSlot;
        this.arenaId     = arenaId;
        this.patternKey  = patternKey;

        if (overrideMaterial != null) {
            this.selectedMaterial = overrideMaterial;
        } else {
            // Initialise selectedMaterial from saved trim (same arena+pattern) or default
            TrimManager.TrimEntry current = plugin.getTrimManager()
                    .getTrim(player.getUniqueId(), arenaId, armorSlot);
            this.selectedMaterial = (current != null && current.pattern().equals(patternKey))
                    ? current.material() : TrimGUI.DEFAULT_MATERIAL;
        }

        this.inventory = Bukkit.createInventory(this, 54,
            Component.text("Select Material — " + TrimGUI.titleCase(patternKey))
                .color(NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, false));

        populate();
    }

    private void populate() {
        ItemStack filler = ItemUtils.buildFiller(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 54; i++) inventory.setItem(i, filler);

        // One button per material — laid out left-to-right starting at slot 10
        int slot = 10;
        for (String mat : TrimGUI.ALL_MATERIALS) {
            Material icon = MATERIAL_ICONS.getOrDefault(mat, Material.IRON_INGOT);
            boolean selected = mat.equals(selectedMaterial);

            ItemStack btn = buildMaterialButton(mat, icon, selected);
            inventory.setItem(slot, btn);
            slotToMaterial.put(slot, mat);
            slot++;
            if (slot == 17) slot = 19; // skip border
            if (slot == 26) slot = 28; // skip border
        }

        // BACK button (slot 45)
        ItemStack back = new ItemStack(Material.ARROW);
        var bm = back.getItemMeta();
        bm.displayName(Component.text("« Back to Patterns").color(NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false));
        back.setItemMeta(bm);
        inventory.setItem(45, back);

        // CONFIRM button (slot 49) — shows the currently selected material
        inventory.setItem(49, ItemUtils.buildGlow(Material.LIME_DYE,
            "§a✔ Confirm & Save",
            NamedTextColor.GREEN,
            "§7Apply §d" + TrimGUI.titleCase(patternKey) + " §7[§f" + TrimGUI.titleCase(selectedMaterial) + "§7]",
            "§7to your §e" + TrimGUI.titleCase(armorSlot.name())));
    }

    private ItemStack buildMaterialButton(String matKey, Material icon, boolean selected) {
        ItemStack item = new ItemStack(icon);
        var meta = item.getItemMeta();

        String display = (selected ? "§a✔ " : "§f") + TrimGUI.titleCase(matKey);
        meta.displayName(Component.text(display).decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("§7Trim material: §f" + TrimGUI.titleCase(matKey))
            .decoration(TextDecoration.ITALIC, false));
        if (selected) lore.add(Component.text("§a● Selected").decoration(TextDecoration.ITALIC, false));
        else lore.add(Component.text("§eClick to select").decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Handle a click at a given inventory slot. Returns true if handled. */
    public boolean handleClick(int slot) {
        // BACK
        if (slot == 45) {
            SoundUtils.playGuiClick(player);
            TrimGUI.open(plugin, player, armorSlot, arenaId);
            return true;
        }

        // CONFIRM — save the currently selectedMaterial, apply to Kit Editor slot, and reopen kit editor
        if (slot == 49) {
            if (!TrimManager.canUsePattern(player, patternKey)) {
                player.sendMessage("§c[Trim] You don't have permission to use the §e"
                    + TrimGUI.titleCase(patternKey) + " §ctrim.");
                return true;
            }
            plugin.getTrimManager().setTrim(
                player.getUniqueId(), arenaId, armorSlot, patternKey, selectedMaterial);

            // Apply trim to the player's personal kit contents (DB save)
            applyTrimToKitEditor();

            SoundUtils.playSave(player);
            player.sendMessage("§a✔ Trim §d" + TrimGUI.titleCase(patternKey)
                + " §a[§f" + TrimGUI.titleCase(selectedMaterial) + "§a] applied to §e"
                + TrimGUI.titleCase(armorSlot.name()) + "§a!");

            // Reopen the kit editor so the armor piece immediately shows the trim
            if (arenaId != null) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    com.duel.gui.PlayerKitEditorGUI.open(plugin, player, arenaId));
            } else {
                player.closeInventory();
            }
            return true;
        }

        // Material slot — update highlight locally, reopen to refresh, do NOT save to DB
        String mat = slotToMaterial.get(slot);
        if (mat != null) {
            SoundUtils.playGuiClick(player);
            // Reopen to refresh highlights — pass the newly selected material as override
            open(plugin, player, armorSlot, arenaId, patternKey, mat);
            return true;
        }
        return false;
    }

    // ─── Apply trim to Kit Editor ────────────────────────────────────────

    /**
     * Applies the selected trim to the player's saved personal kit for the current arena,
     * so the change persists immediately without needing to reopen the editor.
     */
    private void applyTrimToKitEditor() {
        // The player is in PlayerKitEditorGUI — update their personal kit contents
        if (arenaId == null) return;

        // kit slot index for each armor piece (matches PlayerKitEditorGUI.GUI_TO_KIT)
        int kitSlot = switch (armorSlot) {
            case HELMET     -> 39;
            case CHESTPLATE -> 38;
            case LEGGINGS   -> 37;
            case BOOTS      -> 36;
        };

        com.duel.managers.PlayerKitManager pkm = plugin.getPlayerKitManager();
        org.bukkit.inventory.ItemStack[] contents = pkm.getPlayerKit(player.getUniqueId(), arenaId);

        // If the player has no saved personal kit yet, fall back to arena default
        if (contents == null) {
            com.duel.models.Arena arena = plugin.getArenaManager().getArena(arenaId);
            if (arena != null) {
                com.duel.models.Kit kit = plugin.getKitManager().getKit(
                        arena.getKitName() != null ? arena.getKitName() : arenaId);
                if (kit != null) {
                    contents = kit.getContents().clone();
                }
            }
        }
        if (contents == null) return;

        org.bukkit.inventory.ItemStack current = contents[kitSlot];
        if (current == null || current.getType() == org.bukkit.Material.AIR) return;

        TrimManager.TrimEntry entry = new TrimManager.TrimEntry(patternKey, selectedMaterial);
        contents[kitSlot] = TrimManager.applyTrimToItem(current.clone(), entry);
        pkm.savePlayerKit(player.getUniqueId(), arenaId, contents);
    }

    // ─── Static open helpers ──────────────────────────────────────────────

    public static void open(DuelPlugin plugin, Player player,
                            TrimManager.ArmorSlot armorSlot, String arenaId, String patternKey) {
        MaterialSelectorGUI gui = new MaterialSelectorGUI(plugin, player, armorSlot, arenaId, patternKey);
        OPEN_INSTANCES.put(player.getUniqueId(), gui);
        player.openInventory(gui.getInventory());
    }

    /**
     * Open with an explicit pre-selected material (used when refreshing after a click
     * so the newly highlighted button survives the GUI recreation without double-populate).
     */
    public static void open(DuelPlugin plugin, Player player,
                            TrimManager.ArmorSlot armorSlot, String arenaId, String patternKey,
                            String preSelectedMaterial) {
        MaterialSelectorGUI gui = new MaterialSelectorGUI(
                plugin, player, armorSlot, arenaId, patternKey, preSelectedMaterial);
        OPEN_INSTANCES.put(player.getUniqueId(), gui);
        player.openInventory(gui.getInventory());
    }

    @Override public Inventory getInventory()    { return inventory; }
    public TrimManager.ArmorSlot getArmorSlot()  { return armorSlot; }
    public String getArenaId()                   { return arenaId; }
    public String getPatternKey()                { return patternKey; }
}
