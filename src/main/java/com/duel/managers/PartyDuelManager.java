package com.duel.managers;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import com.duel.models.Kit;
import com.duel.models.PartyDuelSession;
import com.duel.utils.SoundUtils;
import com.duel.utils.TitleUtils;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * يُدير دول البارتي — يرسب الكل عشوائيًّا داخل بوردر العالم،
 * كل من يموت يصير spectator، الأخير يفوز.
 */
public class PartyDuelManager {

    private final DuelPlugin plugin;

    /** sessionId → session */
    private final Map<UUID, PartyDuelSession> sessions = new HashMap<>();
    /** playerUUID → session */
    private final Map<UUID, PartyDuelSession> playerSession = new java.util.concurrent.ConcurrentHashMap<>();
    /** جلسات بدأت إنهاءها — حارس ضد الاستدعاء المزدوج (الحالة ENDED ما تصلح حارس، شوف endPartyDuel) */
    private final Set<UUID> endingSessions = new HashSet<>();

    // Spectators خارج المباراة يتابعون (مثل دول العادي)
    private final Map<UUID, PartyDuelSession> spectators = new HashMap<>();

    public PartyDuelManager(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    // ──────────────────────────────────────────────────
    //  بداية المباراة
    // ──────────────────────────────────────────────────

    /**
     * @param team1 اللاعبون في الفريق 1 (شامل القائد)
     * @param team2 اللاعبون في الفريق 2 (شامل القائد)
     * @param arena الأرينا المختارة
     */
    public void startPartyDuel(List<Player> team1, List<Player> team2, Arena arena) {
        startPartyDuel(team1, team2, arena, false);
    }

    /**
     * @param arenaSpawns true = كل تيم يرسبن في نقطة الـ spawn حقته (نفس نقاط 1v1: التيم 1 = P1، التيم 2 = P2)،
     *                    false = رسبن عشوائي داخل البوردر (دول بارتي ضد بارتي القديم)
     */
    public void startPartyDuel(List<Player> team1, List<Player> team2, Arena arena, boolean arenaSpawns) {
        startPartyDuel(team1, team2, arena, arenaSpawns, null);
    }

    /** map chosen by the leader if it belongs to the arena, otherwise a random map of the arena */
    private String pickWorld(Arena arena, String mapWorld) {
        return (mapWorld != null && arena.getWorlds().contains(mapWorld)) ? mapWorld : arena.getRandomWorld();
    }

    /** mapWorld = ماب القائد المختارة (null = عشوائي) */
    public void startPartyDuel(List<Player> team1, List<Player> team2, Arena arena, boolean arenaSpawns, String mapWorld) {
        // أرينا عشوائية (السهم) → تنكشف لهم بعد النقل فقط
        if (com.duel.utils.RandomArena.is(mapWorld)) {
            for (Player p : mergeTeams(team1, team2)) plugin.getDuelManager().markRandom(p);
        }
        String templateWorld = pickWorld(arena, mapWorld);
        if (templateWorld == null) {
            broadcastToAll(team1, team2, color("&cNo world is set up for this arena!"));
            return;
        }

        // تأكد أن لا أحد في دول أخرى
        for (Player p : mergeTeams(team1, team2)) {
            if (plugin.getDuelManager().isInDuel(p) || isInPartyDuel(p)) {
                broadcastToAll(team1, team2, color("&c" + p.getName() + " is already in a duel!"));
                return;
            }
        }

        String copyName = plugin.getConfig().getString("settings.arena-copy-prefix", "duel_arena_")
                + arena.getId() + "_party_" + System.currentTimeMillis();

        broadcastToAll(team1, team2, color("&a⚔ Match found! Preparing the arena..."));

        // تجميد الحركة
        for (Player p : mergeTeams(team1, team2)) {
            p.setWalkSpeed(0f);
            p.setFlySpeed(0f);
        }

        final String finalTemplate = templateWorld;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            final String worldName = plugin.getWorldPoolManager().acquire(finalTemplate, copyName);
            waitForWorld(worldName,
                    () -> preloadParty(arena, finalTemplate, worldName, !arenaSpawns,
                            () -> onWorldReady(team1, team2, arena, finalTemplate, worldName, arenaSpawns)),
                    () -> abortStart(team1, team2, "&c[Party] Failed to prepare the arena. Match cancelled."));
        });
    }

    /** يحمّل شنكات الأرينا async قبل الـ teleport (wholeBorder = رسبن عشوائي/FFA → كل البوردر). */
    private void preloadParty(Arena arena, String templateWorld, String worldName, boolean wholeBorder, Runnable next) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) { next.run(); return; }
        DuelManager dm = plugin.getDuelManager();
        List<Location> seeds = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Location l = dm.getSpawn(arena, templateWorld, w, i);
            if (l != null) seeds.add(l);
        }
        plugin.getChunkPreloader().preload(w, seeds, wholeBorder, () -> {
            next.run();
            plugin.getChunkPreloader().releaseLater(worldName);
        });
    }

    /** انتظر حتى يتحمّل الورلد ثم اشغّل الكولباك */
    private void waitForWorld(String worldName, Runnable callback) {
        waitForWorld(worldName, callback, null);
    }

    private void waitForWorld(String worldName, Runnable callback, Runnable onFail) {
        if (Bukkit.getWorld(worldName) != null) {
            callback.run();
            return;
        }
        final int[] attempts = {0};
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            attempts[0]++;
            World w = Bukkit.getWorld(worldName);
            if (w != null) {
                task.cancel();
                callback.run();
            } else if (attempts[0] > 200) {
                task.cancel();
                plugin.getLogger().warning("[PartyDuel] World copy timed out: " + worldName);
                if (onFail != null) onFail.run();
            }
        }, 10L, 10L);
    }

    /** يلغي بداية الماتش قبل ما تنشأ الجلسة: يفك التجميد عن اللاعبين ويبلغهم */
    private void abortStart(List<Player> t1, List<Player> t2, String msg) {
        for (Player p : mergeTeams(t1, t2)) {
            if (!p.isOnline()) continue;
            p.setWalkSpeed(0.2f);
            p.setFlySpeed(0.1f);
            p.setInvulnerable(false);
            p.sendMessage(color(msg));
        }
    }

    private void onWorldReady(List<Player> team1In, List<Player> team2In, Arena arena,
                              String templateWorld, String copyName, boolean arenaSpawns) {
        World world = Bukkit.getWorld(copyName);
        if (world == null) {
            abortStart(team1In, team2In, "&c[Party] Failed to prepare the arena. Match cancelled.");
            return;
        }

        // FIX: لاعب طلع أثناء تحميل الأرينا → نشيله (يُعتبر خاسر) ونكمّل بالباقين.
        // لو تيم كامل طلع → نلغي الماتش (ما بدأ أحد يلعب).
        List<Player> team1 = new ArrayList<>();
        List<Player> team2 = new ArrayList<>();
        for (Player p : team1In) if (p.isOnline()) team1.add(p);
        for (Player p : team2In) if (p.isOnline()) team2.add(p);
        int left = (team1In.size() - team1.size()) + (team2In.size() - team2.size());
        if (team1.isEmpty() || team2.isEmpty()) {
            abortStart(team1, team2, "&c[Party] A whole team disconnected — match cancelled.");
            plugin.getArenaManager().deleteWorld(copyName);
            return;
        }
        if (left > 0) {
            broadcastToAll(team1, team2, color("&c" + left + " player(s) disconnected before the match started."));
        }
        plugin.getArenaManager().tuneWorld(world);
        applyArenaSettings(world, arena);
        try {
            Location ref = plugin.getDuelManager().getSpawn(arena, templateWorld, world, 0);
            if (ref != null) {
                plugin.getArenaManager().applyBarrierBorder(world, ref.getBlockY());
                plugin.getArenaManager().autoPrepareTemplate(templateWorld, ref.getBlockY());
            }
        } catch (Throwable ignored) {}

        // إنشاء الجلسة
        PartyDuelSession session = new PartyDuelSession(team1, team2, arena, copyName);

        // تسجيل في الخرائط
        sessions.put(session.getSessionId(), session);
        for (Player p : mergeTeams(team1, team2)) {
            playerSession.put(p.getUniqueId(), session);
        }

        if (arenaSpawns) {
            // ── ماتش تيمات: كل تيم في spawn مختلف — نفس نقاط 1v1 (التيم 1 = P1، التيم 2 = P2) ──
            DuelManager dm = plugin.getDuelManager();
            Location spawnBlue = dm.getSpawn(arena, templateWorld, world, 0);
            Location spawnRed  = dm.getSpawn(arena, templateWorld, world, 1);
            teleportTeam(team1, spawnBlue);
            teleportTeam(team2, spawnRed);
        } else {
            // رسبن عشوائي داخل البوردر لكل لاعب — بنفس ارتفاع نقاط 1v1
            List<Location> usedSpawns = new ArrayList<>();
            for (Player p : mergeTeams(team1, team2)) {
                Location randomLoc = getArenaLevelSpawn(world, arena, templateWorld, usedSpawns);
                usedSpawns.add(randomLoc);
                p.teleport(randomLoc);
                p.setInvulnerable(true);
            }
        }

        // تطبيق الكيت
        String kitName = arena.getKitName() != null ? arena.getKitName() : arena.getId();
        Kit defaultKit = plugin.getKitManager().getKit(kitName);
        for (Player p : mergeTeams(team1, team2)) {
            applyPersonalOrDefault(p, arena.getId(), defaultKit);
            arena.applyPotions(p); // بوشنات الأرينا فور النقل
        }

        // Force all players to see each other, even if "Hide Players" is enabled
        List<Player> allPlayers = mergeTeams(team1, team2);
        for (Player viewer : allPlayers) {
            for (Player target : allPlayers) {
                if (!viewer.equals(target)) {
                    viewer.showPlayer(plugin, target);
                }
            }
        }

        // ── تمييز الفريقين بألوان TAB ──
        applyTeamColors(session, team1, team2);

        // ── عزل الـ TAB — يشوفون بس بعض مو كل السيرفر ──
        plugin.getTabIsolationManager().isolate(allPlayers);

        // ── سكوربورد البارتي (بعد ألوان التيمات عشان يحافظ عليها) ──
        plugin.getPartyScoreboardManager().start(session, allPlayers);

        // كشف الأرينا العشوائية بعد النقل
        for (Player p : allPlayers) plugin.getDuelManager().revealRandomArena(p, arena);

        // عدّ تنازلي ثم ابدأ
        startCountdown(session, allPlayers);
    }

    // ──────────────────────────────────────────────────
    //  ماتش التيمات (Party FFA → Team)
    // ──────────────────────────────────────────────────

    /**
     * نقطة الدخول الوحيدة لزر/أمر FFA في البارتي:
     * لو القائد مسوّي تيمات → ماتش تيمات، وإلا FFA عادي.
     */
    public void startPartyMatch(com.duel.party.Party party, Arena arena) {
        if (party.hasTeams()) startPartyTeams(party, arena);
        else startPartyFFA(party.getOnlineMembers(), arena);
    }

    /** يبدأ ماتش تيمات (الأزرق = فوق، الأحمر = تحت) بالترتيب المحفوظ */
    public void startPartyTeams(com.duel.party.Party party, Arena arena) {
        startPartyTeams(party, arena, null);
    }

    public void startPartyTeams(com.duel.party.Party party, Arena arena, String mapWorld) {
        Player leader = party.getLeader();

        // كل أعضاء البارتي لازم يكونون موزّعين على تيم (ما دخل أحد جديد بعد الحفظ)
        for (UUID u : party.getMembers()) {
            if (!party.getTeamTop().contains(u) && !party.getTeamBottom().contains(u)) {
                leader.sendMessage(color("&c[Party] The teams are outdated (a member joined after saving). "
                        + "Use &eParty FFA → Team &cto update them or &e/team disband&c."));
                return;
            }
        }

        List<Player> blue = new ArrayList<>();
        List<Player> red  = new ArrayList<>();
        for (UUID u : party.getTeamTop()) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) { leader.sendMessage(color("&c[Party] A Blue Team member is offline. Wait for them or use &eParty FFA → Team&c.")); return; }
            blue.add(p);
        }
        for (UUID u : party.getTeamBottom()) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) { leader.sendMessage(color("&c[Party] A Red Team member is offline. Wait for them or use &eParty FFA → Team&c.")); return; }
            red.add(p);
        }

        int min = com.duel.gui.PartyTeamGUI.MIN_TEAM;
        if (blue.size() < min || red.size() < min) {
            leader.sendMessage(color("&c[Party] Each team needs at least " + min + " players. Use &eParty FFA → Team &cto fix the teams."));
            return;
        }
        if (blue.size() + red.size() > com.duel.gui.PartyTeamGUI.MAX_PARTY) {
            leader.sendMessage(color("&c[Party] Teams support at most " + com.duel.gui.PartyTeamGUI.MAX_PARTY + " players."));
            return;
        }

        for (Player p : blue) p.sendMessage(color("&6[Party] &7Team match! You are on the &9Blue Team&7."));
        for (Player p : red)  p.sendMessage(color("&6[Party] &7Team match! You are on the &cRed Team&7."));

        startPartyDuel(blue, red, arena, true, mapWorld);
    }

    /** يوزّع لاعبي التيم حول نقطة الـ spawn (شبكة صغيرة) عشان ما يتكدسون في نفس البلوك */
    private void teleportTeam(List<Player> team, Location base) {
        int n = team.size();
        int cols = (int) Math.ceil(Math.sqrt(n));
        int rows = (int) Math.ceil(n / (double) cols);
        double step = 1.2;

        for (int i = 0; i < n; i++) {
            Player p = team.get(i);
            Location target = base.clone();
            if (n > 1) {
                int c = i % cols, r = i / cols;
                target.add((c - (cols - 1) / 2.0) * step, 0, (r - (rows - 1) / 2.0) * step);
                // لو الخانة داخل بلوك (جدار/زاوية) نرجع لنقطة الـ spawn الأصلية
                if (!target.getBlock().isPassable() || !target.clone().add(0, 1, 0).getBlock().isPassable()) {
                    target = base.clone();
                }
            }
            p.teleport(target);
            p.setInvulnerable(true);
        }
    }

    // ──────────────────────────────────────────────────
    //  رسبن عشوائي داخل البوردر
    // ──────────────────────────────────────────────────

    /**
     * رسبن عشوائي بنفس ارتفاع نقاط الـ spawn حق 1v1:
     * X/Z عشوائي داخل البوردر، و Y يُلتقط من أقرب أرضية صالحة لارتفاع نقاط الـ spawn
     * (بدل أعلى بلوك في العمود اللي يطلّع اللاعب فوق السقف/الشجر أو يدخّله مغارة).
     * لو ما لقى مكان صالح يرجع لإحدى نقاط الـ spawn الأصلية.
     */
    private Location getArenaLevelSpawn(World world, Arena arena, String templateWorld, List<Location> taken) {
        // نقاط الـ spawn الأصلية (نفس اللي يستخدمها 1v1)
        List<Location> refs = new ArrayList<>();
        DuelManager dm = plugin.getDuelManager();
        for (int i = 0; i < 2; i++) {
            Location l = dm.getSpawn(arena, templateWorld, world, i);
            if (l != null) refs.add(l);
        }
        if (refs.isEmpty()) return getRandomLocationInBorder(world);

        double refY = 0;
        for (Location l : refs) refY += l.getY();
        refY /= refs.size();
        int baseY = (int) Math.floor(refY);

        double[] area = plugin.getArenaManager().getPlayArea(world);
        double half = area[2];
        double cx = area[0];
        double cz = area[1];
        double margin = Math.min(10.0, half * 0.05);
        double range = Math.max(1.0, half - margin);

        final int vRange = Math.max(0, plugin.getConfig().getInt("party-respawn.vertical-range", 0)); // 0 = نفس Y نقاط 1v1 بالضبط
        final double minDist = 6.0; // أقل مسافة بين لاعبين عند الرسبن
        Random rng = new Random();

        // ── ماب دائري؟ (البوردر في ماين كرافت مربع دائماً، فنحدد الدائرة من الكونفق) ──
        boolean circular = false;
        double circX = cx, circZ = cz, circR = range;
        org.bukkit.configuration.ConfigurationSection circles =
                plugin.getConfig().getConfigurationSection("party-respawn.circles");
        if (circles != null && templateWorld != null) {
            for (String key : circles.getKeys(false)) {
                if (!key.equalsIgnoreCase(templateWorld)) continue;
                circular = true;
                org.bukkit.configuration.ConfigurationSection c = circles.getConfigurationSection(key);
                if (c != null) {
                    circX = c.getDouble("x", cx);
                    circZ = c.getDouble("z", cz);
                    circR = c.getDouble("radius", range);
                }
                break;
            }
        }
        // هامش أمان من الحافة الدائرية
        circR = Math.max(1.0, circR - 2.0);

        // ── ماب زجاج؟ (الرسبن لازم يكون داخل الزجاج المحيط، مو برا) ──
        boolean glassMode = false;
        for (String gw : plugin.getConfig().getStringList("party-respawn.glass-worlds")) {
            if (gw == null || templateWorld == null) continue;
            String entry = gw.trim();
            // صيغة "Arena:world" = الماب هذي في أرينا معينة فقط، أو "world" = في كل الأرينات
            int colon = entry.indexOf(':');
            if (colon > 0) {
                String entryArena = entry.substring(0, colon).trim();
                String entryWorld = entry.substring(colon + 1).trim();
                if (entryArena.equalsIgnoreCase(arena.getId()) && entryWorld.equalsIgnoreCase(templateWorld)) {
                    glassMode = true;
                    break;
                }
            } else if (entry.equalsIgnoreCase(templateWorld)) {
                glassMode = true;
                break;
            }
        }
        final int maxAttempts = glassMode ? 250 : 80;
        final int tooCloseLimit = glassMode ? 180 : 60;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            double x, z;
            if (circular) {
                // توزيع منتظم داخل القرص (sqrt عشان ما يتجمعون في المركز)
                double ang = rng.nextDouble() * Math.PI * 2;
                double rad = Math.sqrt(rng.nextDouble()) * circR;
                x = circX + Math.cos(ang) * rad;
                z = circZ + Math.sin(ang) * rad;
            } else {
                x = cx + (rng.nextDouble() * 2 - 1) * range;
                z = cz + (rng.nextDouble() * 2 - 1) * range;
            }
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);

            // نفتش عن أقرب أرضية صالحة لارتفاع الـ spawn
            Location best = null;
            for (int d = 0; d <= vRange && best == null; d++) {
                for (int sign : (d == 0 ? new int[]{1} : new int[]{1, -1})) {
                    int y = baseY + d * sign;
                    if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 2) continue;
                    if (isSafeStandingSpot(world, bx, y, bz)) {
                        best = new Location(world, bx + 0.5, y, bz + 0.5, rng.nextFloat() * 360f, 0f);
                        break;
                    }
                }
            }
            if (best == null) continue;

            // ماب زجاج: لازم يكون الزجاج حواليه من كل الاتجاهات
            if (glassMode && !isEnclosedByGlass(world, best, cx, cz, half)) continue;

            // ما نبي لاعبين فوق بعض
            boolean tooClose = false;
            for (Location t : taken) {
                if (t.getWorld() == world && t.distanceSquared(best) < minDist * minDist) { tooClose = true; break; }
            }
            if (tooClose && attempt < tooCloseLimit) continue;
            return best;
        }

        // fallback: إحدى نقاط الـ spawn الأصلية (نفس ارتفاع 1v1 بالضبط)
        Location ref = refs.get(rng.nextInt(refs.size())).clone();
        ref.add((rng.nextDouble() - 0.5) * 2.0, 0, (rng.nextDouble() - 0.5) * 2.0);
        if (!ref.getBlock().isPassable() || !ref.clone().add(0, 1, 0).getBlock().isPassable()) {
            ref = refs.get(0).clone();
        }
        return ref;
    }

    /** هل المادة أي نوع زجاج (عادي/ملوّن/شفاف/ألواح) */
    private boolean isGlassMaterial(Material m) {
        String n = m.name();
        return n.equals("GLASS") || n.equals("TINTED_GLASS")
                || n.endsWith("_STAINED_GLASS") || n.equals("GLASS_PANE")
                || n.endsWith("_STAINED_GLASS_PANE");
    }

    /**
     * يتأكد إن اللاعب داخل الزجاج: يرمي 8 اتجاهات أفقية، وكل اتجاه لازم يلقى زجاج
     * (أي نوع) قبل ما يطلع من حدود البوردر. لو اتجاه واحد ما لقى زجاج = برا الزجاج.
     */
    private boolean isEnclosedByGlass(World world, Location loc, double cx, double cz, double half) {
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        int maxDist = (int) Math.min(Math.ceil(half * 1.5) + 4, 400);
        double[][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{0.7071,0.7071},{0.7071,-0.7071},{-0.7071,0.7071},{-0.7071,-0.7071}};
        for (double[] d : dirs) {
            boolean hit = false;
            int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
            for (int i = 1; i <= maxDist; i++) {
                int x = (int) Math.floor(loc.getX() + d[0] * i);
                int z = (int) Math.floor(loc.getZ() + d[1] * i);
                if (x == lastX && z == lastZ) continue;
                lastX = x; lastZ = z;
                if (Math.abs(x + 0.5 - cx) > half || Math.abs(z + 0.5 - cz) > half) break;
                for (int y = by; y <= by + 2; y++) {
                    if (isGlassMaterial(world.getBlockAt(x, y, z).getType())) { hit = true; break; }
                }
                if (hit) break;
            }
            if (!hit) return false;
        }
        return true;
    }

    /** أرضية صلبة آمنة تحت رجل اللاعب + مساحة فاضية لجسمه (بدون ماء/لافا/نار/صبار) */
    private boolean isSafeStandingSpot(World world, int x, int y, int z) {
        org.bukkit.block.Block feet  = world.getBlockAt(x, y, z);
        org.bukkit.block.Block head  = world.getBlockAt(x, y + 1, z);
        org.bukkit.block.Block floor = world.getBlockAt(x, y - 1, z);
        if (!feet.isPassable() || !head.isPassable()) return false;
        if (feet.isLiquid() || head.isLiquid()) return false;
        if (!floor.getType().isSolid()) return false;
        Material fm = floor.getType();
        if (fm == Material.MAGMA_BLOCK || fm == Material.CACTUS || fm == Material.CAMPFIRE
                || fm == Material.SOUL_CAMPFIRE || fm == Material.SWEET_BERRY_BUSH
                || fm == Material.POINTED_DRIPSTONE) return false;
        Material m = feet.getType();
        return m != Material.FIRE && m != Material.SOUL_FIRE && m != Material.COBWEB
                && m != Material.SWEET_BERRY_BUSH && m != Material.WITHER_ROSE;
    }

    /**
     * يختار موقع عشوائي داخل حدود البوردر (WorldBorder).
     * يحاول يلاقي سطح صالح (ليس هواء ولا تحت الأرض).
     */
    private Location getRandomLocationInBorder(World world) {
        double[] area = plugin.getArenaManager().getPlayArea(world);
        double size   = area[2];
        double centerX = area[0];
        double centerZ = area[1];

        // اترك هامش 10 بلوك من الحواف
        double margin = Math.min(10.0, size * 0.05);
        double range  = size - margin;

        Random rng = new Random();

        for (int attempt = 0; attempt < 30; attempt++) {
            double x = centerX + (rng.nextDouble() * 2 - 1) * range;
            double z = centerZ + (rng.nextDouble() * 2 - 1) * range;

            // ابحث عن أعلى بلوك صلب
            int highestY = world.getHighestBlockYAt((int) x, (int) z);
            Location loc = new Location(world, x + 0.5, highestY + 1.0, z + 0.5);

            // تحقق أن البلوك تحته صلب
            if (loc.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
                return loc;
            }
        }

        // fallback: مركز البوردر + Y عالي
        return new Location(world,
                centerX, world.getHighestBlockYAt((int) centerX, (int) centerZ) + 1,
                centerZ);
    }

    // ──────────────────────────────────────────────────
    //  العدّ التنازلي
    // ──────────────────────────────────────────────────

    private void startCountdown(PartyDuelSession session, List<Player> players) {
        int seconds = plugin.getConfig().getInt("settings.countdown-seconds", 5);
        AtomicInteger countdown = new AtomicInteger(seconds);

        BukkitTask task = new org.bukkit.scheduler.BukkitRunnable() { @Override public void run() {
            if (session.getState() == PartyDuelSession.State.ENDED) { cancel(); return; }
            int current = countdown.getAndDecrement();
            if (current <= 0) return;
            for (Player p : players) {
                if (!p.isOnline()) continue;
                TitleUtils.sendCountdown(p, current);
                SoundUtils.playCountdown(p);
            }
        } }.runTaskTimer(plugin, 0L, 20L);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            task.cancel();
            // لو الجلسة انتهت أثناء العدّ لا نبدأ شي
            if (session.getState() == PartyDuelSession.State.ENDED) return;

            // الحالة أولاً (تسمح بالضرب)، وبعدها فك التجميد و GO — كل لاعب بحماية try/catch
            session.setState(PartyDuelSession.State.FIGHTING);
            session.markStarted();

            for (Player p : players) {
                try {
                    p.setInvulnerable(false);
                    p.setWalkSpeed(0.2f);
                    p.setFlySpeed(0.1f);
                } catch (Throwable t) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "[PartyDuel] Unfreeze failed", t);
                }
            }
            for (Player p : players) {
                try {
                    if (p.isOnline()) { TitleUtils.sendGo(p); SoundUtils.playGo(p); }
                } catch (Throwable t) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "[PartyDuel] GO failed", t);
                }
            }
        }, (long) seconds * 20L);
    }

    // ──────────────────────────────────────────────────
    //  موت لاعب → spectator
    // ──────────────────────────────────────────────────

    /**
     * يُستدعى من PartyDuelListener عند موت لاعب.
     * @return true إذا انتهت المباراة
     */
    public boolean onPlayerDeath(Player dead) {
        PartyDuelSession session = playerSession.get(dead.getUniqueId());
        if (session == null || session.getState() != PartyDuelSession.State.FIGHTING) return false;

        // أوقف الدروب ورسالة الموت
        // (تُعالَج في الـ Listener)

        // تحديث حالة الجلسة
        int wonTeam = session.registerDeath(dead);

        // اللاعب يصير spectator
        makeSpectator(dead, session);

        // إخبار الكل بالموت
        int teamNum = session.getTeamOf(dead);
        String msg = color("&c☠ &e" + dead.getName() + " &chas been eliminated from the match!");
        broadcastSession(session, msg);

        // إذا انتهت المباراة
        if (wonTeam != -1) {
            endPartyDuel(session);
            return true;
        }

        // أعلن كم باقي من كل فريق
        broadcastAliveCount(session);

        return false;
    }

    /** يعلن الأحياء: FFA → عدد واحد، تيمات → أزرق/أحمر */
    private void broadcastAliveCount(PartyDuelSession session) {
        if (session.isFFA()) {
            long alive = session.getTeam1().stream().filter(session::isAlive).count();
            broadcastSession(session, color("&7Players alive: &a" + alive));
            return;
        }
        long blue = session.getTeam1().stream().filter(session::isAlive).count();
        long red  = session.getTeam2().stream().filter(session::isAlive).count();
        broadcastSession(session, color("&9Blue: &a" + blue + " &7| &cRed: &a" + red));
    }

    /** إصلاح 4: makeSpectator — يحوّل اللاعب spectator ويُنقله للسبون لو المباراة انتهت */
    private void makeSpectator(Player p, PartyDuelSession session) {
        p.getInventory().clear();
        // متفرج طائر (سيرفايفل + بوصلة) بدل gamemode spectator — بعد الريسباون بـ 3 ticks
        plugin.getFlySpectatorManager().scheduleEnter(p, 3L);

        // انتظر config ثوان — لو المباراة لا تزال جارية يبقى spectator، لو انتهت ننقله
        int delay = plugin.getConfig().getInt("settings.loser-spectator-seconds", 5);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (session.getState() == PartyDuelSession.State.ENDED) {
                // المباراة انتهت بالفعل — تُعالَج في endPartyDuel
                return;
            }
            // المباراة لا تزال جارية — اللاعب يبقى spectator في نفس العالم
            // لا نفعل شيئاً — endPartyDuel ستنقله لاحقاً
        }, (long) delay * 20L);
    }

    // ──────────────────────────────────────────────────
    //  نهاية المباراة
    // ──────────────────────────────────────────────────

    private void endPartyDuel(PartyDuelSession session) {
        // FIX: registerDeath() يحوّل الحالة إلى ENDED بنفسه قبل ما نوصل هنا، فالحارس القديم
        // (state == ENDED → return) كان يطلع من الدالة فوراً: ما فيه فوز/خسارة، ما فيه نقل للسبون،
        // ولا تنحذف الجلسة من playerSession — فيظل اللاعب "داخل party duel" للأبد.
        if (!endingSessions.add(session.getSessionId())) return;
        session.setState(PartyDuelSession.State.ENDED);

        // طرد المتفرجين من عالم المباراة قبل حذفه
        plugin.getSpectateCommand().ejectSpectators(session.getCopiedWorldName());

        // إيقاف سكوربورد البارتي (يرجّع الـ main scoreboard)
        plugin.getPartyScoreboardManager().stop(session);

        // إزالة ألوان الفريقين من TAB + استرجاع الـ TAB الكامل
        removeTeamColors(session);
        plugin.getTabIsolationManager().restoreAll(session.getAllPlayers());

        List<Player> winners = session.getWinningTeam();
        List<Player> losers  = session.getLosingTeam();
        Player mvp           = session.getWinner(); // أول لاعب حي من الفريق الرابح

        // رسائل الفوز
        String winMsg  = color("&6&l⚔ Your team won! &e&l🏆");
        String loseMsg = color("&c&l⚔ Your team lost!");

        for (Player p : session.getAllPlayers()) {
            if (!p.isOnline()) continue;
            boolean isWinner = winners.stream().anyMatch(w -> w.getUniqueId().equals(p.getUniqueId()));

            if (isWinner) {
                TitleUtils.sendWinner(p);
                SoundUtils.playWinner(p);
                p.sendMessage(winMsg);
            } else {
                TitleUtils.sendLoser(p);
                SoundUtils.playLoser(p);
                p.sendMessage(loseMsg);
            }
        }

        int delay = plugin.getConfig().getInt("settings.loser-spectator-seconds", 5);

        // بعد الـ delay — نقل الكل للسبون
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // FIX: لازم نحرر اللاعبين من playerSession "قبل" ما نعطيهم الهوتبار.
            // PartyManager.refreshHotbar() يتجاهل أي لاعب isInPartyDuel == true،
            // وكان الحذف يصير بعد 20 tick بينما giveMenuSword يشتغل بعد 5 ticks
            // → فكان يبقى الهوتبار العادي بدون أدوات البارتي.
            for (Player p : session.getAllPlayers()) {
                playerSession.remove(p.getUniqueId(), session);
            }
            for (Player p : session.getAllPlayers()) {
                if (!p.isOnline() || session.hasLeftEarly(p)) continue;
                p.getInventory().clear();
                plugin.getFlySpectatorManager().exit(p);
                p.setGameMode(GameMode.SURVIVAL);
                plugin.getArenaManager().clearArenaPotions(p);
                p.teleport(getServerSpawn());
                giveMenuSword(p);
                // Re-apply hide players setting now they're back on the main world
                plugin.getSettingsManager().applyEffect(p,
                        com.duel.managers.SettingsManager.Setting.HIDE_PLAYERS,
                        plugin.getSettingsManager().isEnabled(p.getUniqueId(),
                                com.duel.managers.SettingsManager.Setting.HIDE_PLAYERS));
            }

            // تنظيف الجلسة وحذف العالم
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                sessions.remove(session.getSessionId());
                ffaSessions.remove(session.getSessionId());
                endingSessions.remove(session.getSessionId());
                for (Player p : session.getAllPlayers()) {
                    playerSession.remove(p.getUniqueId(), session); // نفس الجلسة فقط
                }
                plugin.getArenaManager().deleteWorld(session.getCopiedWorldName());
            }, 20L);

        }, (long) delay * 20L);
    }

    // ──────────────────────────────────────────────────
    //  تنظيف عام
    // ──────────────────────────────────────────────────

    /**
     * يُستدعى عند disconnect لاعب في Party Duel أو Party FFA.
     * يعامله كميت — لو بقي لاعب واحد تنتهي المباراة.
     */
    public void onPlayerDisconnect(Player player) {
        // الخروج من السيرفر = إقصاء + تنظيف كامل (ما يبقى اللاعب "داخل جلسة" بعد ما يرجع)
        eliminateAndRelease(player, "disconnected");
    }

    /**
     * لاعب طلع من الماتش (disconnect / /party leave / kick) →
     * يُقصى لو كان حي (وتنتهي المباراة لو انتهت شروطها) ثم يُحرَّر ويرجع للسبون.
     */
    public void eliminateAndRelease(Player player, String reason) {
        PartyDuelSession session = playerSession.get(player.getUniqueId());
        if (session == null) return;

        if (session.getState() != PartyDuelSession.State.ENDED && session.isAlive(player)) {
            broadcastSession(session, color("&c☠ &e" + player.getName() + " &c" + reason + " and was eliminated!"));
            int wonTeam = session.registerDeath(player);
            if (wonTeam != -1) endPartyDuel(session);
            else broadcastAliveCount(session);
        }
        releasePlayer(player, session);
    }

    /** يحرّر لاعب من أي جلسة بارتي (حتى لو الجلسة منتهية/عالقة) ويرجعه للسبون — يُستعمل من /leave والـ join */
    public void forceRelease(Player player) {
        PartyDuelSession session = playerSession.get(player.getUniqueId());
        if (session == null) return;
        releasePlayer(player, session);
    }

    private void releasePlayer(Player p, PartyDuelSession session) {
        session.markLeftEarly(p.getUniqueId());
        playerSession.remove(p.getUniqueId(), session);
        try {
            plugin.getTabIsolationManager().restoreAll(new ArrayList<>(List.of(p)));
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[PartyDuel] release (tab/scoreboard) failed", t);
        }
        resetAndSendToSpawn(p);
    }

    /** يرجّع حالة اللاعب الطبيعية وينقله للسبون (آمن حتى لو اللاعب طالع من السيرفر الحين) */
    private void resetAndSendToSpawn(Player p) {
        try {
            p.setWalkSpeed(0.2f);
            p.setFlySpeed(0.1f);
            p.setInvulnerable(false);
            p.getInventory().clear();
            plugin.getFlySpectatorManager().exit(p);
            p.setGameMode(GameMode.SURVIVAL);
            plugin.getArenaManager().clearArenaPotions(p);
                p.teleport(getServerSpawn());
            giveMenuSword(p);
            plugin.getSettingsManager().applyEffect(p,
                    com.duel.managers.SettingsManager.Setting.HIDE_PLAYERS,
                    plugin.getSettingsManager().isEnabled(p.getUniqueId(),
                            com.duel.managers.SettingsManager.Setting.HIDE_PLAYERS));
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[PartyDuel] reset to spawn failed for " + p.getName(), t);
        }
    }

    /** true لو هذا اللاعب (بالـ UUID) داخل جلسة بارتي */
    public boolean isInPartyDuel(UUID uuid) {
        return playerSession.containsKey(uuid);
    }

    /**
     * القائد طلع (من الماتش/البارتي/السيرفر) → ننهي الجلسة كاملة:
     * الكل يرجع للسبون وتنحذف الجلسة والأرينا.
     */
    public void abortMatchesOf(Collection<UUID> partyMembers, String message) {
        Set<PartyDuelSession> found = new LinkedHashSet<>();
        for (UUID u : partyMembers) {
            PartyDuelSession s = playerSession.get(u);
            if (s != null) found.add(s);
        }
        for (PartyDuelSession s : found) abortSession(s, message);
    }

    private void abortSession(PartyDuelSession session, String message) {
        if (!endingSessions.add(session.getSessionId())) return; // المباراة تنتهي أصلاً بشكل طبيعي
        session.setState(PartyDuelSession.State.ENDED);

        plugin.getPartyScoreboardManager().stop(session);
        removeTeamColors(session);
        plugin.getSpectateCommand().ejectSpectators(session.getCopiedWorldName());

        List<Player> all = session.getAllPlayers();
        plugin.getTabIsolationManager().restoreAll(all);

        for (Player p : all) {
            session.markLeftEarly(p.getUniqueId());
            playerSession.remove(p.getUniqueId(), session);
            if (!p.isOnline()) continue;
            p.sendMessage(color(message));
            try { p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard()); } catch (Throwable ignored) {}
            resetAndSendToSpawn(p);
        }

        sessions.remove(session.getSessionId());
        ffaSessions.remove(session.getSessionId());

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            endingSessions.remove(session.getSessionId());
            plugin.getArenaManager().deleteWorld(session.getCopiedWorldName());
        }, 20L);
    }

    /**
     * الميت (متفرج داخل المباراة) يطلع للسبون بدري — يشتغل من /spectate و /leave.
     * @return true لو اللاعب ميت داخل مباراة بارتي وانقل للسبون
     */
    public boolean leaveAsDeadSpectator(Player p) {
        PartyDuelSession session = playerSession.get(p.getUniqueId());
        if (session == null || session.isAlive(p)) return false;

        session.markLeftEarly(p.getUniqueId());
        playerSession.remove(p.getUniqueId(), session);

        plugin.getTabIsolationManager().restoreAll(new ArrayList<>(List.of(p)));
        p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        p.setWalkSpeed(0.2f);
        p.setFlySpeed(0.1f);
        p.setInvulnerable(false);
        p.getInventory().clear();
        plugin.getFlySpectatorManager().exit(p);
        p.setGameMode(GameMode.SURVIVAL);
        plugin.getArenaManager().clearArenaPotions(p);
                p.teleport(getServerSpawn());
        giveMenuSword(p);
        plugin.getSettingsManager().applyEffect(p,
                com.duel.managers.SettingsManager.Setting.HIDE_PLAYERS,
                plugin.getSettingsManager().isEnabled(p.getUniqueId(),
                        com.duel.managers.SettingsManager.Setting.HIDE_PLAYERS));
        return true;
    }

    private void returnAllToSpawn(PartyDuelSession session) {
        plugin.getSpectateCommand().ejectSpectators(session.getCopiedWorldName());
        int delay = plugin.getConfig().getInt("settings.loser-spectator-seconds", 3);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (Player p : session.getAllPlayers()) {
                if (!p.isOnline() || session.hasLeftEarly(p)) continue;
                p.setWalkSpeed(0.2f);
                p.setFlySpeed(0.1f);
                p.setInvulnerable(false);
                p.getInventory().clear();
                plugin.getFlySpectatorManager().exit(p);
                p.setGameMode(org.bukkit.GameMode.SURVIVAL);
                plugin.getArenaManager().clearArenaPotions(p);
                p.teleport(getServerSpawn());
                giveMenuSword(p);
            }
            sessions.remove(session.getSessionId());
            ffaSessions.remove(session.getSessionId());
            endingSessions.remove(session.getSessionId());
            for (Player p : session.getAllPlayers()) playerSession.remove(p.getUniqueId(), session);
            plugin.getTabIsolationManager().restoreAll(session.getAllPlayers());
            plugin.getArenaManager().deleteWorld(session.getCopiedWorldName());
        }, delay * 20L);
    }

    public java.util.Collection<PartyDuelSession> getAllActiveSessions() {
        return sessions.values().stream()
                .filter(s -> s.getState() == PartyDuelSession.State.FIGHTING)
                .toList();
    }

    public void cleanupAll() {
        plugin.getPartyScoreboardManager().stopAll();
        for (PartyDuelSession s : new ArrayList<>(sessions.values())) {
            plugin.getArenaManager().deleteWorld(s.getCopiedWorldName());
        }
        sessions.clear();
        playerSession.clear();
        ffaSessions.clear();
        endingSessions.clear();
    }

    // ──────────────────────────────────────────────────
    //  Helpers / Getters
    // ──────────────────────────────────────────────────


    /** تطبيق إعدادات الأرينا (تكسير/صعوبة) على العالم المنسوخ — نفس اللي يسويه DuelManager في 1v1 */
    private void applyArenaSettings(World world, Arena arena) {
        if (world == null || arena == null) return;
        world.setGameRule(org.bukkit.GameRule.DO_TILE_DROPS, arena.isAllowBreaking());
        org.bukkit.Difficulty diff = switch (arena.getDifficulty()) {
            case "easy" -> org.bukkit.Difficulty.EASY;
            case "hard" -> org.bukkit.Difficulty.HARD;
            default     -> org.bukkit.Difficulty.NORMAL;
        };
        world.setDifficulty(diff);
    }

    public boolean isInPartyDuel(Player p) {
        return playerSession.containsKey(p.getUniqueId());
    }

    public PartyDuelSession getSession(Player p) {
        return playerSession.get(p.getUniqueId());
    }

    private List<Player> mergeTeams(List<Player> t1, List<Player> t2) {
        List<Player> all = new ArrayList<>(t1);
        all.addAll(t2);
        return all;
    }

    private void broadcastToAll(List<Player> t1, List<Player> t2, String msg) {
        for (Player p : mergeTeams(t1, t2)) p.sendMessage(msg);
    }

    private void broadcastSession(PartyDuelSession session, String msg) {
        for (Player p : session.getAllPlayers()) {
            if (p.isOnline()) p.sendMessage(msg);
        }
    }

    private Location getServerSpawn() {
        return plugin.getSpawnManager().getSpawn();
    }

    private void giveMenuSword(Player player) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            plugin.getPlayerJoinListener().giveDuelSword(player);
            // Restore party hotbar items if player is in a party
            plugin.getPartyManager().refreshHotbar(player);
        }, 5L);
    }

    private void applyPersonalOrDefault(Player player, String arenaId, Kit defaultKit) {
        org.bukkit.inventory.ItemStack[] personal =
                plugin.getPlayerKitManager().getPlayerKit(player.getUniqueId(), arenaId);
        if (personal != null) {
            player.getInventory().clear();
            Kit personalKit = new Kit("__personal__", personal);
            plugin.getKitManager().applyKit(player, personalKit);
        } else if (defaultKit != null) {
            plugin.getKitManager().applyKit(player, defaultKit);
        }
    }

    // ──────────────────────────────────────────────────
    //  FFA داخل البارتي الواحدة
    // ──────────────────────────────────────────────────

    /**
     * يُشغّل FFA — كل لاعب في البارتي ضد الكل.
     * كل عضو يُعامَل كـ "فريق" منفرد.
     * الأخير يبقى يفوز.
     */
    public void startPartyFFA(List<Player> members, Arena arena) {
        startPartyFFA(members, arena, null);
    }

    public void startPartyFFA(List<Player> members, Arena arena, String mapWorld) {
        if (members.size() < 2) {
            for (Player p : members)
                p.sendMessage(color("&cThe party needs at least two players to start."));
            return;
        }

        // أرينا عشوائية (السهم) → تنكشف لهم بعد النقل فقط
        if (com.duel.utils.RandomArena.is(mapWorld)) {
            for (Player p : members) plugin.getDuelManager().markRandom(p);
        }
        String templateWorld = pickWorld(arena, mapWorld);
        if (templateWorld == null) {
            for (Player p : members)
                p.sendMessage(color("&cNo world is set up for this arena!"));
            return;
        }

        for (Player p : members) {
            if (isInPartyDuel(p) || plugin.getDuelManager().isInDuel(p)) {
                for (Player m : members)
                    m.sendMessage(color("&c" + p.getName() + " is already in a duel!"));
                return;
            }
        }

        String copyName = plugin.getConfig().getString("settings.arena-copy-prefix", "duel_arena_")
                + arena.getId() + "_ffa_" + System.currentTimeMillis();

        for (Player p : members) {
            p.sendMessage(color("&a⚔ Party FFA has started! Preparing the arena..."));
            p.setWalkSpeed(0f);
            p.setFlySpeed(0f);
        }

        final String finalTemplate = templateWorld;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            final String worldName = plugin.getWorldPoolManager().acquire(finalTemplate, copyName);
            waitForWorld(worldName,
                    () -> preloadParty(arena, finalTemplate, worldName, true,
                            () -> onFFAWorldReady(members, arena, finalTemplate, worldName)),
                    () -> abortStart(members, new ArrayList<>(), "&c[Party] Failed to prepare the arena. Match cancelled."));
        });
    }

    private void onFFAWorldReady(List<Player> members, Arena arena,
                                 String templateWorld, String copyName) {
        World world = Bukkit.getWorld(copyName);
        if (world == null) {
            abortStart(members, new ArrayList<>(), "&c[Party] Failed to prepare the arena. Match cancelled.");
            return;
        }

        // FIX: لاعب طلع أثناء تحميل الأرينا → نشيله من المباراة (يُعتبر خاسر)،
        // وإلا يبقى "حي" للأبد والمباراة ما تنتهي أبداً
        List<Player> online = new ArrayList<>();
        for (Player p : members) if (p.isOnline()) online.add(p);
        if (online.size() < members.size()) {
            for (Player p : online)
                p.sendMessage(color("&c" + (members.size() - online.size()) + " player(s) disconnected before the match started."));
        }
        if (online.size() < 2) {
            abortStart(online, new ArrayList<>(), "&c[Party] Not enough players left — match cancelled.");
            plugin.getArenaManager().deleteWorld(copyName);
            return;
        }
        members = online;
        plugin.getArenaManager().tuneWorld(world);
        applyArenaSettings(world, arena);

        // كل لاعب = فريق منفرد — نحوّل List<Player> إلى List<List<Player>>
        // ونستخدم PartyDuelSession بفريق وهمي فارغ للفريق 2
        // الأسهل: نبني session خاصة بـ FFA عبر فريقين وهميين
        // team1 = كل اللاعبين, team2 = قائمة فارغة → نعدّل registerDeath لـ FFA

        // نستخدم PartyDuelSession بوضع FFA:
        // team1 = members, team2 = [] → registerDeath يتعامل معها بشكل مختلف
        // لكن الأسهل: نبني FFASession مخصصة

        FFASession session = new FFASession(members, arena, copyName);
        com.duel.models.PartyDuelSession duelSession = session.asDuelSession();
        // Use the duelSession's own sessionId as the key everywhere
        ffaSessions.put(duelSession.getSessionId(), session);
        sessions.put(duelSession.getSessionId(), duelSession);

        List<Location> usedFfaSpawns = new ArrayList<>();
        for (Player p : members) {
            playerSession.put(p.getUniqueId(), duelSession);
            Location loc = getArenaLevelSpawn(world, arena, templateWorld, usedFfaSpawns);
            usedFfaSpawns.add(loc);
            p.teleport(loc);
            p.setInvulnerable(true);
        }

        String kitName = arena.getKitName() != null ? arena.getKitName() : arena.getId();
        Kit defaultKit = plugin.getKitManager().getKit(kitName);
        for (Player p : members) {
            applyPersonalOrDefault(p, arena.getId(), defaultKit);
            arena.applyPotions(p); // بوشنات الأرينا فور النقل
        }

        // Force all players to see each other, even if "Hide Players" is enabled (نفس ماتش التيمات)
        for (Player viewer : members) {
            for (Player target : members) {
                if (!viewer.equals(target)) viewer.showPlayer(plugin, target);
            }
        }

        // ── عزل الـ TAB — أعضاء الـ FFA يشوفون بس بعض ──
        plugin.getTabIsolationManager().isolate(members);

        // ── سكوربورد البارتي ──
        plugin.getPartyScoreboardManager().start(duelSession, members);

        // كشف الأرينا العشوائية بعد النقل
        for (Player p : members) plugin.getDuelManager().revealRandomArena(p, arena);

        startFFACountdown(session, members);
    }

    /** جلسة FFA داخلية — كل لاعب ضد الكل */
    private class FFASession {
        final UUID sessionId = UUID.randomUUID();
        final List<Player> all;
        final Set<UUID> alive = new LinkedHashSet<>();
        final Arena arena;
        final String worldName;
        com.duel.models.PartyDuelSession cachedSession;

        FFASession(List<Player> members, Arena arena, String worldName) {
            this.all = new ArrayList<>(members);
            this.arena = arena;
            this.worldName = worldName;
            for (Player p : members) alive.add(p.getUniqueId());
        }

        /** نلفّها في PartyDuelSession وهمية حتى يشتغل playerSession map */
        com.duel.models.PartyDuelSession asDuelSession() {
            if (cachedSession == null) {
                // فريق 1 = كل اللاعبين، فريق 2 = فارغ
                cachedSession = new com.duel.models.PartyDuelSession(all, new ArrayList<>(), arena, worldName) {
                    @Override
                    public int registerDeath(org.bukkit.entity.Player dead) {
                        alive.remove(dead.getUniqueId());
                        if (alive.size() == 1) {
                            setState(com.duel.models.PartyDuelSession.State.ENDED);
                            return 1; // الفريق 1 (اللاعب الباقي)
                        }
                        if (alive.isEmpty()) {
                            setState(com.duel.models.PartyDuelSession.State.ENDED);
                            return 1;
                        }
                        return -1;
                    }

                    @Override
                    public boolean isAlive(org.bukkit.entity.Player p) {
                        return alive.contains(p.getUniqueId());
                    }

                    @Override
                    public int getTeamOf(org.bukkit.entity.Player p) {
                        // في FFA — كل لاعب فريق مختلف إلا نفسه
                        // نرجع team رقمه الفردي لمنع team-kill check
                        int idx = all.indexOf(p);
                        return idx >= 0 ? idx + 10 : -1; // أرقام كبيرة فريدة
                    }

                    @Override
                    public java.util.List<org.bukkit.entity.Player> getWinningTeam() {
                        // اللاعب الحي الوحيد
                        return all.stream()
                                .filter(p -> alive.contains(p.getUniqueId()))
                                .collect(java.util.stream.Collectors.toList());
                    }

                    @Override
                    public java.util.List<org.bukkit.entity.Player> getLosingTeam() {
                        return all.stream()
                                .filter(p -> !alive.contains(p.getUniqueId()))
                                .collect(java.util.stream.Collectors.toList());
                    }

                    @Override
                    public org.bukkit.entity.Player getWinner() {
                        return all.stream()
                                .filter(p -> alive.contains(p.getUniqueId()))
                                .findFirst().orElse(all.get(0));
                    }
                };
                // نعيّن sessionId
                try {
                    java.lang.reflect.Field f = com.duel.models.PartyDuelSession.class.getDeclaredField("sessionId");
                    // sessionId هو final UUID — نتركه كما هو، الـ map نستخدم sessionId من FFASession
                } catch (Exception ignored) {}
            }
            return cachedSession;
        }
    }

    // ── خريطة إضافية لتتبع FFASessions ──
    private final Map<UUID, FFASession> ffaSessions = new HashMap<>();

    private void startFFACountdown(FFASession ffa, List<Player> players) {
        // إصلاح 3: نستخدم duelSession بدل ffa.sessionId — sessionId موحّد
        com.duel.models.PartyDuelSession ds = ffa.asDuelSession();

        int seconds = plugin.getConfig().getInt("settings.countdown-seconds", 5);
        AtomicInteger countdown = new AtomicInteger(seconds);

        BukkitTask task = new org.bukkit.scheduler.BukkitRunnable() { @Override public void run() {
            if (ds.getState() == com.duel.models.PartyDuelSession.State.ENDED) { cancel(); return; }
            int cur = countdown.getAndDecrement();
            if (cur <= 0) return;
            for (Player p : players) {
                if (!p.isOnline()) continue;
                TitleUtils.sendCountdown(p, cur);
                SoundUtils.playCountdown(p);
            }
        } }.runTaskTimer(plugin, 0L, 20L);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            task.cancel();
            // FIX: لو المباراة انتهت أثناء العدّ (خرج لاعب) لا نرجّعها FIGHTING
            if (ds.getState() == com.duel.models.PartyDuelSession.State.ENDED) return;
            for (Player p : players) {
                if (!p.isOnline()) continue;
                p.setInvulnerable(false);
                p.setWalkSpeed(0.2f);
                p.setFlySpeed(0.1f);
            }
            ds.setState(com.duel.models.PartyDuelSession.State.FIGHTING);
            ds.markStarted();
            for (Player p : players) {
                if (!p.isOnline()) continue;
                TitleUtils.sendGo(p);
                SoundUtils.playGo(p);
            }
        }, (long) (seconds + 1) * 20L);
    }

    // ──────────────────────────────────────────────────
    //  ألوان الفريقين في TAB
    // ──────────────────────────────────────────────────

    private void applyTeamColors(PartyDuelSession session, List<Player> team1, List<Player> team2) {
        org.bukkit.scoreboard.Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();

        // فريق 1 = أزرق
        org.bukkit.scoreboard.Team blueTeam = board.registerNewTeam("party_team1_" + session.getSessionId().toString().substring(0, 8));
        blueTeam.setColor(org.bukkit.ChatColor.BLUE);
        blueTeam.setPrefix("§9"); // أزرق
        for (Player p : team1) {
            blueTeam.addEntry(p.getName());
        }

        // فريق 2 = أحمر
        org.bukkit.scoreboard.Team redTeam = board.registerNewTeam("party_team2_" + session.getSessionId().toString().substring(0, 8));
        redTeam.setColor(org.bukkit.ChatColor.RED);
        redTeam.setPrefix("§c"); // أحمر
        for (Player p : team2) {
            redTeam.addEntry(p.getName());
        }

        // نطبق الـ scoreboard على كل اللاعبين
        for (Player p : mergeTeams(team1, team2)) {
            p.setScoreboard(board);
        }

        // نحفظ الـ scoreboard في الجلسة للتنظيف لاحقاً
        session.setScoreboard(board);
    }

    private void removeTeamColors(PartyDuelSession session) {
        org.bukkit.scoreboard.Scoreboard board = session.getScoreboard();
        if (board == null) return;
        // نرجع الكل للـ scoreboard الافتراضي
        for (Player p : session.getAllPlayers()) {
            if (p.isOnline()) {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
