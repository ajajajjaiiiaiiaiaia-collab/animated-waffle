package com.duel.managers;

import com.duel.DuelPlugin;
import com.duel.gui.GameSelectorGUI;
import com.duel.models.Arena;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * نظام الكيو (Multi-Queue):
 *  - اللاعب يقدر يدخل أكثر من كيو في نفس الوقت.
 *  - أول كيو يلقى خصم يُعتمد مباشرة، ويُلغى باقي كيوهات اللاعبين (الاثنين) تلقائياً.
 */
public class QueueManager {

    private final DuelPlugin plugin;

    // arenaId(lowercase) → ordered list of waiting players
    private final Map<String, List<Player>> queues = new HashMap<>();
    // player → (arenaId lowercase → arena display id) بترتيب الدخول
    private final Map<UUID, LinkedHashMap<String, String>> playerArenas = new HashMap<>();
    // player → task for actionbar update
    private final Map<UUID, BukkitTask> actionbarTasks = new HashMap<>();
    // player → first queue join time (millis)
    private final Map<UUID, Long> joinTimes = new HashMap<>();

    // ── كيو الأرينا العشوائية (السهم) ──
    public static final String RANDOM_ID = "*random*";
    private static final String RANDOM_LABEL = "Random";
    // لاعبين كانوا ينتظرون في الكيو العشوائي وانلقى لهم خصم من لاعب دخل أرينا معينة
    private final Set<UUID> randomMatched = new HashSet<>();

    /** نتيجة دخول الكيو العشوائي لما ينلقى خصم فوراً. opponentWasRandom = الخصم كان ينتظر في الكيو العشوائي. */
    public record RandomMatch(Player opponent, Arena arena, boolean opponentWasRandom) {}

    /** يُستدعى لما الـ ticker يلقى زوج متوافق بالتقييم (بعد توسع المدى). */
    public interface MatchHandler {
        void onMatch(Player a, Player b, Arena arena, boolean aRandom, boolean bRandom);
    }
    private MatchHandler matchHandler;
    public void setMatchHandler(MatchHandler h) { this.matchHandler = h; }

    public QueueManager(DuelPlugin plugin) {
        this.plugin = plugin;
        // كل ثانية: نحاول نزاوج اللاعبين المنتظرين (المدى يتوسع مع وقت الانتظار)
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickMatchmaking, 40L, 20L);
    }

    private long waitSeconds(Player p) {
        Long t = joinTimes.get(p.getUniqueId());
        return t == null ? 0L : (System.currentTimeMillis() - t) / 1000L;
    }

    /** توافق التقييم (نظام التيرات). لو النظام مطفي/غير جاهز → أي خصم. */
    private boolean compatible(Player a, Player b, String arenaId) {
        com.duel.tier.TierManager tm = plugin.getTierManager();
        if (tm == null || !tm.isEnabled()) return true;
        return tm.isCompatible(a, b, arenaId, Math.max(waitSeconds(a), waitSeconds(b)));
    }

    private void tickMatchmaking() {
        if (matchHandler == null) return;
        com.duel.tier.TierManager tm = plugin.getTierManager();
        if (tm == null || !tm.isEnabled()) return;

        // كيو الأرينات المحددة
        for (Map.Entry<String, List<Player>> e : new ArrayList<>(queues.entrySet())) {
            String id = e.getKey();
            if (id.equals(RANDOM_ID)) continue;
            List<Player> list = e.getValue();
            if (list.size() < 2) continue;
            Arena arena = plugin.getArenaManager().getArena(id);
            if (arena == null) continue;
            Player fa = null, fb = null;
            outer:
            for (int i = 0; i < list.size(); i++) {
                Player a = list.get(i);
                if (!a.isOnline()) continue;
                for (int j = i + 1; j < list.size(); j++) {
                    Player b = list.get(j);
                    if (!b.isOnline()) continue;
                    if (compatible(a, b, id)) { fa = a; fb = b; break outer; }
                }
            }
            if (fa != null) {
                removeFromQueue(fa);
                removeFromQueue(fb);
                matchHandler.onMatch(fa, fb, arena, false, false);
            }
        }

        // الكيو العشوائي (الأرينا تنختار مخفية)
        List<Player> rq = queues.get(RANDOM_ID);
        if (rq != null && rq.size() >= 2) {
            Arena picked = com.duel.utils.RandomArena.pick(plugin);
            if (picked != null) {
                Player fa = null, fb = null;
                outer2:
                for (int i = 0; i < rq.size(); i++) {
                    Player a = rq.get(i);
                    if (!a.isOnline()) continue;
                    for (int j = i + 1; j < rq.size(); j++) {
                        Player b = rq.get(j);
                        if (!b.isOnline()) continue;
                        if (compatible(a, b, picked.getId())) { fa = a; fb = b; break outer2; }
                    }
                }
                if (fa != null) {
                    removeFromQueue(fa);
                    removeFromQueue(fb);
                    matchHandler.onMatch(fa, fb, picked, true, true);
                }
            }
        }
    }

    /** هل اللاعب في الكيو العشوائي؟ */
    public boolean isQueuedRandom(Player player) {
        LinkedHashMap<String, String> m = playerArenas.get(player.getUniqueId());
        return m != null && m.containsKey(RANDOM_ID);
    }

    public int getRandomQueueSize() {
        List<Player> q = queues.get(RANDOM_ID);
        return q == null ? 0 : q.size();
    }

    /** يرجّع true (مرة وحدة) لو هذا اللاعب انلقى له خصم وهو في الكيو العشوائي (عن طريق دخول لاعب في أرينا معينة). */
    public boolean consumeRandomMatched(Player player) {
        return randomMatched.remove(player.getUniqueId());
    }

    /** يطلع اللاعب من الكيو العشوائي فقط. */
    public void removeFromRandom(Player player) {
        LinkedHashMap<String, String> m = playerArenas.get(player.getUniqueId());
        if (m == null || m.remove(RANDOM_ID) == null) return;
        List<Player> queue = queues.get(RANDOM_ID);
        if (queue != null) queue.remove(player);
        if (m.isEmpty()) removeFromQueue(player);
        else scheduleGuiRefresh();
    }

    /**
     * يدخل اللاعب الكيو العشوائي:
     *  1) لو في لاعب ينتظر في الكيو العشوائي → أرينا عشوائية.
     *  2) وإلا لو في لاعب ينتظر في أي أرينا → يلعبون في أرينته (اللاعب ما يعرف أي أرينا إلا بعد النقل).
     *  3) وإلا ينتظر. يرجّع null لو انتظر.
     */
    public RandomMatch addToRandomQueue(Player player) {
        if (plugin.getPartyManager().isInParty(player)) return null;
        if (isQueuedRandom(player)) return null;
        UUID uuid = player.getUniqueId();
        randomMatched.remove(uuid);

        plugin.getDuelRequestManager().cancelAllFor(player);

        List<Player> rq = queues.computeIfAbsent(RANDOM_ID, k -> new ArrayList<>());

        // 1) خصم عشوائي ثاني (متوافق بالتقييم)
        Player opp = null;
        Arena picked = rq.isEmpty() ? null : com.duel.utils.RandomArena.pick(plugin);
        if (picked != null) {
            for (Player p : rq) {
                if (p.isOnline() && !p.equals(player) && compatible(player, p, picked.getId())) { opp = p; break; }
            }
        }
        if (opp != null) {
            removeFromQueue(opp);
            removeFromQueue(player);
            return new RandomMatch(opp, picked, true);
        }

        // 2) أقدم لاعب ينتظر في أرينا معينة
        Player best = null;
        String bestArena = null;
        long bestTime = Long.MAX_VALUE;
        for (Map.Entry<String, List<Player>> e : queues.entrySet()) {
            if (e.getKey().equals(RANDOM_ID)) continue;
            for (Player p : e.getValue()) {
                if (!p.isOnline() || p.equals(player)) continue;
                if (!compatible(player, p, e.getKey())) continue;
                long t = joinTimes.getOrDefault(p.getUniqueId(), System.currentTimeMillis());
                if (t < bestTime) { bestTime = t; best = p; bestArena = e.getKey(); }
                break;
            }
        }
        if (best != null) {
            Arena a = plugin.getArenaManager().getArena(bestArena);
            if (a != null) {
                removeFromQueue(best);
                removeFromQueue(player);
                return new RandomMatch(best, a, false);
            }
        }

        // 3) انتظر
        rq.add(player);
        playerArenas.computeIfAbsent(uuid, k -> new LinkedHashMap<>()).put(RANDOM_ID, RANDOM_LABEL);
        joinTimes.putIfAbsent(uuid, System.currentTimeMillis());
        startActionbar(player);
        scheduleGuiRefresh();
        return null;
    }

    /** هل اللاعب في أي كيو؟ */
    public boolean isQueued(Player player) {
        LinkedHashMap<String, String> m = playerArenas.get(player.getUniqueId());
        return m != null && !m.isEmpty();
    }

    /** هل اللاعب في كيو هذي الأرينا بالذات؟ */
    public boolean isQueuedFor(Player player, Arena arena) {
        LinkedHashMap<String, String> m = playerArenas.get(player.getUniqueId());
        return m != null && m.containsKey(arena.getId().toLowerCase());
    }

    /** أول أرينا دخلها اللاعب (للتوافق مع الكود القديم) */
    public String getQueuedArena(Player player) {
        LinkedHashMap<String, String> m = playerArenas.get(player.getUniqueId());
        if (m == null || m.isEmpty()) return null;
        return m.values().iterator().next();
    }

    public List<String> getQueuedArenas(Player player) {
        LinkedHashMap<String, String> m = playerArenas.get(player.getUniqueId());
        return m == null ? List.of() : new ArrayList<>(m.values());
    }

    /**
     * يضيف اللاعب لكيو أرينا معيّنة (بدون ما يطلعه من كيوهاته الثانية).
     * لو لقى خصم ينتظر في نفس الأرينا → يرجّعه، ويشيل الاثنين من كل الكيوهات.
     * وإلا يرجّع null.
     */
    public Player addToQueue(Player player, Arena arena) {
        // حماية مركزية — عضو البارتي ما يدخل كيو 1v1 من أي مسار
        if (plugin.getPartyManager().isInParty(player)) return null;

        String id = arena.getId().toLowerCase();
        UUID uuid = player.getUniqueId();
        randomMatched.remove(uuid);

        // already in this arena's queue — ignore
        if (isQueuedFor(player, arena)) return null;

        // دخول الانتظار يلغي أي دعوة دول معلّقة (مرسلة أو مستلمة)
        plugin.getDuelRequestManager().cancelAllFor(player);

        List<Player> queue = queues.computeIfAbsent(id, k -> new ArrayList<>());

        // أقدم لاعب ينتظر في نفس الأرينا
        Player opponent = null;
        for (Player p : queue) {
            if (p.isOnline() && !p.equals(player) && compatible(player, p, id)) { opponent = p; break; }
        }

        // ما في أحد بنفس الأرينا → أي لاعب ينتظر في الكيو العشوائي (يلعب في أرينتي)
        if (opponent == null) {
            List<Player> rq = queues.get(RANDOM_ID);
            if (rq != null) {
                for (Player p : rq) {
                    if (p.isOnline() && !p.equals(player) && compatible(player, p, id)) {
                        opponent = p;
                        randomMatched.add(p.getUniqueId());
                        break;
                    }
                }
            }
        }

        if (opponent != null) {
            // أول كيو يلقى خصم يُؤخذ — ونلغي باقي الكيوهات للاثنين
            removeFromQueue(opponent);
            removeFromQueue(player);
            return opponent;
        }

        queue.add(player);
        playerArenas.computeIfAbsent(uuid, k -> new LinkedHashMap<>()).put(id, arena.getId());
        joinTimes.putIfAbsent(uuid, System.currentTimeMillis());

        startActionbar(player);
        scheduleGuiRefresh();
        return null;
    }

    /** يطلع اللاعب من كيو أرينا وحدة فقط */
    public void removeFromArena(Player player, Arena arena) {
        String id = arena.getId().toLowerCase();
        LinkedHashMap<String, String> m = playerArenas.get(player.getUniqueId());
        if (m == null || m.remove(id) == null) return;

        List<Player> queue = queues.get(id);
        if (queue != null) queue.remove(player);

        if (m.isEmpty()) {
            removeFromQueue(player); // آخر كيو → نظّف كل شي
        } else {
            scheduleGuiRefresh();
        }
    }

    /** يطلع اللاعب من كل الكيوهات */
    public void removeFromQueue(Player player) {
        LinkedHashMap<String, String> m = playerArenas.remove(player.getUniqueId());
        joinTimes.remove(player.getUniqueId());

        if (m != null) {
            for (String arenaId : m.keySet()) {
                List<Player> queue = queues.get(arenaId);
                if (queue != null) queue.remove(player);
            }
        }

        stopActionbar(player);
        scheduleGuiRefresh();
    }

    /** عدد اللاعبين الموجودين في أي كيو */
    public int getTotalQueued() {
        return playerArenas.size();
    }

    public int getQueueSize(Arena arena) {
        List<Player> q = queues.get(arena.getId().toLowerCase());
        return q == null ? 0 : q.size();
    }

    // ─────────────────────────────────────────
    //  GUI refresh (يحدّث الـ lore للي فاتح القائمة)
    // ─────────────────────────────────────────

    private void scheduleGuiRefresh() {
        if (!plugin.isEnabled()) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> GameSelectorGUI.refreshAll(plugin));
    }

    // ─────────────────────────────────────────
    //  Actionbar
    // ─────────────────────────────────────────

    private void startActionbar(Player player) {
        // لو الـ task شغّالة أصلاً (دخل كيو ثاني) — تقرأ الحالة ديناميكياً، ما نحتاج نعيدها
        if (actionbarTasks.containsKey(player.getUniqueId())) return;

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || !isQueued(player)) {
                stopActionbar(player);
                return;
            }
            if (plugin.getSettingsManager().isEnabled(player.getUniqueId(), SettingsManager.Setting.HIDE_ACTIONBAR)) {
                player.sendActionBar(Component.empty());
                return;
            }
            long seconds = (System.currentTimeMillis() - joinTimes.getOrDefault(player.getUniqueId(), System.currentTimeMillis())) / 1000;

            List<String> names = getQueuedArenas(player);
            String label = names.size() <= 3 ? String.join(", ", names) : names.size() + " games";

            Component bar = Component.text()
                    .append(Component.text("⚔ ", NamedTextColor.YELLOW))
                    .append(Component.text(label, NamedTextColor.GREEN))
                    .append(Component.text("  │  ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("1/2", NamedTextColor.AQUA))
                    .append(Component.text("  │  ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(formatTime(seconds), NamedTextColor.YELLOW))
                    .build();

            player.sendActionBar(bar);
        }, 0L, 20L);

        actionbarTasks.put(player.getUniqueId(), task);
    }

    private void stopActionbar(Player player) {
        BukkitTask old = actionbarTasks.remove(player.getUniqueId());
        if (old != null) old.cancel();
        // Clear actionbar
        player.sendActionBar(Component.empty());
    }

    private String formatTime(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        return String.format("%02d:%02d", m, s);
    }
}
