package com.duel.listeners;

import com.duel.DuelPlugin;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * يمنع لاعب المباراة يقرّب من حافة البوردر أقل من min-distance (الافتراضي 1 بلوك).
 * يعدّل مكان الحركة الجاي مباشرة، فاللاعب ما يوصل للحافة أصلاً ولا يدخل جسمه فيها.
 * يغطي المشي، الدفع (knockback)، وانتقالات مثل الإندر بيرل (PlayerTeleportEvent يرث PlayerMoveEvent).
 *
 * config.yml:
 *   border-guard.enabled: true
 *   border-guard.min-distance: 1.0
 */
public class BorderGuardListener implements Listener {

    private final DuelPlugin plugin;

    public BorderGuardListener(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to == null) return;
        Location from = e.getFrom();
        if (from.getX() == to.getX() && from.getZ() == to.getZ()) return;

        Player p = e.getPlayer();
        if (!plugin.getConfig().getBoolean("border-guard.enabled", true)) return;
        final boolean spectator = p.getGameMode() == GameMode.SPECTATOR
                || plugin.getFlySpectatorManager().isActive(p);
        if (!plugin.getDuelManager().isInDuel(p) && !plugin.getPartyDuelManager().isInPartyDuel(p)
                && !spectator) return;

        World w = to.getWorld();
        if (w == null) return;
        boolean barrier = plugin.getArenaManager().hasBarrierArea(w);
        // المتفرج: البارير ما يوقفه، فنحصره بنفس المنطقة (البوردر الحقيقي كبّرناه)
        if (spectator && !barrier) return;

        double[] area = plugin.getArenaManager().getPlayArea(w);
        double half = area[2];
        if (half > 5000) return;

        double d = Math.max(0.0, plugin.getConfig().getDouble("border-guard.min-distance", 1.0));
        // مع البارير: الحلقة الأخيرة بلوكات بارير، فنحتفظ ببلوك كامل على الأقل
        if (barrier) d = Math.max(d, 1.0);
        if (half <= d * 2) return;

        double cx = area[0], cz = area[1];
        double minX = cx - half + d, maxX = cx + half - d;
        double minZ = cz - half + d, maxZ = cz + half - d;

        double x = Math.max(minX, Math.min(maxX, to.getX()));
        double z = Math.max(minZ, Math.min(maxZ, to.getZ()));
        if (x == to.getX() && z == to.getZ()) return;

        Location fixed = to.clone();
        fixed.setX(x);
        fixed.setZ(z);
        e.setTo(fixed);
    }
}
