package com.stark.christmastree;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * يبني شجرة بلوط (Oak) طبيعية وجميلة:
 * - جذور بارزة عند القاعدة
 * - جذع رئيسي بسمك 2x2 يضيق تدريجيًا، مع تفرعات جانبية مائلة بالثلث العلوي
 * - تاج أوراق بيضاوي/كروي كثيف وطبيعي الشكل (مو مربع)
 */
public class OakTreeBuilder {

    private final ChristmasTreePlugin plugin;
    private final Location base;
    private final int height;
    private final int crownWidth; // عرض التاج المطلوب
    private final World world;
    private final Random random = new Random();

    public OakTreeBuilder(ChristmasTreePlugin plugin, Location base, int height, int crownWidth) {
        this.plugin = plugin;
        this.base = base;
        this.height = height;
        this.crownWidth = crownWidth;
        this.world = base.getWorld();
    }

    public void buildAsync(Player notify) {
        List<Runnable> steps = new ArrayList<>();

        buildRootsSteps(steps);
        buildTrunkSteps(steps);
        buildBranchesSteps(steps);
        buildCrownSteps(steps);

        runStepsGradually(steps, notify);
    }

    private void runStepsGradually(List<Runnable> steps, Player notify) {
        final int[] index = {0};
        final int perTick = 70;

        new BukkitRunnable() {
            @Override
            public void run() {
                int done = 0;
                while (done < perTick && index[0] < steps.size()) {
                    steps.get(index[0]).run();
                    index[0]++;
                    done++;
                }
                if (index[0] >= steps.size()) {
                    notify.sendMessage("§a§l✔ تم بناء شجرة البلوط بنجاح! §7استمتع 🌳");
                    notify.playSound(notify.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.1f);
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void set(List<Runnable> steps, int x, int y, int z, Material mat) {
        steps.add(() -> {
            Block b = world.getBlockAt(base.getBlockX() + x, base.getBlockY() + y, base.getBlockZ() + z);
            b.setType(mat, false);
        });
    }

    /* ================= الجذور ================= */

    private void buildRootsSteps(List<Runnable> steps) {
        // جذور بارزة تمتد من قاعدة الجذع للخارج بشكل متعرج طبيعي
        int rootCount = 6;
        for (int r = 0; r < rootCount; r++) {
            double angle = (2 * Math.PI / rootCount) * r + random.nextDouble() * 0.4;
            int length = 2 + random.nextInt(2);
            for (int step = 1; step <= length; step++) {
                int x = (int) Math.round(step * Math.cos(angle));
                int z = (int) Math.round(step * Math.sin(angle));
                int y = -(step > length - 1 ? 1 : 0); // تغوص الجذور شوي بالأرض بآخرها
                set(steps, x, y, z, Material.OAK_LOG);
            }
        }
    }

    /* ================= الجذع ================= */

    private void buildTrunkSteps(List<Runnable> steps) {
        int trunkTop = (int) (height * 0.65); // الجذع يوصل 65% من الارتفاع، والباقي تاج وتفرعات

        for (int y = 0; y <= trunkTop; y++) {
            // الجذع 2x2 بالأسفل، ويصير 1x1 بالنص العلوي لمظهر طبيعي متدرج
            boolean thick = y < trunkTop * 0.5;
            if (thick) {
                set(steps, 0, y, 0, Material.OAK_LOG);
                set(steps, 1, y, 0, Material.OAK_LOG);
                set(steps, 0, y, 1, Material.OAK_LOG);
                set(steps, 1, y, 1, Material.OAK_LOG);
            } else {
                set(steps, 0, y, 0, Material.OAK_LOG);
            }
        }
    }

    /* ================= التفرعات الجانبية ================= */

    private void buildBranchesSteps(List<Runnable> steps) {
        int trunkTop = (int) (height * 0.65);
        int branchStart = (int) (height * 0.45);
        int branchCount = 5 + random.nextInt(3);

        for (int b = 0; b < branchCount; b++) {
            int startY = branchStart + random.nextInt(Math.max(1, trunkTop - branchStart));
            double angle = random.nextDouble() * 2 * Math.PI;
            int length = 3 + random.nextInt(3);

            int x = 0, z = 0, y = startY;
            for (int step = 1; step <= length; step++) {
                x = (int) Math.round(step * Math.cos(angle) * 0.9);
                z = (int) Math.round(step * Math.sin(angle) * 0.9);
                y = startY + (int) Math.round(step * 0.5); // تميل لفوق شوي
                set(steps, x, y, z, Material.OAK_LOG);
            }
            // كتلة أوراق صغيرة بنهاية كل فرع (قبل التاج الرئيسي) لمظهر أكثر طبيعية
            final int fx = x, fy = y, fz = z;
            addLeafBlob(steps, fx, fy, fz, 2);
        }
    }

    /* ================= التاج الرئيسي ================= */

    private void buildCrownSteps(List<Runnable> steps) {
        int trunkTop = (int) (height * 0.65);
        int crownHeight = height - trunkTop + 4; // التاج يبدأ شوي قبل قمة الجذع
        int crownCenterY = trunkTop + crownHeight / 2;
        double radiusX = crownWidth / 2.0;
        double radiusY = crownHeight / 2.0;
        double radiusZ = crownWidth / 2.0;

        // تاج بيضاوي (إهليلجي) باستخدام معادلة الكرة الممططة، مع تعرجات عشوائية بالحافة
        int range = (int) Math.ceil(Math.max(radiusX, Math.max(radiusY, radiusZ))) + 1;

        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    double dx = x / radiusX;
                    double dy = y / radiusY;
                    double dz = z / radiusZ;
                    double dist = dx * dx + dy * dy + dz * dz;

                    if (dist <= 1.0) {
                        boolean edgeLayer = dist > 0.75; // الحافة الخارجية
                        // نسوي الحافة متعرجة بدل كروية مثالية، والداخل أوراق كثيفة صلبة
                        if (!edgeLayer || random.nextDouble() > 0.12) {
                            int wx = x, wy = crownCenterY + y, wz = z;
                            set(steps, wx, wy, wz, Material.OAK_LEAVES);
                        }
                    }
                }
            }
        }

        // نتأكد إن قمة الجذع متصلة بالتاج (خشب يدخل شوي بالأوراق)
        for (int y = trunkTop; y <= trunkTop + 2; y++) {
            set(steps, 0, y, 0, Material.OAK_LOG);
        }
    }

    private void addLeafBlob(List<Runnable> steps, int cx, int cy, int cz, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    double dist = Math.sqrt(x * x + y * y + z * z);
                    if (dist <= radius + 0.3 && random.nextDouble() > 0.15) {
                        set(steps, cx + x, cy + y, cz + z, Material.OAK_LEAVES);
                    }
                }
            }
        }
    }
}
