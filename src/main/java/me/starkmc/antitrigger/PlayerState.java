package me.starkmc.antitrigger;

import java.util.ArrayDeque;
import java.util.UUID;

/** Per-player data kept in memory only. */
final class PlayerState
{
    /** One qualifying hit: how many ticks the crosshair had been on the victim, and when it happened. */
    record Sample(int reaction, int tick)
    {
    }

    UUID target;
    int onTargetTicks;
    int lastAttackTick = -1000;

    final ArrayDeque<Sample> samples = new ArrayDeque<>();
    final ArrayDeque<Integer> airSwings = new ArrayDeque<>();

    double vl;
    long lastDecayMillis = System.currentTimeMillis();
    int totalFlags;

    void decay(double perMinute)
    {
        long now = System.currentTimeMillis();
        double minutes = (now - this.lastDecayMillis) / 60000.0;
        this.lastDecayMillis = now;
        this.vl = Math.max(0.0, this.vl - perMinute * minutes);
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
