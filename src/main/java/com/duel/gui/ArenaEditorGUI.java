package com.duel.gui;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import com.duel.utils.ItemUtils;
import com.duel.utils.SoundUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class ArenaEditorGUI implements InventoryHolder {

    // Slots في البوردر السفلي (45-53)
    public static final int BUILD_SLOT       = 45; // بناء yes/no
    public static final int BREAK_MAP_SLOT   = 46; // تكسير بلوكات الماب yes/no
    public static final int BREAK_PLACED_SLOT= 47; // تكسير بلوكات مبنية yes/no
    public static final int DIFFICULTY_SLOT  = 48; // صعوبة easy/normal/hard
    public static final int SAVE_SLOT        = 49; // SAVE
    public static final int SIZE             = 54;

    // State per admin
    public static final java.util.Map<UUID, String>  OPEN_EDITORS   = new java.util.HashMap<>();
    public static final java.util.Map<UUID, Boolean> EDITOR_BUILD   = new java.util.HashMap<>();
    public static final java.util.Map<UUID, Boolean> EDITOR_BREAK   = new java.util.HashMap<>();
    public static final java.util.Map<UUID, Boolean> EDITOR_PLACED  = new java.util.HashMap<>();
    public static final java.util.Map<UUID, String>  EDITOR_DIFF    = new java.util.HashMap<>();

    private final DuelPlugin plugin;
    private final Inventory  inventory;
    private final String     arenaId;

    public ArenaEditorGUI(DuelPlugin plugin, String arenaId) {
        this.plugin   = plugin;
        this.arenaId  = arenaId;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("Arena Editor — " + arenaId)
                        .color(NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, false));
        populate();
    }

    private void populate() {
        // Border
        ItemStack border = ItemUtils.buildFiller(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 45; i < 54; i++) inventory.setItem(i, border);

        // Save
        inventory.setItem(SAVE_SLOT, ItemUtils.buildGlow(Material.LIME_DYE, "✔ SAVE Arena",
                NamedTextColor.GREEN,
                "Click to save all items as the arena display",
                "§7Then you'll be asked to configure worlds & spawns"));

        // Load existing arena settings
        Arena existing = plugin.getArenaManager().getArena(arenaId);
        boolean build   = existing != null && existing.isAllowBuilding();
        boolean breakM  = existing != null && existing.isAllowBreaking();
        boolean breakP  = existing != null && existing.isAllowBreakPlaced();
        String  diff    = existing != null ? existing.getDifficulty() : "normal";

        inventory.setItem(BUILD_SLOT,        buildToggle("§e🔨 بناء",        build,  Material.OAK_PLANKS));
        inventory.setItem(BREAK_MAP_SLOT,    buildToggle("§c⛏ تكسير الماب",  breakM, Material.STONE));
        inventory.setItem(BREAK_PLACED_SLOT, buildToggle("§b⛏ تكسير المبني", breakP, Material.DIRT));
        inventory.setItem(DIFFICULTY_SLOT,   buildDifficulty(diff));

        // Load GUI items
        if (existing != null && !existing.getGuiItems().isEmpty()) {
            int slot = 0;
            for (ItemStack item : existing.getGuiItems()) {
                if (slot >= 45) break;
                inventory.setItem(slot, item);
                slot++;
            }
        }
    }

    // ── زر Yes/No ────────────────────────────────────────────────────────
    public static ItemStack buildToggle(String label, boolean enabled, Material mat) {
        Material m = enabled ? Material.LIME_CONCRETE : Material.RED_CONCRETE;
        String state = enabled ? "§a✔ YES" : "§c✘ NO";

        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(ChatColor.stripColor(label))
                .color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(state).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("§7اضغط للتغيير").decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    // ── زر الصعوبة ────────────────────────────────────────────────────────
    public static ItemStack buildDifficulty(String diff) {
        Material mat = switch (diff) {
            case "easy"  -> Material.GREEN_WOOL;
            case "hard"  -> Material.RED_WOOL;
            default      -> Material.YELLOW_WOOL;
        };
        String label = switch (diff) {
            case "easy"  -> "§a⚡ Easy";
            case "hard"  -> "§c☠ Hard";
            default      -> "§e⚔ Normal";
        };

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("الصعوبة: " + ChatColor.stripColor(label))
                .color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(label).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("§7اضغط للتغيير").decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    public static String nextDifficulty(String current) {
        return switch (current) {
            case "easy"   -> "normal";
            case "normal" -> "hard";
            default       -> "easy";
        };
    }

    // ── Save ─────────────────────────────────────────────────────────────
    public static void onSave(DuelPlugin plugin, Player admin, Inventory inv, String arenaId) {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != org.bukkit.Material.AIR)
                items.add(item.clone());
        }

        Arena arena = plugin.getArenaManager().arenaExists(arenaId)
                ? plugin.getArenaManager().getArena(arenaId)
                : plugin.getArenaManager().createArena(arenaId);

        arena.getGuiItems().clear();
        arena.getGuiItems().addAll(items);
        arena.setKitName(arenaId);

        arena.setAllowBuilding(EDITOR_BUILD.getOrDefault(admin.getUniqueId(),   false));
        arena.setAllowBreaking(EDITOR_BREAK.getOrDefault(admin.getUniqueId(),   false));
        arena.setAllowBreakPlaced(EDITOR_PLACED.getOrDefault(admin.getUniqueId(), false));
        arena.setDifficulty(EDITOR_DIFF.getOrDefault(admin.getUniqueId(), "normal"));

        admin.closeInventory();
        SoundUtils.playSave(admin);
        admin.sendMessage(color("&a✔ Items saved for arena &e" + arenaId));
        admin.sendMessage(color("&7How many worlds does this arena have? &f(type in chat)"));

        plugin.getConversationManager().startWorldSetup(admin, arena);
    }

    public static void open(DuelPlugin plugin, Player admin, String arenaId) {
        Arena existing = plugin.getArenaManager().getArena(arenaId);
        OPEN_EDITORS.put(admin.getUniqueId(), arenaId);
        EDITOR_BUILD.put(admin.getUniqueId(),  existing != null && existing.isAllowBuilding());
        EDITOR_BREAK.put(admin.getUniqueId(),  existing != null && existing.isAllowBreaking());
        EDITOR_PLACED.put(admin.getUniqueId(), existing != null && existing.isAllowBreakPlaced());
        EDITOR_DIFF.put(admin.getUniqueId(),   existing != null ? existing.getDifficulty() : "normal");

        ArenaEditorGUI gui = new ArenaEditorGUI(plugin, arenaId);
        admin.openInventory(gui.getInventory());
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    @Override public Inventory getInventory() { return inventory; }
    public String getArenaId()                { return arenaId; }
}
