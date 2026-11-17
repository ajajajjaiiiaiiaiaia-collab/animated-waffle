package com.duel.tier;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** قائمة التيرات: كل أرينا = تيرك + التقييم + حالة التحدي/الدفاع. الكليك ملغي (عرض فقط). */
public class TierGUI implements InventoryHolder {

    private static final int[] SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};

    private final Inventory inv;

    private TierGUI(Component title) {
        this.inv = Bukkit.createInventory(this, 54, title);
    }

    @Override public Inventory getInventory() { return inv; }

    private static Component txt(String legacy) {
        return LegacyComponentSerializer.builder().character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build().deserialize(legacy.replace('&', '§'))
                .decoration(TextDecoration.ITALIC, false);
    }

    public static void open(DuelPlugin plugin, Player player) {
        TierManager tm = plugin.getTierManager();
        TierGUI gui = new TierGUI(txt("&b&l⚔ &f&lYour Tiers &8| &7Season " + tm.getSeason()));

        ItemStack filler = new ItemStack(Material.BLUE_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.displayName(Component.text(" "));
        filler.setItemMeta(fm);
        for (int i = 0; i < 54; i++) gui.inv.setItem(i, filler);

        List<Arena> arenas = new ArrayList<>(plugin.getArenaManager().getAllArenas());
        arenas.sort((a, b) -> a.getId().compareToIgnoreCase(b.getId()));

        int idx = 0;
        for (Arena arena : arenas) {
            if (idx >= SLOTS.length) break;
            TierProfile p = tm.getFresh(player.getUniqueId(), player.getName(), arena.getId());

            ItemStack item = arena.getDisplayItem();
            item = (item == null) ? new ItemStack(Material.DIAMOND_SWORD) : item.clone();
            ItemMeta meta = item.getItemMeta();
            meta.displayName(txt("&b&l" + arena.getId() + " &8» " + p.tier.colored()));

            List<Component> lore = new ArrayList<>();
            lore.add(txt("&7Rating&8: &f" + p.rating + (p.games < tm.placementMatches()
                    ? " &8(&eplacement " + p.games + "/" + tm.placementMatches() + "&8)" : "")));
            lore.add(txt("&7Peak tier&8: " + p.peakTier.colored() + "  &7Season peak&8: &f" + p.seasonPeakRating));
            lore.add(txt("&7W&8/&7L&8: &a" + p.wins + "&8/&c" + p.losses));
            lore.add(Component.empty());

            Tier next = p.tier.next();
            if (p.defensePending) {
                lore.add(txt("&c&l⚠ TIER DEFENSE REQUIRED"));
                lore.add(txt("&7Your rating fell below &f" + tm.threshold(p.tier) + "&7."));
                lore.add(txt("&7Queue ranked — you will face a &f" + p.tier.colored() + " &7player."));
                lore.add(txt("&7Lose and you drop to " + (p.tier.prev() == null ? "&7—" : p.tier.prev().colored())));
            } else if (p.challengePending && next != null) {
                lore.add(txt("&6&l★ RANKUP CHALLENGE READY"));
                lore.add(txt("&7Queue ranked — your next match is a"));
                lore.add(txt("&7best-of-" + tm.challengeRounds() + " for " + next.colored() + "&7."));
            } else if (next != null) {
                long cd = p.challengeCooldown - System.currentTimeMillis();
                int need = tm.threshold(next) - p.rating;
                if (cd > 0 && need <= 0) {
                    lore.add(txt("&7Challenge cooldown&8: &e" + Math.max(1, cd / 60000) + "m"));
                } else {
                    lore.add(txt("&7Next&8: " + next.colored() + " &7at &f" + tm.threshold(next)
                            + " &8(&e" + Math.max(0, need) + " &7more&8)"));
                }
            } else {
                lore.add(txt("&c&lTOP TIER &7— defend it!"));
            }
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
            gui.inv.setItem(SLOTS[idx++], item);
        }

        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta im = info.getItemMeta();
        im.displayName(txt("&e&lHow tiers work"));
        List<Component> il = new ArrayList<>();
        il.add(txt("&7Win ranked queue matches to raise your"));
        il.add(txt("&7hidden rating. Reach the threshold and a"));
        il.add(txt("&6Rankup Challenge &7unlocks. Win it to rank up."));
        il.add(txt("&7Fall too low and you must pass a &cTier Defense&7."));
        il.add(Component.empty());
        il.add(txt("&8/tier top <arena>  /tier hall <arena>"));
        im.lore(il);
        info.setItemMeta(im);
        gui.inv.setItem(49, info);

        player.openInventory(gui.inv);
    }
}
