package me.starkmc.antitrigger;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.BoundingBox;

/** Per-player data kept in memory only. */
final class PlayerState
{
    /** One qualifying hit: ticks the crosshair was on the victim, when it happened, and whether the mouse was still. */
    record Sample(int reaction, int tick, boolean passive)
    {
    }

    /** One attack: when it happened and whether the crosshair was away from the victim. */
    record Attempt(int tick, boolean off)
    {
    }

    /** Where a player's hitbox was on one tick, so a hit can be judged against what the attacker's client saw. */
    record BoxSnap(int tick, BoundingBox box)
    {
    }

    UUID uuid;
    final ArrayDeque<BoxSnap> boxes = new ArrayDeque<>();
    final ArrayDeque<Attempt> attempts = new ArrayDeque<>();
    /** Last player the look-ray touched, and when. */
    UUID seenVictim;
    int seenTick = -1000;

    /** Evidence recording: last few seconds, per tick and per attack. */
    final ArrayDeque<Evidence.Frame> frames = new ArrayDeque<>();
    final ArrayDeque<Evidence.AttackRec> attackLog = new ArrayDeque<>();
    final ArrayDeque<Integer> clicks = new ArrayDeque<>();
    final java.util.List<String> flagHistory = new java.util.ArrayList<>();
    UUID focus;
    int focusUntil;
    boolean reviewPending;
    /** ElytraTarget tracking: rocket uses, slot-swap cycles and how long the crosshair has been locked on the nearest player while gliding. */
    final ArrayDeque<Integer> boosts = new ArrayDeque<>();
    final ArrayDeque<Integer> swapCycles = new ArrayDeque<>();
    int lastSwapTick = -1000;
    int lockTicks;
    int lockMiss;
    int lastLockTick = -100000;
    int lastElytraFlagTick = -100000;
    int lastAimCheckTick = -100000;
    int lastAimFlagTick = -100000;
    String elytraTarget;
    double elytraDist;
    double elytraAngle;
    long lastScreenCheck;
    long lastScreenMillis;
    final java.util.Map<String, ArrayDeque<Long>> flagTimes = new java.util.HashMap<>();
    long lastCorroborateMillis;
    boolean forceBan;
    int lastSwingTick = -100000;
    boolean pendingSwing;
    boolean pendingHadSwing;
    final ArrayDeque<Integer> swingMisses = new ArrayDeque<>();
    boolean invOpen;
    int lastInvClickTick = -100000;
    final java.util.Map<String, Integer> lastRuleTick = new java.util.HashMap<>();
    final ArrayDeque<Integer> hitTicks = new ArrayDeque<>();
    final ArrayDeque<UUID> hitVictims = new ArrayDeque<>();
    int blockedCount;
    long lastBlockAlert;
    int lastMultiTick = -100000;
    int lastReachTick = -100000;
    int lastCpsTick = -100000;
    int aiHits;
    long lastAiHitMillis;
    long lastReviewMillis;

    void click(int nowTick)
    {
        this.clicks.addLast(nowTick);

        while (!this.clicks.isEmpty() && nowTick - this.clicks.peekFirst() > 20)
        {
            this.clicks.pollFirst();
        }
    }

    int cps(int nowTick)
    {
        while (!this.clicks.isEmpty() && nowTick - this.clicks.peekFirst() > 20)
        {
            this.clicks.pollFirst();
        }

        return this.clicks.size();
    }

    void addHistory(String entry)
    {
        this.flagHistory.add(entry);

        while (this.flagHistory.size() > 30)
        {
            this.flagHistory.remove(0);
        }
    }

    UUID target;
    int onTargetTicks;
    int lastAttackTick = -1000;
    float lastYaw;
    float lastPitch;

    final ArrayDeque<Sample> samples = new ArrayDeque<>();
    final ArrayDeque<Integer> airSwings = new ArrayDeque<>();

    double vl;
    long lastFlagMillis;
    long lastDecayMillis = System.currentTimeMillis();
    int totalFlags;
    /** Indexes of punish actions already run during this VL climb. */
    final Set<Integer> firedActions = new HashSet<>();

    /** Applies the timed reset and the optional slow decay. */
    void refreshVl(double decayPerMinute, long resetAfterMillis)
    {
        long now = System.currentTimeMillis();

        if (this.vl > 0.0 && resetAfterMillis > 0 && now - this.lastFlagMillis >= resetAfterMillis)
        {
            this.vl = 0.0;
            this.firedActions.clear();
        }

        if (decayPerMinute > 0.0)
        {
            double minutes = (now - this.lastDecayMillis) / 60000.0;
            this.vl = Math.max(0.0, this.vl - decayPerMinute * minutes);
        }

        this.lastDecayMillis = now;
    }

    void trim(int nowTick, int windowTicks, int maxSamples)
    {
        while (!this.samples.isEmpty() && (nowTick - this.samples.peekFirst().tick() > windowTicks || this.samples.size() > maxSamples))
        {
            this.samples.pollFirst();
        }

        while (!this.airSwings.isEmpty() && nowTick - this.airSwings.peekFirst() > windowTicks)
        {
            this.airSwings.pollFirst();
        }
    }

    void trimAttempts(int nowTick, int windowTicks, int maxAttempts)
    {
        while (!this.attempts.isEmpty() && (nowTick - this.attempts.peekFirst().tick() > windowTicks || this.attempts.size() > maxAttempts))
        {
            this.attempts.pollFirst();
        }
    }
}
