package com.duel.tier;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Set;

public final class TierIcon {

    private TierIcon() {}

    private static final Map<String, String> SPECIAL = Map.ofEntries(
            Map.entry("crossbow", "minecraft:item/crossbow_standby"),
            Map.entry("compass", "minecraft:item/compass_16"),
            Map.entry("recovery_compass", "minecraft:item/recovery_compass_16"),
            Map.entry("clock", "minecraft:item/clock_00"),
            Map.entry("tnt", "minecraft:blocks|minecraft:block/tnt_side"),
            Map.entry("respawn_anchor", "minecraft:blocks|minecraft:block/respawn_anchor_top"),
            Map.entry("grass_block", "minecraft:blocks|minecraft:block/grass_block_side"),
            Map.entry("hay_block", "minecraft:blocks|minecraft:block/hay_block_side"),
            Map.entry("furnace", "minecraft:blocks|minecraft:block/furnace_front"),
            Map.entry("crafting_table", "minecraft:blocks|minecraft:block/crafting_table_front"),
            Map.entry("anvil", "minecraft:blocks|minecraft:block/anvil"),
            Map.entry("end_crystal", "minecraft:item/end_crystal")
    );

    private static final Set<String> ITEM_TEXTURED_BLOCKS = Set.of(
            "lantern", "soul_lantern", "chain", "bell", "campfire", "soul_campfire",
            "brewing_stand", "cauldron", "hopper", "repeater", "comparator", "flower_pot");

    public static boolean enabled(DuelPlugin plugin) {
        return plugin.getConfig().getBoolean("tier.tab-icon.enabled", true);
    }

    private static String[] resolve(DuelPlugin plugin, Arena arena) {
        if (arena == null || !enabled(plugin)) return null;
        String atlas = plugin.getConfig().getString("tier.tab-icon.atlas", "minecraft:items");
        String sprite;

        String override = plugin.getConfig().getString("tier.tab-icon.overrides." + arena.getId().toLowerCase());
        if (override != null && !override.isBlank()) {
            String o = override.trim();
            int bar = o.indexOf('|');
            if (bar > 0) { atlas = o.substring(0, bar).trim(); sprite = o.substring(bar + 1).trim(); }
            else sprite = o;
        } else {
            ItemStack item = arena.getDisplayItem();
            Material m = (item == null || item.getType().isAir()) ? null : item.getType();
            if (m == null) return null;
            String key = m.getKey().getKey();
            String fix = SPECIAL.get(key);
            if (fix == null && m.isBlock() && !ITEM_TEXTURED_BLOCKS.contains(key)) {
                fix = "minecraft:blocks|minecraft:block/" + key;
            }
            if (fix != null) {
                int bar = fix.indexOf('|');
                if (bar > 0) { atlas = fix.substring(0, bar); sprite = fix.substring(bar + 1); }
                else sprite = fix;
            } else {
                sprite = "minecraft:item/" + key;
            }
        }
        if (sprite == null || sprite.isEmpty()) return null;
        return new String[]{atlas, sprite};
    }

    public static String sprite(DuelPlugin plugin, Arena arena) {
        String[] r = resolve(plugin, arena);
        if (r == null) return "";
        return "<sprite:\"" + r[0] + "\":\"" + r[1] + "\">";
    }

    public static Component component(DuelPlugin plugin, Arena arena) {
        String[] r = resolve(plugin, arena);
        if (r == null) return Component.empty();
        try {
            return Component.object(ObjectContents.sprite(Key.key(r[0]), Key.key(r[1])));
        } catch (Throwable t) {
            return Component.empty();
        }
    }

    /** أيقونة الأرينا + نص جاهز (مثل Unranked). */
    public static String withLabel(DuelPlugin plugin, Arena arena, String label) {
        String c = plugin.getConfig().getString("tier.tab-icon.color", "");
        String text = (c == null || c.isBlank()) ? label : (c + org.bukkit.ChatColor.stripColor(label.replace('&', '§')));
        String s = sprite(plugin, arena);
        return s.isEmpty() ? text : s + " " + text;
    }

    public static String withTier(DuelPlugin plugin, Arena arena, Tier tier) {
        String c = plugin.getConfig().getString("tier.tab-icon.color", "");
        String text = (c == null || c.isBlank()) ? tier.colored() : c + tier.name();
        String s = sprite(plugin, arena);
        return s.isEmpty() ? text : s + " " + text;
    }
}
