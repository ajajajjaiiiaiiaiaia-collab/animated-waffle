package me.starkmc.antitrigger;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Per-player data kept in memory only. */
final class PlayerState
{
    /** One qualifying hit: ticks the crosshair was on the victim, when it happened, and whether the mouse was still. */
    record Sample(int reaction, int tick, boolean passive)
    {
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
}
