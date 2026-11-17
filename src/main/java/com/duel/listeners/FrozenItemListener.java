package com.duel.listeners;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * يمنع البناء والتكسير واستخدام الأيتمات (إندر بيرل، تفاح ذهبي، أكل، بوشن، قوس، ثلج...) أثناء العدّ التنازلي.
 * اللاعب المجمّد = walkSpeed 0 (نفس شرط onFrozenJump في DuelListener)، يشمل 1v1 وParty وFFA وبين الرواندات.
 */
public class FrozenItemListener implements Listener {

    private static boolean frozen(Player p) {
        return p != null && p.getWalkSpeed() == 0f && p.getGameMode() != GameMode.SPECTATOR;
    }

    private static boolean blockedItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        Material m = item.getType();
        if (m.isEdible()) return true;
        String n = m.name();
        return m == Material.ENDER_PEARL
                || m == Material.BOW
                || m == Material.CROSSBOW
                || m == Material.TRIDENT
                || m == Material.SNOWBALL
                || m == Material.EGG
                || m == Material.ENDER_EYE
                || m == Material.EXPERIENCE_BOTTLE
                || m == Material.FIREWORK_ROCKET
                || m == Material.FIRE_CHARGE
                || m == Material.WIND_CHARGE
                || m == Material.CHORUS_FRUIT
                || m == Material.MILK_BUCKET
                || m == Material.POTION
                || m == Material.SPLASH_POTION
                || m == Material.LINGERING_POTION
                || m == Material.SHIELD
                || m == Material.END_CRYSTAL
                || m == Material.FLINT_AND_STEEL
                || n.endsWith("_BUCKET");
    }

    // يمنع فتح أي حاوية (صندوق، إندر تشست، بريوينغ ستاند...) وهو مجمّد —
    // هذا هو السبب الفعلي للباق: اللاعب يفتح GUI أثناء العدّ التنازلي/الأنيميشن،
    // يسحب آيتم على الكيرسر، ثم الراوند يبدأ (inventory.clear() ما يلمس الكيرسر)
    // فيضل الآيتم عالق بيده لين يحطه في راوند جاي أو بعد الرجوع للسبون.
    // GUI حق البلقن نفسه (InventoryHolder == null أحيانًا، أو من فئاتنا) ما تُمنع هنا.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        if (!frozen(p)) return;
        InventoryHolder holder = event.getInventory().getHolder();
        // GUIs حق البلقن (قوائمنا) مو حاويات حقيقية — خليها تفتح عادي لو احتاجت
        if (holder != null && holder.getClass().getName().startsWith("com.duel.")) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = event.getPlayer();
        if (!frozen(p)) return;
        if (!blockedItem(event.getItem())) return;
        event.setUseItemInHand(Event.Result.DENY);
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (frozen(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player p && frozen(p)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player p && frozen(p)) event.setCancelled(true);
    }

    // LOWEST: نلغي قبل ما DuelListener يتتبّع البلوكة (هو ignoreCancelled فما يتتبّع الملغي)
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (frozen(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (frozen(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (frozen(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (frozen(event.getPlayer())) event.setCancelled(true);
    }
}
