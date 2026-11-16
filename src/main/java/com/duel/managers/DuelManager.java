package com.duel.managers;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import com.duel.models.DuelSession;
import com.duel.models.Kit;
import com.duel.trim.TrimManager;
import com.duel.utils.SoundUtils;
import com.duel.utils.TitleUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class DuelManager {

    private final DuelPlugin plugin;
    // sessionId → session
    private final Map<UUID, DuelSession> sessions = new HashMap<>();
    // player UUID → session
    private final Map<UUID, DuelSession> playerSession = new java.util.concurrent.ConcurrentHashMap<>();
    /** لاعبين بدأ لهم دول لكن العالم لسا ما جهز — يحسبون "في دول" */
    private final Set<UUID> pendingStart = new HashSet<>();

    /** مدة أنيميشن التوتم — التجهيز يشتغل أثناءه والنقل ينتظر لين يخلص */
    public static final long INTRO_TICKS = 40L;

    public DuelManager(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * يعرض أنيميشن التوتم بأيقونة الأرينا.
     * نسخ العالم وتحميل الشنكات يبدأ مع الأنيميشن (مو بعده)، والنقل ينتظر لين يخلص الأنيميشن.
     */
    public void startDuelWithIntro(Player p1, Player p2, Arena arena, int roundsRequested, String mapWorld) {
        if (!p1.isOnline() || !p2.isOnline()) {
            Player stay = p1.isOnline() ? p1 : (p2.isOnline() ? p2 : null);
            if (stay != null) stay.sendMessage("§c⚔ Your opponent left. Match cancelled.");
            return;
        }
        if (isInDuel(p1) || isInDuel(p2)) return;

        // ── أرينا عشوائية (السهم): نختار الأرينا هنا ونخفيها لين النقل ──
        final boolean random = com.duel.utils.RandomArena.is(mapWorld);
        if (random) {
            Arena picked = com.duel.utils.RandomArena.pick(plugin);
            if (picked == null) {
                p1.sendMessage("§c⚔ No arenas are available right now.");
                p2.sendMessage("§c⚔ No arenas are available right now.");
                return;
            }
            arena = picked;
            mapWorld = null; // الماب عشوائية كمان
            markRandom(p1, p2);
            com.duel.utils.TotemPop.showRandom(plugin, p1);
            com.duel.utils.TotemPop.showRandom(plugin, p2);
        } else if (arena != null) {
            com.duel.utils.TotemPop.show(plugin, p1, arena);
            com.duel.utils.TotemPop.show(plugin, p2, arena);
        }
        startDuel(p1, p2, arena, roundsRequested, mapWorld, INTRO_TICKS);
    }

    // ── كشف الأرينا العشوائية بعد النقل فقط ──
    // UUID → وقت التعليم (يُتجاهل بعد دقيقتين عشان ما يعلق لو انلغت المباراة قبل النقل)
    private final Map<UUID, Long> randomReveal = new HashMap<>();

    /** يعلّم اللاعبين إن أرينتهم عشوائية → تنكشف لهم بعد النقل. */
    public void markRandom(Player... players) {
        for (Player p : players) if (p != null) randomReveal.put(p.getUniqueId(), System.currentTimeMillis());
    }

    /** يُستدعى بعد النقل: يعرض اسم الأرينا للي علّمناهم كعشوائي. */
    public void revealRandomArena(Player p, Arena arena) {
        if (p == null) return;
        Long t = randomReveal.remove(p.getUniqueId());
        if (t == null || System.currentTimeMillis() - t > 120_000L || !p.isOnline()) return;
        p.sendMessage(color("&7⚔ Arena: &b" + arena.getId()));
        p.sendActionBar(net.kyori.adventure.text.Component.text("Arena: " + arena.getId(),
                net.kyori.adventure.text.format.NamedTextColor.AQUA));
    }

    // ─────────────────────────────────────────
    //  Start a duel between two players
    // ─────────────────────────────────────────

    public void startDuel(Player p1, Player p2, Arena arena) {
        startDuel(p1, p2, arena, 1);
    }

    /** يبدأ مباراة من عدة رواندات (rounds = 1 → دول عادي). */
    public void startDuel(Player p1, Player p2, Arena arena, int roundsRequested) {
        startDuel(p1, p2, arena, roundsRequested, null);
    }

    /** mapWorld = map picked by the challenger (must belong to the arena); null/invalid = random map. */
    public void startDuel(Player p1, Player p2, Arena arena, int roundsRequested, String mapWorld) {
        startDuel(p1, p2, arena, roundsRequested, mapWorld, 0L);
    }

    /** holdTicks = أقل مدة (من لحظة الاستدعاء) قبل النقل — عشان التجهيز يصير أثناء الأنيميشن. */
    public void startDuel(Player p1, Player p2, Arena arena, int roundsRequested, String mapWorld, long holdTicks) {
        startDuel(p1, p2, arena, roundsRequested, mapWorld, holdTicks, false);
    }

    /** ranked = true فقط للمباريات من الكيو (تحسب على التوبات). /duel و rematch ما تحسب. */
    public void startDuel(Player p1, Player p2, Arena arena, int roundsRequested, String mapWorld, long holdTicks, boolean ranked) {
        final long startedAt = System.currentTimeMillis();
        final int rounds = Math.max(1, Math.min(com.duel.gui.RoundsAnvilGUI.MAX_ROUNDS, roundsRequested));
        String templateWorld = (mapWorld != null && arena.getWorlds().contains(mapWorld))
                ? mapWorld : arena.getRandomWorld();
        if (templateWorld == null) {
            p1.sendMessage(color("&cNo worlds configured for this arena!"));
            p2.sendMessage(color("&cNo worlds configured for this arena!"));
            return;
        }

        // حماية مركزية: اللاعب اللي في بارتي ما يدخل دول 1v1 (ريماتش/كيو/دعوات/أي مسار)
        if (plugin.getPartyManager().isInParty(p1) || plugin.getPartyManager().isInParty(p2)) {
            p1.sendMessage(color("&c⚔ You can't duel while in a party."));
            p2.sendMessage(color("&c⚔ You can't duel while in a party."));
            for (Player p : new Player[]{p1, p2}) {
                if (plugin.getQueueManager().isQueued(p)) plugin.getQueueManager().removeFromQueue(p);
                plugin.getDuelRequestManager().cancelAllFor(p);
            }
            return;
        }

        if (isBusy(p1) || isBusy(p2)) return; // حماية من بدء دولين لنفس اللاعب (الجلسات المنتهية لا تُحسب — مهم للريماتش)

        // نظّف أي انتظار/دعوات متبقية للاعبين
        for (Player p : new Player[]{p1, p2}) {
            if (plugin.getQueueManager().isQueued(p)) plugin.getQueueManager().removeFromQueue(p);
            plugin.getDuelRequestManager().cancelAllFor(p);
            pendingStart.add(p.getUniqueId());
        }

        String copyName = plugin.getConfig().getString("settings.arena-copy-prefix", "duel_arena_")
                + arena.getId() + "_" + System.currentTimeMillis();

        // Notify players
        p1.sendMessage(color("&a⚔ Match found! Preparing arena..."));
        p2.sendMessage(color("&a⚔ Match found! Preparing arena..."));

        // Lock movement immediately
        p1.setWalkSpeed(0f);
        p2.setWalkSpeed(0f);
        p1.setFlySpeed(0f);
        p2.setFlySpeed(0f);

        // Clone world async (Multiverse handles this), then schedule duel setup
        final String finalTemplateWorld = templateWorld;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            final String worldName = plugin.getWorldPoolManager().acquire(finalTemplateWorld, copyName);
            waitForWorld(worldName, () -> preloadSpawns(arena, finalTemplateWorld, worldName, () -> {
                        final Runnable go = () -> {
                            if (!p1.isOnline() || !p2.isOnline()) { abortOffline(p1, p2, worldName); return; }
                            onWorldReady(p1, p2, arena, finalTemplateWorld, worldName, rounds, ranked);
                            plugin.getChunkPreloader().releaseLater(worldName);
                        };
                        long waitMs = holdTicks * 50L - (System.currentTimeMillis() - startedAt);
                        long delay = Math.max(0L, (waitMs + 49L) / 50L);
                        if (delay <= 0L) go.run();
                        else plugin.getServer().getScheduler().runTaskLater(plugin, go, delay);
                    }),
                    () -> { abortStart(p1, p2); });
        });
    }

    /** يحمّل شنكات نقاط الـ spawn للعالم async قبل الـ teleport، ثم يشغّل next (دايماً مرة وحدة). */
    private void preloadSpawns(Arena arena, String templateWorld, String worldName, Runnable next) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) { next.run(); return; }
        List<Location> seeds = new ArrayList<>();
        seeds.add(getSpawn(arena, templateWorld, w, 0));
        seeds.add(getSpawn(arena, templateWorld, w, 1));
        plugin.getChunkPreloader().preload(w, seeds, false, next);
    }

    /** لاعب طلع أثناء تحميل الشنكات — نلغي ونحذف العالم. */
    private void abortOffline(Player p1, Player p2, String worldName) {
        for (Player p : new Player[]{p1, p2}) {
            pendingStart.remove(p.getUniqueId());
            if (p.isOnline()) {
                unfreeze(p);
                p.sendMessage(color("&c⚔ Your opponent left — match cancelled."));
            }
        }
        try { plugin.getArenaManager().deleteWorld(worldName); } catch (Throwable ignored) {}
    }

    /** يرجّع الحركة الطبيعية للاعب (آمن لو كان offline) */
    private void unfreeze(Player p) {
        try {
            if (p != null && p.isOnline()) {
                p.setWalkSpeed(0.2f);
                p.setFlySpeed(0.1f);
                p.setInvulnerable(false);
            }
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[DuelManager] unfreeze failed", t);
        }
    }

    /** اللاعب مشغول لو عنده جلسة نشطة (غير ENDED) أو بدء دول قيد التجهيز */
    private boolean isBusy(Player p) {
        if (pendingStart.contains(p.getUniqueId())) return true;
        DuelSession s = playerSession.get(p.getUniqueId());
        return s != null && s.getState() != DuelSession.State.ENDED;
    }

    /** فشل تجهيز العالم — نفك التجميد ونشيل حالة الانتظار حتى ما يعلق اللاعبين */
    private void abortStart(Player p1, Player p2) {
        for (Player p : new Player[]{p1, p2}) {
            pendingStart.remove(p.getUniqueId());
            if (p.isOnline()) {
                p.setWalkSpeed(0.2f);
                p.setFlySpeed(0.1f);
                p.setInvulnerable(false);
                p.sendMessage(color("&c⚔ Failed to prepare the arena. Please try again."));
            }
        }
    }

    /** Poll every 10 ticks until the world is loaded, then run callback */
    private void waitForWorld(String worldName, Runnable callback, Runnable onTimeout) {
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
            } else if (plugin.getArenaManager().isCopyFailed(worldName) || attempts[0] > 200) { // 50s timeout
                task.cancel();
                plugin.getLogger().warning("World copy timed out: " + worldName);
                onTimeout.run();
            }
        }, 10L, 10L);
    }

    /** يطبّق إعدادات الأرينا على العالم المنسوخ (أول راوند أو أي راوند جديد). */
    private void configureWorld(World world, Arena arena) {
        plugin.getArenaManager().tuneWorld(world);
        world.setGameRule(org.bukkit.GameRule.DO_TILE_DROPS, arena.isAllowBreaking());
        org.bukkit.Difficulty diff = switch (arena.getDifficulty()) {
            case "easy" -> org.bukkit.Difficulty.EASY;
            case "hard" -> org.bukkit.Difficulty.HARD;
            default     -> org.bukkit.Difficulty.NORMAL;
        };
        world.setDifficulty(diff);
    }

    private void onWorldReady(Player p1, Player p2, Arena arena, String templateWorld, String copyName, int rounds, boolean ranked) {
        pendingStart.remove(p1.getUniqueId());
        pendingStart.remove(p2.getUniqueId());
        World world = Bukkit.getWorld(copyName);
        if (world == null) { abortStart(p1, p2); return; }
        configureWorld(world, arena);

        // Get spawn locations — P1 = index 0, P2 = index 1
        Location spawn1 = getSpawn(arena, templateWorld, world, 0);
        Location spawn2 = getSpawn(arena, templateWorld, world, 1);
        plugin.getArenaManager().applyBarrierBorder(world, spawn1.getBlockY());
        plugin.getArenaManager().autoPrepareTemplate(templateWorld, spawn1.getBlockY());

        // Teleport
        p1.teleport(spawn1);
        p2.teleport(spawn2);
        revealRandomArena(p1, arena);
        revealRandomArena(p2, arena);

        // Create session
        DuelSession session = new DuelSession(p1, p2, arena, copyName);
        session.setTotalRounds(rounds);
        session.setRanked(ranked);
        session.setTemplateWorld(templateWorld);
        session.setPlayerSpawn(p1, spawn1);
        session.setPlayerSpawn(p2, spawn2);
        sessions.put(session.getSessionId(), session);
        playerSession.put(p1.getUniqueId(), session);
        playerSession.put(p2.getUniqueId(), session);

        // Apply kits — player personal arrangement takes priority over arena default
        // (كل خطوة محمية بـ try/catch حتى لو فشلت وحدة ما يتعطل العدّاد و GO)
        String kitName = arena.getKitName() != null ? arena.getKitName() : arena.getId();
        try {
            Kit defaultKit = plugin.getKitManager().getKit(kitName);
            applyPersonalOrDefault(p1, arena.getId(), defaultKit);
            applyPersonalOrDefault(p2, arena.getId(), defaultKit);
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[DuelManager] Failed applying kits", t);
        }

        // Apply saved armor trims on top of the kit (scoped to this arena)
        try {
            applyTrimsToPlayer(p1, arena.getId());
            applyTrimsToPlayer(p2, arena.getId());
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[DuelManager] Failed applying trims", t);
        }

        // بوشنات الأرينا — فور النقل للأرينا
        arena.applyPotions(p1);
        arena.applyPotions(p2);

        // Freeze players
        p1.setInvulnerable(true);
        p2.setInvulnerable(true);

        // Force both players to see each other, even if "Hide Players" is enabled
        try {
            p1.showPlayer(plugin, p2);
            p2.showPlayer(plugin, p1);
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[DuelManager] showPlayer failed", t);
        }

        // ── عزل الـ TAB — يشوف كل لاعب بس خصمه ──
        try {
            plugin.getTabIsolationManager().isolate(List.of(p1, p2));
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[DuelManager] TAB isolation failed", t);
        }

        // Countdown
        startCountdown(session, p1, p2);
    }

    public Location getSpawn(Arena arena, String templateWorldName, World copiedWorld, int playerIndex) {
        // Try exact template world name first
        Location loc = arena.getSpawn(templateWorldName, playerIndex);

        // Fallback: try any stored world's spawns
        if (loc == null) {
            for (List<org.bukkit.Location> spawns : arena.getSpawnPoints().values()) {
                if (spawns.size() > playerIndex) { loc = spawns.get(playerIndex); break; }
                if (!spawns.isEmpty())            { loc = spawns.get(0); break; }
            }
        }

        if (loc == null) {
            plugin.getLogger().warning("[DuelManager] No spawn found for arena '"
                    + arena.getId() + "' world '" + templateWorldName + "' — using world spawn.");
            return copiedWorld.getSpawnLocation();
        }

        // Always adapt to the copied world (handles null-world lazy locations too)
        // pitch = 0 دائماً: نقطة الـ spawn تُحفظ بزاوية رأس الأدمن وقت الضغط (غالباً ينظر لأسفل)
        return new Location(copiedWorld, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), 0f);
    }

    private void startCountdown(DuelSession session, Player p1, Player p2) {
        final int seconds = Math.max(1, plugin.getConfig().getInt("settings.countdown-seconds", 5));
        session.markCountdownStart();

        // مؤقت واحد فقط يتحكم بالعدّاد و GO (قبل كان فيه مؤقتين منفصلين ويمكن يختل التزامن بينهم)
        final AtomicInteger remaining = new AtomicInteger(seconds);
        final BukkitTask[] holder = new BukkitTask[1];

        holder[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            try {
                // انتهت الجلسة أثناء العدّ (disconnect / leave) — نوقف المؤقت
                if (session.getState() != DuelSession.State.COUNTDOWN) {
                    if (holder[0] != null) holder[0].cancel();
                    session.setCountdownTask(null);
                    return;
                }
                int current = remaining.getAndDecrement();
                if (current > 0) {
                    if (p1.isOnline()) { TitleUtils.sendCountdown(p1, current); SoundUtils.playCountdown(p1); }
                    if (p2.isOnline()) { TitleUtils.sendCountdown(p2, current); SoundUtils.playCountdown(p2); }
                } else {
                    if (holder[0] != null) holder[0].cancel();
                    session.setCountdownTask(null);
                    beginFight(session);
                }
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "[DuelManager] Countdown error — forcing fight start", t);
                if (holder[0] != null) holder[0].cancel();
                session.setCountdownTask(null);
                beginFight(session);
            }
        }, 0L, 20L);

        // نحفظ الـ task في الجلسة حتى يمكن إلغاؤها لو انتهت المباراة مبكراً
        session.setCountdownTask(holder[0]);

        // شبكة أمان: لو لأي سبب المؤقت ما كمل (تأخر السيرفر، إلغاء المهمة…) نبدأ القتال إجبارياً
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (session.getState() == DuelSession.State.COUNTDOWN) {
                plugin.getLogger().warning("[DuelManager] Countdown watchdog fired — forcing GO for session " + session.getSessionId());
                if (session.getCountdownTask() != null) {
                    session.getCountdownTask().cancel();
                    session.setCountdownTask(null);
                }
                beginFight(session);
            }
        }, (long) (seconds + 3) * 20L);
    }

    /**
     * يبدأ القتال فعلياً: يفك التجميد، يحوّل الحالة إلى FIGHTING، ويرسل GO.
     * آمن للاستدعاء أكثر من مرة (يشتغل مرة وحدة فقط).
     * الترتيب مهم: الحالة و فك التجميد أولاً، وأي شي ثانوي (سكوربورد/تايتل) داخل try/catch
     * عشان أي خطأ فيه ما يمنع GO أو الضرب.
     */
    public void beginFight(DuelSession session) {
        if (session.getState() != DuelSession.State.COUNTDOWN) return;

        Player p1 = session.getPlayer1();
        Player p2 = session.getPlayer2();

        // 1) الحالة أولاً — هذا اللي يسمح بالضرب
        session.setState(DuelSession.State.FIGHTING);
        session.markStarted();

        // 2) فك التجميد
        for (Player p : new Player[]{p1, p2}) {
            try {
                if (p.isOnline()) {
                    p.setInvulnerable(false);
                    p.setWalkSpeed(0.2f);
                    p.setFlySpeed(0.1f);
                }
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "[DuelManager] Unfreeze failed for " + p.getName(), t);
            }
        }

        // 3) GO! (قبل السكوربورد عشان ما يتأخر)
        for (Player p : new Player[]{p1, p2}) {
            try {
                if (p.isOnline()) { TitleUtils.sendGo(p); SoundUtils.playGo(p); }
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "[DuelManager] GO title failed for " + p.getName(), t);
            }
        }

        // 3.5) رسالة الراوند (للمباريات متعددة الرواندات فقط)
        if (session.isRoundMatch()) {
            String msg = color("&e⚔ Round &f" + session.getCurrentRound() + "&7/&f" + session.getTotalRounds());
            for (Player p : new Player[]{p1, p2}) {
                if (p.isOnline()) p.sendMessage(msg);
            }
        }

        // 4) Scoreboard ping live (مرة وحدة فقط — الرواندات اللاحقة تكمل على نفس السكوربورد)
        try {
            if (session.getPingTask() == null) plugin.getDuelScoreboardManager().start(session);
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[DuelManager] Scoreboard start failed", t);
        }
    }

    /**
     * شبكة أمان تُستدعى من DuelListener: لو الجلسة لسا COUNTDOWN مع إن وقت العدّ مرّ بالكامل
     * نبدأ القتال فوراً بدل ما يظل اللاعب عالق.
     */
    public boolean ensureFightStarted(DuelSession session) {
        if (session.getState() == DuelSession.State.FIGHTING) return true;
        if (session.getState() != DuelSession.State.COUNTDOWN) return false;
        int seconds = Math.max(1, plugin.getConfig().getInt("settings.countdown-seconds", 5));
        long elapsed = System.currentTimeMillis() - session.getCountdownStartMs();
        if (elapsed >= (seconds + 2) * 1000L) {
            plugin.getLogger().warning("[DuelManager] Session stuck in COUNTDOWN — forcing fight start");
            if (session.getCountdownTask() != null) {
                session.getCountdownTask().cancel();
                session.setCountdownTask(null);
            }
            beginFight(session);
            return true;
        }
        return false;
    }

    // ─────────────────────────────────────────
    //  End duel
    // ─────────────────────────────────────────

    /**
     * يُستدعى لما يموت لاعب = نهاية راوند.
     * مباراة عادية (راوند واحد) → تنتهي مباشرة. مباراة رواندات → نحسب النقطة ونبدأ الراوند التالي
     * أو ننهي المباراة لو خلصت الرواندات.
     */
    public void endDuel(DuelSession session, Player winner) {
        if (session.getState() == DuelSession.State.ENDED) return;
        if (!session.isRoundMatch()) { endMatch(session, winner, true); return; }
        if (session.getState() == DuelSession.State.ROUND_BREAK) return; // الراوند منتهي أصلاً

        Player loser = session.getOpponent(winner);
        session.addRoundScore(winner);

        int round = session.getCurrentRound();
        int total = session.getTotalRounds();
        int wScore = session.getRoundScore(winner);
        int lScore = session.getRoundScore(loser);

        Player matchWinner = null;
        if (round >= total) {
            if (wScore != lScore) {
                matchWinner = wScore > lScore ? winner : loser;
            } else {
                // تعادل بعد آخر راوند → راوند حاسم
                session.extendRounds();
                total++;
                for (Player p : new Player[]{winner, loser}) {
                    if (p.isOnline()) p.sendMessage(color("&6⚔ Tie! Deciding round incoming..."));
                }
            }
        } else if (plugin.getConfig().getBoolean("rounds.end-early-when-decided", false)) {
            if (Math.abs(wScore - lScore) > (total - round)) {
                matchWinner = wScore > lScore ? winner : loser;
            }
        }

        if (matchWinner != null) {
            endMatch(session, matchWinner, true);
            return;
        }

        // ── استراحة بين الرواندات ──
        session.setState(DuelSession.State.ROUND_BREAK);
        // العالم ما تغيّر؟ نكمّل بنفس العالم (سحب اللاعبين للسبون) بدل عالم جديد
        final boolean reuse = canReuseWorld(session);
        if (!reuse) prepareNextRoundWorld(session); // عالم جديد للراوند التالي — يجهز أثناء الاستراحة
        if (session.getCountdownTask() != null) {
            session.getCountdownTask().cancel();
            session.setCountdownTask(null);
        }
        try { winner.setInvulnerable(true); } catch (Throwable ignored) {}
        giveWinnerFlight(winner); // الفايز يبقى سيرفايفل + يقدر يطير لين يرجعون للسبون
        plugin.getRoundPullManager().lock(winner); // مقفول فوراً (بدون حركة/طيران حر) لين يخلص المشهد

        for (Player p : new Player[]{winner, loser}) {
            if (!p.isOnline()) continue;
            boolean won = p.equals(winner);
            int self = won ? wScore : lScore;
            int opp  = won ? lScore : wScore;
            TitleUtils.sendRoundResult(p, won, self, opp, round, total);
            SoundUtils.playQueueFound(p);
            p.sendMessage(color("&e⚔ Round " + round + "/" + total + " &7— "
                    + (won ? "&aYou won" : "&cYou lost") + " &7(&a" + self + "&7 - &c" + opp + "&7)"));
        }

        long breakTicks = Math.max(1, plugin.getConfig().getInt("rounds.break-seconds", 8)) * 20L;

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> awaitCinematicThenStart(session, 0), breakTicks);

        // مشهد سينمائي (فيزياء بدون teleport): يرتفعون /\ ويتواجهون ثم يُسحبون للسبون بنزول تدريجي.
        // الماب ترجع لحظة بدء النزول (لو نفس العالم).
        plugin.getRoundPullManager().start(session, breakTicks,
                reuse ? () -> { if (session.getState() == DuelSession.State.ROUND_BREAK) resetArenaBlocks(session); } : null);
    }

    /**
     * نفس العالم دائماً بين الرواندات (حتى لو تغيّر): البلوكات المكسورة/المبنية/المنفجرة/الماء/النار
     * تترجّع من الـ tracking، واللاعبين ينسحبون لنقاط السبون. config: rounds.same-world (افتراضي true).
     */
    private boolean canReuseWorld(DuelSession session) {
        return plugin.getConfig().getBoolean("rounds.same-world", true);
    }

    /** يسجّل البلوكة الأصلية قبل أي تغيير (سطل، انفجار، نار، سيلان ماء...) عشان تترجّع بعد الراوند. */
    public void trackWorldChange(org.bukkit.block.Block b) {
        if (b == null) return;
        final String wn = b.getWorld().getName();
        for (DuelSession s : sessions.values()) {
            if (s.getState() != DuelSession.State.ENDED && s.isRoundMatch()
                    && wn.equals(s.getCopiedWorldName())) {
                if (s.isPlacedBlock(b.getLocation())) return; // أصلها محفوظ وقت البناء
                s.trackBrokenOriginal(b.getLocation(), b.getBlockData());
                return;
            }
        }
    }

    /** يعلّم عالم المباراة كـ"متغيّر" (يُستدعى من WorldDirtyListener). */
    public void markWorldDirty(World world) {
        if (world == null) return;
        for (DuelSession s : sessions.values()) {
            if (s.getState() != DuelSession.State.ENDED && world.getName().equals(s.getCopiedWorldName())) {
                s.markWorldDirty();
            }
        }
    }

    /**
     * خروج لاعب (/leave أو disconnect) = ينهي المباراة كاملة (مو راوند واحد).
     * الخصم يفوز بالمباراة كلها. في مباريات الرواندات يمكن إلغاء احتساب النتيجة من config.
     */
    public void forfeitMatch(DuelSession session, Player winner) {
        if (session.getState() == DuelSession.State.ENDED) return;
        boolean record = !session.isRoundMatch()
                || plugin.getConfig().getBoolean("rounds.leave-counts-as-loss", true);
        endMatch(session, winner, record);
    }

    private boolean freshWorldPerRound() {
        return plugin.getConfig().getBoolean("rounds.new-world-per-round", true);
    }

    /** يبدأ تجهيز عالم الراوند التالي (من الـ pool أو نسخ جديد) أثناء الاستراحة. */
    private void prepareNextRoundWorld(DuelSession session) {
        if (!freshWorldPerRound() || session.getNextWorldName() != null) return;
        String template = session.getTemplateWorld();
        if (template == null) return;
        try {
            String name = plugin.getConfig().getString("settings.arena-copy-prefix", "duel_arena_")
                    + session.getArena().getId() + "_" + System.currentTimeMillis()
                    + "_r" + (session.getCurrentRound() + 1);
            final String next = plugin.getWorldPoolManager().acquire(template, name);
            session.setNextWorldName(next);
            // نحمّل شنكات الراوند الجاي أثناء الاستراحة — الانتقال يصير فوري
            waitForWorld(next, () -> preloadSpawns(session.getArena(), template, next, () -> {}), () -> {});
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "[DuelManager] Failed preparing next round world", t);
        }
    }

    /** عالم راوند جُهّز بس ما انستخدم (المباراة انتهت) — نحذفه حتى ما يتراكم. */
    private void discardPendingWorld(DuelSession session) {
        final String name = session.getNextWorldName();
        if (name == null) return;
        session.setNextWorldName(null);
        // مرتين: لو النسخ لسا شغّال أول مرة ما لقى العالم
        for (long delay : new long[]{40L, 600L}) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                try { plugin.getArenaManager().deleteWorld(name); } catch (Throwable ignored) {}
            }, delay);
        }
    }

    /**
     * ديناميكي: لو المشهد السينمائي لسا ما خلص (أرينا كبيرة تحتاج وقت أطول) ننتظره
     * لين يخلص، بحد أقصى rounds.cinematic.max-extra-seconds، بعدها نبدأ الراوند.
     */
    private void awaitCinematicThenStart(DuelSession session, int waitedTicks) {
        if (session.getState() != DuelSession.State.ROUND_BREAK) return;
        long maxExtra = Math.max(0, plugin.getConfig().getInt("rounds.cinematic.max-extra-seconds", 6)) * 20L
                + 40L; // هامش لانتظار الريسباون
        if (plugin.getRoundPullManager().isRunning(session) && waitedTicks < maxExtra) {
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> awaitCinematicThenStart(session, waitedTicks + 2), 2L);
            return;
        }
        startNextRound(session);
    }

    /** يجهّز الراوند التالي: عالم جديد ينقلهم له (أو يرجّع نفس الأرينا لو فشل)، ثم كيت وعدّ تنازلي. */
    private void startNextRound(DuelSession session) {
        if (session.getState() != DuelSession.State.ROUND_BREAK) return;

        Player p1 = session.getPlayer1();
        Player p2 = session.getPlayer2();
        if (!p1.isOnline() || !p2.isOnline()) return; // الـ quit handler يتكفل بإنهاء المباراة

        if (!freshWorldPerRound() || canReuseWorld(session)) {
            plugin.getRoundPullManager().stop(session);
            beginRound(session, null);
            return;
        }

        if (session.getNextWorldName() == null) prepareNextRoundWorld(session);
        final String nextName = session.getNextWorldName();
        if (nextName == null) { beginRound(session, null); return; }

        if (Bukkit.getWorld(nextName) == null) {
            for (Player p : new Player[]{p1, p2}) p.sendMessage(color("&e⚔ Preparing the next arena..."));
        }
        waitForWorld(nextName, () -> preloadSpawns(session.getArena(), session.getTemplateWorld(), nextName, () -> {
            if (session.getState() != DuelSession.State.ROUND_BREAK) return; // انتهت المباراة — discardPendingWorld يحذفه
            beginRound(session, nextName);
            plugin.getChunkPreloader().releaseLater(nextName);
        }), () -> {
            plugin.getLogger().warning("[DuelManager] Next round world failed — resetting the current arena instead.");
            session.setNextWorldName(null);
            if (session.getState() == DuelSession.State.ROUND_BREAK) beginRound(session, null);
        });
    }

    /**
     * يبدأ الراوند فعلياً.
     * newWorldName != null → ينقل اللاعبين (والمتفرجين) لعالم جديد ويحذف القديم.
     * newWorldName == null → نفس العالم مع ريست للبلوكات (احتياط).
     */
    private void beginRound(DuelSession session, String newWorldName) {
        Player p1 = session.getPlayer1();
        Player p2 = session.getPlayer2();
        if (!p1.isOnline() || !p2.isOnline()) return;

        plugin.getRoundPullManager().stop(session); // احتياط: يرجّع الجاذبية ويوقف المشهد
        session.nextRound();
        session.setNextWorldName(null);

        World newWorld = newWorldName != null ? Bukkit.getWorld(newWorldName) : null;
        String oldWorldName = session.getCopiedWorldName();
        boolean swap = newWorld != null && !newWorldName.equals(oldWorldName);

        if (swap) {
            Arena arena = session.getArena();
            configureWorld(newWorld, arena);
            Location spawn1 = getSpawn(arena, session.getTemplateWorld(), newWorld, 0);
            Location spawn2 = getSpawn(arena, session.getTemplateWorld(), newWorld, 1);
            plugin.getArenaManager().applyBarrierBorder(newWorld, spawn1.getBlockY());

            session.setCopiedWorldName(newWorldName);
            session.resetWorldTracking();
            session.setPlayerSpawn(p1, spawn1);
            session.setPlayerSpawn(p2, spawn2);

            // المتفرجين ينتقلون مع اللاعبين للعالم الجديد
            try { plugin.getSpectateCommand().moveSpectators(oldWorldName, spawn1); }
            catch (Throwable t) { plugin.getLogger().log(java.util.logging.Level.WARNING, "[DuelManager] moveSpectators failed", t); }

            // العالم القديم يُحذف بعد ما ينتقلون (ثانيتين)
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                try { plugin.getArenaManager().deleteWorld(oldWorldName); }
                catch (Throwable t) { plugin.getLogger().log(java.util.logging.Level.WARNING, "[DuelManager] Failed deleting old round world " + oldWorldName, t); }
            }, 40L);
        } else {
            if (newWorldName != null) {
                // العالم الجديد ما انحمّل — نحذفه ونكمل بالقديم
                final String dead = newWorldName;
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    try { plugin.getArenaManager().deleteWorld(dead); } catch (Throwable ignored) {}
                }, 40L);
            }
            resetArenaBlocks(session);
        }

        for (Player p : new Player[]{p1, p2}) {
            try {
                if (p.isDead()) p.spigot().respawn();
                try { if (p.getSpectatorTarget() != null) p.setSpectatorTarget(null); } catch (Throwable ignored) {}
                p.setGameMode(GameMode.SURVIVAL); // الخاسر كان سبكتر → يرجع لاعب
                clearFlight(p);                   // الفايز يخسر الطيران
                p.getInventory().clear(); p.setItemOnCursor(null);
                for (org.bukkit.potion.PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) {
                    p.removePotionEffect(e.getType());
                }
                org.bukkit.attribute.AttributeInstance maxHp = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                p.setHealth(maxHp != null ? maxHp.getValue() : 20.0);
                p.setFoodLevel(20);
                p.setSaturation(20f);
                p.setFireTicks(0);
                p.setFallDistance(0f);
                p.setAbsorptionAmount(0.0);
                Location sp = session.getPlayerSpawn(p);
                if (sp != null) {
                    // وصلوا فيزيائياً بالسحب → بدون teleport؛ التليبورت فقط احتياط (علقوا بعيد / عالم جديد)
                    boolean landed = p.getWorld().equals(sp.getWorld())
                            && p.getLocation().distanceSquared(sp) < 4.0;
                    if (landed) {
                        p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                        try { p.setRotation(sp.getYaw(), sp.getPitch()); } catch (Throwable ignored) {}
                    } else {
                        p.teleport(sp);
                    }
                }
                p.setGameMode(GameMode.SURVIVAL); // تغيير العالم ممكن يرجّع الـ gamemode الافتراضي للعالم
                p.setInvulnerable(true);
                p.setWalkSpeed(0f);
                p.setFlySpeed(0f);
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "[DuelManager] Round reset failed for " + p.getName(), t);
            }
        }

        giveKitsAndTrims(session, p1, p2);

        session.setState(DuelSession.State.COUNTDOWN);
        startCountdown(session, p1, p2);
    }

    /** يرجّع البلوكات: المبنية تنشال، الأصلية المكسورة ترجع، ويمسح الدروبات والـ projectiles. */
    private void resetArenaBlocks(DuelSession session) {
        World world = Bukkit.getWorld(session.getCopiedWorldName());
        if (world == null) return;
        try {
            for (Location loc : new ArrayList<>(session.getPlacedBlocks().keySet())) {
                org.bukkit.block.data.BlockData orig = session.getBrokenOriginals().get(loc);
                loc.getBlock().setBlockData(orig != null ? orig : Material.AIR.createBlockData(), false);
            }
            session.getPlacedBlocks().clear();

            for (Map.Entry<Location, org.bukkit.block.data.BlockData> e : session.getBrokenOriginals().entrySet()) {
                e.getKey().getBlock().setBlockData(e.getValue(), false);
            }
            session.getBrokenOriginals().clear();

            for (org.bukkit.entity.Entity ent : new ArrayList<>(world.getEntities())) {
                if (ent instanceof org.bukkit.entity.Item
                        || ent instanceof org.bukkit.entity.Projectile
                        || ent instanceof org.bukkit.entity.ExperienceOrb
                        || ent instanceof org.bukkit.entity.TNTPrimed
                        || ent instanceof org.bukkit.entity.FallingBlock
                        || ent instanceof org.bukkit.entity.AreaEffectCloud) {
                    ent.remove();
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[DuelManager] Arena reset failed", t);
        }
    }

    /** يعطي الكيت (الشخصي أو الافتراضي) + الـ trims للاعبين — يُستخدم في بداية كل راوند. */
    private void giveKitsAndTrims(DuelSession session, Player p1, Player p2) {
        Arena arena = session.getArena();
        String kitName = arena.getKitName() != null ? arena.getKitName() : arena.getId();
        try {
            Kit defaultKit = plugin.getKitManager().getKit(kitName);
            applyPersonalOrDefault(p1, arena.getId(), defaultKit);
            applyPersonalOrDefault(p2, arena.getId(), defaultKit);
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[DuelManager] Failed applying kits", t);
        }
        try {
            applyTrimsToPlayer(p1, arena.getId());
            applyTrimsToPlayer(p2, arena.getId());
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[DuelManager] Failed applying trims", t);
        }
        // بوشنات الأرينا — كل راوند (التأثيرات انمسحت في بداية beginRound)
        arena.applyPotions(p1);
        arena.applyPotions(p2);
    }

    // ─────────────────────────────────────────
    //  1v1: الميت سبكتر عادي / الفايز سيرفايفل + طيران
    // ─────────────────────────────────────────

    /** الميت في 1v1: gamemode SPECTATOR عادي (بدون بوصلة ولا ريشة) بعد الريسباون بـ tick. */
    public void makeDeadSpectator(Player p) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (p == null || !p.isOnline()) return;
            try {
                p.setInvulnerable(false);
                p.setGameMode(GameMode.SPECTATOR);
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "[DuelManager] makeDeadSpectator failed", t);
            }
        }, 1L);
    }

    /** الفايز: يبقى SURVIVAL لكن يقدر يطير. */
    private void giveWinnerFlight(Player w) {
        if (w == null || !w.isOnline()) return;
        try {
            if (w.getGameMode() != GameMode.SURVIVAL) w.setGameMode(GameMode.SURVIVAL);
            w.setAllowFlight(true);
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[DuelManager] giveWinnerFlight failed", t);
        }
    }

    /** يشيل الطيران من اللاعب (الفايز/الخاسر) عند الرجوع للسبون أو بداية راوند جديد. */
    public void clearFlight(Player p) {
        if (p == null || !p.isOnline()) return;
        try {
            if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
                p.setFlying(false);
                p.setAllowFlight(false);
            }
            p.setFallDistance(0f);
        } catch (Throwable ignored) {}
    }

    /** ماينكرافت يربط كاميرا الميت بالقاتل — نفكها كل tick لمدة محدودة. */
    private void detachSpectatorCamera(Player p, long ticks) {
        final long[] left = {ticks};
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            if (!p.isOnline() || --left[0] < 0) { task.cancel(); return; }
            try {
                if (!p.isDead() && p.getGameMode() == GameMode.SPECTATOR && p.getSpectatorTarget() != null) {
                    p.setSpectatorTarget(null);
                }
            } catch (Throwable ignored) {}
        }, 2L, 1L);
    }

    /** نهاية المباراة كاملة (فوز نهائي) — نفس منطق النهاية القديم. */
    private void endMatch(DuelSession session, Player winner, boolean recordStats) {
        if (session.getState() == DuelSession.State.ENDED) return;
        session.setState(DuelSession.State.ENDED);
        plugin.getRoundPullManager().stop(session); // لو انتهت المباراة أثناء المشهد (انسحاب): رجّع الجاذبية
        // Bug fix: إلغاء الـ countdown task إذا مات اللاعب خلال العدّ التنازلي
        if (session.getCountdownTask() != null) {
            session.getCountdownTask().cancel();
            session.setCountdownTask(null);
        }
        session.setWinner(winner);
        discardPendingWorld(session);

        // ── فك التجميد دائماً (لو انتهى الدول أثناء العدّ التنازلي walkSpeed يبقى 0) ──
        unfreeze(session.getPlayer1());
        unfreeze(session.getPlayer2());
        plugin.getArenaManager().clearArenaPotions(session.getPlayer1());
        plugin.getArenaManager().clearArenaPotions(session.getPlayer2());

        // ── إزالة عزل الـ TAB ──
        plugin.getTabIsolationManager().restore(session.getPlayer1());
        plugin.getTabIsolationManager().restore(session.getPlayer2());

        // ── إيقاف الـ Scoreboard ──
        plugin.getDuelScoreboardManager().stop(session);

        Player loser = session.getLoser();

        // Eject spectators from the duel world before cleanup
        plugin.getSpectateCommand().ejectSpectators(session);

        // Record stats (مرة وحدة للمباراة كاملة، مو لكل راوند)
        if (recordStats && (session.isRanked() || plugin.getConfig().getBoolean("stats.count-challenges", false))) {
            plugin.getStatsManager().recordWin(winner.getUniqueId(), winner.getName(), session.getArena().getId());
            plugin.getStatsManager().recordLoss(loser.getUniqueId(), loser.getName(), session.getArena().getId());
        }

        // نظام التيرات (Rating / Rankup Challenge / Tier Defense) — مباريات الكيو فقط
        if (recordStats && session.isRanked()) {
            try {
                plugin.getTierManager().onMatchEnd(session, winner, loser);
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "[DuelManager] Tier update failed", t);
            }
        }

        // النتيجة النهائية للمباريات متعددة الرواندات
        if (session.isRoundMatch()) {
            String fin = color("&6⚔ Match finished! &7Final score: &f" + winner.getName() + " &a"
                    + session.getRoundScore(winner) + "&7 - &c" + session.getRoundScore(loser) + " &f" + loser.getName());
            if (winner.isOnline()) winner.sendMessage(fin);
            if (loser.isOnline())  loser.sendMessage(fin);
        }

        // Refresh leaderboard holograms
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> plugin.getHologramManager().refreshAll(), 20L);

        // Show results — فقط للاعبين الـ online
        if (winner.isOnline()) {
            TitleUtils.sendWinner(winner);
            SoundUtils.playWinner(winner);
        }
        if (loser.isOnline()) {
            TitleUtils.sendLoser(loser);
            SoundUtils.playLoser(loser);
        }

        // ── Clear inventories immediately so kit doesn't carry over to spawn ──
        if (winner.isOnline()) {
            winner.getInventory().clear(); winner.setItemOnCursor(null);
            giveWinnerFlight(winner); // سيرفايفل + يقدر يطير لين ينقل للسبون
        }
        if (loser.isOnline()) {
            loser.getInventory().clear(); loser.setItemOnCursor(null);
            // الخاسر سبكتر عادي (بدون بوصلة/ريشة)
            makeDeadSpectator(loser);
            detachSpectatorCamera(loser, Math.max(20L, plugin.getConfig().getInt("settings.loser-spectator-seconds", 5) * 20L));
        }

        // ── Offer rematch AFTER clearing inventories ──
        int rematchDelay = plugin.getConfig().getInt("settings.winner-delay-seconds", 5);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (winner.isOnline() && loser.isOnline()) {
                plugin.getRematchManager().offer(winner, loser, session.getArena(), session.getBaseRounds());
            }
        }, rematchDelay * 20L);

        // Winner → send to spawn if no rematch offered (one-shot fallback, runs 2 ticks AFTER offer)
        // المشكلة السابقة: rematchDelay و winnerDelay كانوا نفس القيمة فكان Bukkit أحياناً يشغّل
        // الـ spawn-fallback قبل offer()، فيشوف isOffering=false ويطير للسبون بدون hotbar لأن
        // RematchManager ما أُبلغ بعد. الحل: نزيد تأخير الـ fallback بـ 2 tick فقط لضمان الترتيب.
        int winnerDelay = plugin.getConfig().getInt("settings.winner-delay-seconds", 5);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!winner.isOnline()) return;
            // If rematch is pending, RematchManager will handle teleport + hotbar
            if (plugin.getRematchManager().isOffering(winner)) return;
            // No rematch offered — send to spawn now
            clearFlight(winner);
            winner.setGameMode(GameMode.SURVIVAL);
            winner.teleport(getServerSpawn());
            giveMenuSword(winner);
            plugin.getTabIsolationManager().scheduleResync(winner);
            plugin.getSettingsManager().applyEffect(winner,
                    com.duel.managers.SettingsManager.Setting.HIDE_PLAYERS,
                    plugin.getSettingsManager().isEnabled(winner.getUniqueId(),
                            com.duel.managers.SettingsManager.Setting.HIDE_PLAYERS));
        }, winnerDelay * 20L + 2L); // +2 ticks بعد offer لضمان isOffering() صحيحة

        // Loser — لو offline ما نحتاج نفعل شيء (يرجع للسبون عند rejoin)
        int loserDelay = plugin.getConfig().getInt("settings.loser-spectator-seconds", 5);
        if (loser.isOnline()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!loser.isOnline()) return;
                // If rematch is pending, RematchManager will handle teleport + hotbar
                if (plugin.getRematchManager().isOffering(loser)) return;
                // No rematch offered — send to spawn now
                loser.setGameMode(GameMode.SURVIVAL);
                clearFlight(loser);
                loser.teleport(getServerSpawn());
                giveMenuSword(loser);
                plugin.getTabIsolationManager().scheduleResync(loser);
                plugin.getSettingsManager().applyEffect(loser,
                        com.duel.managers.SettingsManager.Setting.HIDE_PLAYERS,
                        plugin.getSettingsManager().isEnabled(loser.getUniqueId(),
                                com.duel.managers.SettingsManager.Setting.HIDE_PLAYERS));
            }, loserDelay * 20L + 2L); // +2 ticks بعد offer لضمان isOffering() صحيحة
        }

        // Clean up session and delete world (after both have been teleported)
        int cleanupDelay = Math.max(winnerDelay, loserDelay) + 2;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            sessions.remove(session.getSessionId());
            playerSession.remove(session.getPlayer1().getUniqueId(), session); // نفس الجلسة فقط (لا نمسح جلسة ريماتش جديدة)
            playerSession.remove(session.getPlayer2().getUniqueId(), session); // نفس الجلسة فقط (لا نمسح جلسة ريماتش جديدة)

            // Safety: teleport anyone still in the duel world to server spawn
            String copyName = session.getCopiedWorldName();
            org.bukkit.World duelWorld = org.bukkit.Bukkit.getWorld(copyName);
            if (duelWorld != null && !duelWorld.getPlayers().isEmpty()) {
                org.bukkit.Location fallback = getServerSpawn();
                for (Player stuckPlayer : new java.util.ArrayList<>(duelWorld.getPlayers())) {
                    stuckPlayer.teleport(fallback);
                }
                // Give Bukkit one more tick to process teleports before deleting
                plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                        plugin.getArenaManager().deleteWorld(copyName), 5L);
            } else {
                plugin.getArenaManager().deleteWorld(copyName);
            }
        }, cleanupDelay * 20L);
    }

    /**
     * يُنظف الجلسة بدون إعلان فائز — يُستخدم لما الاثنين offline.
     */
    public void forceCleanup(com.duel.models.DuelSession session) {
        if (session.getState() == com.duel.models.DuelSession.State.ENDED) return;
        session.setState(com.duel.models.DuelSession.State.ENDED);
        discardPendingWorld(session);
        if (session.getPingTask() != null) session.getPingTask().cancel();
        if (session.getCountdownTask() != null) {
            session.getCountdownTask().cancel();
            session.setCountdownTask(null);
        }
        unfreeze(session.getPlayer1());
        unfreeze(session.getPlayer2());
        plugin.getArenaManager().clearArenaPotions(session.getPlayer1());
        plugin.getArenaManager().clearArenaPotions(session.getPlayer2());
        try {
            plugin.getTabIsolationManager().restoreAll(new ArrayList<>(List.of(session.getPlayer1(), session.getPlayer2())));
        } catch (Throwable ignored) {}
        sessions.remove(session.getSessionId());
        playerSession.remove(session.getPlayer1().getUniqueId(), session); // نفس الجلسة فقط (لا نمسح جلسة ريماتش جديدة)
        playerSession.remove(session.getPlayer2().getUniqueId(), session); // نفس الجلسة فقط (لا نمسح جلسة ريماتش جديدة)

        // Teleport any players still in the duel world before deleting
        String copyName = session.getCopiedWorldName();
        org.bukkit.World duelWorld = org.bukkit.Bukkit.getWorld(copyName);
        if (duelWorld != null) {
            org.bukkit.Location fallback = getServerSpawn();
            for (Player stuckPlayer : new java.util.ArrayList<>(duelWorld.getPlayers())) {
                stuckPlayer.teleport(fallback);
            }
            plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                    plugin.getArenaManager().deleteWorld(copyName), 5L);
        } else {
            plugin.getArenaManager().deleteWorld(copyName);
        }
    }

    public int getActiveCountForArena(String arenaId) {
        return (int) sessions.values().stream()
                .filter(s -> s.getState() == DuelSession.State.FIGHTING
                          && s.getArena().getId().equalsIgnoreCase(arenaId))
                .count();
    }

    public java.util.Collection<DuelSession> getAllActiveSessions() {
        return sessions.values().stream()
                .filter(s -> s.getState() == DuelSession.State.FIGHTING)
                .toList();
    }

    public void cleanupAll() {
        for (DuelSession session : new ArrayList<>(sessions.values())) {
            plugin.getArenaManager().deleteWorld(session.getCopiedWorldName());
        }
        sessions.clear();
        playerSession.clear();
    }

    // ─────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────

    public DuelSession getSession(Player player) {
        return playerSession.get(player.getUniqueId());
    }

    public boolean isInDuel(Player player) {
        return playerSession.containsKey(player.getUniqueId()) || pendingStart.contains(player.getUniqueId());
    }

    private Location getServerSpawn() {
        return plugin.getSpawnManager().getSpawn();
    }

    private void giveMenuSword(Player player) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            plugin.getPlayerJoinListener().giveDuelSword(player);
            // Restore party hotbar items if player is in a party
            plugin.getPartyManager().refreshHotbar(player);
        }, 5L);
    }

    /** Apply the player's personal kit arrangement if they have one, else the arena default. */
    private void applyPersonalOrDefault(Player player, String arenaId, Kit defaultKit) {
        org.bukkit.inventory.ItemStack[] personal =
                plugin.getPlayerKitManager().getPlayerKit(player.getUniqueId(), arenaId);

        if (personal != null) {
            // Apply personal arrangement directly
            player.getInventory().clear(); player.setItemOnCursor(null);
            com.duel.models.Kit personalKit = new com.duel.models.Kit("__personal__", personal);
            plugin.getKitManager().applyKit(player, personalKit);
        } else if (defaultKit != null) {
            plugin.getKitManager().applyKit(player, defaultKit);
        }
    }

    /**
     * Apply the player's saved armor trims (for the given arena) to whatever armor
     * they currently have equipped. Must be called AFTER applyPersonalOrDefault so
     * armor is already set.
     */
    private void applyTrimsToPlayer(Player player, String arenaId) {
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        TrimManager trimManager = plugin.getTrimManager();
        UUID uuid = player.getUniqueId();

        org.bukkit.inventory.ItemStack[] trimmed = trimManager.applyTrims(
            player,
            arenaId,
            inv.getHelmet(),
            inv.getChestplate(),
            inv.getLeggings(),
            inv.getBoots()
        );
        // trimmed order: HELMET, CHESTPLATE, LEGGINGS, BOOTS
        if (trimmed[0] != null) inv.setHelmet(trimmed[0]);
        if (trimmed[1] != null) inv.setChestplate(trimmed[1]);
        if (trimmed[2] != null) inv.setLeggings(trimmed[2]);
        if (trimmed[3] != null) inv.setBoots(trimmed[3]);
        player.updateInventory();
    }

    /** Called from DuelListener when a player dies in a duel */
    public void onPlayerDeath(Player dead) {
        DuelSession session = getSession(dead);
        if (session == null || session.getState() != DuelSession.State.FIGHTING) return;
        Player opponent = session.getOpponent(dead);
        endDuel(session, opponent);
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
