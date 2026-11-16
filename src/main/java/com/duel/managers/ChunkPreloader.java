package com.duel.managers;

import com.duel.DuelPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * يحمّل شنكات الأرينا async (Paper) قبل ما اللاعبين ينتقلون لها،
 * ويثبّتها بـ plugin chunk ticket لين يخلص الـ teleport — فما يصير load/generate على الـ main thread وقت الانتقال.
 */
public class ChunkPreloader {

    private final DuelPlugin plugin;

    public ChunkPreloader(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() { return plugin.getConfig(); }

    public boolean isEnabled() { return cfg().getBoolean("chunk-preload.enabled", true); }

    /**
     * @param seeds       نقاط الـ spawn (يحمّل حولها radius شنك)
     * @param wholeBorder لو true يحمّل كل شنكات البوردر (للـ FFA / الرسبن العشوائي) بشرط ما يتعدى max-chunks
     * @param done        يشتغل على الـ main thread بعد ما يخلص التحميل (أو بعد timeout) — دايماً يشتغل مرة وحدة
     */
    public void preload(World world, Collection<Location> seeds, boolean wholeBorder, Runnable done) {
        if (world == null || !isEnabled()) { done.run(); return; }

        final Set<Long> keys;
        try {
            keys = collect(world, seeds, wholeBorder);
        } catch (Throwable t) {
            plugin.getLogger().warning("[ChunkPreloader] collect failed: " + t.getMessage());
            done.run();
            return;
        }
        if (keys.isEmpty()) { done.run(); return; }

        final AtomicBoolean finished = new AtomicBoolean(false);
        final Runnable finish = () -> {
            if (!finished.compareAndSet(false, true)) return;
            if (Bukkit.isPrimaryThread()) done.run();
            else Bukkit.getScheduler().runTask(plugin, done);
        };

        final long timeout = Math.max(20, cfg().getInt("chunk-preload.timeout-ticks", 100));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!finished.get()) {
                plugin.getLogger().warning("[ChunkPreloader] Timed out for '" + world.getName() + "' — continuing anyway.");
            }
            finish.run();
        }, timeout);

        final AtomicInteger remaining = new AtomicInteger(keys.size());
        for (long key : keys) {
            final int cx = (int) (key >> 32);
            final int cz = (int) key;
            try {
                world.getChunkAtAsync(cx, cz, true).whenComplete((chunk, err) -> {
                    if (chunk != null) {
                        try { chunk.addPluginChunkTicket(plugin); } catch (Throwable ignored) {}
                    }
                    if (remaining.decrementAndGet() == 0) finish.run();
                });
            } catch (Throwable t) {
                if (remaining.decrementAndGet() == 0) finish.run();
            }
        }
    }

    /** يفك التثبيت بعد الـ teleport (اللاعبين نفسهم يبقّون الشنكات محمّلة بعدها). */
    public void releaseLater(String worldName) {
        long hold = Math.max(0, cfg().getInt("chunk-preload.hold-ticks", 100));
        Bukkit.getScheduler().runTaskLater(plugin, () -> release(worldName), hold);
    }

    public void release(String worldName) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return;
        try { w.removePluginChunkTickets(plugin); } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────

    private Set<Long> collect(World world, Collection<Location> seeds, boolean wholeBorder) {
        final int radius   = Math.max(0, cfg().getInt("chunk-preload.radius", 4));
        final int maxChunks = Math.max(1, cfg().getInt("chunk-preload.max-chunks", 400));

        // حدود البوردر بالشنكات — ما نحمّل/نولّد شي خارجه
        double[] area = plugin.getArenaManager().getPlayArea(world);
        double half = area[2];
        int minCx = (int) Math.floor((area[0] - half) / 16.0);
        int maxCx = (int) Math.floor((area[0] + half) / 16.0);
        int minCz = (int) Math.floor((area[1] - half) / 16.0);
        int maxCz = (int) Math.floor((area[1] + half) / 16.0);

        Set<Long> out = new LinkedHashSet<>();

        // حلقات من الـ spawn للخارج — الأقرب أول
        for (int d = 0; d <= radius; d++) {
            for (Location s : seeds) {
                if (s == null) continue;
                int sx = s.getBlockX() >> 4, sz = s.getBlockZ() >> 4;
                for (int dx = -d; dx <= d; dx++) {
                    for (int dz = -d; dz <= d; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                        int cx = sx + dx, cz = sz + dz;
                        if (cx < minCx || cx > maxCx || cz < minCz || cz > maxCz) continue;
                        out.add(key(cx, cz));
                    }
                }
            }
        }

        if (wholeBorder) {
            long total = (long) (maxCx - minCx + 1) * (long) (maxCz - minCz + 1);
            if (total > 0 && total <= maxChunks) {
                for (int cx = minCx; cx <= maxCx; cx++)
                    for (int cz = minCz; cz <= maxCz; cz++)
                        out.add(key(cx, cz));
            } else if (total > maxChunks) {
                // بوردر كبير — نكتفي بمنطقة حول مركزه بدل ما نحمّل كل شي
                int ccx = (int) Math.floor(area[0] / 16.0);
                int ccz = (int) Math.floor(area[1] / 16.0);
                int r = (int) Math.floor((Math.sqrt(maxChunks) - 1) / 2.0);
                for (int dx = -r; dx <= r; dx++)
                    for (int dz = -r; dz <= r; dz++) {
                        int cx = ccx + dx, cz = ccz + dz;
                        if (cx < minCx || cx > maxCx || cz < minCz || cz > maxCz) continue;
                        out.add(key(cx, cz));
                    }
            }
        }

        // سقف أمان
        if (out.size() > maxChunks) {
            Set<Long> capped = new LinkedHashSet<>();
            for (long k : out) { if (capped.size() >= maxChunks) break; capped.add(k); }
            return capped;
        }
        return out;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
