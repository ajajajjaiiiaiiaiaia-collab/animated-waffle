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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /duel <player> — نفس شكل GameSelectorGUI (Duel Menu) بالضبط:
 * 45 خانة، إطار زجاج أزرق، صف أوسط مع خانة فاضية، ونفس توزيع الأرينات (menu_slot).
 * الفرق الوحيد: الكليك يرسل تحدي للاعب المحدد بدل الدخول في الكيو.
 */
public class DuelArenaPickerGUI implements InventoryHolder {

    public static final Map<UUID, UUID> PICKER_TARGETS = new ConcurrentHashMap<>();

    /** خانة راس اللاعب المتحدّى (الصف العلوي، النص) — الكليك عليها ما يسوي شيء */
    public static final int TARGET_SLOT = 4;

    /** خانة سهم الأرينا العشوائية (الصف الأخير، النص) */
    public static final int RANDOM_SLOT = 40;

    private final DuelPlugin plugin;
    private final Inventory  inventory;
    private final Player     target;

    // slot → Arena (نفس أسلوب GameSelectorGUI)
    private final Map<Integer, Arena> slotArenaMap = new HashMap<>();

    public DuelArenaPickerGUI(DuelPlugin plugin, Player target) {
        this.plugin = plugin;
        this.target = target;
        this.inventory = Bukkit.createInventory(this, 45,
                Component.text("⚔ Duel Arena — Challenge " + target.getName())
                        .color(NamedTextColor.DARK_AQUA)
                        .decoration(TextDecoration.BOLD, false));
        populate();
    }

    private void populate() {
        // نفس إطار GameSelectorGUI: زجاج أزرق + صف أوسط بخانة فاضية في النص
        ItemStack border = ItemUtils.buildFiller(Material.BLUE_STAINED_GLASS_PANE);
        for (int i = 0; i < 9; i++)   inventory.setItem(i, border);
        for (int i = 36; i < 45; i++) inventory.setItem(i, border);
        for (int i = 9; i < 36; i += 9) {
            inventory.setItem(i, border);
            inventory.setItem(i + 8, border);
        }
        for (int i = 18; i < 27; i++) {
            if (i != GameSelectorGUI.MIDDLE_GAP) inventory.setItem(i, border);
        }

        inventory.setItem(TARGET_SLOT, buildTargetInfoItem());
        inventory.setItem(RANDOM_SLOT, com.duel.utils.RandomArena.arrowItem(false, List.of(
                com.duel.utils.RandomArena.line("▶ Click to challenge " + target.getName(), NamedTextColor.GREEN))));

        Collection<Arena> arenas = plugin.getArenaManager().getAllArenas();
        int[] slots = GameSelectorGUI.getInteriorSlots();
        Set<Integer> valid = new HashSet<>();
        for (int sl : slots) valid.add(sl);

        // 1) الأرينات اللي لها menu_slot صالح تاخذ مكانها
        Map<Arena, Integer> placement = new LinkedHashMap<>();
        Set<Integer> used = new HashSet<>();
        for (Arena arena : arenas) {
            int ms = arena.getMenuSlot();
            if (valid.contains(ms) && used.add(ms)) placement.put(arena, ms);
        }
        // 2) الباقي تلقائياً على أول خانات فاضية
        int slotIdx = 0;
        for (Arena arena : arenas) {
            if (placement.containsKey(arena)) continue;
            while (slotIdx < slots.length && used.contains(slots[slotIdx])) slotIdx++;
            if (slotIdx >= slots.length) break;
            used.add(slots[slotIdx]);
            placement.put(arena, slots[slotIdx++]);
        }

        for (Map.Entry<Arena, Integer> entry : placement.entrySet()) {
            Arena arena = entry.getKey();
            int guiSlot = entry.getValue();

            ItemStack display;
            List<ItemStack> items = arena.getGuiItems();
            if (items != null && !items.isEmpty()) display = items.get(0).clone();
            else display = new ItemStack(Material.DIAMOND_SWORD);

            ItemMeta meta = display.getItemMeta();
            if (meta == null) meta = Bukkit.getItemFactory().getItemMeta(display.getType());

            meta.displayName(Component.text("⚔ " + arena.getId())
                    .color(NamedTextColor.AQUA)
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false));

            int playing = plugin.getDuelManager().getActiveCountForArena(arena.getId());
            int waiting = plugin.getQueueManager().getQueueSize(arena);

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Playing: ").color(NamedTextColor.GRAY)
                    .append(Component.text(String.valueOf(playing)).color(NamedTextColor.GREEN))
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Waiting: ").color(NamedTextColor.GRAY)
                    .append(Component.text(String.valueOf(waiting)).color(NamedTextColor.YELLOW))
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text("▶ Click to challenge " + target.getName()).color(NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);

            display.setItemMeta(meta);
            inventory.setItem(guiSlot, display);
            slotArenaMap.put(guiSlot, arena);
        }

        if (arenas.isEmpty()) {
            inventory.setItem(GameSelectorGUI.MIDDLE_GAP, ItemUtils.build(Material.BARRIER, "No Arenas Available",
                    NamedTextColor.RED, "Ask an admin to configure arenas."));
        }
    }

    private ItemStack buildTargetInfoItem() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        org.bukkit.inventory.meta.SkullMeta meta = (org.bukkit.inventory.meta.SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(target.getUniqueId()));

        int ping = target.getPing();
        NamedTextColor pingColor = ping < 80 ? NamedTextColor.GREEN : ping < 150 ? NamedTextColor.YELLOW : NamedTextColor.RED;

        meta.displayName(Component.text("🎯  Challenging: " + target.getName())
                .color(NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.text("Player: ").color(NamedTextColor.GRAY)
                .append(Component.text(target.getName()).color(NamedTextColor.WHITE)));
        lore.add(Component.text("Ping: ").color(NamedTextColor.GRAY)
                .append(Component.text(ping + " ms").color(pingColor)));
        lore.add(Component.empty());
        lore.add(Component.text("Select an arena below").color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, true));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Returns the Arena mapped to this GUI slot, or null if none. */
    public Arena getArenaAt(int slot) {
        return slotArenaMap.get(slot);
    }

    public static void open(DuelPlugin plugin, Player sender, Player target) {
        DuelArenaPickerGUI gui = new DuelArenaPickerGUI(plugin, target);
        PICKER_TARGETS.put(sender.getUniqueId(), target.getUniqueId());
        sender.openInventory(gui.getInventory());
    }

    public Player getTarget() { return target; }

    @Override
    public Inventory getInventory() { return inventory; }
}
