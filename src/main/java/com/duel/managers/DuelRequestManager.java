package com.duel.managers;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages pending /duel invite requests.
 * A request is: sender chose arena → target gets a GUI to accept/decline.
 * Requests expire after 30 seconds automatically.
 */
public class DuelRequestManager {

    private final DuelPlugin plugin;

    /** Pending invite: target UUID → request data */
    private final Map<UUID, DuelRequest> pendingByTarget = new HashMap<>();

    /** Pending invite: sender UUID → request data (so sender can't send twice) */
    private final Map<UUID, DuelRequest> pendingBySender = new HashMap<>();

    /** Timeout tasks */
    private final Map<UUID, BukkitTask> timeoutTasks = new HashMap<>();

    private static final int TIMEOUT_SECONDS = 30;

    public DuelRequestManager(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    // ─────────────────────────────────────────
    //  Create a new request
    // ─────────────────────────────────────────

    public void createRequest(Player sender, Player target, Arena arena) {
        createRequest(sender, target, arena, 1);
    }

    public void createRequest(Player sender, Player target, Arena arena, int rounds) {
        createRequest(sender, target, arena, rounds, null);
    }

    /** mapWorld = template world chosen by the sender (null = random map of the arena). */
    public void createRequest(Player sender, Player target, Arena arena, int rounds, String mapWorld) {
        // Cancel any old pending request from this sender
        cancelBySender(sender);

        DuelRequest request = new DuelRequest(sender, target, arena, rounds, mapWorld);
        pendingByTarget.put(target.getUniqueId(), request);
        pendingBySender.put(sender.getUniqueId(), request);

        // Auto-expire after TIMEOUT_SECONDS
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            expire(request);
        }, TIMEOUT_SECONDS * 20L);

        timeoutTasks.put(target.getUniqueId(), task);
    }

    // ─────────────────────────────────────────
    //  Query
    // ─────────────────────────────────────────

    public DuelRequest getRequestForTarget(Player target) {
        return pendingByTarget.get(target.getUniqueId());
    }

    public boolean hasPendingRequestAsSender(Player sender) {
        return pendingBySender.containsKey(sender.getUniqueId());
    }

    public boolean hasPendingRequestAsTarget(Player target) {
        return pendingByTarget.containsKey(target.getUniqueId());
    }

    // ─────────────────────────────────────────
    //  Remove / expire
    // ─────────────────────────────────────────

    public void removeRequest(Player target) {
        DuelRequest request = pendingByTarget.remove(target.getUniqueId());
        if (request != null) {
            pendingBySender.remove(request.getSender().getUniqueId());
        }
        cancelTimeoutTask(target.getUniqueId());
    }

    public void cancelBySender(Player sender) {
        DuelRequest request = pendingBySender.remove(sender.getUniqueId());
        if (request != null) {
            pendingByTarget.remove(request.getTarget().getUniqueId());
            cancelTimeoutTask(request.getTarget().getUniqueId());
        }
    }

    /** Cancel every pending request where this player is sender OR target (used when entering queue / duel). */
    public void cancelAllFor(Player player) {
        DuelRequest asSender = pendingBySender.get(player.getUniqueId());
        if (asSender != null) {
            Player t = asSender.getTarget();
            cancelBySender(player);
            if (t.isOnline()) {
                t.closeInventory();
                t.sendMessage(color("&c⚔ The duel request from &e" + player.getName() + " &cwas cancelled."));
            }
        }
        DuelRequest asTarget = pendingByTarget.get(player.getUniqueId());
        if (asTarget != null) {
            Player s = asTarget.getSender();
            removeRequest(player);
            if (s.isOnline()) {
                s.sendMessage(color("&c⚔ Your duel request to &e" + player.getName() + " &cwas cancelled."));
            }
        }
    }

    private void expire(DuelRequest request) {
        Player target = request.getTarget();
        Player sender = request.getSender();

        boolean removed = pendingByTarget.remove(target.getUniqueId(), request);
        pendingBySender.remove(sender.getUniqueId(), request);

        if (removed) {
            // Close the invite GUI if target still has it open
            if (target.isOnline()) {
                target.closeInventory();
                target.sendMessage(color("&c⚔ The duel request from &e" + sender.getName() + " &chas expired."));
            }
            if (sender.isOnline()) {
                sender.sendMessage(color("&c⚔ &e" + target.getName() + " &cdid not respond to your duel request."));
            }
        }
    }

    private void cancelTimeoutTask(UUID targetId) {
        BukkitTask task = timeoutTasks.remove(targetId);
        if (task != null) task.cancel();
    }

    private String color(String s) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }

    // ─────────────────────────────────────────
    //  Inner record
    // ─────────────────────────────────────────

    public static class DuelRequest {
        private final Player sender;
        private final Player target;
        private final Arena arena;
        private final int rounds;
        private final String mapWorld; // null = random

        public DuelRequest(Player sender, Player target, Arena arena) {
            this(sender, target, arena, 1);
        }

        public DuelRequest(Player sender, Player target, Arena arena, int rounds) {
            this(sender, target, arena, rounds, null);
        }

        public DuelRequest(Player sender, Player target, Arena arena, int rounds, String mapWorld) {
            this.sender = sender;
            this.target = target;
            this.arena = arena;
            this.rounds = Math.max(1, rounds);
            this.mapWorld = mapWorld;
        }

        public int getRounds() { return rounds; }
        public String getMapWorld() { return mapWorld; }

        public Player getSender() { return sender; }
        public Player getTarget() { return target; }
        public Arena getArena()   { return arena; }
    }
}
