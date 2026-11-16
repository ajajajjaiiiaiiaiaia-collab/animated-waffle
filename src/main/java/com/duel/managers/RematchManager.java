package com.duel.managers;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import com.duel.utils.SoundUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Manages rematch requests after a duel ends.
 *
 * Flow:
 * 1. Both players get a Rematch item in their hotbar.
 * 2. If both click within TIMEOUT seconds → new duel starts.
 * 3. If one declines or time expires → both go to spawn normally.
 */
public class RematchManager {

    private static final int    TIMEOUT_SECONDS = 5;
    private static final int    REMATCH_SLOT    = 4; // center hotbar

    // playerUUID → arena they want a rematch for
    private final Map<UUID, Arena>               pending     = new HashMap<>();
    // playerUUID → عدد الرواندات في المباراة السابقة (الريماتش يحافظ عليه)
    private final Map<UUID, Integer> roundsByPlayer = new HashMap<>();
    // playerUUID → their opponent UUID
    private final Map<UUID, UUID>                opponents   = new HashMap<>();
    // playerUUID → timeout task id
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> tasks = new HashMap<>();

    private final DuelPlugin plugin;
    private final NamespacedKey REMATCH_KEY;

    public RematchManager(DuelPlugin plugin) {
        this.plugin      = plugin;
        this.REMATCH_KEY = new NamespacedKey(plugin, "rematch_item");
    }

    // ─────────────────────────────────────────
    //  Offer rematch to both players
    // ─────────────────────────────────────────

    public void offer(Player winner, Player loser, Arena arena) {
        offer(winner, loser, arena, 1);
    }

    public void offer(Player winner, Player loser, Arena arena, int rounds) {
        roundsByPlayer.put(winner.getUniqueId(), rounds);
        roundsByPlayer.put(loser.getUniqueId(), rounds);
        opponents.put(winner.getUniqueId(), loser.getUniqueId());
        opponents.put(loser.getUniqueId(), winner.getUniqueId());

        giveRematchItem(winner, arena);
        giveRematchItem(loser,  arena);

        startTimeout(winner, loser);
    }

    // ─────────────────────────────────────────
    //  Player clicked Rematch item
    // ─────────────────────────────────────────

    public void accept(Player player) {
        UUID uid   = player.getUniqueId();
        UUID oppId = opponents.get(uid);
        if (oppId == null) return;

        Arena arena = pending.get(uid);
        if (arena == null) return;
        int rounds = roundsByPlayer.getOrDefault(uid, 1);

        Player opponent = plugin.getServer().getPlayer(oppId);

        accepted.add(uid);

        if (hasAccepted(oppId) && opponent != null && opponent.isOnline()) {
            cancelTimeout(uid);
            cancelTimeout(oppId);
            cleanup(uid);
            cleanup(oppId);

            player.sendMessage(color("&a⚔ Rematch starting!"));
            opponent.sendMessage(color("&a⚔ Rematch starting!"));
            SoundUtils.playQueueFound(player);
            SoundUtils.playQueueFound(opponent);

            removeRematchItem(player);
            removeRematchItem(opponent);

            // Restore both players before starting a new duel
            restorePlayerForDuel(player);
            restorePlayerForDuel(opponent);

            plugin.getDuelManager().startDuel(player, opponent, arena, rounds);
        } else {
            player.sendMessage(color("&a⚔ Rematch request sent! Waiting for &e"
                    + (opponent != null ? opponent.getName() : "opponent") + "&a..."));
            removeRematchItem(player);
        }
    }

    // ─────────────────────────────────────────
    //  Player declined / went to spawn
    // ─────────────────────────────────────────

    public void decline(Player player) {
        UUID uid   = player.getUniqueId();
        UUID oppId = opponents.get(uid);

        cancelTimeout(uid);
        cleanup(uid);
        removeRematchItem(player);

        // Restore the player who declined
        sendToSpawnWithHotbar(player);

        if (oppId != null) {
            cancelTimeout(oppId);
            cleanup(oppId);

            Player opponent = plugin.getServer().getPlayer(oppId);
            if (opponent != null && opponent.isOnline()) {
                removeRematchItem(opponent);
                opponent.sendMessage(color("&c⚔ " + player.getName() + " declined the rematch."));
                // Restore the opponent too
                sendToSpawnWithHotbar(opponent);
            }
        }
    }

    // ─────────────────────────────────────────
    //  Helpers — restore player state
    // ─────────────────────────────────────────

    /**
     * ينظف الإنفنتوري ويرجع SURVIVAL ويعطي الهوتبار.
     * يُستخدم لما اللاعب يرفض الريماتش أو يتجاهله.
     */
    private void sendToSpawnWithHotbar(Player player) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            player.getInventory().clear();
            plugin.getFlySpectatorManager().exit(player);
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            plugin.getDuelManager().clearFlight(player);
            player.teleport(plugin.getSpawnManager().getSpawn());
            plugin.getTabIsolationManager().scheduleResync(player);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    plugin.getPlayerJoinListener().giveDuelSword(player);
                    plugin.getPartyManager().refreshHotbar(player);
                }
            }, 2L);
        }, 2L);
    }

    /**
     * يرجع GameMode SURVIVAL ويعطي الصحة ولكن ما يطير للسبون —
     * يُستخدم قبل startDuel مباشرة.
     */
    private void restorePlayerForDuel(Player player) {
        player.getInventory().clear();
        plugin.getFlySpectatorManager().exit(player);
        player.setGameMode(GameMode.SURVIVAL);
        plugin.getDuelManager().clearFlight(player);
        player.setHealth(player.getAttribute(
                org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setSaturation(20f);
    }

    // ─────────────────────────────────────────
    //  Timeout
    // ─────────────────────────────────────────

    private void startTimeout(Player p1, Player p2) {
        org.bukkit.scheduler.BukkitTask task = plugin.getServer().getScheduler()
                .runTaskLater(plugin, () -> {
                    boolean p1Pending = pending.containsKey(p1.getUniqueId())
                            && !hasAccepted(p1.getUniqueId());
                    boolean p2Pending = pending.containsKey(p2.getUniqueId())
                            && !hasAccepted(p2.getUniqueId());

                    if (p1.isOnline()) {
                        removeRematchItem(p1);
                        if (p1Pending) {
                            p1.sendMessage(color("&7Rematch offer expired."));
                            sendToSpawnWithHotbar(p1);
                        }
                    }
                    if (p2.isOnline()) {
                        removeRematchItem(p2);
                        if (p2Pending) {
                            p2.sendMessage(color("&7Rematch offer expired."));
                            sendToSpawnWithHotbar(p2);
                        }
                    }
                    // If one accepted but not the other
                    if (hasAccepted(p1.getUniqueId()) && !hasAccepted(p2.getUniqueId())) {
                        if (p1.isOnline()) sendToSpawnWithHotbar(p1);
                    }
                    if (hasAccepted(p2.getUniqueId()) && !hasAccepted(p1.getUniqueId())) {
                        if (p2.isOnline()) sendToSpawnWithHotbar(p2);
                    }

                    cleanup(p1.getUniqueId());
                    cleanup(p2.getUniqueId());

                }, TIMEOUT_SECONDS * 20L);

        tasks.put(p1.getUniqueId(), task);
        tasks.put(p2.getUniqueId(), task);
    }

    private final Set<UUID> accepted = new HashSet<>();

    private boolean hasAccepted(UUID uid) {
        return accepted.contains(uid);
    }

    private void cancelTimeout(UUID uid) {
        org.bukkit.scheduler.BukkitTask t = tasks.remove(uid);
        if (t != null) t.cancel();
    }

    private void cleanup(UUID uid) {
        pending.remove(uid);
        roundsByPlayer.remove(uid);
        opponents.remove(uid);
        accepted.remove(uid);
        tasks.remove(uid); // Bug fix: لازم ننظف الـ task وإلا يتعارض مع الريماتش التالي
    }

    // ─────────────────────────────────────────
    //  Rematch item helpers
    // ─────────────────────────────────────────

    private void giveRematchItem(Player player, Arena arena) {
        pending.put(player.getUniqueId(), arena);

        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text("⚔ Rematch")
                .color(NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(List.of(
                Component.text(" ").decoration(TextDecoration.ITALIC, false),
                Component.text("Click to request a rematch!")
                        .color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Both players must click within " + TIMEOUT_SECONDS + "s")
                        .color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));

        meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(REMATCH_KEY, PersistentDataType.STRING, arena.getId());
        item.setItemMeta(meta);

        player.getInventory().setItem(REMATCH_SLOT, item);
        player.updateInventory();
    }

    private void removeRematchItem(Player player) {
        ItemStack item = player.getInventory().getItem(REMATCH_SLOT);
        if (item != null && isRematchItem(item)) {
            player.getInventory().setItem(REMATCH_SLOT, null);
            player.updateInventory();
        }
    }

    public boolean isRematchItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(REMATCH_KEY, PersistentDataType.STRING);
    }

    public boolean isOffering(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    private String color(String s) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }
}
