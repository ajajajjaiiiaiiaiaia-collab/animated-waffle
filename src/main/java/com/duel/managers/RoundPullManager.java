package com.duel.managers;

import com.duel.DuelPlugin;
import com.duel.models.DuelSession;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * مشهد سينمائي بين رواندات 1v1 — كله فيزياء (velocity) بدون ولا teleport:
 *
 *  1) RISE    : الاثنين يرتفعون بخطين قطريين  /\  من مكانهم لقمة في وسط الأرينا.
 *  2) HOLD    : يتواجهون (كل واحد يطالع الثاني) + صاعقة/موجات طاقة.
 *  3) DESCEND : يُسحبون للخلف بنفس الشكل مع نزول تدريجي لين يوصلون نقاط السبون.
 *
 *  - الاثنين سيرفايفل (الخاسر يرجع حي)، مع طيران مؤقت + جاذبية معطّلة + تجميد الإدخال (flySpeed 0).
 *  - الحركة: كل tick نحسب مكانهم المطلوب على المسار ونعطيهم velocity للوصول له.
 *  - beginRound يشيل الطيران ويثبّت الراوند (teleport فقط كاحتياط لو علقوا بعيد عن السبون).
 *  - الـ task يوقف نفسه لو المباراة طلعت من ROUND_BREAK أو لاعب طلع.
 *
 * config (rounds.cinematic): rise-height (افتراضي 10) / gap (المسافة بينهم فوق، افتراضي 3.0)
 * rounds.pull-max-speed: أقصى سرعة (بلوك/tick).
 */
public class RoundPullManager {

    private final DuelPlugin plugin;
    private final Map<UUID, BukkitTask> tasks = new HashMap<>();
    private final Map<UUID, Run> runs = new HashMap<>();
    /** لاعبين عطّلنا عليهم الجاذبية (عشان نرجّعها حتى لو صار خطأ) */
    private final Set<UUID> gravityOff = new HashSet<>();

    public RoundPullManager(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * يبدأ المشهد فوراً ويستغرق totalTicks تقريباً.
     * onDescend يُستدعى مرة وحدة لما يبدأ النزول (نرجّع الماب وقتها) — ممكن null.
     */
    public void start(DuelSession session, long totalTicks, Runnable onDescend) {
        stop(session);

        final Player p1 = session.getPlayer1();
        final Player p2 = session.getPlayer2();
        if (!p1.isOnline() || !p2.isOnline()) return;

        Location s1 = session.getPlayerSpawn(p1);
        Location s2 = session.getPlayerSpawn(p2);
        if (s1 == null || s2 == null || s1.getWorld() == null || s2.getWorld() == null
                || !s1.getWorld().equals(s2.getWorld())) {
            if (onDescend != null) onDescend.run(); // بدون مشهد — بس نرجّع الماب
            return;
        }

        Run run = new Run(session, p1, p2, s1, s2, totalTicks, onDescend);
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, run, 1L, 1L);
        tasks.put(session.getSessionId(), task);
        runs.put(session.getSessionId(), run);
    }

    public void stop(DuelSession session) {
        BukkitTask task = tasks.remove(session.getSessionId());
        runs.remove(session.getSessionId());
        if (task != null) task.cancel();
        restore(session.getPlayer1());
        restore(session.getPlayer2());
    }

    private void restore(Player p) {
        if (p != null && gravityOff.remove(p.getUniqueId())) {
            try { p.setGravity(true); } catch (Throwable ignored) {}
        }
    }

    /** المشهد شغّال ولسا ما خلص (يشمل انتظار الريسباون)؟ DuelManager يؤخر الراوند الجاي لين يخلص. */
    public boolean isRunning(DuelSession session) {
        Run r = runs.get(session.getSessionId());
        return r != null && !r.finished;
    }

    /** هل اللاعب مقفول (مشهد بين الرواندات)؟ يُستخدم لمنع تبديل الطيران. */
    public boolean isLocked(Player p) {
        return p != null && gravityOff.contains(p.getUniqueId());
    }

    /**
     * يقفل اللاعب تماماً: سيرفايفل + طيران ثابت + سرعة مشي/طيران صفر + بدون جاذبية + invulnerable
     * + إزالة أي تأثيرات (سبيد/جمب...). يُستدعى فور نهاية الراوند وعند ريسباون الخاسر.
     */
    public void lock(Player pl) {
        if (pl == null || !pl.isOnline()) return;
        try {
            if (pl.getGameMode() != GameMode.SURVIVAL) pl.setGameMode(GameMode.SURVIVAL);
            for (org.bukkit.potion.PotionEffect e : new java.util.ArrayList<>(pl.getActivePotionEffects())) {
                pl.removePotionEffect(e.getType());
            }
            pl.setInvulnerable(true);
            pl.setAllowFlight(true);
            pl.setFlying(true);
            pl.setWalkSpeed(0f);
            pl.setFlySpeed(0f);
            pl.setFireTicks(0);
            pl.setFallDistance(0f);
            pl.setGravity(false);
            gravityOff.add(pl.getUniqueId());
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "[RoundCinematic] lock failed for " + pl.getName(), t);
        }
    }

    // ─────────────────────────────────────────
    //  المشهد
    // ─────────────────────────────────────────

    private final class Run implements Runnable {
        final DuelSession session;
        final Player[] p;
        final Location[] spawn;
        final World w;
        final long total;
        final Runnable onDescend;
        final double maxSpeed;
        final double riseHeight;
        final double gap;

        Location[] start = new Location[2];
        Location[] hover = new Location[2];
        Location mid;

        boolean ready = false;
        volatile boolean finished = false;
        final boolean dynamic;
        final long maxExtra;
        boolean descendFired = false;
        int waited = 0;
        int k = 0;
        int riseT, holdT, descT, totalT;
        boolean snap = false;                       // سقف واطي: بدون صعود، ننقلهم للسبون مباشرة
        final boolean[] snapped = new boolean[2];
        final Location[] lastPos = new Location[2];
        final Vector[] lastV = new Vector[2];
        final int[] stuck = new int[2];

        Run(DuelSession session, Player p1, Player p2, Location s1, Location s2, long totalTicks, Runnable onDescend) {
            this.session = session;
            this.p = new Player[]{p1, p2};
            this.spawn = new Location[]{s1.clone(), s2.clone()};
            this.w = s1.getWorld();
            this.total = totalTicks;
            this.onDescend = onDescend;
            this.maxSpeed = Math.max(0.2, plugin.getConfig().getDouble("rounds.pull-max-speed", 0.8));
            this.riseHeight = Math.max(3.0, plugin.getConfig().getDouble("rounds.cinematic.rise-height", 10.0));
            this.gap = Math.max(1.5, plugin.getConfig().getDouble("rounds.cinematic.gap", 3.0));
            this.dynamic = plugin.getConfig().getBoolean("rounds.cinematic.dynamic", true);
            this.maxExtra = dynamic
                    ? Math.max(0, plugin.getConfig().getInt("rounds.cinematic.max-extra-seconds", 6)) * 20L : 0L;
        }

        @Override
        public void run() {
            try {
                step();
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "[RoundCinematic] step failed", t);
            }
        }

        private void step() {
            if (session.getState() != DuelSession.State.ROUND_BREAK
                    || !p[0].isOnline() || !p[1].isOnline()
                    || !p[0].getWorld().equals(w) || !p[1].getWorld().equals(w)) {
                stop(session);
                return;
            }
            if (!ready) {
                // الخاسر يموت → ريسباون تلقائي بعد tick: ننتظر الاثنين أحياء
                if (p[0].isDead() || p[1].isDead()) { waited++; return; }
                init();
            }

            final int rise = riseT, hold = holdT, desc = descT;

            // ── أحداث بداية الأطوار ──
            if (k == rise) onHoldStart();
            if (k == rise + hold && !descendFired) onDescendStart();

            // ── الحركة (كل شي velocity) ──
            for (int i = 0; i < 2; i++) {
                // أي شي يحاول يفك القفل (بلجن ثاني/تبديل طيران) نرجّعه
                if (!p[i].isFlying() || p[i].getFlySpeed() != 0f || p[i].getWalkSpeed() != 0f) lock(p[i]);
                Vector want = target(i, k + 1);
                Vector v = want.clone().subtract(target(i, k));
                Vector err = want.clone().subtract(p[i].getLocation().toVector());
                if (err.length() > 6.0) err.multiply(6.0 / err.length());
                v.add(err.multiply(0.10));
                double len = v.length();
                if (len > maxSpeed) v.multiply(maxSpeed / len);
                else if (len < 0.005) v.zero();
                if (snap || snapped[i]) {
                    v = new Vector(0, 0, 0);
                } else {
                    Location nowLoc = p[i].getLocation();
                    if (k >= 12 && lastPos[i] != null && lastV[i] != null   // مهلة أول 12 tick (الخاسر لسا يحمّل بعد الريسباون)
                            && lastV[i].lengthSquared() > 0.0025
                            && nowLoc.distanceSquared(lastPos[i]) < 0.0004) {
                        stuck[i]++;
                    } else {
                        stuck[i] = 0;
                    }
                    if (stuck[i] >= 10) {          // علق (سقف/جدار): ننقله للسبون
                        sendToSpawn(i);
                        v = new Vector(0, 0, 0);
                    }
                }
                lastPos[i] = p[i].getLocation();
                lastV[i] = v.clone();
                p[i].setFallDistance(0f);
                p[i].setVelocity(v);
                if (k == 0 || k == rise) faceOpponent(i);
            }

            // ── المؤثرات ──
            if (k < rise) riseEffects();
            else if (k < rise + hold) holdEffects(k - rise);
            else descendEffects();

            if (k < totalT) k++;
            if (k >= totalT) finished = true;
        }

        // ── تجهيز ──
        private void init() {
            ready = true;
            descendFired = false;

            for (int i = 0; i < 2; i++) {
                start[i] = p[i].getLocation().clone();
                lock(p[i]);
            }

            // ── ديناميكي: نحسب طول المسار الفعلي (حجم الأرينا + مكان اللاعبين) ──
            // أقصى سرعة بالـ smoothstep = 1.5 × (المسافة / المدة) → نبي ≤ 85% من pull-max-speed
            final double cx = (spawn[0].getX() + spawn[1].getX()) / 2.0;
            final double cz = (spawn[0].getZ() + spawn[1].getZ()) / 2.0;
            final double baseY = Math.max(spawn[0].getY(), spawn[1].getY());

            double rise = riseHeight;
            buildHover(cx, cz, baseY);
            double ceilY = Double.MAX_VALUE;
            Location[] cols = { start[0], start[1], spawn[0], spawn[1], hover[0], hover[1], mid };
            for (Location c : cols) {
                ceilY = Math.min(ceilY, ceilingAt(c.getX(), c.getY() + 0.9, c.getZ(), riseHeight + 6.0));
            }
            if (ceilY != Double.MAX_VALUE) {
                rise = Math.min(rise, ceilY - 2.4 - baseY);
                if (rise < 3.0) snap = true;   // السقف واطي: ما نصعّدهم (كانوا يعلقون بالبلوك)
            }

            int baseTotal = (int) Math.max(40, total - waited - 3);
            int holdBase = Math.max(10, Math.min(30, (int) Math.round(baseTotal * 0.16)));
            int cap = (int) (baseTotal + maxExtra);   // أقصى مدة مسموحة للمشهد
            int rr = 0, rd = 0, need = 0;
            if (snap) buildHover(cx, cz, baseY + 1.0);
            while (!snap) {
                buildHover(cx, cz, baseY + rise);
                double maxRise = Math.max(start[0].distance(hover[0]), start[1].distance(hover[1]));
                double maxDesc = Math.max(hover[0].distance(spawn[0]), hover[1].distance(spawn[1]));
                rr = ticksFor(maxRise);
                rd = ticksFor(maxDesc);
                need = rr + holdBase + rd;
                // لو ما يكفي حتى بأقصى مدة: نقصّر الارتفاع (مسار أقصر) لين 4 بلوكات
                if (!dynamic || need <= cap || rise <= 4.0) break;
                rise = Math.max(4.0, rise - 1.0);
            }

            totalT = dynamic ? Math.max(baseTotal, Math.min(cap, need)) : baseTotal;
            holdT = holdBase;
            int remain = Math.max(30, totalT - holdT);
            riseT = Math.max(15, (int) Math.round(remain * 0.48));
            descT = Math.max(15, remain - riseT);
            if (dynamic) {
                // وزّع الوقت حسب المسار الأطول
                if (riseT < rr && remain - rr >= 15) { riseT = rr; descT = remain - rr; }
                if (descT < rd && remain - rd >= 15) { descT = rd; riseT = remain - rd; }
            }
            totalT = riseT + holdT + descT;

            // بداية: صوت صعود + خطوط المسار /\ بالجزيئات
            w.playSound(mid, Sound.ITEM_TRIDENT_RIPTIDE_3, 2f, 0.7f);
            w.playSound(mid, Sound.BLOCK_BEACON_ACTIVATE, 1.5f, 0.6f);
            for (int i = 0; i < 2; i++) {
                w.playSound(start[i], Sound.ENTITY_ENDER_DRAGON_FLAP, 1.2f, 0.8f);
                if (!snap) beam(start[i], hover[i], Particle.END_ROD);
            }
        }

        /** إحداثية Y المطلقة لسقف العمود فوق النقطة (MAX_VALUE لو ما في سقف قريب أو النقطة داخل بلوك). */
        private double ceilingAt(double x, double y, double z, double maxDist) {
            try {
                Location o = new Location(w, x, y, z);
                if (!o.getBlock().isPassable()) return Double.MAX_VALUE;
                RayTraceResult r = w.rayTraceBlocks(o, new Vector(0, 1, 0), maxDist, FluidCollisionMode.NEVER, true);
                if (r != null && r.getHitPosition() != null) return r.getHitPosition().getY();
            } catch (Throwable ignored) {}
            return Double.MAX_VALUE;
        }

        /** ينقل اللاعب مباشرة لنقطة السبون (لو علق أو السقف واطي). */
        private void sendToSpawn(int i) {
            snapped[i] = true;
            try {
                Location cur = p[i].getLocation();
                Location dest = spawn[i].clone();
                dest.setYaw(cur.getYaw());
                dest.setPitch(cur.getPitch());
                p[i].setVelocity(new Vector(0, 0, 0));
                p[i].teleport(dest);
                w.spawnParticle(Particle.REVERSE_PORTAL, dest.getX(), dest.getY() + 0.9, dest.getZ(), 25, 0.3, 0.5, 0.3, 0.1);
                w.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f);
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "[RoundCinematic] sendToSpawn failed", t);
            }
        }

        /** نقطتا التواجه فوق + نقطة المنتصف (للصاعقة والموجات). */
        private void buildHover(double cx, double cz, double apexY) {
            Vector dir = new Vector(spawn[1].getX() - spawn[0].getX(), 0, spawn[1].getZ() - spawn[0].getZ());
            if (dir.lengthSquared() < 0.01) dir = new Vector(1, 0, 0); else dir.normalize();
            double half = gap / 2.0;
            hover[0] = new Location(w, cx - dir.getX() * half, apexY, cz - dir.getZ() * half);
            hover[1] = new Location(w, cx + dir.getX() * half, apexY, cz + dir.getZ() * half);
            mid = new Location(w, cx, apexY, cz);
        }

        /** أقل عدد ticks لمسار بطول dist بدون ما تتجاوز السرعة 85% من الحد الأقصى. */
        private int ticksFor(double dist) {
            return (int) Math.ceil(1.5 * dist / (0.85 * maxSpeed));
        }

        // ── المسار ──
        private double smooth(double t) {
            t = Math.max(0.0, Math.min(1.0, t));
            return t * t * (3.0 - 2.0 * t);
        }

        private Vector lerp(Location a, Location b, double t) {
            return new Vector(
                    a.getX() + (b.getX() - a.getX()) * t,
                    a.getY() + (b.getY() - a.getY()) * t,
                    a.getZ() + (b.getZ() - a.getZ()) * t);
        }

        /** المكان المطلوب للاعب i عند tick رقم kk. */
        private Vector target(int i, int kk) {
            if (snap) return (kk <= riseT + holdT ? start[i] : spawn[i]).toVector();
            if (kk <= riseT) {
                return lerp(start[i], hover[i], smooth(kk / (double) riseT));
            }
            if (kk <= riseT + holdT) {
                Vector h = lerp(hover[i], hover[i], 0);
                double bob = Math.sin((kk - riseT) * 0.35 + i * Math.PI) * 0.18;
                h.setY(h.getY() + bob);
                return h;
            }
            int d = kk - riseT - holdT;
            return lerp(hover[i], spawn[i], smooth(d / (double) descT));
        }

        // ── الاتجاه: كل واحد يطالع الثاني (بدون teleport، دوران ناعم) ──
        private void faceOpponent(int i) {
            try {
                Player me = p[i], other = p[1 - i];
                Location a = me.getEyeLocation();
                Vector d = other.getEyeLocation().toVector().subtract(a.toVector());
                double hor = Math.sqrt(d.getX() * d.getX() + d.getZ() * d.getZ());
                float wantYaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
                float wantPitch = (float) Math.toDegrees(-Math.atan2(d.getY(), hor));
                float curYaw = a.getYaw(), curPitch = a.getPitch();
                float dy = wrap(wantYaw - curYaw);
                float dp = wantPitch - curPitch;
                if (Math.abs(dy) < 1.5f && Math.abs(dp) < 1.5f) return;
                float ny = curYaw + dy;
                float np = curPitch + dp;
                me.setRotation(ny, np);
            } catch (Throwable ignored) {}
        }

        private float wrap(float a) {
            while (a > 180f) a -= 360f;
            while (a < -180f) a += 360f;
            return a;
        }

        // ── المؤثرات ──
        private void beam(Location from, Location to, Particle pt) {
            double len = from.distance(to);
            int n = (int) Math.max(6, Math.min(45, len / 0.6));
            for (int j = 0; j <= n; j++) {
                double t = j / (double) n;
                w.spawnParticle(pt,
                        from.getX() + (to.getX() - from.getX()) * t,
                        from.getY() + (to.getY() - from.getY()) * t + 0.9,
                        from.getZ() + (to.getZ() - from.getZ()) * t,
                        1, 0, 0, 0, 0);
            }
        }

        private void ring(Location c, double r, Particle pt, int pts) {
            for (int j = 0; j < pts; j++) {
                double ang = 2 * Math.PI * j / pts;
                w.spawnParticle(pt, c.getX() + Math.cos(ang) * r, c.getY(), c.getZ() + Math.sin(ang) * r,
                        1, 0, 0, 0, 0);
            }
        }

        private void riseEffects() {
            for (int i = 0; i < 2; i++) {
                Location l = p[i].getLocation();
                w.spawnParticle(Particle.END_ROD, l.getX(), l.getY() + 0.9, l.getZ(), 3, 0.25, 0.45, 0.25, 0.02);
                w.spawnParticle(Particle.CLOUD, l.getX(), l.getY() + 0.1, l.getZ(), 1, 0.2, 0.05, 0.2, 0.01);
                if (k % 4 == 0) {
                    w.spawnParticle(Particle.ELECTRIC_SPARK, l.getX(), l.getY() + 1.0, l.getZ(), 6, 0.4, 0.6, 0.4, 0.3);
                }
            }
            if (k % 12 == 0) w.playSound(mid, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 0.6f + 0.8f * (k / (float) riseT));
        }

        private void onHoldStart() {
            w.strikeLightningEffect(mid);
            w.playSound(mid, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2.5f, 1.0f);
            w.playSound(mid, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 1.2f);
            w.spawnParticle(Particle.SONIC_BOOM, mid, 1, 0, 0, 0, 0);
        }

        private void holdEffects(int j) {
            double r = 0.6 + j * 0.45;
            if (j % 3 == 0) ring(mid, r, Particle.END_ROD, 24);
            if (j % 6 == 0) ring(mid, r * 0.6, Particle.ELECTRIC_SPARK, 12);
            w.spawnParticle(Particle.REVERSE_PORTAL, mid, 6, 0.5, 0.5, 0.5, 0.4);
            for (int i = 0; i < 2; i++) {
                Location l = p[i].getLocation();
                w.spawnParticle(Particle.END_ROD, l.getX(), l.getY() + 1.0, l.getZ(), 2, 0.3, 0.5, 0.3, 0.02);
            }
        }

        private void onDescendStart() {
            descendFired = true;
            w.playSound(mid, Sound.ITEM_TRIDENT_RIPTIDE_3, 2f, 0.5f);
            w.playSound(mid, Sound.BLOCK_BEACON_DEACTIVATE, 1.5f, 0.7f);
            if (snap) {
                for (int i = 0; i < 2; i++) if (!snapped[i]) sendToSpawn(i);
            } else {
                for (int i = 0; i < 2; i++) beam(hover[i], spawn[i], Particle.SOUL_FIRE_FLAME);
            }
            if (onDescend != null) {
                try { onDescend.run(); } catch (Throwable t) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "[RoundCinematic] onDescend failed", t);
                }
            }
        }

        private void descendEffects() {
            for (int i = 0; i < 2; i++) {
                Location l = p[i].getLocation();
                w.spawnParticle(Particle.REVERSE_PORTAL, l.getX(), l.getY() + 0.9, l.getZ(), 4, 0.25, 0.45, 0.25, 0.1);
                w.spawnParticle(Particle.END_ROD, l.getX(), l.getY() + 0.9, l.getZ(), 2, 0.25, 0.45, 0.25, 0.02);
            }
        }
    }
}
