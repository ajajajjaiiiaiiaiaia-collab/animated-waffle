package com.duel.listeners;

import com.duel.DuelPlugin;
import com.duel.gui.FlySpeedGUI;
import com.duel.gui.SpectatorPlayersGUI;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;

/** حماية وتحكم المتفرج الطائر (بوصلة، GUI، باريير للخروج، ومنع أي تفاعل مع الماتش). */
public class FlySpectatorListener implements Listener {

    private final DuelPlugin plugin;

    public FlySpectatorListener(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean spec(Entity e) {
        return e instanceof Player p && plugin.getFlySpectatorManager().isActive(p);
    }

    // ── البوصلة ─────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!spec(p)) return;
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        event.setCancelled(true);

        if (event.getHand() != EquipmentSlot.HAND) return;
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK
                && a != Action.LEFT_CLICK_AIR && a != Action.LEFT_CLICK_BLOCK) return;
        if (plugin.getFlySpectatorManager().isCompass(event.getItem())) {
            new SpectatorPlayersGUI(plugin, p, 0).open(p);
        } else if (plugin.getFlySpectatorManager().isSpeedItem(event.getItem())) {
            new FlySpeedGUI(plugin, p).open();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (spec(event.getPlayer())) event.setCancelled(true);
    }

    // ── الـ GUI ─────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p) || !spec(p)) return;
        event.setCancelled(true);

        if (event.getView().getTopInventory().getHolder() instanceof FlySpeedGUI speedGui) {
            if (event.getClickedInventory() == event.getView().getTopInventory()) {
                speedGui.handleClick(p, event.getRawSlot());
            }
            return;
        }

        if (!(event.getView().getTopInventory().getHolder() instanceof SpectatorPlayersGUI gui)) return;
        if (event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getTopInventory()) return;

        int slot = event.getRawSlot();

        if (slot == SpectatorPlayersGUI.EXIT_SLOT) {
            if (event.getCurrentItem() == null || event.getCurrentItem().getType() != org.bukkit.Material.BARRIER) return;
            p.closeInventory();
            exitSpectator(p);
            return;
        }
        if (gui.isPaged() && slot == SpectatorPlayersGUI.PREV_SLOT && gui.getPage() > 0) {
            new SpectatorPlayersGUI(plugin, p, gui.getPage() - 1).open(p);
            return;
        }
        if (gui.isPaged() && slot == SpectatorPlayersGUI.NEXT_SLOT) {
            new SpectatorPlayersGUI(plugin, p, gui.getPage() + 1).open(p);
            return;
        }

        Player target = gui.getTargetAt(slot);
        if (target == null) return;
        p.closeInventory();
        if (!target.isOnline() || !target.getWorld().equals(p.getWorld())) {
            p.sendMessage("§c⚔ That player is no longer in the match.");
            return;
        }
        Location dest = target.getLocation().clone();
        p.teleport(dest);
        p.setFlying(true);
        p.sendMessage("§a👁 Teleported to §e" + target.getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player p && spec(p)) event.setCancelled(true);
    }

    /** الباريير: يطلّع المتفرج حسب نوعه. */
    private void exitSpectator(Player p) {
        // متفرج خارجي (/spectate أو GUI المباريات)
        if (plugin.getSpectateCommand().isSpectating(p)) {
            plugin.getSpectateCommand().stopSpectating(p);
            return;
        }
        // ميت في بارتي / FFA → يطلع للسبون بدري
        if (plugin.getPartyDuelManager().isInPartyDuel(p)) {
            if (plugin.getPartyDuelManager().leaveAsDeadSpectator(p)) {
                p.sendMessage("§a👁 You have exited spectator mode.");
                return;
            }
        }
        // خاسر في 1v1 → نفس /leave (انسحاب)
        if (plugin.getDuelManager().isInDuel(p)) {
            p.performCommand("leave");
            return;
        }
        // احتياط
        plugin.getFlySpectatorManager().exit(p);
        p.teleport(plugin.getSpawnManager().getSpawn());
        plugin.getPlayerJoinListener().giveDuelSword(p);
        plugin.getPartyManager().refreshHotbar(p);
    }

    // ── المتفرج ما يسد بناء البلوكات: السيرفر يتجاهله (بدون نقله) ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onCanBuildSpectator(org.bukkit.event.block.BlockCanBuildEvent e) {
        if (e.isBuildable()) return;
        org.bukkit.block.Block b = e.getBlock();
        try { if (!e.getBlockData().isSupported(b)) return; } catch (Throwable ignored) {}
        boolean spectatorHit = false;
        for (org.bukkit.entity.Entity en : b.getWorld().getNearbyEntities(org.bukkit.util.BoundingBox.of(b))) {
            if (en instanceof Player sp && spec(sp)) { spectatorHit = true; continue; }
            if (en instanceof org.bukkit.entity.LivingEntity || en instanceof org.bukkit.entity.Vehicle
                    || en instanceof org.bukkit.entity.Hanging) return;
        }
        if (spectatorHit) e.setBuildable(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBuildNearSpectator(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (spec(p)) return;
        org.bukkit.block.Block clicked = e.getClickedBlock();
        org.bukkit.inventory.ItemStack it = e.getItem();
        if (clicked == null || it == null) return;
        org.bukkit.Material m = it.getType();
        org.bukkit.util.BoundingBox box;
        if (m == org.bukkit.Material.ARMOR_STAND) {
            box = org.bukkit.util.BoundingBox.of(clicked.getRelative(e.getBlockFace()));
        } else {
            return;
        }
        for (Player s : clicked.getWorld().getPlayers()) {
            if (!spec(s) || !s.getBoundingBox().overlaps(box)) continue;
            pushOutOf(s, box);
        }
    }

    private void pushOutOf(Player s, org.bukkit.util.BoundingBox box) {
        try {
            Location l = s.getLocation();
            org.bukkit.World w = l.getWorld();
            int bx = l.getBlockX(), bz = l.getBlockZ();
            double base = box.getMaxY() + 0.05;
            for (int i = 0; i < 8; i++) {
                double y = base + i;
                org.bukkit.block.Block feet = w.getBlockAt(bx, (int) Math.floor(y), bz);
                if (feet.isPassable() && feet.getRelative(org.bukkit.block.BlockFace.UP).isPassable()) {
                    Location dest = new Location(w, l.getX(), y, l.getZ(), l.getYaw(), l.getPitch());
                    s.setVelocity(new Vector(0, 0, 0));
                    s.teleport(dest);
                    s.setFlying(true);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    // ── الكريستال: ماينكرافت يتجاهل اللاعبين بوضع SPECTATOR عند فحص المكان ──
    // فلو المتفرج واقف على الأوبسيديان نحوّله SPECTATOR لحظياً، ماينكرافت يحط الكريستال بنفسه (طبيعي 100%)، ونرجّعه بالتك الجاي.
    private final java.util.Set<java.util.UUID> tempSpectatorMode = new java.util.HashSet<>();

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrystalThroughSpectator(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (spec(p)) return;
        org.bukkit.block.Block clicked = e.getClickedBlock();
        org.bukkit.inventory.ItemStack it = e.getItem();
        if (clicked == null || it == null) return;
        if (it.getType() != org.bukkit.Material.END_CRYSTAL) return;
        org.bukkit.Material t = clicked.getType();
        if (t != org.bukkit.Material.OBSIDIAN && t != org.bukkit.Material.BEDROCK) return;
        org.bukkit.block.Block above = clicked.getRelative(org.bukkit.block.BlockFace.UP);
        if (!above.getType().isAir()) return;

        org.bukkit.util.BoundingBox box = new org.bukkit.util.BoundingBox(
                above.getX(), above.getY(), above.getZ(),
                above.getX() + 1.0, above.getY() + 2.0, above.getZ() + 1.0);
        java.util.List<Player> blockers = new java.util.ArrayList<>();
        for (Entity en : above.getWorld().getNearbyEntities(box)) {
            if (en instanceof Player sp && spec(sp)) { blockers.add(sp); continue; }
            return; // أي كيان ثاني: ماينكرافت يرفض مثل العادة
        }
        if (blockers.isEmpty()) return;

        for (Player sp : blockers) {
            if (tempSpectatorMode.contains(sp.getUniqueId())) continue;
            tempSpectatorMode.add(sp.getUniqueId());
            final float flySpeed = sp.getFlySpeed();
            try {
                sp.setGameMode(GameMode.SPECTATOR);
            } catch (Throwable ex) {
                tempSpectatorMode.remove(sp.getUniqueId());
                continue;
            }
            // الحدث ما نلغيه: ماينكرافت يكمل ويحط الكريستال، ونرجّع المتفرج بعدها بتك
            Bukkit.getScheduler().runTask(plugin, () -> restoreFromTempSpectator(sp, flySpeed, box));
        }
    }

    private void restoreFromTempSpectator(Player sp, float flySpeed, org.bukkit.util.BoundingBox box) {
        if (!tempSpectatorMode.remove(sp.getUniqueId())) return;
        if (!sp.isOnline()) return;
        try {
            sp.setGameMode(GameMode.SURVIVAL);
            if (spec(sp)) {
                sp.setAllowFlight(true);
                sp.setFlying(true);
                sp.setFlySpeed(flySpeed);
                sp.setVelocity(new Vector(0, 0, 0));
                sp.setFallDistance(0f);
                // لو الكريستال انحط فعلاً: ندفع المتفرج لفوقه عشان ما نحوّله مرة ثانية مع كل كريستال
                boolean placed = false;
                for (Entity en : sp.getWorld().getNearbyEntities(box)) {
                    if (en instanceof org.bukkit.entity.EnderCrystal) { placed = true; break; }
                }
                if (placed && sp.getBoundingBox().overlaps(box)) pushOutOf(sp, box);
            }
        } catch (Throwable ex) {
            plugin.getLogger().warning("[Spectator] restore after crystal failed: " + ex);
        }
    }

    // ── منع أي تأثير على الماتش ──────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (spec(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (spec(event.getPlayer())) event.setCancelled(true);
    }

    // تحصين إضافي: دلاء، إطارات/لوحات، أرمور ستاند
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (spec(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (spec(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        if (spec(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (spec(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (spec(event.getRemover())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (spec(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (spec(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (spec(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (spec(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamaged(EntityDamageEvent event) {
        if (spec(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamageOthers(EntityDamageByEntityEvent event) {
        Entity d = event.getDamager();
        if (spec(d)) { event.setCancelled(true); return; }
        if (d instanceof Projectile proj && proj.getShooter() instanceof Player sp && spec(sp)) {
            event.setCancelled(true);
        }
    }

    // ── منع الدفع (انفجارات، ويند تشارج، ضرب، ماء، بستون...) ──────────────

    /** أي velocity يجي للمتفرج يتلغى (الصفر مسموح عشان نقدر نصفّر حركته بأنفسنا). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVelocity(PlayerVelocityEvent event) {
        if (!spec(event.getPlayer())) return;
        if (event.getVelocity().lengthSquared() < 1.0E-6) return;
        event.setVelocity(new Vector(0, 0, 0));
    }

    /** دفع الانفجار يوصل للكلاينت بباكيت الانفجار نفسه، فنصفّر حركة المتفرجين القريبين بعده. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityExplode(EntityExplodeEvent event) {
        neutralize(event.getLocation(), 24);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockExplode(BlockExplodeEvent event) {
        neutralize(event.getBlock().getLocation(), 24);
    }

    /** الويند تشارج: انفجارها يدفع حتى لو ما كسر بلوكات. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWindChargeHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof WindCharge) neutralize(event.getEntity().getLocation(), 12);
    }

    private void neutralize(Location origin, double radius) {
        if (origin == null || origin.getWorld() == null) return;
        if (plugin.getFlySpectatorManager().hasPacketGuard()) return;
        double r2 = radius * radius;
        for (Player p : origin.getWorld().getPlayers()) {
            if (!spec(p) || p.getLocation().distanceSquared(origin) > r2) continue;
            final Location safe = p.getLocation().clone();
            resetMotion(p, null);
            for (long d : new long[]{1L, 2L, 4L}) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> resetMotion(p, safe), d);
            }
        }
    }

    private void resetMotion(Player p, Location safe) {
        if (!p.isOnline() || !spec(p)) return;
        try {
            p.setVelocity(new Vector(0, 0, 0));
            p.setFallDistance(0f);
            p.setAllowFlight(true);
            if (!p.isFlying()) p.setFlying(true);
            // لو انزاح أكثر من 3 بلوكات (دفعة الانفجار) نرجّعه مكانه ونعيد مزامنة الطيران مع الكلاينت
            if (safe != null && safe.getWorld() != null && p.getWorld().equals(safe.getWorld())
                    && p.getLocation().distanceSquared(safe) > 9.0) {
                Location back = safe.clone();
                back.setYaw(p.getLocation().getYaw());
                back.setPitch(p.getLocation().getPitch());
                p.teleport(back);
                p.setFlying(true);
            }
        } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTarget(EntityTargetEvent event) {
        if (spec(event.getTarget())) event.setCancelled(true);
    }

    /** السهام/اللآلئ تمرّ من خلال المتفرج. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (spec(event.getHitEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player p && spec(p)) event.setCancelled(true);
    }

    /** المتفرج يبقى SURVIVAL — نمنع أي بلجن يغيّره (Multiverse...). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (!spec(event.getPlayer())) return;
        if (tempSpectatorMode.contains(event.getPlayer().getUniqueId())) return; // تحويلنا اللحظي للكريستال
        if (event.getNewGameMode() != GameMode.SURVIVAL) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        tempSpectatorMode.remove(event.getPlayer().getUniqueId());
        plugin.getFlySpectatorManager().exit(event.getPlayer());
    }
}
