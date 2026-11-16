package com.duel.listeners;

import com.duel.DuelPlugin;
import com.duel.models.PartyDuelSession;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public class PartyDuelListener implements Listener {

    private final DuelPlugin plugin;

    /** مكان موت اللاعب داخل عالم المباراة */
    private final java.util.Map<java.util.UUID, Location> deathLocations = new java.util.HashMap<>();

    /** كريستال → آخر لاعب ضربه (يفجّره). نحتاجه عشان ننسب ضرر الانفجار للي فجّره (فريندلي فاير) */
    private final java.util.Map<java.util.UUID, java.util.UUID> crystalOwner = new java.util.HashMap<>();

    public PartyDuelListener(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    // ──────────────────────────────────────────
    //  إعدادات الأرينا (تكسير / بناء) — نفس 1v1
    // ──────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("duelplugin.admin")) return;

        PartyDuelSession session = plugin.getPartyDuelManager().getSession(player);
        if (session == null) return;
        com.duel.models.Arena arena = session.getArena();
        if (arena == null) return;

        Location loc = event.getBlock().getLocation();
        if (session.isPlacedBlock(loc)) {
            if (!arena.isAllowBreakPlaced()) {
                event.setCancelled(true);
                return;
            }
            session.untrackBlock(loc);
        } else if (!arena.isAllowBreaking()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("duelplugin.admin")) return;

        PartyDuelSession session = plugin.getPartyDuelManager().getSession(player);
        if (session == null) return;
        com.duel.models.Arena arena = session.getArena();
        if (arena == null) return;

        if (!arena.isAllowBuilding()) {
            event.setCancelled(true);
            return;
        }
        session.trackPlacedBlock(event.getBlock().getLocation());
    }

    // ──────────────────────────────────────────
    //  موت → spectator (أو نهاية مباراة)
    // ──────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        if (!plugin.getPartyDuelManager().isInPartyDuel(dead)) return;
        // متفرج طائر مات (فويد/أمر) — ما يحسب موت لاعب
        if (plugin.getFlySpectatorManager().isActive(dead)) {
            event.setDeathMessage(null);
            event.getDrops().clear();
            event.setDroppedExp(0);
            return;
        }

        PartyDuelSession session = plugin.getPartyDuelManager().getSession(dead);
        if (session == null || session.getState() != PartyDuelSession.State.FIGHTING) return;

        // احفظ مكان الموت + تخطّي شاشة الموت (respawn تلقائي بعد tick)
        deathLocations.put(dead.getUniqueId(), dead.getLocation().clone());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (dead.isOnline() && dead.isDead()) dead.spigot().respawn();
        }, 1L);

        // رسالة الموت + اللوت: يسقط حق اللاعب عند موته (settings.party-drop-loot، افتراضي true)
        event.setDeathMessage(null);
        event.setDroppedExp(0);
        if (plugin.getConfig().getBoolean("settings.party-drop-loot", true)) {
            // لو عالم الأرينا فيه keepInventory (من نسخة الماب) نلغيه ونسقط الأغراض يدوياً
            if (event.getKeepInventory()) {
                event.setKeepInventory(false);
                event.getDrops().clear();
                for (org.bukkit.inventory.ItemStack it : dead.getInventory().getContents()) {
                    if (it != null && it.getType() != org.bukkit.Material.AIR) event.getDrops().add(it.clone());
                }
            }
        } else {
            event.getDrops().clear();
        }

        // سجّل الموت في الجلسة
        plugin.getPartyDuelManager().onPlayerDeath(dead);
    }

    // ──────────────────────────────────────────
    //  Respawn → ابقِه spectator في نفس العالم
    // ──────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        Location death = deathLocations.remove(p.getUniqueId());
        PartyDuelSession session = plugin.getPartyDuelManager().getSession(p);
        if (session == null) return;

        org.bukkit.World duelWorld = org.bukkit.Bukkit.getWorld(session.getCopiedWorldName());
        if (duelWorld == null) return;

        // مكان الموت داخل عالم المباراة، وإلا (فويد/عالم مختلف) سبون العالم
        Location respawnLoc = death;
        if (respawnLoc == null || respawnLoc.getWorld() == null
                || !respawnLoc.getWorld().equals(duelWorld)
                || respawnLoc.getY() < duelWorld.getMinHeight()) {
            respawnLoc = duelWorld.getSpawnLocation();
        }
        event.setRespawnLocation(respawnLoc);

        // بعد النقل (tick) → متفرج طائر (سيرفايفل + طيران + بوصلة)
        plugin.getFlySpectatorManager().scheduleEnter(p, 1L);
    }

    // ──────────────────────────────────────────
    //  PvP — اسمح بالضرر داخل نفس الجلسة فقط
    // ──────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity()  instanceof Player victim))   return;

        // المهاجم: لاعب مباشرة، أو لاعب رمى مقذوف (سهم/ترايدنت/كرة ثلج...) — وإلا الفريندلي فاير يمر بالسهام
        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof org.bukkit.entity.TNTPrimed tnt
                && tnt.getSource() instanceof Player p) {
            // انفجار TNT → ننسبه للي أشعله (يشمل السلاسل)
            attacker = p;
        } else if (event.getDamager() instanceof org.bukkit.entity.EnderCrystal crystal) {
            // انفجار كريستال → ننسبه لآخر لاعب ضربه
            java.util.UUID owner = crystalOwner.get(crystal.getUniqueId());
            if (owner != null) attacker = org.bukkit.Bukkit.getPlayer(owner);
        }
        if (attacker == null) return;

        boolean aInPartyDuel = plugin.getPartyDuelManager().isInPartyDuel(attacker);
        boolean vInPartyDuel = plugin.getPartyDuelManager().isInPartyDuel(victim);

        // لا أحد في party duel — اتركها للـ DuelListener العادي
        if (!aInPartyDuel && !vInPartyDuel) return;

        // أحدهم في party duel والثاني لا — الغِ
        if (aInPartyDuel != vInPartyDuel) {
            event.setCancelled(true);
            return;
        }

        // كلاهما في party duel — يجب أن يكونا في نفس الجلسة
        PartyDuelSession aSession = plugin.getPartyDuelManager().getSession(attacker);
        PartyDuelSession vSession = plugin.getPartyDuelManager().getSession(victim);

        if (aSession == null || !aSession.equals(vSession)) {
            event.setCancelled(true);
            return;
        }

        // لا يجوز الضرب بين أعضاء نفس الفريق (ضرر نفسك من متفجراتك مسموح)
        if (!attacker.equals(victim) && aSession.getTeamOf(attacker) == aSession.getTeamOf(victim)) {
            event.setCancelled(true);
            return;
        }

        // هل الجلسة في مرحلة القتال؟
        if (aSession.getState() != PartyDuelSession.State.FIGHTING) {
            event.setCancelled(true);
            return;
        }

        // هل الضحية لا تزال حية؟
        if (!aSession.isAlive(victim)) {
            event.setCancelled(true);
            return;
        }

        // كل الشروط مرت — اسمح بالضرر
    }

    // ──────────────────────────────────────────
    //  تتبّع من فجّر الكريستال (لمنع ضرر الانفجار على الزميل)
    // ──────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.EnderCrystal crystal)) return;

        Player hitter = null;
        if (event.getDamager() instanceof Player p) {
            hitter = p;
        } else if (event.getDamager() instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof Player p) {
            hitter = p;
        }
        if (hitter == null || !plugin.getPartyDuelManager().isInPartyDuel(hitter)) return;

        // الانفجار يصير بنفس التيك — نمسح التسجيل بعده بشوي
        final java.util.UUID id = crystal.getUniqueId();
        crystalOwner.put(id, hitter.getUniqueId());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> crystalOwner.remove(id), 5L);
    }
}
