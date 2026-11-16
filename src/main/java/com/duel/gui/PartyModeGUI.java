package com.duel.gui;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import com.duel.party.Party;
import com.duel.utils.SoundUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * اختيار نوع المباراة بعد ما القائد يختار الأرينا (Party FFA).
 *
 *  3 صفوف، كله زجاج:
 *   slot 12 : FFA  (الأكس الدايموند) → FFA مباشرة، كل واحد ضد الكل
 *   slot 13 : Map  (بلوك الكراس)     → يفتح مابات الأرينا المختارة، والاختيار يرجع لهنا
 *   slot 14 : Team (الأكس الذهبي)    → يفتح PartyTeamGUI بنفس الفحوصات
 */
public class PartyModeGUI implements InventoryHolder {

    public static final int SIZE      = 27;
    public static final int FFA_SLOT  = 12;
    public static final int MAP_SLOT  = 13;
    public static final int TEAM_SLOT = 14;

    private final DuelPlugin plugin;
    private final Arena arena;
    private final String mapWorld;   // الماب المختارة (null = عشوائي) — أو RandomArena.TOKEN لو الأرينا عشوائية (السهم)
    private final boolean random;    // أرينا عشوائية: ما نكشف الأرينا ولا الماب، ونمنع اختيار الماب
    private final Inventory inventory;

    private PartyModeGUI(DuelPlugin plugin, Arena arena, String mapWorld) {
        this.plugin = plugin;
        this.arena = arena;
        this.random = com.duel.utils.RandomArena.is(mapWorld);
        this.mapWorld = this.random ? mapWorld
                : (mapWorld != null && arena.getWorlds().contains(mapWorld)) ? mapWorld : null;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("⚔ Party — Select Mode").color(NamedTextColor.DARK_AQUA)
                        .decoration(TextDecoration.BOLD, false));

        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        gm.displayName(Component.text(" "));
        glass.setItemMeta(gm);
        for (int i = 0; i < SIZE; i++) inventory.setItem(i, glass);

        inventory.setItem(FFA_SLOT, button(Material.DIAMOND_AXE, "FFA", NamedTextColor.AQUA,
                "Everyone vs everyone", "No teams — starts right away"));
        if (this.random) {
            // السهم مكان الماب: الأرينا والماب عشوائيين وما ينكشفون إلا بعد النقل
            inventory.setItem(MAP_SLOT, com.duel.utils.RandomArena.arrowItem(true, List.of(
                    com.duel.utils.RandomArena.line("Arena and map are random", NamedTextColor.GREEN))));
        } else {
            inventory.setItem(MAP_SLOT, button(Material.GRASS_BLOCK, "Map", NamedTextColor.GREEN,
                    "Selected: " + (this.mapWorld != null ? arena.getWorldDisplayName(this.mapWorld) : "Random"),
                    "Click to choose a map"));
        }
        inventory.setItem(TEAM_SLOT, button(Material.GOLDEN_AXE, "Team", NamedTextColor.GOLD,
                "Arrange two teams", "then the match starts"));
    }

    private static ItemStack button(Material mat, String name, NamedTextColor color, String l1, String l2) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(color)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(l1).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text(l2).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    // ─────────────────────────────────────────
    //  فتح الـ GUI (بعد اختيار الأرينا)
    // ─────────────────────────────────────────

    public static void open(DuelPlugin plugin, Player player, Arena arena) {
        open(plugin, player, arena, null);
    }

    public static void open(DuelPlugin plugin, Player player, Arena arena, String mapWorld) {
        Party party = plugin.getPartyManager().getParty(player);
        if (party == null) { player.sendMessage("§cYou are not in a party."); return; }
        if (!party.getLeader().equals(player)) { player.sendMessage("§c[Party] You must be a party leader to start FFA."); return; }
        if (party.getSize() < 2) { player.sendMessage("§cThe party needs at least two players."); return; }
        if (plugin.getDuelManager().isInDuel(player) || plugin.getPartyDuelManager().isInPartyDuel(player)) {
            player.sendMessage("§cYou can't start a match while in a match.");
            return;
        }
        SoundUtils.playGuiClick(player);
        player.openInventory(new PartyModeGUI(plugin, arena, mapWorld).getInventory());
    }

    // ─────────────────────────────────────────
    //  Click
    // ─────────────────────────────────────────

    public void handleClick(Player player, int slot) {
        if (slot != FFA_SLOT && slot != TEAM_SLOT && slot != MAP_SLOT) return;

        Party party = plugin.getPartyManager().getParty(player);
        if (party == null || !party.getLeader().equals(player)) {
            player.closeInventory();
            player.sendMessage("§c[Party] You must be a party leader to start FFA.");
            return;
        }

        if (slot == MAP_SLOT) {
            if (random) return; // أرينا عشوائية: ما في اختيار ماب
            SoundUtils.playGuiClick(player);
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    MapSelectorGUI.openForParty(plugin, player, arena, mapWorld,
                            chosen -> open(plugin, player, arena, chosen)));
            return;
        }

        if (slot == FFA_SLOT) {
            if (party.getSize() < 2) { player.closeInventory(); player.sendMessage("§cThe party needs at least two players."); return; }
            player.closeInventory();
            SoundUtils.playGuiClick(player);
            // FFA مباشر بدون تيمات (حتى لو القائد حافظ تيمات قبل)
            plugin.getPartyDuelManager().startPartyFFA(party.getOnlineMembers(), arena, mapWorld);
        } else {
            // نفس فحوصات فتح GUI الترتيب (قائد، ما في مباراة، الحد الأدنى والأقصى)
            player.closeInventory();
            PartyTeamGUI.open(plugin, player, arena, mapWorld);
        }
    }

    @Override
    public Inventory getInventory() { return inventory; }
}
