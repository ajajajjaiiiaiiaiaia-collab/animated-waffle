package com.stark.christmastree;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * يبني شجرة كريسمس ضخمة:
 * - جذع سميك فيه غرف مربعة متفرعة (يمين/يسار) متصلة ببعضها عمودياً بسلم داخلي
 * - كل غرفة فيها فتحتين (شرق/غرب) تطل للخارج
 * - تاج مخروطي من الأوراق فوق الجذع
 * - ديكور: كرات صوف ملونة + جلوستون/شمعدان كأضواء + نجمة فوق
 */
public class TreeBuilder {

    private final ChristmasTreePlugin plugin;
    private final Location base;
    private final int height;
    private final World world;
    private final Random random = new Random();

    // جذع عريض بما يكفي لاستيعاب غرف 5x5 داخلية
    private static final int TRUNK_RADIUS = 3; // قطر الجذع ~7x7
    private static final int ROOM_SIZE = 5;    // غرفة داخلية 5x5
    private static final int ROOM_HEIGHT = 4;  // ارتفاع كل غرفة (أرضية لأرضية)

    private final Material[] ornamentColors = {
            Material.RED_WOOL, Material.LIME_WOOL, Material.YELLOW_WOOL,
            Material.LIGHT_BLUE_WOOL, Material.WHITE_WOOL, Material.ORANGE_WOOL
    };

    public TreeBuilder(ChristmasTreePlugin plugin, Location base, int height) {
        this.plugin = plugin;
        this.base = base;
        this.height = height;
        this.world = base.getWorld();
    }

    public void buildAsync(Player notify) {
        List<Runnable> steps = new ArrayList<>();

        // 1) الجذع المجوف من الخشب فيه فتحة سلم بالنص
        buildTrunkSteps(steps);

        // 2) الغرف المتفرعة يمين/يسار كل عدة طوابق
        buildRoomsSteps(steps);

        // 3) تاج الأوراق المخروطي فوق الجذع
        buildFoliageSteps(steps);

        // 4) الديكور: كرات ملونة + أضواء
        buildDecorationSteps(steps);

        // 5) النجمة فوق الشجرة
        buildStarSteps(steps);

        runStepsGradually(steps, notify);
    }

    /* ================= تنفيذ متدرج لتفادي اللاق ================= */

    private void runStepsGradually(List<Runnable> steps, Player notify) {
        final int[] index = {0};
        final int perTick = 60; // عدد عمليات وضع البلوكات بكل تيك

        org.bukkit.scheduler.BukkitRunnable task = new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                int done = 0;
                while (done < perTick && index[0] < steps.size()) {
                    steps.get(index[0]).run();
                    index[0]++;
                    done++;
                }
                if (index[0] >= steps.size()) {
                    notify.sendMessage("§a§l✔ تم بناء شجرة الكريسمس بنجاح! §7استمتع 🎄");
                    notify.playSound(notify.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                    this.cancel();
                }
            }
        };
        task.runTaskTimer(plugin, 1L, 1L);
    }

    private void set(List<Runnable> steps, int x, int y, int z, Material mat) {
        steps.add(() -> {
            Block b = world.getBlockAt(base.getBlockX() + x, base.getBlockY() + y, base.getBlockZ() + z);
            b.setType(mat, false);
        });
    }

    /* ================= 1) الجذع ================= */

    private void buildTrunkSteps(List<Runnable> steps) {
        // الجذع يرتفع لغاية 60% من الارتفاع الكلي، الباقي للأوراق
        int trunkTop = (int) (height * 0.6);

        for (int y = 0; y <= trunkTop; y++) {
            for (int x = -TRUNK_RADIUS; x <= TRUNK_RADIUS; x++) {
                for (int z = -TRUNK_RADIUS; z <= TRUNK_RADIUS; z++) {
                    boolean edge = Math.max(Math.abs(x), Math.abs(z)) == TRUNK_RADIUS;
                    boolean insideHollow = Math.max(Math.abs(x), Math.abs(z)) < TRUNK_RADIUS;

                    if (edge) {
                        set(steps, x, y, z, Material.SPRUCE_LOG);
                    } else if (insideHollow) {
                        // نجوف الداخل، ونسوي أرضية خشب كل ROOM_HEIGHT طابق (يصير سلم بينها فتحة)
                        boolean isFloorLevel = y % ROOM_HEIGHT == 0;
                        boolean isLadderHole = (x == 0 && z == 0); // فتحة السلم بالمنتصف
                        if (isFloorLevel && !isLadderHole) {
                            set(steps, x, y, z, Material.SPRUCE_PLANKS);
                        } else {
                            set(steps, x, y, z, Material.AIR);
                        }
                    }
                }
            }
            // سلم عمودي بمنتصف الجذع يوصل كل الطوابق ببعض
            set(steps, 0, y, 0, Material.LADDER);
        }

        // إضاءة داخل عمود السلم كل كم بلوك
        for (int y = 2; y <= trunkTop; y += 5) {
            set(steps, 1, y, 0, Material.WALL_TORCH);
        }
    }

    /* ================= 2) الغرف المتفرعة يمين/يسار ================= */

    private void buildRoomsSteps(List<Runnable> steps) {
        int trunkTop = (int) (height * 0.6);
        int roomOffset = TRUNK_RADIUS; // الغرفة تبدأ من حافة الجذع

        // غرفة كل طابقين عشان الجذع يتحمل الحجم ويصير منظر متدرج
        for (int floorY = ROOM_HEIGHT; floorY + ROOM_HEIGHT < trunkTop; floorY += ROOM_HEIGHT * 2) {
            buildRoom(steps, floorY, +1, roomOffset); // غرفة يمين (X موجب)
            buildRoom(steps, floorY, -1, roomOffset); // غرفة يسار (X سالب)
        }
    }

    /**
     * side = +1 (يمين/شرق) أو -1 (يسار/غرب)
     * الغرفة مربعة ROOM_SIZE x ROOM_SIZE، متصلة بالجذع عبر فتحة بدون جدار من جهة الجذع،
     * وفيها فتحتين تطلان للخارج (شمال وجنوب الغرفة) + نوافذ شرق/غرب حسب الطلب.
     */
    private void buildRoom(List<Runnable> steps, int floorY, int side, int startOffset) {
        int half = ROOM_SIZE / 2; // 2 لو ROOM_SIZE=5
        int xStart = side > 0 ? startOffset : -startOffset - ROOM_SIZE + 1;
        int xEnd = xStart + ROOM_SIZE - 1;

        // أرضية وسقف الغرفة
        for (int x = xStart; x <= xEnd; x++) {
            for (int z = -half; z <= half; z++) {
                set(steps, x, floorY, z, Material.SPRUCE_PLANKS);
                set(steps, x, floorY + ROOM_HEIGHT - 1, z, Material.SPRUCE_PLANKS);
                for (int y = floorY + 1; y < floorY + ROOM_HEIGHT - 1; y++) {
                    set(steps, x, y, z, Material.AIR); // تفريغ الداخل
                }
            }
        }

        // الجدران الجانبية (شمال وجنوب بالنسبة للغرفة) فيها فتحة = نافذة تطل برا
        for (int y = floorY + 1; y < floorY + ROOM_HEIGHT - 1; y++) {
            for (int x = xStart; x <= xEnd; x++) {
                boolean isWindowHole = (x == xStart + ROOM_SIZE / 2); // فتحة بمنتصف الجدار
                boolean isMidHeight = (y == floorY + 1 || y == floorY + ROOM_HEIGHT - 2) ? false : true;

                Material wallMat = Material.SPRUCE_PLANKS;
                if (isWindowHole && isMidHeight) {
                    set(steps, x, y, half, Material.AIR);   // فتحة جنوب
                    set(steps, x, y, -half, Material.AIR);  // فتحة شمال
                } else {
                    set(steps, x, y, half, wallMat);
                    set(steps, x, y, -half, wallMat);
                }
            }
        }

        // الجدار الخارجي البعيد عن الجذع (نهاية الغرفة) مغلق بالكامل
        int farX = side > 0 ? xEnd : xStart;
        for (int y = floorY + 1; y < floorY + ROOM_HEIGHT - 1; y++) {
            for (int z = -half; z <= half; z++) {
                set(steps, farX, y, z, Material.SPRUCE_PLANKS);
            }
        }

        // الجدار القريب من الجذع: نفتحه بالكامل = ممر يوصل الغرفة بالجذع
        int nearX = side > 0 ? xStart : xEnd;
        for (int y = floorY + 1; y < floorY + ROOM_HEIGHT - 1; y++) {
            for (int z = -1; z <= 1; z++) {
                set(steps, nearX, y, z, Material.AIR);
            }
        }

        // إضاءة داخل الغرفة
        set(steps, (xStart + xEnd) / 2, floorY + ROOM_HEIGHT - 2, 0, Material.GLOWSTONE);
    }

    /* ================= 3) تاج الأوراق المخروطي ================= */

    private void buildFoliageSteps(List<Runnable> steps) {
        int trunkTop = (int) (height * 0.6);
        int foliageHeight = height - trunkTop;
        int maxRadius = TRUNK_RADIUS + 5; // أعرض من الجذع عشان يشكل مخروط واضح

        for (int i = 0; i < foliageHeight; i++) {
            int y = trunkTop + i;
            double ratio = 1.0 - ((double) i / foliageHeight); // يضيق كل ما نطلع
            int radius = Math.max(1, (int) Math.round(maxRadius * ratio));

            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    double dist = Math.sqrt(x * x + z * z);
                    if (dist <= radius) {
                        // نسوي الطبقة الخارجية أوراق كثيفة، والحافة القصوى كل كم طبقة نخليها متعرجة لمظهر طبيعي
                        boolean outerRing = dist > radius - 1.3;
                        if (outerRing || random.nextInt(6) != 0) {
                            set(steps, x, y, z, Material.SPRUCE_LEAVES);
                        }
                    }
                }
            }
        }
    }

    /* ================= 4) الديكور: كرات ملونة + أضواء ================= */

    private void buildDecorationSteps(List<Runnable> steps) {
        int trunkTop = (int) (height * 0.6);
        int foliageHeight = height - trunkTop;
        int maxRadius = TRUNK_RADIUS + 5;

        for (int i = 2; i < foliageHeight; i += 2) {
            int y = trunkTop + i;
            double ratio = 1.0 - ((double) i / foliageHeight);
            int radius = Math.max(1, (int) Math.round(maxRadius * ratio)) + 1;

            // نوزع كرات ملونة وأضواء على محيط دائري بزوايا ثابتة
            int points = Math.max(4, radius * 2);
            for (int p = 0; p < points; p++) {
                double angle = (2 * Math.PI / points) * p;
                int x = (int) Math.round(radius * Math.cos(angle));
                int z = (int) Math.round(radius * Math.sin(angle));

                if (p % 3 == 0) {
                    // ضوء مضيء (جلوستون أو شمعدان)
                    Material light = random.nextBoolean() ? Material.GLOWSTONE : Material.SEA_LANTERN;
                    set(steps, x, y, z, light);
                } else {
                    // كرة زينة ملونة من الصوف
                    Material wool = ornamentColors[random.nextInt(ornamentColors.length)];
                    set(steps, x, y, z, wool);
                }
            }
        }

        // شريط أضواء حلزوني حول الجذع نفسه
        int trunkDecoRadius = TRUNK_RADIUS + 1;
        for (int y = 2; y < trunkTop; y++) {
            double angle = y * 0.9;
            int x = (int) Math.round(trunkDecoRadius * Math.cos(angle));
            int z = (int) Math.round(trunkDecoRadius * Math.sin(angle));
            Material deco = (y % 4 == 0) ? Material.GLOWSTONE : ornamentColors[random.nextInt(ornamentColors.length)];
            set(steps, x, y, z, deco);
        }
    }

    /* ================= 5) النجمة فوق الشجرة ================= */

    private void buildStarSteps(List<Runnable> steps) {
        int topY = height + 1;
        // نجمة بسيطة ثلاثية الأبعاد من ذهب + جلوستون بالنص
        set(steps, 0, topY, 0, Material.GLOWSTONE);
        set(steps, 1, topY, 0, Material.GOLD_BLOCK);
        set(steps, -1, topY, 0, Material.GOLD_BLOCK);
        set(steps, 0, topY, 1, Material.GOLD_BLOCK);
        set(steps, 0, topY, -1, Material.GOLD_BLOCK);
        set(steps, 0, topY + 1, 0, Material.GOLD_BLOCK);
        set(steps, 1, topY + 1, 1, Material.GOLD_BLOCK);
        set(steps, -1, topY + 1, -1, Material.GOLD_BLOCK);
        set(steps, 1, topY + 1, -1, Material.GOLD_BLOCK);
        set(steps, -1, topY + 1, 1, Material.GOLD_BLOCK);
    }
}
