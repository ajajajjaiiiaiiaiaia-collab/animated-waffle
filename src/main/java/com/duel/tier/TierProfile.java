package com.duel.tier;

import java.util.UUID;

/** بيانات لاعب في أرينا (kit) واحدة. الـ key = uuid + arenaId (lowercase). */
public class TierProfile {

    public final UUID uuid;
    public final String arenaId;       // lowercase
    public String name;
    public int rating;
    public Tier tier = Tier.LT5;
    public Tier peakTier = Tier.LT5;   // أعلى تير وصله (كل المواسم)
    public int seasonPeakRating;
    public int games;                  // مباريات هذا الموسم (للـ placement)
    public int wins;
    public int losses;
    public boolean challengePending;   // مفتوح له Rankup Challenge
    public long challengeCooldown;     // millis — قبل هذا الوقت ما ينفتح تحدي جديد
    public boolean defensePending;     // مطلوب منه Tier Defense

    public TierProfile(UUID uuid, String arenaId, String name, int startRating) {
        this.uuid = uuid;
        this.arenaId = arenaId;
        this.name = name;
        this.rating = startRating;
        this.seasonPeakRating = startRating;
    }

    public TierProfile copy() {
        TierProfile c = new TierProfile(uuid, arenaId, name, rating);
        c.tier = tier; c.peakTier = peakTier; c.seasonPeakRating = seasonPeakRating;
        c.games = games; c.wins = wins; c.losses = losses;
        c.challengePending = challengePending; c.challengeCooldown = challengeCooldown;
        c.defensePending = defensePending;
        return c;
    }
}
