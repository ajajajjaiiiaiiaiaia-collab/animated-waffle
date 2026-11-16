package com.duel.listeners;

import com.duel.DuelPlugin;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

/**
 * يسجّل البلوكة الأصلية قبل أي تغيير ما يغطيه كسر/بناء اللاعب
 * (سطل ماء/لافا، سيلان، انفجار، نار، رمل/كونكريت، تكوين كوبل...) عشان تترجّع بين الرواندات
 * وما نحتاج عالم جديد. كل الأحداث تنطلق قبل ما يتغيّر البلوك.
 */
public class WorldDirtyListener implements Listener {

    private final DuelPlugin plugin;

    public WorldDirtyListener(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    private void track(Block b) { plugin.getDuelManager().trackWorldChange(b); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) { track(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) { track(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent e) { track(e.getToBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        for (Block b : e.blockList()) track(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        for (Block b : e.blockList()) track(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) { track(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { track(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) { track(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) { track(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) { track(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) { track(e.getBlock()); }

    // ── تغييرات إضافية ما كانت متتبّعة ──

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent e) { track(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpongeAbsorb(SpongeAbsorbEvent e) {
        for (org.bukkit.block.BlockState st : e.getBlocks()) track(st.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent e) {
        for (org.bukkit.block.BlockState st : e.getBlocks()) track(st.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        BlockFace dir = e.getDirection();
        track(e.getBlock().getRelative(dir)); // رأس البستون
        for (Block b : e.getBlocks()) { track(b); track(b.getRelative(dir)); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        BlockFace dir = e.getDirection();
        track(e.getBlock().getRelative(dir)); // رأس البستون
        for (Block b : e.getBlocks()) {
            track(b);
            track(b.getRelative(dir));
            track(b.getRelative(dir.getOppositeFace()));
        }
    }
}
