package com.duel.gui;

import com.duel.DuelPlugin;
import com.duel.managers.CountryManager;
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
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Confirmation screen shown to the sender AFTER choosing the arena and
 * BEFORE the request is sent to the opponent.
 *
 *   Row 1 :  . . . . [Opponent head] . . . .
 *   Row 2 :  . [Cancel] . [Flag] [Arena] [Map/Grass] . [Continue] .
 *   Row 3 :  . . . . . . [Rounds book under the Arena icon] . . . .
 *   Everything else is gray glass.
 *   Rounds: click the Book & Quill → anvil GUI → type a number (1-99). Default 1.
 */
public class DuelConfirmGUI implements InventoryHolder {

    public static final int SIZE         = 45;
    public static final int SLOT_HEAD    = 13;
    public static final int SLOT_CANCEL  = 19;
    public static final int SLOT_FLAG    = 21;
    public static final int SLOT_ARENA   = 22;
    public static final int SLOT_MAP     = 23;
    public static final int SLOT_CONFIRM = 25;
    public static final int SLOT_ROUNDS  = 31;

    private final DuelPlugin plugin;
    private final Player     sender;
    private final Player     target;
    private final Arena      arena;
    private final int        rounds;
    private final String     mapWorld;   // selected map (template world), null = random (RandomArena.TOKEN = أرينا عشوائية)
    private final boolean    random;     // أرينا عشوائية (السهم): ما نكشف الأرينا ولا الماب
    private final Inventory  inventory;

    public DuelConfirmGUI(DuelPlugin plugin, Player sender, Player target, Arena arena, int rounds, String mapWorld) {
        this.plugin = plugin;
        this.sender = sender;
        this.target = target;
        this.arena  = arena;
        this.rounds = Math.max(1, Math.min(RoundsAnvilGUI.MAX_ROUNDS, rounds));
        this.random = com.duel.utils.RandomArena.is(mapWorld);
        this.mapWorld = this.random ? mapWorld
                : (mapWorld != null && arena.getWorlds().contains(mapWorld)) ? mapWorld : null;
        this.inventory = Bukkit.createInventory(this, SIZE, Component.text("Duel " + target.getName()));
        populate();
    }

    // ─────────────────────────────────────────
    //  Layout
    // ─────────────────────────────────────────

    private void populate() {
        ItemStack filler = ItemUtils.buildFiller(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < SIZE; i++) inventory.setItem(i, filler);

        inventory.setItem(SLOT_HEAD,    buildHead());
        inventory.setItem(SLOT_ARENA,   buildArena());
        inventory.setItem(SLOT_FLAG,    buildFlag());
        inventory.setItem(SLOT_MAP,     buildMap());
        inventory.setItem(SLOT_CANCEL,  buildCancel());
        inventory.setItem(SLOT_CONFIRM, buildConfirm());
        inventory.setItem(SLOT_ROUNDS,  buildRounds());
    }

    private ItemStack buildHead() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(target.getUniqueId()));
        meta.displayName(Component.text("Opponent")
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(target.getName())
                .color(NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildArena() {
        if (random) {
            ItemStack arrow = new ItemStack(Material.SPECTRAL_ARROW);
            ItemMeta am = arrow.getItemMeta();
            am.displayName(Component.text("Arena").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
            am.lore(List.of(Component.text(com.duel.utils.RandomArena.NAME).color(NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false)));
            am.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            arrow.setItemMeta(am);
            return arrow;
        }
        ItemStack item;
        List<ItemStack> guiItems = arena.getGuiItems();
        if (!guiItems.isEmpty()) item = guiItems.get(0).clone();
        else item = new ItemStack(Material.GRASS_BLOCK);

        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Arena")
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(arena.getId())
                .color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Opponent's country flag as a textured player head (no resource pack).
     * Unknown / unsupported countries get a plain white banner.
     */
    private ItemStack buildFlag() {
        CountryManager cm = plugin.getCountryManager();
        String code = cm.getCountryCode(target.getUniqueId());
        String countryName = CountryManager.getCountryName(code);

        // علم كرأس لاعب (بدون ريسورس باك) — لو ما فيه علم للدولة: بانر أبيض
        ItemStack item = plugin.getTabFlagManager().flagSkull(code);
        if (item == null) item = new ItemStack(Material.WHITE_BANNER);

        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Country")
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(countryName)
                .color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Grass block — shows the selected map's name on hover; click to open the map picker. */
    private ItemStack buildMap() {
        ItemStack item = new ItemStack(Material.GRASS_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Map")
                .color(NamedTextColor.GREEN)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        if (random) {
            lore.add(Component.text("Selected  ").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("Random").color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)));
            lore.add(line("Random arena — map is random too", NamedTextColor.DARK_GRAY));
            meta.lore(lore);
            item.setItemMeta(meta);
            return item;
        }
        if (mapWorld != null) {
            lore.add(Component.text("Selected  ").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(arena.getWorldDisplayName(mapWorld)).color(NamedTextColor.AQUA)
                            .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)));
        } else {
            lore.add(Component.text("Selected  ").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("Random").color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)));
        }
        lore.add(line("Click to choose a map", NamedTextColor.GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildConfirm() {
        ItemStack item = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Continue")
                .color(NamedTextColor.GREEN)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Click to send the challenge", NamedTextColor.GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Book & Quill under the Arena icon — shows the chosen rounds, click to change (anvil). */
    private ItemStack buildRounds() {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Rounds")
                .color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Selected  ").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(String.valueOf(rounds)).color(NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)));
        lore.add(line("Click to type the number of rounds (max " + RoundsAnvilGUI.MAX_ROUNDS + ")", NamedTextColor.GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildCancel() {
        ItemStack item = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Cancel")
                .color(NamedTextColor.RED)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Click to cancel the challenge", NamedTextColor.GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }

    // ─────────────────────────────────────────
    //  API
    // ─────────────────────────────────────────

    public static void open(DuelPlugin plugin, Player sender, Player target, Arena arena) {
        open(plugin, sender, target, arena, 1, null);
    }

    public static void open(DuelPlugin plugin, Player sender, Player target, Arena arena, int rounds) {
        open(plugin, sender, target, arena, rounds, null);
    }

    public static void open(DuelPlugin plugin, Player sender, Player target, Arena arena, int rounds, String mapWorld) {
        DuelConfirmGUI gui = new DuelConfirmGUI(plugin, sender, target, arena, rounds, mapWorld);
        sender.openInventory(gui.getInventory());
    }

    public Player getSender() { return sender; }
    public Player getTarget() { return target; }
    public Arena  getArena()  { return arena; }
    public int    getRounds() { return rounds; }
    public String getMapWorld() { return mapWorld; }
    public boolean isRandom()   { return random; }

    @Override
    public Inventory getInventory() { return inventory; }
}
