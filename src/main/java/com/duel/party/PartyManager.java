package com.duel.party;

import com.duel.DuelPlugin;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Central registry: playerUUID → their Party.
 * All other classes call getParty(player) here.
 */
public class PartyManager {

    private final DuelPlugin plugin;
    private final Map<UUID, Party> playerPartyMap = new HashMap<>();

    public PartyManager(DuelPlugin plugin) {
        this.plugin = plugin;
        Party.init(plugin);
    }

    public Party getParty(Player player) {
        return playerPartyMap.get(player.getUniqueId());
    }

    public boolean isInParty(Player player) {
        return playerPartyMap.containsKey(player.getUniqueId());
    }

    public void updatePlayerParty(Player player, Party party) {
        if (party == null) {
            playerPartyMap.remove(player.getUniqueId());
        } else {
            playerPartyMap.put(player.getUniqueId(), party);
        }
        // إصلاح 2: نستدعي PartyHotbar.refresh() فقط — مكان واحد للهوتبار
        refreshHotbar(player);
    }

    /** Called when player quits — clean them out of their party */
    public void handleQuit(Player player) {
        Party party = getParty(player);
        if (party == null) return;

        if (party.getLeader().equals(player)) {
            party.disband();
        } else {
            party.leave(player, false);
        }
        // إصلاح 1: مسح اللاعب من الماب عند الخروج
        playerPartyMap.remove(player.getUniqueId());
    }

    /**
     * إصلاح 2: نفوّض الهوتبار لـ PartyHotbar كاملاً — لا كود مكرر هنا
     */
    public void refreshHotbar(Player player) {
        if (!player.isOnline()) return;

        // لا نلمس الهوتبار إذا اللاعب في دوول أو كيو
        if (plugin.getDuelManager().isInDuel(player)) return;
        if (plugin.getPartyDuelManager().isInPartyDuel(player)) return;
        if (plugin.getQueueManager().isQueued(player)) return;

        // إصلاح 2: PartyHotbar.refresh() هو المكان الوحيد اللي يبني الهوتبار
        plugin.getServer().getScheduler().runTask(plugin, () -> PartyHotbar.refresh(player));
    }

    /** إصلاح 1: يُستدعى عند onDisable لتنظيف الـ static list */
    public void shutdown() {
        playerPartyMap.clear();
        Party.clearAll();
    }
}
