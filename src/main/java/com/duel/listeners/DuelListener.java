package com.duel.listeners;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import com.duel.models.DuelSession;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;

public class DuelListener implements Listener {

    private final DuelPlugin plugin;

    /** مكان موت اللاعب داخل عالم المباراة — يُستخدم لإرجاعه كمتفرج في نفس المكان */
    private final java.util.Map<java.util.UUID, Location> deathLocations = new java.util.HashMap<>();

    public DuelListener(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    // ─────────────────────────────────────────
    //  Freeze — منع القفز أثناء التجهيز والعدّ التنازلي
    //  (walkSpeed=0 يمنع المشي فقط، فنمنع الحركة للأعلى هنا)
    //  التجميد في هذا البلغن = walkSpeed 0، يبدأ من "Match found"
    //  ويشمل 1v1 وParty وFFA حتى قبل إنشاء الجلسة.
    // ─────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFrozenJump(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || to.getY() <= from.getY()) return;

        Player p = event.getPlayer();
        if (p.getWalkSpeed() != 0f) return;
        if (p.getGameMode() == GameMode.SPECTATOR || p.isFlying()) return;
        // مشهد بين الرواندات: الحركة كلها velocity من السيرفر (الخاسر بعد الريسباون يكون على الأرض وisFlying=false
        // فكان هذا الفلتر يثبّته على بلوكه ويمنع الصعود) — لا نتدخل
        if (plugin.getRoundPullManager().isLocked(p)) return;

        // نبقي الدوران (yaw/pitch) ونثبّت الموقع
        Location fixed = from.clone();
        fixed.setYaw(to.getYaw());
        fixed.setPitch(to.getPitch());
        event.setTo(fixed);
    }

    /** المشهد بين الرواندات: ممنوع تبديل الطيران (الحركة كلها من السيرفر). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (plugin.getRoundPullManager().isLocked(event.getPlayer())) event.setCancelled(true);
    }

    // ─────────────────────────────────────────
    //  Death → end duel
    // ─────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        // متفرج طائر مات (فويد/أمر) — ما يحسب موت لاعب
        if (plugin.getFlySpectatorManager().isActive(dead)) {
            event.setDeathMessage(null);
            event.getDrops().clear();
            event.setDroppedExp(0);
            return;
        }
        // Party duel يتعامل معها PartyDuelListener
        if (plugin.getPartyDuelManager().isInPartyDuel(dead)) return;
        if (!plugin.getDuelManager().isInDuel(dead)) return;

        // Suppress death message and drops
        event.setDeathMessage(null);
        event.getDrops().clear();
        event.setDroppedExp(0);

        // احفظ مكان الموت (لإرجاعه كمتفرج داخل نفس العالم)
        deathLocations.put(dead.getUniqueId(), dead.getLocation().clone());

        // Trigger duel end
        plugin.getDuelManager().onPlayerDeath(dead);

        // تخطّي شاشة الموت: respawn تلقائي بعد tick
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (dead.isOnline() && dead.isDead()) dead.spigot().respawn();
        }, 1L);
    }

    // ─────────────────────────────────────────
    //  Respawn → send to arena (handled by DuelManager already)
    // ─────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        Location death = deathLocations.remove(p.getUniqueId());
        if (plugin.getPartyDuelManager().isInPartyDuel(p)) return;

        DuelSession session = plugin.getDuelManager().getSession(p);
        if (session == null) return;

        World duelWorld = plugin.getServer().getWorld(session.getCopiedWorldName());
        if (duelWorld == null) return;

        // مكان الرجوع: مكان الموت داخل عالم المباراة، وإلا (فويد/عالم مختلف) نقطة سبون اللاعب في الأرينا
        Location respawnLoc = death;
        if (respawnLoc == null || respawnLoc.getWorld() == null
                || !respawnLoc.getWorld().equals(duelWorld)
                || respawnLoc.getY() < duelWorld.getMinHeight()) {
            Location spawn = session.getPlayerSpawn(p);
            respawnLoc = spawn != null ? spawn.clone().add(0, 3, 0) : duelWorld.getSpawnLocation();
        }
        event.setRespawnLocation(respawnLoc);

        // لو مات أثناء العد التنازلي (مثلاً فويد) — يرجع سبونه كلاعب عادي بدون spectator
        if (session.getState() == DuelSession.State.COUNTDOWN) return;

        // بين الرواندات: الخاسر يبقى سيرفايفل (RoundPullManager يجهّزه للمشهد السينمائي)
        if (session.getState() == DuelSession.State.ROUND_BREAK) {
            // نقفله بعد الريسباون بـ tick عشان ما يتحرك/يطير قبل ما يبدأ المشهد
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (p.isOnline() && session.getState() == DuelSession.State.ROUND_BREAK) {
                    plugin.getRoundPullManager().lock(p);
                }
            });
            return;
        }

        // نهاية المباراة → الميت يصير سبكتر عادي (gamemode SPECTATOR) بدون بوصلة/ريشة
        plugin.getDuelManager().makeDeadSpectator(p);
    }

    // ─────────────────────────────────────────
    //  Block break — enforce arena setting
    // ─────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        // الأدمن/الأوب ما تنطبق عليه قيود الأرينا، لكن لازم نتتبّع تغييراته برضو
        // (قبل كان يرجع هنا بدون تتبّع → البلوكات ما ترجع بين الرواندات)
        boolean admin = player.hasPermission("duelplugin.admin");

        DuelSession session = plugin.getDuelManager().getSession(player);
        if (session == null) return;

        Arena arena = session.getArena();
        if (arena == null) return;

        org.bukkit.Location loc = event.getBlock().getLocation();
        boolean isPlaced = session.isPlacedBlock(loc);

        if (isPlaced) {
            // بلوكة مبنية أثناء المباراة
            if (!admin && !arena.isAllowBreakPlaced()) {
                event.setCancelled(true);
                return;
            }
            // مسموح بتكسير المبنية — نشيلها من التتبع (أصلها محفوظ في brokenOriginals وقت البناء)
            session.untrackBlock(loc);
        } else {
            // بلوكة أصلية من الماب
            if (!admin && !arena.isAllowBreaking()) {
                event.setCancelled(true);
                return;
            }
            if (session.isRoundMatch()) {
                // نحفظ البلوكة الأصلية عشان نرجعها بين الرواندات
                session.trackBrokenOriginal(loc, event.getBlock().getBlockData());
            }
        }

        // البلوكات المجاورة اللي ممكن تنكسر/تتغيّر معها (شعلة على الجنب، باب، سرير، ورد...)
        if (session.isRoundMatch()) {
            trackNeighbors(session, event.getBlock());
        }
    }

    /** يحفظ الأصل لكل البلوكات الصلبة حول البلوكة المكسورة (6 اتجاهات) لو ما كانت مبنية من لاعب. */
    private void trackNeighbors(DuelSession session, org.bukkit.block.Block center) {
        for (org.bukkit.block.BlockFace face : new org.bukkit.block.BlockFace[]{
                org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN,
                org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
            org.bukkit.block.Block r = center.getRelative(face);
            if (r.getType().isAir()) continue;
            if (session.isPlacedBlock(r.getLocation())) continue;
            session.trackBrokenOriginal(r.getLocation(), r.getBlockData());
        }
    }

    // ─────────────────────────────────────────
    //  Block place — enforce arena setting
    // ─────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        boolean admin = player.hasPermission("duelplugin.admin"); // قيود فقط، التتبّع للكل

        DuelSession session = plugin.getDuelManager().getSession(player);
        if (session == null) return;

        Arena arena = session.getArena();
        if (arena == null) return;

        if (!admin) {
            if (!arena.isAllowBuilding()) {
                event.setCancelled(true);
                return;
            }

            // منع البناء أكثر من 30 بلوك فوق Y الإسبون
            org.bukkit.Location spawnLoc = session.getPlayerSpawn(player);
            if (spawnLoc != null && event.getBlock().getY() - spawnLoc.getBlockY() > 30) {
                event.setCancelled(true);
                return;
            }
        }

        // نحفظ اللي كان مكانها (هواء/عشب/ماء...) عشان يترجّع بعد الراوند
        if (session.isRoundMatch() && !session.isPlacedBlock(event.getBlock().getLocation())) {
            session.trackBrokenOriginal(event.getBlock().getLocation(), event.getBlockReplacedState().getBlockData());
        }
        // تتبع البلوكة المبنية
        session.trackPlacedBlock(event.getBlock().getLocation(), player);
    }

    // ─────────────────────────────────────────
    //  PvP — only allow damage within active duels
    // ─────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;

        // إذا أي منهم في party duel — PartyDuelListener تتعامل معها
        if (plugin.getPartyDuelManager().isInPartyDuel(attacker) ||
            plugin.getPartyDuelManager().isInPartyDuel(victim)) return;

        boolean attackerInDuel = plugin.getDuelManager().isInDuel(attacker);
        boolean victimInDuel   = plugin.getDuelManager().isInDuel(victim);

        // If neither is in a duel — let normal server rules apply
        if (!attackerInDuel && !victimInDuel) return;

        // If both are in a duel, check they're in the same session
        if (attackerInDuel && victimInDuel) {
            DuelSession aSession = plugin.getDuelManager().getSession(attacker);
            DuelSession vSession = plugin.getDuelManager().getSession(victim);

            if (aSession != null && aSession.equals(vSession)
                    && plugin.getDuelManager().ensureFightStarted(aSession)) {
                return; // valid duel fight — allow damage
            }
        }

        // Otherwise cancel
        event.setCancelled(true);
    }
}
