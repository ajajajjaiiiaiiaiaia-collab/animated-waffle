package com.duel.managers;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

public class ArenaManager {

    private final DuelPlugin plugin;
    // In-memory cache
    private final Map<String, Arena> arenas = new LinkedHashMap<>();

    public ArenaManager(DuelPlugin plugin) {
        this.plugin = plugin;
        loadAll();
        loadBarrierAreas();
    }

    /** كاش حدود القوالب المبنية بالبارير (world → {x, z, half}) — يتحمّل مرة وحدة من DB عند التشغيل. */
    private final Map<String, double[]> prebuiltCache = new java.util.concurrent.ConcurrentHashMap<>();

    private void loadBarrierAreas() {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT world, x, z, half FROM barrier_areas");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                prebuiltCache.put(rs.getString("world"),
                        new double[]{rs.getDouble("x"), rs.getDouble("z"), rs.getDouble("half")});
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[ArenaManager] Failed loading barrier areas", e);
        }
    }

    // ─────────────────────────────────────────
    //  Load all arenas from DB on startup
    // ─────────────────────────────────────────

    private void loadAll() {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {

            // Load arenas
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, kit_name, data, allow_breaking, allow_building, allow_break_placed, difficulty, menu_slot FROM arenas");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id      = rs.getString("id");
                    String kitName = rs.getString("kit_name");
                    String data    = rs.getString("data");

                    Arena arena = new Arena(id);
                    arena.setKitName(kitName);
                    arena.setAllowBreaking(rs.getInt("allow_breaking") == 1);
                    arena.setAllowBuilding(rs.getInt("allow_building") == 1);
                    arena.setAllowBreakPlaced(rs.getInt("allow_break_placed") == 1);
                    String diff = rs.getString("difficulty");
                    arena.setDifficulty(diff != null ? diff : "normal");
                    int ms = rs.getInt("menu_slot");
                    arena.setMenuSlot(rs.wasNull() ? -1 : ms);

                    if (data != null && !data.isEmpty()) {
                        List<ItemStack> items = deserializeItems(data);
                        arena.getGuiItems().addAll(items);
                    }
                    arenas.put(id.toLowerCase(), arena);
                }
            }

            // Load worlds + spawns
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT arena_id, world_name, spawns FROM arena_worlds");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String arenaId   = rs.getString("arena_id");
                    String worldName = rs.getString("world_name");
                    String spawnsStr = rs.getString("spawns");

                    Arena arena = arenas.get(arenaId.toLowerCase());
                    if (arena == null) continue;

                    if (!arena.getWorlds().contains(worldName))
                        arena.getWorlds().add(worldName);

                    // spawns stored as "x,y,z,yaw,pitch|x,y,z,yaw,pitch"
                    if (spawnsStr != null && !spawnsStr.isEmpty()) {
                        for (String spawnStr : spawnsStr.split("\\|")) {
                            String[] parts = spawnStr.split(",");
                            if (parts.length < 5) continue;
                            double x = Double.parseDouble(parts[0]);
                            double y = Double.parseDouble(parts[1]);
                            double z = Double.parseDouble(parts[2]);
                            float yaw   = Float.parseFloat(parts[3]);
                            float pitch = Float.parseFloat(parts[4]);
                            // Store with worldName key even if world not loaded yet
                            // DuelManager will resolve the world at match time
                            World world = Bukkit.getWorld(worldName);
                            Location loc;
                            if (world != null) {
                                loc = new Location(world, x, y, z, yaw, pitch);
                            } else {
                                // Lazy location — world will be resolved when match starts
                                loc = new Location(null, x, y, z, yaw, pitch);
                                plugin.getLogger().warning("[ArenaManager] World '" + worldName
                                        + "' not loaded — spawn stored with null world, will resolve at match time.");
                            }
                            arena.addSpawn(worldName, loc);
                        }
                    }
                }
            }

            // Load per-map display name + icon
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT arena_id, world_name, display_name, icon FROM arena_world_meta");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Arena arena = arenas.get(rs.getString("arena_id").toLowerCase());
                    if (arena == null) continue;
                    arena.getWorldMeta().put(rs.getString("world_name"),
                            new Arena.WorldMeta(rs.getString("display_name"), rs.getString("icon")));
                }
            }

            // Load per-arena potion effects
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT arena_id, effect, amplifier, duration_seconds FROM arena_potions");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Arena arena = arenas.get(rs.getString("arena_id").toLowerCase());
                    if (arena == null) continue;
                    Arena.PotionSetting ps2 = new Arena.PotionSetting(rs.getString("effect"),
                            rs.getInt("amplifier"), rs.getInt("duration_seconds"));
                    arena.getPotions().put(ps2.getEffect(), ps2);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[ArenaManager] Failed loading arena potions", e);
            }

            plugin.getLogger().info("[DuelPlugin] Loaded " + arenas.size() + " arena(s) from database.");

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to load arenas from DB!", e);
        }
    }

    // ─────────────────────────────────────────
    //  Save arena to DB
    // ─────────────────────────────────────────

    public void saveArena(Arena arena) {
        arenas.put(arena.getId().toLowerCase(), arena);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {

                // Upsert arena row
                try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO arenas (id, kit_name, data, allow_breaking, allow_building, allow_break_placed, difficulty)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        kit_name       = excluded.kit_name,
                        data           = excluded.data,
                        allow_breaking = excluded.allow_breaking,
                        allow_building = excluded.allow_building,
                        allow_break_placed = excluded.allow_break_placed,
                        difficulty     = excluded.difficulty
                """)) {
                    ps.setString(1, arena.getId());
                    ps.setString(2, arena.getKitName());
                    ps.setString(3, serializeItems(arena.getGuiItems()));
                    ps.setInt   (4, arena.isAllowBreaking() ? 1 : 0);
                    ps.setInt   (5, arena.isAllowBuilding() ? 1 : 0);
                    ps.setInt   (6, arena.isAllowBreakPlaced() ? 1 : 0);
                    ps.setString(7, arena.getDifficulty());
                    ps.executeUpdate();
                }

                // Delete old worlds then re-insert
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM arena_worlds WHERE arena_id = ?")) {
                    del.setString(1, arena.getId());
                    del.executeUpdate();
                }

                for (Map.Entry<String, List<Location>> entry : arena.getSpawnPoints().entrySet()) {
                    String worldName = entry.getKey();
                    List<Location> spawns = entry.getValue();

                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < spawns.size(); i++) {
                        Location loc = spawns.get(i);
                        if (i > 0) sb.append("|");
                        sb.append(loc.getX()).append(",")
                          .append(loc.getY()).append(",")
                          .append(loc.getZ()).append(",")
                          .append(loc.getYaw()).append(",")
                          .append(loc.getPitch());
                    }

                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO arena_worlds (arena_id, world_name, spawns) VALUES (?, ?, ?)")) {
                        ps.setString(1, arena.getId());
                        ps.setString(2, worldName);
                        ps.setString(3, sb.toString());
                        ps.executeUpdate();
                    }
                }

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to save arena: " + arena.getId(), e);
            }
        });
    }

    // ─────────────────────────────────────────
    //  Map (world) display metadata: icon + display name
    // ─────────────────────────────────────────

    /** Writes one map's display name + icon row (upsert). Safe to call from the main thread (admin command). */
    public void saveWorldMeta(Arena arena, String worldName) {
        Arena.WorldMeta meta = arena.getOrCreateWorldMeta(worldName);
        final String display = meta.getDisplayName();
        final String icon    = meta.getIcon();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO arena_world_meta (arena_id, world_name, display_name, icon)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(arena_id, world_name) DO UPDATE SET
                        display_name = excluded.display_name,
                        icon         = excluded.icon
                 """)) {
                ps.setString(1, arena.getId());
                ps.setString(2, worldName);
                ps.setString(3, display);
                ps.setString(4, icon);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE,
                        "[DuelPlugin] Failed to save map meta: " + arena.getId() + "/" + worldName, e);
            }
        });
    }

    // ─────────────────────────────────────────
    //  Arena potions
    // ─────────────────────────────────────────

    /** يحفظ بوشنات الأرينا كاملة (حذف + إدخال بترانزاكشن واحد). */
    public void saveArenaPotions(Arena arena) {
        final String id = arena.getId();
        final List<Arena.PotionSetting> snapshot = new ArrayList<>(arena.getPotions().values());
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement del = conn.prepareStatement(
                            "DELETE FROM arena_potions WHERE arena_id = ?")) {
                        del.setString(1, id);
                        del.executeUpdate();
                    }
                    for (Arena.PotionSetting s : snapshot) {
                        try (PreparedStatement ins = conn.prepareStatement(
                                "INSERT INTO arena_potions (arena_id, effect, amplifier, duration_seconds) VALUES (?, ?, ?, ?)")) {
                            ins.setString(1, id);
                            ins.setString(2, s.getEffect());
                            ins.setInt(3, s.getAmplifier());
                            ins.setInt(4, s.getDurationSeconds());
                            ins.executeUpdate();
                        }
                    }
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to save arena potions: " + id, e);
            }
        });
    }

    /** يشيل بوشنات أي أرينا من اللاعب (نرجّعه للوبي بدون بوشنات الأرينا). */
    public void clearArenaPotions(Player p) {
        if (p == null || !p.isOnline()) return;
        for (Arena a : arenas.values()) a.clearPotions(p);
    }

    // ─────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────

    public Arena getArena(String id)           { return arenas.get(id.toLowerCase()); }
    public Collection<Arena> getAllArenas()     { return arenas.values(); }
    public boolean arenaExists(String id)      { return arenas.containsKey(id.toLowerCase()); }

    public Arena createArena(String id) {
        Arena arena = new Arena(id);
        arenas.put(id.toLowerCase(), arena);
        return arena;
    }

    public boolean deleteArena(String id) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM arena_potions WHERE arena_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM arena_world_meta WHERE arena_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM arena_worlds WHERE arena_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM arenas WHERE id = ?")) {
                ps.setString(1, id);
                if (ps.executeUpdate() == 0) return false;
            }
            arenas.remove(id.toLowerCase());
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to delete arena: " + id, e);
            return false;
        }
    }

    /** أسماء العوالم اللي فشل نسخها (عشان المنتظرين يوقفون فوراً بدل التايم أوت) */
    private final Set<String> failedCopies = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public boolean isCopyFailed(String worldName) {
        return failedCopies.contains(worldName);
    }

    /** ملفات/مجلدات ما ننسخها من العالم الأصلي */
    private static final Set<String> COPY_SKIP = Set.of(
            "uid.dat", "session.lock", "playerdata", "stats", "advancements", "level.dat_old");

    /**
     * ينسخ العالم بدون ما يعلّق السيرفر:
     *  1) نسخ الملفات على thread منفصل (الجزء الثقيل)
     *  2) تحميل العالم على الـ main thread (WorldCreator) — الجزء الوحيد اللي ياخذ من الـ tick
     *
     * يرجع فوراً. اللي ينتظر العالم يسوي polling على Bukkit.getWorld(name)
     * أو يفحص isCopyFailed(name).
     */
    public String copyWorld(String templateWorldName, String newWorldName) {
        failedCopies.remove(newWorldName);

        final World template = Bukkit.getWorld(templateWorldName);
        if (template == null) {
            plugin.getLogger().warning("[ArenaManager] Template world not loaded: " + templateWorldName);
            failedCopies.add(newWorldName);
            return null;
        }

        final java.nio.file.Path source = template.getWorldFolder().toPath();
        final java.nio.file.Path target = new java.io.File(Bukkit.getWorldContainer(), newWorldName).toPath();
        final org.bukkit.WorldCreator creator = new org.bukkit.WorldCreator(newWorldName).copy(template);
        final boolean pvp = template.getPVP();
        final org.bukkit.WorldBorder tb = template.getWorldBorder();
        final Location borderCenter = tb.getCenter();
        final double borderSize = tb.getSize();
        final double borderDamage = tb.getDamageAmount();
        final double borderBuffer = tb.getDamageBuffer();
        final int borderWarnDist = tb.getWarningDistance();
        final int borderWarnTime = tb.getWarningTime();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                copyFolder(source, target);
                copyWorldGuardRegions(templateWorldName, newWorldName);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[ArenaManager] Async copy failed: " + newWorldName, e);
                failedCopies.add(newWorldName);
                deleteDirectory(target.toFile());
                return;
            }

            if (!plugin.isEnabled()) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    World created = creator.createWorld();
                    if (created == null) {
                        failedCopies.add(newWorldName);
                        return;
                    }
                    created.setPVP(pvp);
                    org.bukkit.WorldBorder b = created.getWorldBorder();
                    final double[] pre = prebuiltArea(templateWorldName);
                    if (pre != null && plugin.getConfig().getBoolean("barrier-border.enabled", true)) {
                        // القالب فيه جدار بارير جاهز: ما ننسخ ولا نلمس أي بوردر ولا نبني شي
                        playAreas.put(newWorldName, pre);
                        barrierBuilt.add(newWorldName);
                    } else if (plugin.getConfig().getBoolean("barrier-border.enabled", true) && borderSize / 2.0 <= 5000) {
                        // بدون نسخ البوردر: نحفظ حدود الماب فقط ونكبّر البوردر فوراً (البارير ينبني وقت الماتش)
                        playAreas.put(newWorldName, new double[]{borderCenter.getX(), borderCenter.getZ(), borderSize / 2.0});
                        b.setCenter(borderCenter.getX(), borderCenter.getZ());
                        b.setWarningDistance(0);
                        b.setDamageAmount(0);
                        b.setSize(59999968);
                    } else {
                        b.setCenter(borderCenter.getX(), borderCenter.getZ());
                        b.setSize(borderSize);
                        b.setDamageAmount(borderDamage);
                        b.setDamageBuffer(borderBuffer);
                        b.setWarningDistance(borderWarnDist);
                        b.setWarningTime(borderWarnTime);
                    }
                    tuneWorld(created);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "[ArenaManager] World load failed: " + newWorldName, e);
                    failedCopies.add(newWorldName);
                }
            });
        });
        return newWorldName;
    }

    private void copyFolder(java.nio.file.Path source, java.nio.file.Path target) throws java.io.IOException {
        java.nio.file.Files.walkFileTree(source, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(java.nio.file.Path dir,
                                                                   java.nio.file.attribute.BasicFileAttributes attrs)
                    throws java.io.IOException {
                if (!dir.equals(source) && COPY_SKIP.contains(dir.getFileName().toString())) {
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                }
                java.nio.file.Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file,
                                                           java.nio.file.attribute.BasicFileAttributes attrs)
                    throws java.io.IOException {
                if (!COPY_SKIP.contains(file.getFileName().toString())) {
                    java.nio.file.Files.copy(file, target.resolve(source.relativize(file).toString()),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    // ─────────────────────────────────────────
    //  Barrier border: بلوكات بارير مخفية بدل الـ WorldBorder (شكله مو حلو)
    // ─────────────────────────────────────────

    /** عالم → {centerX, centerZ, halfSize} (منطقة اللعب الأصلية قبل ما نكبّر البوردر). */
    private final Map<String, double[]> playAreas = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> barrierBuilt = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** true لو هذا العالم يستخدم بلوكات بارير بدل البوردر. */
    public boolean hasBarrierArea(World w) {
        return w != null && playAreas.containsKey(w.getName());
    }

    /** منطقة اللعب: {centerX, centerZ, halfSize} — من البارير لو مفعّل وإلا من WorldBorder. */
    public double[] getPlayArea(World w) {
        double[] a = playAreas.get(w.getName());
        if (a != null) return a;
        org.bukkit.WorldBorder b = w.getWorldBorder();
        return new double[]{b.getCenter().getX(), b.getCenter().getZ(), b.getSize() / 2.0};
    }

    /** حدود القالب المبني بالبارير من الكاش (بدون أي وصول لـ DB أو config). */
    private double[] prebuiltArea(String templateWorld) {
        return prebuiltCache.get(templateWorld);
    }

    /**
     * (احتياط) يبني الجدار وقت الماتش لو القالب ما انبنى فيه مسبقاً — الأفضل /duelbarrier مرة وحدة.
     * config: barrier-border.enabled / height-below / height-above
     */
    public void applyBarrierBorder(World world, int baseY) {
        if (world == null || !plugin.getConfig().getBoolean("barrier-border.enabled", true)) return;
        if (!barrierBuilt.add(world.getName())) return; // مبني مسبقاً (منسوخ مع القالب) أو انبنى

        double[] stored = playAreas.get(world.getName());
        final org.bukkit.WorldBorder wb = world.getWorldBorder();
        final double half = stored != null ? stored[2] : wb.getSize() / 2.0;
        if (half > 5000) { barrierBuilt.remove(world.getName()); return; }
        final double cx = stored != null ? stored[0] : wb.getCenter().getX();
        final double cz = stored != null ? stored[1] : wb.getCenter().getZ();
        playAreas.put(world.getName(), new double[]{cx, cz, half});

        final String name = world.getName();
        buildWall(world, cx, cz, half, baseY, () -> {
            World w = Bukkit.getWorld(name);
            if (w == null) return;
            org.bukkit.WorldBorder b = w.getWorldBorder();
            b.setWarningDistance(0);
            b.setDamageAmount(0);
            b.setSize(59999968);
        });
    }

    /**
     * يبني جدار بارير مرة وحدة داخل عالم القالب نفسه من حدود بوردره الحالي، يحفظ الحدود في config،
     * ويكبّر بوردر القالب. بعدها كل نسخة تجي بالجدار جاهز بدون أي بناء/بوردر وقت الماتش.
     */
    public boolean buildTemplateBarrier(World template, int baseY, Runnable done) {
        final double half = template.getWorldBorder().getSize() / 2.0;
        if (half > 5000) return false; // ما فيه بوردر نحوّله لبارير
        final double cx = template.getWorldBorder().getCenter().getX();
        final double cz = template.getWorldBorder().getCenter().getZ();
        final String name = template.getName();

        buildWall(template, cx, cz, half, baseY, () -> {
            prebuiltCache.put(name, new double[]{cx, cz, half});
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
                plugin.getDatabaseManager().execute(
                    "INSERT OR REPLACE INTO barrier_areas (world, x, z, half) VALUES (?, ?, ?, ?)",
                    name, cx, cz, half));
            World w = Bukkit.getWorld(name);
            if (w != null) {
                org.bukkit.WorldBorder b = w.getWorldBorder();
                b.setWarningDistance(0);
                b.setDamageAmount(0);
                b.setSize(59999968);
                try { w.save(); } catch (Throwable ignored) {}
            }
            if (done != null) done.run();
        });
        return true;
    }

    private final Map<String, Integer> templateMatches = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> templateBuilding = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * تلقائي: بعد أول ماتش (config barrier-border.auto-build-after-matches، افتراضي 1) على ماب،
     * يأخذ حدود بوردر القالب، يبني جدار البارير في القالب مرة وحدة، يحفظ الحدود (DB + كاش)
     * ويكبّر بوردر القالب. بعدها كل النسخ بارير جاهز وبدون بوردر، وما تحتاج تسوي شي.
     * يُستدعى من DuelManager/PartyDuelManager عند بداية كل ماتش (خفيف: يطلع بسرعة لو انبنى).
     */
    public void autoPrepareTemplate(String templateWorld, int baseY) {
        if (templateWorld == null) return;
        if (!plugin.getConfig().getBoolean("barrier-border.enabled", true)
                || !plugin.getConfig().getBoolean("barrier-border.auto-build", true)) return;
        if (prebuiltCache.containsKey(templateWorld) || templateBuilding.contains(templateWorld)) return;

        int after = Math.max(1, plugin.getConfig().getInt("barrier-border.auto-build-after-matches", 1));
        if (templateMatches.merge(templateWorld, 1, Integer::sum) < after) return;

        World template = Bukkit.getWorld(templateWorld);
        if (template == null || !templateBuilding.add(templateWorld)) return;
        boolean started = buildTemplateBarrier(template, baseY, () -> {
            templateBuilding.remove(templateWorld);
            plugin.getLogger().info("[Barrier] Template '" + templateWorld
                    + "' converted to barrier walls (border removed) — future copies need no border.");
        });
        if (!started) templateBuilding.remove(templateWorld);
    }

    /** يبني حلقة بارير (بلوك واحد على حافة الحدود) شنك شنك async، ثم finish. */
    private void buildWall(World world, double cx, double cz, double half, int baseY, Runnable finish) {
        final int minX = (int) Math.floor(cx - half), maxX = (int) Math.ceil(cx + half) - 1;
        final int minZ = (int) Math.floor(cz - half), maxZ = (int) Math.ceil(cz + half) - 1;
        final int yMin = Math.max(world.getMinHeight(),
                baseY - Math.max(1, plugin.getConfig().getInt("barrier-border.height-below", 15)));
        final int yMax = Math.min(world.getMaxHeight() - 1,
                baseY + Math.max(1, plugin.getConfig().getInt("barrier-border.height-above", 60)));

        final Map<Long, List<int[]>> byChunk = new HashMap<>();
        for (int x = minX; x <= maxX; x++) { addWall(byChunk, x, minZ); addWall(byChunk, x, maxZ); }
        for (int z = minZ + 1; z <= maxZ - 1; z++) { addWall(byChunk, minX, z); addWall(byChunk, maxX, z); }

        final org.bukkit.block.data.BlockData barrier = Material.BARRIER.createBlockData();
        final boolean keepTerrain = plugin.getConfig().getBoolean("barrier-border.keep-terrain", true);
        final int[] pending = {byChunk.size()};
        final String name = world.getName();
        if (byChunk.isEmpty()) { finish.run(); return; }

        for (Map.Entry<Long, List<int[]>> e : byChunk.entrySet()) {
            final int ccx = (int) (e.getKey() >> 32), ccz = (int) (long) e.getKey();
            final List<int[]> cols = e.getValue();
            world.getChunkAtAsync(ccx, ccz).thenAccept(chunk -> {
                try {
                    for (int[] c : cols)
                        for (int y = yMin; y <= yMax; y++) {
                            org.bukkit.block.Block b = chunk.getBlock(c[0] & 15, y, c[1] & 15);
                            // نحافظ على شكل الأرض: البارير ينحط بس مكان الهواء/السوائل، ما نحفر البلوكات الصلبة
                            if (keepTerrain && !(b.getType().isAir() || b.isLiquid())) continue;
                            b.setBlockData(barrier, false);
                        }
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING, "[ArenaManager] Barrier wall failed in " + name, t);
                }
                if (--pending[0] == 0) finish.run();
            });
        }
    }

    private static void addWall(Map<Long, List<int[]>> map, int x, int z) {
        long key = (((long) (x >> 4)) << 32) | ((z >> 4) & 0xFFFFFFFFL);
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{x, z});
    }

    public void tuneWorld(org.bukkit.World world) {
        world.setAutoSave(false);
        world.setSpawnFlags(false, false);
    }

    /**
     * ينسخ ملف regions.yml من WorldGuard للعالم الأصلي إلى العالم المنسوخ.
     * plugins/WorldGuard/worlds/<world>/regions.yml
     */
    private void copyWorldGuardRegions(String sourceWorld, String targetWorld) {
        java.io.File sourceFile = new java.io.File(
                plugin.getServer().getPluginsFolder(),
                "WorldGuard/worlds/" + sourceWorld + "/regions.yml"
        );

        if (!sourceFile.exists()) return; // لا توجد regions — لا شيء نفعله

        java.io.File targetDir = new java.io.File(
                plugin.getServer().getPluginsFolder(),
                "WorldGuard/worlds/" + targetWorld
        );
        targetDir.mkdirs();

        java.io.File targetFile = new java.io.File(targetDir, "regions.yml");

        try {
            java.nio.file.Files.copy(
                    sourceFile.toPath(),
                    targetFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );
            plugin.getLogger().info("[ArenaManager] Copied WorldGuard regions: "
                    + sourceWorld + " → " + targetWorld);
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("[ArenaManager] Failed to copy WorldGuard regions for '"
                    + targetWorld + "': " + e.getMessage());
        }
    }

    public void deleteWorld(String worldName) {
        playAreas.remove(worldName);
        barrierBuilt.remove(worldName);
        org.bukkit.World world = Bukkit.getWorld(worldName);

        if (world != null) {
            // Kick all players out to the default world before unloading
            org.bukkit.World fallback = Bukkit.getWorlds().stream()
                    .filter(w -> !w.getName().equals(worldName))
                    .findFirst()
                    .orElse(null);
            org.bukkit.Location spawn = fallback != null ? fallback.getSpawnLocation() : null;

            for (org.bukkit.entity.Player p : world.getPlayers()) {
                if (spawn != null) p.teleport(spawn);
                else p.kickPlayer("§cThe arena world was removed.");
            }

            try { world.removePluginChunkTickets(plugin); } catch (Throwable ignored) {}

            // Try to unload — retry up to 3 times with 1-tick gap
            tryUnloadWorld(worldName);
            return; // deleteWorld will be called again by tryUnloadWorld after unload succeeds
        }

        // If world was null (already unloaded), delete from disk directly
        deleteDuelWorldFromDisk(worldName);
    }

    private void tryUnloadWorld(String worldName) {
        tryUnloadWorld(worldName, 0);
    }

    private void tryUnloadWorld(String worldName, int attempt) {
        org.bukkit.World w = Bukkit.getWorld(worldName);
        if (w == null) {
            // Already unloaded — proceed to delete from disk
            deleteDuelWorldFromDisk(worldName);
            return;
        }
        boolean unloaded = Bukkit.unloadWorld(w, false);
        if (unloaded) {
            deleteDuelWorldFromDisk(worldName);
        } else if (attempt < 3) {
            plugin.getLogger().warning("[ArenaManager] Unload attempt " + (attempt + 1) + " failed for: " + worldName + " — retrying...");
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> tryUnloadWorld(worldName, attempt + 1), 20L);
        } else {
            plugin.getLogger().severe("[ArenaManager] Failed to unload world after 3 attempts: " + worldName + " — forcing disk delete.");
            deleteDuelWorldFromDisk(worldName);
        }
    }

    private void deleteDuelWorldFromDisk(String worldName) {
        Runnable job = () -> {
            java.io.File worldFolder = new java.io.File(Bukkit.getWorldContainer(), worldName);
            if (worldFolder.exists()) {
                deleteDirectory(worldFolder);
                plugin.getLogger().info("[ArenaManager] Deleted world folder: " + worldName);
            }
            java.io.File wgDir = new java.io.File(
                    plugin.getServer().getPluginsFolder(),
                    "WorldGuard/worlds/" + worldName
            );
            if (wgDir.exists()) {
                deleteDirectory(wgDir);
                plugin.getLogger().info("[ArenaManager] Deleted WorldGuard regions for: " + worldName);
            }
        };

        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, job);
        } else {
            job.run();
        }
    }

    private void deleteDirectory(java.io.File dir) {
        java.io.File[] files = dir.listFiles();
        if (files != null) for (java.io.File f : files) {
            if (f.isDirectory()) deleteDirectory(f);
            else f.delete();
        }
        dir.delete();
    }

    // ─────────────────────────────────────────
    //  Serialize / Deserialize GUI items
    // ─────────────────────────────────────────

    private String serializeItems(List<ItemStack> items) {
        if (items == null || items.isEmpty()) return "";
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(bos)) {
            oos.writeInt(items.size());
            for (ItemStack item : items) oos.writeObject(item);
            return Base64Coder.encodeLines(bos.toByteArray());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to serialize arena items", e);
            return "";
        }
    }

    private List<ItemStack> deserializeItems(String data) {
        List<ItemStack> items = new ArrayList<>();
        try (ByteArrayInputStream bis = new ByteArrayInputStream(Base64Coder.decodeLines(data));
             BukkitObjectInputStream ois = new BukkitObjectInputStream(bis)) {
            int len = ois.readInt();
            for (int i = 0; i < len; i++) items.add((ItemStack) ois.readObject());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to deserialize arena items", e);
        }
        return items;
    }
}
