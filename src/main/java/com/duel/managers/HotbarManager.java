package com.duel.managers;

import com.duel.DuelPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * يقرأ إعدادات الهوتبار العادي من config.yml ويطبقها على اللاعب.
 *
 * config path: hotbar.<key>.slot / material / name / lore
 */
public class HotbarManager {

    public enum HotbarItem {
        DUEL_MENU   ("duel-menu"),
        SETTINGS    ("settings"),
        KIT_EDITOR  ("kit-editor"),
        SPECTATE    ("spectate");

        public final String configKey;
        HotbarItem(String key) { this.configKey = key; }
    }

    private final DuelPlugin plugin;

    public HotbarManager(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    // ────────────────────────────────────────────
    //  Apply full hotbar
    // ────────────────────────────────────────────

    public void applyHotbar(Player player) {
        for (HotbarItem item : HotbarItem.values()) {
            int slot = getSlot(item);
            ItemStack is = buildItem(item);
            if (is != null) player.getInventory().setItem(slot, is);
        }
    }

    // ────────────────────────────────────────────
    //  Getters used by listeners for item detection
    // ────────────────────────────────────────────

    public int getSlot(HotbarItem item) {
        return plugin.getConfig().getInt("hotbar." + item.configKey + ".slot", defaultSlot(item));
    }

    public Material getMaterial(HotbarItem item) {
        String mat = plugin.getConfig().getString("hotbar." + item.configKey + ".material",
                defaultMaterial(item).name());
        try { return Material.valueOf(mat.toUpperCase()); }
        catch (IllegalArgumentException e) { return defaultMaterial(item); }
    }

    public String getName(HotbarItem item) {
        return plugin.getConfig().getString("hotbar." + item.configKey + ".name",
                defaultName(item));
    }

    public List<String> getLore(HotbarItem item) {
        return plugin.getConfig().getStringList("hotbar." + item.configKey + ".lore");
    }

    // ────────────────────────────────────────────
    //  Build ItemStack
    // ────────────────────────────────────────────

    public ItemStack buildItem(HotbarItem item) {
        Material mat = getMaterial(item);
        ItemStack is = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return is;

        // Name
        Component name = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(getName(item))
                .decoration(TextDecoration.ITALIC, false);
        meta.displayName(name);

        // Lore
        List<Component> loreComponents = new ArrayList<>();
        for (String line : getLore(item)) {
            loreComponents.add(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(line)
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (!loreComponents.isEmpty()) meta.lore(loreComponents);

        // Glow
        meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);

        // NBT tag للتعرف على الـ item بدون مقارنة أسماء
        meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "hotbar_item"),
                PersistentDataType.STRING,
                item.configKey
        );

        is.setItemMeta(meta);
        return is;
    }

    // ────────────────────────────────────────────
    //  Defaults
    // ────────────────────────────────────────────

    private int defaultSlot(HotbarItem item) {
        return switch (item) {
            case DUEL_MENU  -> 4;
            case SETTINGS   -> 0;
            case KIT_EDITOR -> 8;
            case SPECTATE   -> 6;
        };
    }

    private Material defaultMaterial(HotbarItem item) {
        return switch (item) {
            case DUEL_MENU  -> Material.IRON_SWORD;
            case SETTINGS   -> Material.CLOCK;
            case KIT_EDITOR -> Material.BOOK;
            case SPECTATE   -> Material.ENDER_EYE;
        };
    }

    private String defaultName(HotbarItem item) {
        return switch (item) {
            case DUEL_MENU  -> "&b⚔ Duel Menu";
            case SETTINGS   -> "&e⚙ Settings";
            case KIT_EDITOR -> "&d✦ Kit Editor";
            case SPECTATE   -> "&3👁 Spectate";
        };
    }
}
