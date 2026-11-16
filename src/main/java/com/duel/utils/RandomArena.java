package com.duel.utils;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * الأرينا العشوائية (السهم).
 * - اللاعب ما يعرف أي أرينا انختارت إلا بعد النقل.
 * - في /duel و البارتي: يمرّ التوكن TOKEN مكان mapWorld عبر كل الـ GUIs والطلبات (الأرينا الحقيقية تنختار بشكل مخفي).
 */
public final class RandomArena {

    private RandomArena() {}

    /** قيمة خاصة تمرّ مكان mapWorld لتدل على "أرينا عشوائية". */
    public static final String TOKEN = "__random_arena__";

    /** الاسم اللي يظهر للخصم / في الـ GUI */
    public static final String NAME = "عشوائية";

    public static boolean is(String mapWorld) {
        return TOKEN.equals(mapWorld);
    }

    /** أرينا عشوائية (لازم يكون لها عالم واحد على الأقل). null لو ما في أي أرينا جاهزة. */
    public static Arena pick(DuelPlugin plugin) {
        List<Arena> ok = new ArrayList<>();
        for (Arena a : plugin.getArenaManager().getAllArenas()) {
            if (a.getRandomWorld() != null) ok.add(a);
        }
        if (ok.isEmpty()) return null;
        return ok.get(ThreadLocalRandom.current().nextInt(ok.size()));
    }

    /** أيتم السهم المستعمل في الـ GUIs الثلاثة. */
    public static ItemStack arrowItem(boolean glow, List<Component> extraLore) {
        ItemStack item = new ItemStack(Material.SPECTRAL_ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("➤").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(" (Random)").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false)));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Random arena & map").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("You will see it after the teleport").color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, true));
        lore.add(Component.empty());
        if (extraLore != null) lore.addAll(extraLore);
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        if (glow) meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    public static Component line(String text, NamedTextColor color) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }
}
