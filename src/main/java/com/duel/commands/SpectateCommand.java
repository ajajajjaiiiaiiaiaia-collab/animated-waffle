package com.duel.commands;

import com.duel.DuelPlugin;
import com.duel.models.DuelSession;
import com.duel.models.PartyDuelSession;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SpectateCommand implements CommandExecutor, TabCompleter {

    private final DuelPlugin plugin;
    private final Map<UUID, SpectatorData> spectators = new ConcurrentHashMap<>();

    public SpectateCommand(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player spectator)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length == 0) {
            if (isSpectating(spectator)) stopSpectating(spectator);
            else if (plugin.getPartyDuelManager().leaveAsDeadSpectator(spectator))
                spectator.sendMessage(color("&a👁 You have exited spectator mode."));
            else spectator.sendMessage(color("&cUsage: /spectate <player>"));
            return true;
        }

        if (plugin.getDuelManager().isInDuel(spectator) || plugin.getPartyDuelManager().isInPartyDuel(spectator)) {
            spectator.sendMessage(color("&c⚔ You cannot spectate while in a duel!"));
            return true;
        }

        if (isSpectating(spectator)) {
            spectator.sendMessage(color("&c⚔ You are already spectating. Type /spectate to exit."));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);

        if (target == null) {
            spectator.sendMessage(color("&c⚔ Player &e" + args[0] + " &cis offline."));
            return true;
        }

        if (target.equals(spectator)) {
            spectator.sendMessage(color("&c⚔ You cannot spectate yourself!"));
            return true;
        }

        spectate(spectator, target);
        return true;
    }

    /**
     * التنقل أولاً ثم تفعيل وضع المتفرج الطائر (SURVIVAL + طيران + بوصلة) — لأن تغيير العالم
     * (Multiverse) ممكن يرجّع اللاعب للـ gamemode الافتراضي للعالم، فلازم نفعّل بعد النقل.
     */
    private void startSpectating(Player spectator, Player target, String info) {
        spectators.put(spectator.getUniqueId(), new SpectatorData(
                spectator.getLocation(), spectator.getGameMode(), spectator.getAllowFlight(),
                spectator.getInventory().getContents().clone(),
                spectator.getInventory().getArmorContents().clone(),
                spectator.getInventory().getItemInOffHand().clone()));

        // 1) انقله أول
        spectator.teleport(target.getLocation().clone().add(0, 3, 0));

        // 2) فعّل وضع المتفرج الطائر مباشرة بعد النقل
        plugin.getFlySpectatorManager().enter(spectator);

        // 3) تأكيد بعد tick و 5 ticks (احتياط لو Multiverse/بلجن ثاني غيّر الـ gamemode متأخر)
        for (long delay : new long[]{1L, 5L}) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!spectator.isOnline() || !isSpectating(spectator)) return;
                plugin.getFlySpectatorManager().refresh(spectator);
            }, delay);
        }

        spectator.sendMessage(color("&a👁 Now spectating: &e" + info));
        spectator.sendMessage(color("&7Use the &b🧭 compass &7to pick a player, or the barrier in the menu to exit."));
    }

    public void stopSpectating(Player spectator) {
        SpectatorData data = spectators.remove(spectator.getUniqueId());
        if (data == null) return;
        plugin.getFlySpectatorManager().exit(spectator);

        Location back = data.returnLocation();
        if (back == null || back.getWorld() == null || Bukkit.getWorld(back.getWorld().getName()) == null) {
            back = plugin.getSpawnManager().getSpawn();
        }
        GameMode mode = data.previousGameMode() == GameMode.SPECTATOR ? GameMode.SURVIVAL : data.previousGameMode();

        spectator.teleport(back);
        spectator.setGameMode(mode);
        try {
            spectator.getInventory().setContents(data.contents());
            spectator.getInventory().setArmorContents(data.armor());
            spectator.getInventory().setItemInOffHand(data.offhand());
        } catch (Throwable ignored) {}
        if (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) {
            spectator.setAllowFlight(data.allowFlight());
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (spectator.isOnline() && !isSpectating(spectator) && !plugin.getFlySpectatorManager().isActive(spectator)
                    && spectator.getGameMode() != mode
                    && !plugin.getDuelManager().isInDuel(spectator)
                    && !plugin.getPartyDuelManager().isInPartyDuel(spectator)) {
                spectator.setGameMode(mode);
            }
        }, 2L);
        spectator.sendMessage(color("&a👁 You have exited spectator mode."));
    }

    public void ejectSpectators(DuelSession session) {
        ejectSpectators(session.getCopiedWorldName());
    }

    /** يطرد كل المتفرجين الموجودين في عالم المباراة (1v1 / Party / FFA). */
    public void ejectSpectators(String worldName) {
        World duelWorld = Bukkit.getWorld(worldName);
        if (duelWorld == null) return;
        new ArrayList<>(spectators.keySet()).forEach(uuid -> {
            Player sp = Bukkit.getPlayer(uuid);
            if (sp != null && sp.getWorld().equals(duelWorld)) {
                sp.sendMessage(color("&6👁 The match has ended — you have been removed from spectator mode."));
                stopSpectating(sp);
            }
        });
    }

    /** ينقل المتفرجين من عالم الراوند القديم لعالم الراوند الجديد (مباريات الرواندات). */
    public void moveSpectators(String fromWorldName, Location to) {
        World from = Bukkit.getWorld(fromWorldName);
        if (from == null || to == null) return;
        Location dest = to.clone().add(0, 3, 0);
        for (UUID uuid : new ArrayList<>(spectators.keySet())) {
            Player sp = Bukkit.getPlayer(uuid);
            if (sp == null || !sp.isOnline() || !sp.getWorld().equals(from)) continue;
            sp.teleport(dest);
            for (long delay : new long[]{1L, 5L}) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (sp.isOnline() && isSpectating(sp)) plugin.getFlySpectatorManager().refresh(sp);
                }, delay);
            }
        }
    }

    public boolean isSpectating(Player player) {
        return spectators.containsKey(player.getUniqueId());
    }

    public void spectate(Player spectator, Player target) {
        if (spectator.equals(target) || isSpectating(spectator)) return;
        if (plugin.getDuelManager().isInDuel(spectator) || plugin.getPartyDuelManager().isInPartyDuel(spectator)) {
            spectator.sendMessage(color("&c⚔ You cannot spectate while in a duel!"));
            return;
        }

        DuelSession session = plugin.getDuelManager().getSession(target);
        if (session != null) {
            if (session.getState() == DuelSession.State.ENDED) {
                spectator.sendMessage(color("&c⚔ The duel has just ended."));
                return;
            }
            startSpectating(spectator, target,
                    target.getName() + " &7vs &e" + session.getOpponent(target).getName());
            return;
        }

        PartyDuelSession party = plugin.getPartyDuelManager().getSession(target);
        if (party != null) {
            if (party.getState() == PartyDuelSession.State.ENDED) {
                spectator.sendMessage(color("&c⚔ The match has just ended."));
                return;
            }
            startSpectating(spectator, target, target.getName() + "&7's party match");
            return;
        }

        spectator.sendMessage(color("&c⚔ &e" + target.getName() + " &cis not currently in a duel."));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return Bukkit.getOnlinePlayers().stream()
                    .filter(p -> plugin.getDuelManager().isInDuel(p) || plugin.getPartyDuelManager().isInPartyDuel(p))
                    .filter(p -> !p.getName().equals(sender.getName()))
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private record SpectatorData(Location returnLocation, GameMode previousGameMode, boolean allowFlight,
                                 org.bukkit.inventory.ItemStack[] contents,
                                 org.bukkit.inventory.ItemStack[] armor,
                                 org.bukkit.inventory.ItemStack offhand) {}
}
