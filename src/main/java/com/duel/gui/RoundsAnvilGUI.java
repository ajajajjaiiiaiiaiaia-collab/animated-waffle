package com.duel.gui;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rounds picker — a real Anvil GUI opened from the Book & Quill in {@link DuelConfirmGUI}.
 *
 *   [0] Paper  [2] Green glass (result)
 *   Only ONE input item is used (slot 1 stays empty): with two different items the anvil
 *   switches to "combine" mode, finds nothing to merge and shows the red X as soon as the
 *   player types. With a single item it is a plain rename, so the result always appears.
 *   The paper is named " " (a single space), so the text field looks empty and the player just types the number.
 *   The title is blank.
 *   The player types the number of rounds (max 99) and clicks the green glass
 *   to go back to the Duel confirm GUI with that number applied.
 *
 * Click / close handling lives in {@link com.duel.listeners.RoundsAnvilListener}.
 */
public final class RoundsAnvilGUI {

    public static final int MAX_ROUNDS = 99;

    public static final int SLOT_PAPER  = 0;
    public static final int SLOT_RESULT = 2;

    /** Open anvil sessions, keyed by the sender. */
    public static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    public static final class Session {
        public final Player target;
        public final Arena  arena;
        public final int    rounds;      // value before opening (kept if the player types nothing)
        public final String mapWorld;    // selected map, carried through (nullable)
        public boolean      done;        // true once the green glass was clicked

        Session(Player target, Arena arena, int rounds, String mapWorld) {
            this.target = target;
            this.arena  = arena;
            this.rounds = rounds;
            this.mapWorld = mapWorld;
        }
    }

    private RoundsAnvilGUI() {}

    public static void open(DuelPlugin plugin, Player sender, Player target, Arena arena, int currentRounds, String mapWorld) {
        AnvilView view = MenuType.ANVIL.builder()
                .title(Component.text(" "))
                .location(sender.getLocation())
                .checkReachable(false)
                .build(sender);
        if (view == null) return;

        SESSIONS.put(sender.getUniqueId(), new Session(target, arena, currentRounds, mapWorld));
        view.setItem(SLOT_PAPER, dot(Material.PAPER));
        sender.openInventory(view);
    }

    /** The green glass shown in the result slot. */
    public static ItemStack buildResult() {
        return dot(Material.LIME_STAINED_GLASS_PANE);
    }

    private static ItemStack dot(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Reads the typed text: keeps only the digits (so the space that is already in the field
     * doesn't have to be deleted), clamps to 1..99. No digits → {@code fallback}.
     */
    public static int parseRounds(String text, int fallback) {
        if (text == null) return fallback;
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return fallback;
        if (digits.length() > 2) return MAX_ROUNDS;
        int n = Integer.parseInt(digits);
        return Math.max(1, Math.min(MAX_ROUNDS, n));
    }
}
