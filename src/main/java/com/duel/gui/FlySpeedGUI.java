package com.duel.gui;

import com.duel.DuelPlugin;
import com.duel.managers.FlySpectatorManager;
import com.duel.utils.SoundUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI التحكم بسرعة طيران المتفرج (3 صفوف):
 *   الصف 1: مؤشر أخضر فوق السرعة المختارة
 *   الصف 2: 7 سرعات جاهزة
 *   الصف 3: زر Reset بالنص
 * السرعة تنلغي تلقائياً أول ما يطلع من وضع التفرج (FlySpectatorManager.exit).
 */
public class FlySpeedGUI implements InventoryHolder {

    public static final int RESET_SLOT = 22;
    private static final int FIRST_SLOT = 10;

    /** mult = المضاعف (1x = سرعة الطيران الطبيعية 0.1). */
    public record Level(double mult, Material icon, NamedTextColor color, String label) {
        public float flySpeed() { return (float) (mult * 0.1); }
        public String text() {
            return (mult == Math.floor(mult) ? String.valueOf((int) mult) : String.valueOf(mult)) + "x";
        }
    }

    public static final Level[] LEVELS = {
            new Level(0.5, Material.CYAN_DYE,    NamedTextColor.AQUA,         "Slow"),
            new Level(1,   Material.LIME_DYE,    NamedTextColor.GREEN,        "Normal"),
            new Level(2,   Material.YELLOW_DYE,  NamedTextColor.YELLOW,       "Fast"),
            new Level(3,   Material.ORANGE_DYE,  NamedTextColor.GOLD,         "Faster"),
            new Level(5,   Material.RED_DYE,     NamedTextColor.RED,          "Turbo"),
            new Level(7,   Material.MAGENTA_DYE, NamedTextColor.LIGHT_PURPLE, "Insane"),
            new Level(10,  Material.PURPLE_DYE,  NamedTextColor.DARK_PURPLE,  "Max"),
    };

    /** index السرعة المطابقة لقيمة flySpeed (أو -1 لو ما تطابق أي مستوى). */
    public static int indexOf(float flySpeed) {
        for (int i = 0; i < LEVELS.length; i++) {
            if (Math.abs(LEVELS[i].flySpeed() - flySpeed) < 0.0005f) return i;
        }
        return -1;
    }

    /** نص السرعة للعرض مثل "2x (Fast)". */
    public static String describe(float flySpeed) {
        int i = indexOf(flySpeed);
        if (i >= 0) return LEVELS[i].text() + " (" + LEVELS[i].label() + ")";
        return String.format("%.1fx", flySpeed / 0.1f);
    }

    private final DuelPlugin plugin;
    private final Player viewer;
    private Inventory inventory;

    public FlySpeedGUI(DuelPlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    public void open() {
        Component title = MiniMessage.miniMessage()
                .deserialize("<gradient:#00E5FF:#7C4DFF><bold>✈ Fly Speed</bold></gradient>")
                .decoration(TextDecoration.ITALIC, false);
        inventory = Bukkit.createInventory(this, 27, title);
        render();
        viewer.openInventory(inventory);
    }

    /** يعيد رسم المحتوى داخل نفس الإنفنتوري (بدون إغلاق/فتح عشان ما يرجّع الماوس). */
    private void render() {
        float current = plugin.getFlySpectatorManager().getSpeed(viewer);
        int sel = indexOf(current);

        ItemStack border = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < 27; i++) inventory.setItem(i, border);

        for (int i = 0; i < LEVELS.length; i++) {
            int slot = FIRST_SLOT + i;
            boolean selected = i == sel;
            inventory.setItem(slot - 9, pane(selected ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE));
            inventory.setItem(slot, levelItem(i, selected));
        }

        inventory.setItem(RESET_SLOT, resetItem(current));
    }

    public void handleClick(Player p, int slot) {
        FlySpectatorManager mgr = plugin.getFlySpectatorManager();
        if (!mgr.isActive(p)) { p.closeInventory(); return; }

        if (slot == RESET_SLOT) {
            if (indexOf(mgr.getSpeed(p)) == indexOf(FlySpectatorManager.DEFAULT_SPEED)) {
                SoundUtils.playGuiClick(p);
                return;
            }
            mgr.resetSpeed(p);
            SoundUtils.playGuiClick(p);
            p.sendActionBar(Component.text("✈ Fly speed reset to ", NamedTextColor.GRAY)
                    .append(Component.text(describe(FlySpectatorManager.DEFAULT_SPEED), NamedTextColor.GREEN)));
            render();
            return;
        }

        int i = slot - FIRST_SLOT;
        if (i < 0 || i >= LEVELS.length) return;
        Level l = LEVELS[i];
        if (indexOf(mgr.getSpeed(p)) == i) { SoundUtils.playGuiClick(p); return; }

        mgr.setSpeed(p, l.flySpeed());
        SoundUtils.playSpeedChange(p, 0.7f + i * 0.2f);
        p.sendActionBar(Component.text("✈ Fly speed: ", NamedTextColor.GRAY)
                .append(Component.text(describe(l.flySpeed()), l.color(), TextDecoration.BOLD)));
        render();
    }

    // ── Items ──────────────────────────────────

    private ItemStack levelItem(int index, boolean selected) {
        Level l = LEVELS[index];
        ItemStack item = new ItemStack(l.icon());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(l.label() + "  " + l.text(), l.color(), TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(line("Speed: ", NamedTextColor.GRAY, l.text(), NamedTextColor.WHITE));
        lore.add(bar(index, l.color()));
        lore.add(Component.empty());
        lore.add(selected
                ? Component.text("✔ Currently selected", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)
                : Component.text("▶ Click to select", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        if (selected) meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack resetItem(float current) {
        ItemStack item = new ItemStack(Material.REDSTONE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("↺ Reset Speed", NamedTextColor.RED, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        boolean already = indexOf(current) == indexOf(FlySpectatorManager.DEFAULT_SPEED);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(line("Current: ", NamedTextColor.GRAY, describe(current), NamedTextColor.WHITE));
        lore.add(line("Default: ", NamedTextColor.GRAY, describe(FlySpectatorManager.DEFAULT_SPEED), NamedTextColor.GREEN));
        lore.add(Component.empty());
        lore.add(already
                ? Component.text("Already at normal speed", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
                : Component.text("▶ Click to reset", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** شريط ▰▰▰▱▱▱▱ يوضح مستوى السرعة. */
    private Component bar(int index, NamedTextColor color) {
        Component c = Component.empty().decoration(TextDecoration.ITALIC, false);
        for (int i = 0; i < LEVELS.length; i++) {
            c = c.append(Component.text(i <= index ? "▰" : "▱", i <= index ? color : NamedTextColor.DARK_GRAY));
        }
        return c;
    }

    private Component line(String a, NamedTextColor ca, String b, NamedTextColor cb) {
        return Component.text(a, ca).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(b, cb));
    }

    private ItemStack pane(Material m) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
