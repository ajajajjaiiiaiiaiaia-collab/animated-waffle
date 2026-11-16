package com.duel.utils;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DeathProtection;
import org.bukkit.EntityEffect;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * يعرض أيقونة الأرينا على الشاشة بأنيميشن توتم (1.21.2+).
 * الأيتم يُرسل للكلاينت فقط (بدون ما يتغير شي في إنفنتوري اللاعب).
 */
public final class TotemPop {

    private TotemPop() {}

    public static void show(DuelPlugin plugin, Player player, Arena arena) {
        if (arena == null) { showRandom(plugin, player); return; }
        ItemStack icon = arena.getDisplayItem();
        icon = (icon == null || icon.getType().isAir())
                ? new ItemStack(Material.MAP)
                : icon.clone();
        play(plugin, player, icon);
    }

    /** أنيميشن التوتم بأيقونة السهم (الأرينا العشوائية) — ما تكشف الأرينا. */
    public static void showRandom(DuelPlugin plugin, Player player) {
        play(plugin, player, new ItemStack(Material.SPECTRAL_ARROW));
    }

    private static void play(DuelPlugin plugin, Player player, ItemStack icon) {
        if (player == null || !player.isOnline()) return;
        icon.setAmount(1);
        icon.setData(DataComponentTypes.DEATH_PROTECTION, DeathProtection.deathProtection().build());

        // أيتم وهمي في اليد الثانية (عند الكلاينت فقط) ثم أنيميشن التوتم
        player.sendEquipmentChange(player, EquipmentSlot.OFF_HAND, icon);
        // مهم: sendEntityEffect يرسل الحدث للاعب نفسه فقط (الصوت + بارتكلز التوتم).
        // player.playEffect() كان يبثّه لكل اللاعبين القريبين.
        player.sendEntityEffect(EntityEffect.TOTEM_RESURRECT, player);

        // رجّع مزامنة الإنفنتوري الحقيقي بعد الأنيميشن
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) player.updateInventory();
        }, 5L);
    }
}
