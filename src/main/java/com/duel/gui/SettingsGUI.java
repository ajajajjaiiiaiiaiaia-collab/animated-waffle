package com.duel.gui;

import com.duel.DuelPlugin;
import com.duel.managers.SettingsManager.Setting;
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

import java.util.ArrayList;
import java.util.List;

public class SettingsGUI implements InventoryHolder {

    public static final int SLOT_HIDE_CHAT           = 20;
    public static final int SLOT_HIDE_SCOREBOARD     = 22;
    public static final int SLOT_HIDE_PLAYERS        = 24;
    public static final int SLOT_BLOCK_DUEL_REQUESTS = 31;
    public static final int SLOT_MUTE_SOUNDS           = 33;
    public static final int SLOT_HIDE_ACTIONBAR          = 40;
    public static final int SLOT_HIDE_DEATH_MESSAGES    = 29;
    public static final int SLOT_HIDE_JOIN_QUIT          = 38;
    public static final int SLOT_AURA_GG                 = 42;

    private final DuelPlugin plugin;
    private final Inventory  inventory;
    private final Player     player;

    public SettingsGUI(DuelPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.inventory = Bukkit.createInventory(this, 54,
                Component.text("⚙  Player Settings")
                        .color(NamedTextColor.DARK_AQUA)
                        .decoration(TextDecoration.BOLD, true));
        populate();
    }

    private void populate() {
        // Gray-glass border covering all 54 slots
        ItemStack border = ItemUtils.buildFiller(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 54; i++) inventory.setItem(i, border);

        boolean hideChat       = plugin.getSettingsManager().isEnabled(player.getUniqueId(), Setting.HIDE_CHAT);
        boolean hideScoreboard = plugin.getSettingsManager().isEnabled(player.getUniqueId(), Setting.HIDE_SCOREBOARD);
        boolean hidePlayers    = plugin.getSettingsManager().isEnabled(player.getUniqueId(), Setting.HIDE_PLAYERS);
        boolean blockRequests  = plugin.getSettingsManager().isEnabled(player.getUniqueId(), Setting.BLOCK_DUEL_REQUESTS);
        boolean muteSounds      = plugin.getSettingsManager().isEnabled(player.getUniqueId(), Setting.MUTE_SOUNDS);
        boolean hideActionbar      = plugin.getSettingsManager().isEnabled(player.getUniqueId(), Setting.HIDE_ACTIONBAR);
        boolean hideDeathMessages  = plugin.getSettingsManager().isEnabled(player.getUniqueId(), Setting.HIDE_DEATH_MESSAGES);
        boolean hideJoinQuit       = plugin.getSettingsManager().isEnabled(player.getUniqueId(), Setting.HIDE_JOIN_QUIT_MESSAGES);
        boolean auraGg             = plugin.getSettingsManager().isEnabled(player.getUniqueId(), Setting.AURA_GG);

        inventory.setItem(SLOT_HIDE_CHAT, buildToggle(
                Material.OAK_SIGN,
                "💬  Hide Chat Messages",
                "Hides all player chat messages from your screen.",
                hideChat));

        inventory.setItem(SLOT_HIDE_SCOREBOARD, buildToggle(
                Material.PAINTING,
                "📋  Hide Scoreboard",
                "Removes the scoreboard from your screen.",
                hideScoreboard));

        inventory.setItem(SLOT_HIDE_PLAYERS, buildToggle(
                Material.PLAYER_HEAD,
                "👥  Hide All Players",
                "Makes all other players invisible to you.",
                hidePlayers));

        inventory.setItem(SLOT_BLOCK_DUEL_REQUESTS, buildToggle(
                Material.SHIELD,
                "⛔  Block Duel Requests",
                "Prevents other players from challenging you to a duel.",
                blockRequests));

        inventory.setItem(SLOT_MUTE_SOUNDS, buildToggle(
                Material.NOTE_BLOCK,
                "🔇  Mute All Duel Sounds",
                "Disables all duel-related sounds for you.",
                muteSounds));

        inventory.setItem(SLOT_HIDE_ACTIONBAR, buildToggle(
                Material.CLOCK,
                "📵  Hide Queue Actionbar",
                "Hides the queue status bar shown above your hotbar.",
                hideActionbar));

        inventory.setItem(SLOT_HIDE_DEATH_MESSAGES, buildToggle(
                Material.SKELETON_SKULL,
                "💀  Hide Death Messages",
                "Hides all player death messages from your chat.",
                hideDeathMessages));

        inventory.setItem(SLOT_HIDE_JOIN_QUIT, buildToggle(
                Material.OAK_DOOR,
                "🚪  Hide Join/Quit Messages",
                "Hides player join and quit messages from your chat.",
                hideJoinQuit));

        inventory.setItem(SLOT_AURA_GG, buildToggle(
                Material.FIREWORK_ROCKET,
                "✨  Aura gg",
                "Automatically says \"gg\" in chat when you win a duel.",
                auraGg));
    }

    private ItemStack buildToggle(Material mat, String name, String description, boolean enabled) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        // مفعّل = الأيتم يلمع
        meta.setEnchantmentGlintOverride(enabled);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);

        meta.displayName(Component.text(name)
                .color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.text(description)
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Status: ")
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(enabled
                        ? Component.text("✔ Enabled").color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)
                        : Component.text("✘ Disabled").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)));
        lore.add(Component.empty());
        lore.add(Component.text("▶  Click to toggle")
                .color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);

        item.setItemMeta(meta);
        return item;
    }

    public static void open(DuelPlugin plugin, Player player) {
        if (SettingsDialog.open(plugin, player)) return;
        SettingsGUI gui = new SettingsGUI(plugin, player);
        player.openInventory(gui.getInventory());
    }

    public Player getPlayer() { return player; }

    @Override
    public Inventory getInventory() { return inventory; }
}
