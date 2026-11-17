package com.duel.tier;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import com.duel.models.DuelSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * نظام التيرات التنافسي (MCPvP-style) — لكل أرينا/kit بشكل مستقل:
 *  - Rating مخفي (ELO) يتحرك مع الفوز/الخسارة في مباريات الكيو.
 *  - لما يوصل عتبة التير التالي → Rankup Challenge (المباراة الجاية بالكيو تصير تحدي ترقية).
 *  - لو نزل تقييمه تحت عتبة تيره → Tier Defense ضد لاعب من نفس التير، والخسارة = نزول تير.
 *  - مواسم: أرشفة + soft reset، مع الاحتفاظ بالـ Peak. Hall of Legends من أرشيف الموسم السابق.
 */
public class TierManager implements Listener {

    public record TopEntry(String name, UUID uuid, int rating, Tier tier) {}

    /** سياق مباراة ranked: مين متحدّي ومين مدافع. */
    private static final class MatchContext {
        final String arenaKey;
        final Set<UUID> challengers = new HashSet<>();
        final Set<UUID> defenders = new HashSet<>();
        MatchContext(String arenaKey) { this.arenaKey = arenaKey; }
    }

    private final DuelPlugin plugin;

    // uuid → (arenaKey → profile). وجود الـ uuid كـ key = تم تحميله من القاعدة.
    private final Map<UUID, Map<String, TierProfile>> profiles = new ConcurrentHashMap<>();
    // "uuidA|uuidB" (مرتبين) → سياق المباراة الجارية/القادمة
    private final Map<String, MatchContext> contexts = new HashMap<>();
    // "uuidA|uuidB" → أوقات مباريات آخر ساعة (anti-farm)
    private final Map<String, ArrayDeque<Long>> pairHistory = new HashMap<>();

    private final Map<String, List<TopEntry>> topCache = new ConcurrentHashMap<>();
    private volatile boolean topDirty = true;

    private volatile int season = 1;
    private volatile long seasonStart = System.currentTimeMillis();

    public TierManager(DuelPlugin plugin) {
        this.plugin = plugin;
        loadMeta();
        startTopRefresher();
        startSeasonWatcher();
    }

    // ═════════════════════════════════════════════
    //  Config helpers
    // ═════════════════════════════════════════════

    private FileConfiguration cfg() { return plugin.getConfig(); }

    public boolean isEnabled()        { return cfg().getBoolean("tier.enabled", true); }
    public int getSeason()            { return season; }
    public int startRating()          { return cfg().getInt("tier.start-rating", 1000); }
    public int placementMatches()     { return cfg().getInt("tier.placement-matches", 3); }
    public int challengeRounds()      { return Math.max(1, cfg().getInt("tier.challenge-rounds", 3)); }
    private int defenseBuffer()       { return cfg().getInt("tier.defense-buffer", 60); }

    public int threshold(Tier t) {
        return cfg().getInt("tier.thresholds." + t.name(), t.defaultThreshold());
    }

    private static String key(String arenaId) { return arenaId.toLowerCase(); }

    private static String pairKey(UUID a, UUID b) {
        String x = a.toString(), y = b.toString();
        return x.compareTo(y) < 0 ? x + "|" + y : y + "|" + x;
    }

    private static String c(String s) { return s.replace('&', '§'); }

    // ═════════════════════════════════════════════
    //  Profiles (cache + DB)
    // ═════════════════════════════════════════════

    /** يرجّع بروفايل اللاعب (من الكاش). لو ما انحمّل بعد → بروفايل افتراضي مؤقت (بدون لمس القاعدة). */
    public TierProfile get(UUID uuid, String name, String arenaId) {
        String k = key(arenaId);
        Map<String, TierProfile> m = profiles.get(uuid);
        if (m == null) return new TierProfile(uuid, k, name, startRating());
        return m.computeIfAbsent(k, x -> new TierProfile(uuid, k, name, startRating()));
    }

    /** نفس get لكن مع تحديث حالة التحدي/الدفاع (انتهاء الـ cooldown، إلخ). */
    public TierProfile getFresh(UUID uuid, String name, String arenaId) {
        TierProfile p = get(uuid, name, arenaId);
        refreshFlags(p);
        return p;
    }

    public Tier getTier(UUID uuid, String arenaId) {
        Map<String, TierProfile> m = profiles.get(uuid);
        if (m == null) return Tier.LT5;
        TierProfile p = m.get(key(arenaId));
        return p == null ? Tier.LT5 : p.tier;
    }

    public Tier getBestTier(UUID uuid) {
        Map<String, TierProfile> m = profiles.get(uuid);
        Tier best = Tier.LT5;
        if (m != null) for (TierProfile p : m.values()) if (p.tier.ordinal() > best.ordinal()) best = p.tier;
        return best;
    }

    /** البروفايل ذو أعلى تير (عند التساوي: الأعلى تقييم) — أو null لو ما لعب. */
    public TierProfile getBestProfile(UUID uuid) {
        Map<String, TierProfile> m = profiles.get(uuid);
        if (m == null) return null;
        TierProfile best = null;
        for (TierProfile p : m.values()) {
            if (best == null || p.tier.ordinal() > best.tier.ordinal()
                    || (p.tier == best.tier && p.rating > best.rating)) best = p;
        }
        return best;
    }

    public void preloadAsync(UUID uuid) {
        if (profiles.containsKey(uuid)) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> loadPlayerRows(uuid));
    }

    private void ensureLoaded(UUID uuid) {
        if (!profiles.containsKey(uuid)) loadPlayerRows(uuid);
    }

    private void loadPlayerRows(UUID uuid) {
        Map<String, TierProfile> m = new ConcurrentHashMap<>();
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM tier_profiles WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String arena = rs.getString("arena_id");
                TierProfile p = new TierProfile(uuid, arena, rs.getString("name"), rs.getInt("rating"));
                p.tier = Tier.parse(rs.getString("tier"), Tier.LT5);
                p.peakTier = Tier.parse(rs.getString("peak_tier"), p.tier);
                p.seasonPeakRating = rs.getInt("season_peak_rating");
                p.games = rs.getInt("games");
                p.wins = rs.getInt("wins");
                p.losses = rs.getInt("losses");
                p.challengePending = rs.getInt("challenge_pending") == 1;
                p.challengeCooldown = rs.getLong("challenge_cooldown");
                p.defensePending = rs.getInt("defense_pending") == 1;
                m.put(arena, p);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[Tier] Failed to load profiles for " + uuid, e);
        }
        profiles.putIfAbsent(uuid, m);
    }

    private void save(TierProfile live) {
        final TierProfile p = live.copy();
        topDirty = true;
        Runnable r = () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO tier_profiles (uuid, arena_id, name, rating, tier, peak_tier, season_peak_rating,
                        games, wins, losses, challenge_pending, challenge_cooldown, defense_pending)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(uuid, arena_id) DO UPDATE SET
                        name=excluded.name, rating=excluded.rating, tier=excluded.tier,
                        peak_tier=excluded.peak_tier, season_peak_rating=excluded.season_peak_rating,
                        games=excluded.games, wins=excluded.wins, losses=excluded.losses,
                        challenge_pending=excluded.challenge_pending,
                        challenge_cooldown=excluded.challenge_cooldown,
                        defense_pending=excluded.defense_pending
                 """)) {
                ps.setString(1, p.uuid.toString());
                ps.setString(2, p.arenaId);
                ps.setString(3, p.name);
                ps.setInt(4, p.rating);
                ps.setString(5, p.tier.name());
                ps.setString(6, p.peakTier.name());
                ps.setInt(7, p.seasonPeakRating);
                ps.setInt(8, p.games);
                ps.setInt(9, p.wins);
                ps.setInt(10, p.losses);
                ps.setInt(11, p.challengePending ? 1 : 0);
                ps.setLong(12, p.challengeCooldown);
                ps.setInt(13, p.defensePending ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "[Tier] Failed to save profile", e);
            }
        };
        if (plugin.isEnabled()) plugin.getServer().getScheduler().runTaskAsynchronously(plugin, r);
        else r.run();
    }

    // ═════════════════════════════════════════════
    //  Rankup / Defense flags
    // ═════════════════════════════════════════════

    /** يحدّث حالة التحدي والدفاع حسب التقييم الحالي. */
    public void refreshFlags(TierProfile p) {
        Tier next = p.tier.next();
        boolean defense = p.tier != Tier.LT5 && p.rating < threshold(p.tier) - defenseBuffer();
        p.defensePending = defense;

        if (next == null || defense || p.rating < threshold(next)) {
            p.challengePending = false;
        } else if (System.currentTimeMillis() >= p.challengeCooldown) {
            p.challengePending = true;
        } else {
            p.challengePending = false; // cooldown شغّال
        }
    }

    // ═════════════════════════════════════════════
    //  Matchmaking
    // ═════════════════════════════════════════════

    /**
     * هل اللاعبين مناسبين لبعض؟ (PaffMC-style): مدى ±base ويتوسع كل ثانية انتظار، وبعد any-after ثانية أي خصم.
     * التحدي يحتاج خصم تيره >= تير المتحدّي، والدفاع يحتاج خصم بنفس التير — ويتخفف الشرط بعد relax ثانية.
     */
    public boolean isCompatible(Player a, Player b, String arenaId, long waitSeconds) {
        if (!isEnabled()) return true;
        int anyAfter = cfg().getInt("tier.matchmaking.any-opponent-after-seconds", 90);
        if (waitSeconds >= anyAfter) return true;

        TierProfile pa = getFresh(a.getUniqueId(), a.getName(), arenaId);
        TierProfile pb = getFresh(b.getUniqueId(), b.getName(), arenaId);

        int base = cfg().getInt("tier.matchmaking.base-range", 100);
        int expand = cfg().getInt("tier.matchmaking.expand-per-second", 10);
        int max = cfg().getInt("tier.matchmaking.max-range", 600);
        int range = (int) Math.min(max, base + expand * waitSeconds);
        if (Math.abs(pa.rating - pb.rating) > range) return false;

        int relax = cfg().getInt("tier.matchmaking.special-relax-after-seconds", 45);
        if (waitSeconds < relax) {
            if (pa.challengePending && pb.tier.ordinal() < pa.tier.ordinal()) return false;
            if (pb.challengePending && pa.tier.ordinal() < pb.tier.ordinal()) return false;
            if (pa.defensePending && pb.tier != pa.tier) return false;
            if (pb.defensePending && pa.tier != pb.tier) return false;
        }
        return true;
    }

    /**
     * يُستدعى لما ينلقى خصم بالكيو (قبل startDuel). يحدد إذا المباراة تحدي ترقية/دفاع، ويرجّع عدد الراوندات.
     */
    public int prepareMatch(Player p1, Player p2, String arenaId) {
        if (!isEnabled()) return 1;
        ensureLoaded(p1.getUniqueId());
        ensureLoaded(p2.getUniqueId());

        MatchContext ctx = new MatchContext(key(arenaId));
        for (Player p : new Player[]{p1, p2}) {
            TierProfile prof = getFresh(p.getUniqueId(), p.getName(), arenaId);
            if (prof.challengePending && prof.tier.next() != null) {
                ctx.challengers.add(p.getUniqueId());
                p.sendMessage(c("&6&l★ RANKUP CHALLENGE &7— win to reach " + prof.tier.next().colored()
                        + " &7(best-of-" + challengeRounds() + ")"));
            }
            if (prof.defensePending) {
                ctx.defenders.add(p.getUniqueId());
                p.sendMessage(c("&c&l⚠ TIER DEFENSE &7— lose and you drop from " + prof.tier.colored()));
            }
        }
        contexts.put(pairKey(p1.getUniqueId(), p2.getUniqueId()), ctx);
        return ctx.challengers.isEmpty() ? 1 : challengeRounds();
    }

    // ═════════════════════════════════════════════
    //  Match end → rating / challenge / defense
    // ═════════════════════════════════════════════

    private static double expected(int ra, int rb) {
        return 1.0 / (1.0 + Math.pow(10.0, (rb - ra) / 400.0));
    }

    private int kFor(TierProfile p) {
        return p.games < placementMatches()
                ? cfg().getInt("tier.placement-k", 40)
                : cfg().getInt("tier.k-factor", 32);
    }

    private static boolean sameIp(Player a, Player b) {
        try {
            InetSocketAddress x = a.getAddress(), y = b.getAddress();
            return x != null && y != null && x.getAddress() != null && x.getAddress().equals(y.getAddress());
        } catch (Throwable t) { return false; }
    }

    /** يُستدعى من DuelManager.endMatch لمباريات الكيو فقط (session.isRanked()). */
    public void onMatchEnd(DuelSession session, Player winner, Player loser) {
        if (!isEnabled()) return;
        String arenaId = session.getArena().getId();
        UUID wid = winner.getUniqueId(), lid = loser.getUniqueId();
        ensureLoaded(wid);
        ensureLoaded(lid);

        TierProfile pw = get(wid, winner.getName(), arenaId);
        TierProfile pl = get(lid, loser.getName(), arenaId);
        pw.name = winner.getName();
        pl.name = loser.getName();

        String pk = pairKey(wid, lid);
        MatchContext ctx = contexts.remove(pk);
        if (ctx != null && !ctx.arenaKey.equals(key(arenaId))) ctx = null;

        // ── حماية من الاستغلال ──
        long now = System.currentTimeMillis();
        String unrated = null;

        if (!session.isRoundMatch()) {
            long durSec = (now - session.getStartTime()) / 1000L;
            if (durSec < cfg().getInt("tier.min-match-seconds", 10)) unrated = "match too short";
        }
        if (unrated == null && cfg().getBoolean("tier.anti-abuse.same-ip-unrated", true) && sameIp(winner, loser)) {
            unrated = "same connection";
        }
        ArrayDeque<Long> dq = pairHistory.computeIfAbsent(pk, x -> new ArrayDeque<>());
        while (!dq.isEmpty() && now - dq.peekFirst() > 3_600_000L) dq.pollFirst();
        if (unrated == null && dq.size() >= cfg().getInt("tier.anti-abuse.pair-limit-per-hour", 3)) {
            unrated = "same opponent too often";
        }
        dq.addLast(now);

        if (unrated != null) {
            String msg = c("&8[&bTier&8] &7This match didn't count for rating &8(" + unrated + ")&7.");
            if (winner.isOnline()) winner.sendMessage(msg);
            if (loser.isOnline())  loser.sendMessage(msg);
            return;
        }

        // ── ELO ──
        int oldW = pw.rating, oldL = pl.rating;
        double ew = expected(pw.rating, pl.rating);
        int dw = Math.max(1, (int) Math.round(kFor(pw) * (1.0 - ew)));
        int dl = Math.max(1, (int) Math.round(kFor(pl) * ew));
        int floor = cfg().getInt("tier.rating-floor", 0);
        pw.rating += dw;
        pl.rating = Math.max(floor, pl.rating - dl);
        pw.games++; pl.games++;
        pw.wins++;  pl.losses++;
        pw.seasonPeakRating = Math.max(pw.seasonPeakRating, pw.rating);

        // ── التحدي / الدفاع ──
        if (ctx != null) {
            if (ctx.challengers.contains(wid)) resolveChallenge(winner, pw, true, arenaId);
            if (ctx.challengers.contains(lid)) resolveChallenge(loser,  pl, false, arenaId);
            if (ctx.defenders.contains(wid))   resolveDefense(winner, pw, true, arenaId);
            if (ctx.defenders.contains(lid))   resolveDefense(loser,  pl, false, arenaId);
        }

        // تحقق من فتح تحدي جديد / طلب دفاع
        boolean hadChW = pw.challengePending, hadDefW = pw.defensePending;
        boolean hadChL = pl.challengePending, hadDefL = pl.defensePending;
        refreshFlags(pw);
        refreshFlags(pl);

        save(pw);
        save(pl);

        notifyResult(winner, pw, oldW, "&a+" + dw, arenaId, !hadChW && pw.challengePending, !hadDefW && pw.defensePending);
        notifyResult(loser,  pl, oldL, "&c-" + (oldL - pl.rating), arenaId, !hadChL && pl.challengePending, !hadDefL && pl.defensePending);
    }

    private void resolveChallenge(Player p, TierProfile prof, boolean won, String arenaId) {
        Tier target = prof.tier.next();
        if (target == null) { prof.challengePending = false; return; }
        if (won) {
            final Tier oldTier = prof.tier;
            prof.tier = target;
            if (target.ordinal() > prof.peakTier.ordinal()) prof.peakTier = target;
            prof.challengePending = false;
            prof.challengeCooldown = 0L;
            prof.rating = Math.max(prof.rating, threshold(target));
            prof.seasonPeakRating = Math.max(prof.seasonPeakRating, prof.rating);
            if (p.isOnline()) {
                p.sendMessage(c("&6&l★ RANKED UP! &7You are now " + target.colored() + " &7in &f" + arenaId));
                showRankUp(p, oldTier, target, arenaId);
            }
            Tier from = Tier.parse(cfg().getString("tier.broadcast-from", "LT2"), Tier.LT2);
            if (target.ordinal() >= from.ordinal()) {
                Bukkit.broadcast(LegacyComponentSerializer.builder().character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build().deserialize(
                        c("&8[&bTier&8] &f" + p.getName() + " &7reached " + target.colored() + " &7in &f" + arenaId + "&7!")));
            }
        } else {
            prof.challengePending = false;
            int mins = cfg().getInt("tier.challenge-cooldown-minutes", 30);
            prof.challengeCooldown = System.currentTimeMillis() + mins * 60_000L;
            if (p.isOnline()) {
                p.sendMessage(c("&c✖ Challenge failed. &7You can try again in &f" + mins + " &7minutes."));
            }
        }
    }

    private void resolveDefense(Player p, TierProfile prof, boolean won, String arenaId) {
        if (won) {
            prof.defensePending = false;
            int restore = threshold(prof.tier) - defenseBuffer() / 2;
            prof.rating = Math.max(prof.rating, restore);
            if (p.isOnline()) p.sendMessage(c("&a✔ Defense successful! &7You keep " + prof.tier.colored()));
        } else {
            Tier down = prof.tier.prev();
            prof.defensePending = false;
            if (down != null) {
                prof.tier = down;
                prof.rating = Math.max(prof.rating, threshold(down));
                prof.challengeCooldown = 0L;
                if (p.isOnline()) {
                    p.sendMessage(c("&c✖ Defense failed. &7You dropped to " + down.colored() + " &7in &f" + arenaId));
                    showTitle(p, "&c&lTIER LOST", down.colored() + " &7— " + arenaId);
                }
            }
        }
    }

    private void notifyResult(Player p, TierProfile prof, int oldRating, String delta, String arenaId,
                              boolean challengeUnlocked, boolean defenseRequired) {
        if (!p.isOnline()) return;
        p.sendMessage(c("&8[&bTier&8] &f" + arenaId + " &8| " + prof.tier.colored() + " &8| " + delta
                + " &7(" + oldRating + " → &f" + prof.rating + "&7)"));
        if (challengeUnlocked && prof.tier.next() != null) {
            p.sendMessage(c("&6&l★ RANKUP CHALLENGE UNLOCKED &7— queue &f" + arenaId + " &7to fight for " + prof.tier.next().colored()));
            showTitle(p, "&6&l★ CHALLENGE", "&7Queue to rank up to " + prof.tier.next().colored());
        }
        if (defenseRequired) {
            p.sendMessage(c("&c&l⚠ TIER DEFENSE &7— your rating is too low for " + prof.tier.colored()
                    + "&7. Your next ranked match in &f" + arenaId + " &7is a defense."));
        }
    }

    /** عنوان الترقية: التير القديم » التير الجديد (بألوانهم) + كلام تحته + صوت. */
    private void showRankUp(Player p, Tier from, Tier to, String arenaId) {
        try {
            LegacyComponentSerializer s = LegacyComponentSerializer.builder().character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
            Component main = s.deserialize(c(from.colored() + " &8\u00bb\u00bb\u00bb &f" + "" + to.colored()));
            Component sub = s.deserialize(c("&a\u2728 Congratulations! &7You were promoted to " + to.colored()
                    + " &7in &f" + arenaId));
            p.showTitle(Title.title(main, sub, Title.Times.times(
                    java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(3500), java.time.Duration.ofMillis(800))));
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            }, 8L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.0f, 1.0f);
            }, 16L);
        } catch (Throwable t) {
            showTitle(p, "&6&lRANK UP!", to.colored() + " &7\u2014 " + arenaId);
        }
    }

    private void showTitle(Player p, String title, String sub) {
        try {
            LegacyComponentSerializer s = LegacyComponentSerializer.builder().character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
            p.showTitle(Title.title(s.deserialize(c(title)), s.deserialize(c(sub))));
        } catch (Throwable ignored) {}
    }

    // ═════════════════════════════════════════════
    //  Leaderboard
    // ═════════════════════════════════════════════

    public List<TopEntry> getTop(String arenaId) {
        return topCache.getOrDefault(key(arenaId), List.of());
    }

    /** مركز اللاعب بتقييم الأرينا (1 = الأول). يرجّع -1 لو ما لعب ranked بهالأرينا. يُستدعى من أي thread، والـ callback على الـ main thread. */
    public void getRankAsync(UUID uuid, String arenaId, Consumer<Integer> cb) {
        final String arena = key(arenaId);
        final Map<String, TierProfile> pm = profiles.get(uuid);
        final TierProfile prof = pm == null ? null : pm.get(arena);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            int rank = -1;
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                int rating;
                int games;
                if (prof != null) {
                    rating = prof.rating;
                    games = prof.games;
                } else {
                    rating = startRating();
                    games = 0;
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT rating, games FROM tier_profiles WHERE uuid = ? AND arena_id = ?")) {
                        ps.setString(1, uuid.toString());
                        ps.setString(2, arena);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) { rating = rs.getInt(1); games = rs.getInt(2); }
                    }
                }
                if (games > 0) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT COUNT(*) FROM tier_profiles WHERE arena_id = ? AND games > 0 AND rating > ?")) {
                        ps.setString(1, arena);
                        ps.setInt(2, rating);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) rank = rs.getInt(1) + 1;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "[Tier] rank query failed", e);
            }
            final int result = rank;
            plugin.getServer().getScheduler().runTask(plugin, () -> cb.accept(result));
        });
    }

    private void startTopRefresher() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!topDirty) return;
            topDirty = false;
            List<String> ids = new ArrayList<>();
            for (Arena a : plugin.getArenaManager().getAllArenas()) ids.add(key(a.getId()));
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                for (String id : ids) {
                    List<TopEntry> list = new ArrayList<>();
                    try (Connection conn = plugin.getDatabaseManager().getConnection();
                         PreparedStatement ps = conn.prepareStatement(
                                 "SELECT uuid, name, rating, tier FROM tier_profiles WHERE arena_id = ? AND games > 0 " +
                                 "ORDER BY rating DESC LIMIT 10")) {
                        ps.setString(1, id);
                        ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            list.add(new TopEntry(rs.getString("name"), UUID.fromString(rs.getString("uuid")),
                                    rs.getInt("rating"), Tier.parse(rs.getString("tier"), Tier.LT5)));
                        }
                    } catch (SQLException e) {
                        plugin.getLogger().log(Level.SEVERE, "[Tier] top refresh failed", e);
                    }
                    topCache.put(id, list);
                }
            });
        }, 100L, 200L);
    }

    // ═════════════════════════════════════════════
    //  Seasons
    // ═════════════════════════════════════════════

    private void loadMeta() {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT meta_key, meta_value FROM tier_meta");
            boolean hasStart = false;
            while (rs.next()) {
                String k = rs.getString(1), v = rs.getString(2);
                if ("season".equals(k)) season = Integer.parseInt(v);
                if ("season_start".equals(k)) { seasonStart = Long.parseLong(v); hasStart = true; }
            }
            if (!hasStart) writeMeta(conn, "season_start", String.valueOf(seasonStart));
            writeMeta(conn, "season", String.valueOf(season));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Tier] meta load failed", e);
        }
    }

    private static void writeMeta(Connection conn, String k, String v) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tier_meta (meta_key, meta_value) VALUES (?, ?) ON CONFLICT(meta_key) DO UPDATE SET meta_value = excluded.meta_value")) {
            ps.setString(1, k);
            ps.setString(2, v);
            ps.executeUpdate();
        }
    }

    private void startSeasonWatcher() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int days = cfg().getInt("tier.season.auto-end-days", 0);
            if (days <= 0) return;
            if (System.currentTimeMillis() - seasonStart >= days * 86_400_000L) {
                endSeason(msg -> plugin.getLogger().info("[Tier] " + msg));
            }
        }, 6000L, 6000L);
    }

    private volatile boolean seasonEnding = false;

    /** ينهي الموسم: أرشفة → soft reset للتقييم → رفع رقم الموسم. التيرات تنخفض حسب التقييم الجديد (بدون ما ترتفع). */
    public void endSeason(Consumer<String> done) {
        if (seasonEnding) { done.accept("Season end already in progress."); return; }
        seasonEnding = true;
        final int ended = season;
        final int start = startRating();
        final double keep = cfg().getDouble("tier.season.soft-reset-keep", 0.5);
        final int[] th = new int[Tier.values().length];
        for (Tier t : Tier.values()) th[t.ordinal()] = threshold(t);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String result;
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                conn.setAutoCommit(false);
                try {
                    long now = System.currentTimeMillis();
                    try (Statement st = conn.createStatement()) {
                        st.execute("INSERT INTO tier_history (season, uuid, arena_id, name, rating, tier, peak_tier, peak_rating, games, ended_at) " +
                                "SELECT " + ended + ", uuid, arena_id, name, rating, tier, peak_tier, season_peak_rating, games, " + now +
                                " FROM tier_profiles WHERE games > 0");
                    }
                    List<Object[]> rows = new ArrayList<>();
                    try (Statement st = conn.createStatement()) {
                        ResultSet rs = st.executeQuery("SELECT uuid, arena_id, rating, tier FROM tier_profiles");
                        while (rs.next()) rows.add(new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)});
                    }
                    try (PreparedStatement up = conn.prepareStatement(
                            "UPDATE tier_profiles SET rating=?, tier=?, season_peak_rating=?, games=0, wins=0, losses=0, " +
                            "challenge_pending=0, challenge_cooldown=0, defense_pending=0 WHERE uuid=? AND arena_id=?")) {
                        for (Object[] r : rows) {
                            int rating = (Integer) r[2];
                            Tier old = Tier.parse((String) r[3], Tier.LT5);
                            int nr = start + (int) Math.round((rating - start) * keep);
                            Tier nt = Tier.LT5;
                            for (Tier t : Tier.values()) if (th[t.ordinal()] <= nr) nt = t;
                            if (nt.ordinal() > old.ordinal()) nt = old;
                            up.setInt(1, nr);
                            up.setString(2, nt.name());
                            up.setInt(3, nr);
                            up.setString(4, (String) r[0]);
                            up.setString(5, (String) r[1]);
                            up.addBatch();
                        }
                        up.executeBatch();
                    }
                    writeMeta(conn, "season", String.valueOf(ended + 1));
                    writeMeta(conn, "season_start", String.valueOf(now));
                    conn.commit();
                    result = "Season " + ended + " ended (" + rows.size() + " profiles archived). Season " + (ended + 1) + " started.";
                    final long ns = now;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        season = ended + 1;
                        seasonStart = ns;
                        profiles.clear();
                        contexts.clear();
                        topDirty = true;
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            preloadAsync(p.getUniqueId());
                            p.sendMessage(c("&8[&bTier&8] &6Season " + ended + " has ended! &7Season &f" + (ended + 1) + " &7begins — ratings were soft-reset."));
                        }
                    });
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "[Tier] Season end FAILED", e);
                result = "Season end failed: " + e.getMessage();
            }
            seasonEnding = false;
            final String out = result;
            plugin.getServer().getScheduler().runTask(plugin, () -> done.accept(out));
        });
    }

    /** Hall of Legends: أفضل 5 تقييمات بالأرينا في الموسم السابق (يرجع من الـ thread الرئيسي). */
    public void hallOfLegends(String arenaId, Consumer<List<String>> cb) {
        final int last = season - 1;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> lines = new ArrayList<>();
            if (last >= 1) {
                try (Connection conn = plugin.getDatabaseManager().getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "SELECT name, peak_rating, tier, peak_tier FROM tier_history WHERE season = ? AND arena_id = ? " +
                             "ORDER BY peak_rating DESC LIMIT 5")) {
                    ps.setInt(1, last);
                    ps.setString(2, key(arenaId));
                    ResultSet rs = ps.executeQuery();
                    int i = 1;
                    while (rs.next()) {
                        Tier t = Tier.parse(rs.getString("tier"), Tier.LT5);
                        lines.add("&e#" + i++ + " &f" + rs.getString("name") + " &8— &7peak &f" + rs.getInt("peak_rating")
                                + " &8| &7finished " + t.colored());
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "[Tier] hall query failed", e);
                }
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> cb.accept(lines));
        });
    }

    // ═════════════════════════════════════════════
    //  Admin
    // ═════════════════════════════════════════════

    public void adminSetRating(Player target, String arenaId, int rating) {
        ensureLoaded(target.getUniqueId());
        TierProfile p = get(target.getUniqueId(), target.getName(), arenaId);
        p.rating = Math.max(0, rating);
        p.seasonPeakRating = Math.max(p.seasonPeakRating, p.rating);
        refreshFlags(p);
        save(p);
    }

    public void adminSetTier(Player target, String arenaId, Tier tier) {
        ensureLoaded(target.getUniqueId());
        TierProfile p = get(target.getUniqueId(), target.getName(), arenaId);
        p.tier = tier;
        if (tier.ordinal() > p.peakTier.ordinal()) p.peakTier = tier;
        p.rating = Math.max(p.rating, threshold(tier));
        p.challengeCooldown = 0L;
        refreshFlags(p);
        save(p);
    }

    /** arenaId == null → كل الأرينات. */
    public void adminReset(Player target, String arenaId) {
        ensureLoaded(target.getUniqueId());
        Map<String, TierProfile> m = profiles.get(target.getUniqueId());
        if (m == null) return;
        List<String> keys = new ArrayList<>();
        if (arenaId == null) keys.addAll(m.keySet()); else keys.add(key(arenaId));
        for (String k : keys) {
            TierProfile fresh = new TierProfile(target.getUniqueId(), k, target.getName(), startRating());
            m.put(k, fresh);
            save(fresh);
        }
    }

    // ═════════════════════════════════════════════
    //  Listener
    // ═════════════════════════════════════════════

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        preloadAsync(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof TierGUI) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof TierGUI) e.setCancelled(true);
    }
}
