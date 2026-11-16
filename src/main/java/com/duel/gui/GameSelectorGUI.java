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

public class GameSelectorGUI implements InventoryHolder {

    public static final String TITLE = "§8⚔ §bDuel Arena §8— §7Choose your game";

    /** The empty slot in the centre of the blue middle row. */
    public static final int MIDDLE_GAP = 22;

    /** خانة سهم الأرينا العشوائية (الصف العلوي، النص) — كيو + Party FFA */
    public static final int RANDOM_SLOT = 4;

    /** خانة زر "Leave all queues" (الصف الأخير، النص) */
    public static final int LEAVE_SLOT = 40;

    private final DuelPlugin plugin;
    private final Inventory inventory;
    private final Player viewer;
    /** true = قائمة الكيو العادية (تعرض حالة الكيو وتبقى مفتوحة). false = وضع الكيت/FFA */
    private final boolean queueMode;

    // slot → Arena (so click handler doesn't rely on index order)
    private final Map<Integer, Arena> slotArenaMap = new HashMap<>();

    public GameSelectorGUI(DuelPlugin plugin, Player viewer, boolean queueMode) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.queueMode = queueMode;
        this.inventory = Bukkit.createInventory(this, 45, Component.text("⚔ Duel Arena — Choose your game")
                .color(NamedTextColor.DARK_AQUA)
                .decoration(TextDecoration.BOLD, false));
        populate();
    }

    public boolean isQueueMode() { return queueMode; }

    /** يعيد بناء المحتوى في نفس الـ inventory (بدون إغلاق/فتح) */
    public void refresh() {
        inventory.clear();
        populate();
    }

    /** يحدّث كل قوائم الكيو المفتوحة عند اللاعبين (Waiting / حالة الكيو الخاصة بكل لاعب) */
    public static void refreshAll(DuelPlugin plugin) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder();
            if (h instanceof GameSelectorGUI gui && gui.queueMode) gui.refresh();
        }
    }

    private void populate() {
        slotArenaMap.clear();
        // 5 rows (45 slots): blue glass border + blue middle row with an empty centre
        ItemStack border = ItemUtils.buildFiller(Material.BLUE_STAINED_GLASS_PANE);
        for (int i = 0; i < 9; i++)   inventory.setItem(i, border);          // top row
        for (int i = 36; i < 45; i++) inventory.setItem(i, border);          // bottom row
        for (int i = 9; i < 36; i += 9) {                                     // left / right columns
            inventory.setItem(i, border);
            inventory.setItem(i + 8, border);
        }
        for (int i = 18; i < 27; i++) {                                       // middle row
            if (i != MIDDLE_GAP) inventory.setItem(i, border);
        }

        // سهم الأرينا العشوائية: في قائمة الكيو وفي قائمة Party FFA (مو في وضع الكيت)
        boolean ffaMode = com.duel.listeners.InventoryListener.isFfaMode(viewer.getUniqueId());
        if (queueMode || ffaMode) {
            inventory.setItem(RANDOM_SLOT, buildRandomItem(ffaMode));
        }

        Collection<Arena> arenas = plugin.getArenaManager().getAllArenas();
        int[] slots = getInteriorSlots();
        Set<Integer> valid = new HashSet<>();
        for (int sl : slots) valid.add(sl);

        // 1) الأرينات اللي لها menu_slot صالح تاخذ مكانها المحدد
        Map<Arena, Integer> placement = new LinkedHashMap<>();
        Set<Integer> used = new HashSet<>();
        for (Arena arena : arenas) {
            int ms = arena.getMenuSlot();
            if (valid.contains(ms) && used.add(ms)) placement.put(arena, ms);
        }
        // 2) الباقي يتوزع تلقائياً على أول خانات فاضية
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

            // Use first GUI item if available, otherwise fallback to DIAMOND_SWORD
            ItemStack display;
            List<ItemStack> items = arena.getGuiItems();
            if (items != null && !items.isEmpty()) {
                display = items.get(0).clone();
            } else {
                display = new ItemStack(Material.DIAMOND_SWORD);
            }

            ItemMeta meta = display.getItemMeta();
            if (meta == null) meta = Bukkit.getItemFactory().getItemMeta(display.getType());

            // Title = arena name
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
            if (queueMode && plugin.getQueueManager().isQueuedFor(viewer, arena)) {
                // يظهر للاعب نفسه فقط (الـ GUI لكل لاعب نسخة مستقلة)
                lore.add(Component.text("✔ You are in this queue").color(NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("▶ Click to leave").color(NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false));
                meta.setEnchantmentGlintOverride(true);
            } else {
                lore.add(Component.text("▶ Click to queue").color(NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);

            display.setItemMeta(meta);
            inventory.setItem(guiSlot, display);
            slotArenaMap.put(guiSlot, arena);
        }

        // زر الخروج من كل الكيوهات (يظهر فقط لو اللاعب في كيو)
        if (queueMode && plugin.getQueueManager().isQueued(viewer)) {
            ItemStack leave = new ItemStack(Material.RED_BED);
            ItemMeta lm = leave.getItemMeta();
            lm.displayName(Component.text("✖ Leave all queues").color(NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, false));
            lm.lore(List.of(Component.text("Queued in: ").color(NamedTextColor.GRAY)
                    .append(Component.text(String.join(", ", plugin.getQueueManager().getQueuedArenas(viewer)))
                            .color(NamedTextColor.YELLOW))
                    .decoration(TextDecoration.ITALIC, false)));
            leave.setItemMeta(lm);
            inventory.setItem(LEAVE_SLOT, leave);
        }

        // If no arenas yet, show placeholder
        if (arenas.isEmpty()) {
            ItemStack ph = ItemUtils.build(Material.BARRIER, "No Arenas Configured",
                    NamedTextColor.RED, "Use /GUI create to add arenas");
            inventory.setItem(22, ph);
        }
    }

    private ItemStack buildRandomItem(boolean ffaMode) {
        List<Component> extra = new ArrayList<>();
        if (queueMode && !ffaMode) {
            extra.add(Component.text("Waiting: ").color(NamedTextColor.GRAY)
                    .append(Component.text(String.valueOf(plugin.getQueueManager().getRandomQueueSize()))
                            .color(NamedTextColor.YELLOW))
                    .decoration(TextDecoration.ITALIC, false));
            extra.add(Component.empty());
            if (plugin.getQueueManager().isQueuedRandom(viewer)) {
                extra.add(com.duel.utils.RandomArena.line("✔ You are in this queue", NamedTextColor.GREEN));
                extra.add(com.duel.utils.RandomArena.line("▶ Click to leave", NamedTextColor.RED));
                return com.duel.utils.RandomArena.arrowItem(true, extra);
            }
            extra.add(com.duel.utils.RandomArena.line("▶ Click to queue", NamedTextColor.GREEN));
            return com.duel.utils.RandomArena.arrowItem(false, extra);
        }
        extra.add(com.duel.utils.RandomArena.line("▶ Click to pick a random arena", NamedTextColor.GREEN));
        return com.duel.utils.RandomArena.arrowItem(false, extra);
    }

    /** Returns the Arena mapped to this GUI slot, or null if none. */
    public Arena getArenaAt(int slot) {
        return slotArenaMap.get(slot);
    }

    public static int[] getInteriorSlots() {
        // Row 2: 10-16 | Row 3: only the centre gap 22 | Row 4: 28-34
        List<Integer> slots = new ArrayList<>();
        for (int col = 1; col <= 7; col++) slots.add(9 + col);
        slots.add(MIDDLE_GAP);
        for (int col = 1; col <= 7; col++) slots.add(27 + col);
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    /** وضع عادي (كيت / FFA) — بدون حالة كيو */
    public static void open(DuelPlugin plugin, Player player) {
        open(plugin, player, false);
    }

    public static void open(DuelPlugin plugin, Player player, boolean queueMode) {
        GameSelectorGUI gui = new GameSelectorGUI(plugin, player, queueMode);
        player.openInventory(gui.getInventory());
    }

    @Override
    public Inventory getInventory() { return inventory; }
}
