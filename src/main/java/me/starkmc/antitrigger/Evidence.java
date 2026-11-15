package me.starkmc.antitrigger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Data records and maths for the evidence recording (per-tick frames and per-attack records). */
final class Evidence
{
    private Evidence()
    {
    }

    static final String[] FRAME_COLUMNS = {"tick", "x", "y", "z", "yaw", "pitch", "dYaw", "dPitch", "sprint", "sneak", "ground", "ping", "target", "distToTarget", "angleErrDeg", "yawErrDeg", "pitchErrDeg", "lookRayOnTarget"};

    /** One server tick of one player. dYaw/dPitch are the rotation change since the previous tick. */
    record Frame(int tick, double x, double y, double z, float yaw, float pitch, float dYaw, float dPitch, boolean sprint, boolean sneak, boolean ground, int ping, String target, double dist, double angleErr, double yawErr, double pitchErr, boolean rayOn)
    {
        double speed()
        {
            return Math.sqrt(this.dYaw * this.dYaw + this.dPitch * this.dPitch);
        }

        JsonArray toRow()
        {
            JsonArray a = new JsonArray();
            a.add(this.tick);
            a.add(r(this.x, 2));
            a.add(r(this.y, 2));
            a.add(r(this.z, 2));
            a.add(r(this.yaw, 1));
            a.add(r(this.pitch, 1));
            a.add(r(this.dYaw, 1));
            a.add(r(this.dPitch, 1));
            a.add(this.sprint ? 1 : 0);
            a.add(this.sneak ? 1 : 0);
            a.add(this.ground ? 1 : 0);
            a.add(this.ping);

            if (this.target == null)
            {
                a.add("-");
            }
            else
            {
                a.add(this.target);
            }

            a.add(r(this.dist, 2));
            a.add(r(this.angleErr, 1));
            a.add(r(this.yawErr, 1));
            a.add(r(this.pitchErr, 1));
            a.add(this.rayOn ? 1 : 0);
            return a;
        }
    }

    /** One attack on a player, with everything known at that moment. */
    record AttackRec(int tick, String victim, double dist, boolean onCrosshair, boolean wideRay, int reaction, long mouseStillMs, int cps, double angleErr, double yawErr, double pitchErr, double damage, double cooldown, String ignored, double hitboxAngle, boolean lagOn, double lagAngle, int crowd, String blockedBy, double victimSpeed)
    {
        JsonObject toJson()
        {
            JsonObject o = new JsonObject();
            o.addProperty("tick", this.tick);
            o.addProperty("victim", this.victim);
            o.addProperty("distance", r(this.dist, 2));
            o.addProperty("crosshair_on_victim_server_ray", this.onCrosshair);
            o.addProperty("crosshair_on_victim_wide_ray", this.wideRay);
            o.addProperty("ticks_crosshair_was_on_victim_before_hit", this.reaction);
            o.addProperty("mouse_still_ms_before_hit", this.mouseStillMs);
            o.addProperty("cps_last_second", this.cps);
            o.addProperty("angle_error_deg", r(this.angleErr, 1));
            o.addProperty("yaw_error_deg", r(this.yawErr, 1));
            o.addProperty("pitch_error_deg", r(this.pitchErr, 1));
            o.addProperty("damage", r(this.damage, 2));
            o.addProperty("attack_cooldown", r(this.cooldown, 2));
            o.addProperty("not_judged_reason", this.ignored == null ? "" : this.ignored);
            o.addProperty("angle_to_hitbox_deg", r(this.hitboxAngle, 1));
            o.addProperty("crosshair_on_victim_lag_compensated", this.lagOn);
            o.addProperty("angle_to_hitbox_lag_compensated_deg", r(this.lagAngle, 1));
            o.addProperty("other_players_within_6_blocks", this.crowd);
            o.addProperty("other_player_in_front_of_crosshair", this.blockedBy == null ? "" : this.blockedBy);
            o.addProperty("victim_speed_blocks_per_tick", r(this.victimSpeed, 2));
            return o;
        }
    }

    static double r(double value, int decimals)
    {
        double scale = Math.pow(10.0, decimals);
        return Math.round(value * scale) / scale;
    }

    static float wrap(float angle)
    {
        float a = angle % 360.0F;

        if (a >= 180.0F)
        {
            a -= 360.0F;
        }

        if (a < -180.0F)
        {
            a += 360.0F;
        }

        return a;
    }

    private static double clamp(double v, double lo, double hi)
    {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Returns {distance to the victim's hitbox, angle between look direction and victim centre, yaw error, pitch error} in degrees/blocks. */
    static double[] aim(Location eye, Player victim)
    {
        BoundingBox bb = victim.getBoundingBox();
        Vector c = bb.getCenter();
        double dx = c.getX() - eye.getX();
        double dy = c.getY() - eye.getY();
        double dz = c.getZ() - eye.getZ();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double px = clamp(eye.getX(), bb.getMinX(), bb.getMaxX());
        double py = clamp(eye.getY(), bb.getMinY(), bb.getMaxY());
        double pz = clamp(eye.getZ(), bb.getMinZ(), bb.getMaxZ());
        double ex = px - eye.getX();
        double ey = py - eye.getY();
        double ez = pz - eye.getZ();
        double dist = Math.sqrt(ex * ex + ey * ey + ez * ez);
        Vector look = eye.getDirection();
        double dot = len == 0.0 ? 1.0 : (look.getX() * dx + look.getY() * dy + look.getZ() * dz) / len;
        double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
        double yawTo = Math.toDegrees(Math.atan2(-dx, dz));
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double pitchTo = -Math.toDegrees(Math.atan2(dy, horizontal));
        double yawErr = wrap((float)(yawTo - eye.getYaw()));
        double pitchErr = pitchTo - eye.getPitch();
        return new double[] {dist, angle, yawErr, pitchErr};
    }

    /**
     * Smallest angle in degrees between the look direction and any point of the hitbox. 0 when the look ray goes
     * through the box. Unlike the angle to the box centre this does not grow when you hit the head or feet up close.
     */
    static double angleToBox(Location eye, BoundingBox box)
    {
        Vector look = eye.getDirection();
        Vector start = eye.toVector();

        if (box.contains(start) || box.rayTrace(start, look, 8.0) != null)
        {
            return 0.0;
        }

        double best = 180.0;
        int nx = 5;
        int ny = 9;
        int nz = 5;

        for (int i = 0; i < nx; i++)
        {
            double x = box.getMinX() + (box.getMaxX() - box.getMinX()) * i / (nx - 1);

            for (int j = 0; j < ny; j++)
            {
                double y = box.getMinY() + (box.getMaxY() - box.getMinY()) * j / (ny - 1);

                for (int k = 0; k < nz; k++)
                {
                    double z = box.getMinZ() + (box.getMaxZ() - box.getMinZ()) * k / (nz - 1);
                    double dx = x - eye.getX();
                    double dy = y - eye.getY();
                    double dz = z - eye.getZ();
                    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);

                    if (len < 1.0E-6)
                    {
                        return 0.0;
                    }

                    double dot = (look.getX() * dx + look.getY() * dy + look.getZ() * dz) / len;
                    best = Math.min(best, Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot)))));
                }
            }
        }

        return best;
    }

    /** Summary numbers computed from the recorded window, so the reviewer does not have to do the maths. */
    /** Below this distance the target overlaps the attacker and the aim angle is meaningless (lag alone can flip it). */
    static final double MIN_RELIABLE_DISTANCE = 1.5;

    static JsonObject stats(Collection<Frame> frames, Collection<AttackRec> allAttacks)
    {
        JsonObject s = new JsonObject();
        List<AttackRec> attacks = new ArrayList<>();
        int tooClose = 0;

        for (AttackRec a : allAttacks)
        {
            if (a.dist() >= MIN_RELIABLE_DISTANCE)
            {
                attacks.add(a);
            }
            else
            {
                tooClose++;
            }
        }

        s.addProperty("attacks_ignored_closer_than_1_5_blocks", tooClose);
        List<Frame> list = new ArrayList<>(frames);
        double maxSpeed = 0.0;
        double sumSpeedWithTarget = 0.0;
        int withTarget = 0;
        int snaps = 0;

        for (Frame f : list)
        {
            double sp = f.speed();
            maxSpeed = Math.max(maxSpeed, sp);

            if (f.target() != null)
            {
                sumSpeedWithTarget += sp;
                withTarget++;
            }

            if (sp > 30.0)
            {
                snaps++;
            }
        }

        int pingMin = Integer.MAX_VALUE;
        int pingMax = 0;

        for (Frame f : list)
        {
            pingMin = Math.min(pingMin, f.ping());
            pingMax = Math.max(pingMax, f.ping());
        }

        s.addProperty("ping_min_ms_in_window", list.isEmpty() ? 0 : pingMin);
        s.addProperty("ping_max_ms_in_window", pingMax);
        s.addProperty("frames_recorded", list.size());
        s.addProperty("frames_with_target", withTarget);
        s.addProperty("max_rotation_speed_deg_per_tick", r(maxSpeed, 1));
        s.addProperty("avg_rotation_speed_while_target_deg_per_tick", withTarget == 0 ? 0.0 : r(sumSpeedWithTarget / withTarget, 2));
        s.addProperty("frames_with_rotation_over_30deg", snaps);

        int n = attacks.size();
        int on = 0;
        int wide = 0;
        int under3 = 0;
        int over20 = 0;
        int snapThenHit = 0;
        int maxCps = 0;
        double sumAngle = 0.0;
        double minAngle = Double.MAX_VALUE;
        double maxAngle = 0.0;
        double sumReaction = 0.0;
        double sumStill = 0.0;
        double sumCps = 0.0;
        double maxDist = 0.0;
        double sumPre = 0.0;
        double maxPre = 0.0;

        for (AttackRec a : attacks)
        {
            if (a.onCrosshair())
            {
                on++;
            }

            if (a.wideRay())
            {
                wide++;
            }

            if (a.angleErr() < 3.0)
            {
                under3++;
            }

            if (a.angleErr() > 20.0)
            {
                over20++;
            }

            sumAngle += a.angleErr();
            minAngle = Math.min(minAngle, a.angleErr());
            maxAngle = Math.max(maxAngle, a.angleErr());
            sumReaction += a.reaction();
            sumStill += a.mouseStillMs();
            sumCps += a.cps();
            maxCps = Math.max(maxCps, a.cps());
            maxDist = Math.max(maxDist, a.dist());

            double pre = 0.0;

            for (Frame f : list)
            {
                if (f.tick() >= a.tick() - 3 && f.tick() <= a.tick())
                {
                    pre = Math.max(pre, f.speed());
                }
            }

            sumPre += pre;
            maxPre = Math.max(maxPre, pre);

            if (pre > 20.0 && a.angleErr() < 5.0)
            {
                snapThenHit++;
            }
        }

        s.addProperty("attacks_recorded", n);
        s.addProperty("attacks_crosshair_on_victim_server_ray", on);
        s.addProperty("attacks_crosshair_off_victim_even_with_wide_ray", n - wide);
        s.addProperty("attacks_off_victim_server_ray", n - on);
        s.addProperty("angle_error_avg_deg", n == 0 ? 0.0 : r(sumAngle / n, 1));
        s.addProperty("angle_error_min_deg", n == 0 ? 0.0 : r(minAngle, 1));
        s.addProperty("angle_error_max_deg", r(maxAngle, 1));
        s.addProperty("attacks_with_angle_error_under_3deg", under3);
        s.addProperty("attacks_with_angle_error_over_20deg", over20);
        s.addProperty("avg_ticks_crosshair_on_victim_before_hit", n == 0 ? 0.0 : r(sumReaction / n, 2));
        s.addProperty("avg_mouse_still_ms_before_hit", n == 0 ? 0.0 : r(sumStill / n, 0));
        s.addProperty("avg_cps", n == 0 ? 0.0 : r(sumCps / n, 1));
        s.addProperty("max_cps", maxCps);
        s.addProperty("max_attack_distance", r(maxDist, 2));
        s.addProperty("avg_rotation_speed_3_ticks_before_attack", n == 0 ? 0.0 : r(sumPre / n, 1));
        s.addProperty("max_rotation_speed_3_ticks_before_attack", r(maxPre, 1));
        s.addProperty("attacks_after_fast_snap_that_ended_on_target", snapThenHit);
        int lagOnCount = 0;
        int blocked = 0;
        int hitThenTurn = 0;
        double sumLagAngle = 0.0;
        double maxLagAngle = 0.0;
        double sumCrowd = 0.0;
        double sumVictimSpeed = 0.0;

        for (AttackRec a : attacks)
        {
            if (a.lagOn())
            {
                lagOnCount++;
            }

            if (a.blockedBy() != null)
            {
                blocked++;
            }

            sumLagAngle += a.lagAngle();
            maxLagAngle = Math.max(maxLagAngle, a.lagAngle());
            sumCrowd += a.crowd();
            sumVictimSpeed += a.victimSpeed();

            if (a.angleErr() >= 20.0 && !a.lagOn())
            {
                double bestAfter = 999.0;
                double turned = 0.0;

                for (Frame f : list)
                {
                    if (f.tick() > a.tick() && f.tick() <= a.tick() + 3 && a.victim().equals(f.target()))
                    {
                        bestAfter = Math.min(bestAfter, f.angleErr());
                        turned += f.speed();
                    }
                }

                if (bestAfter <= 12.0 && turned >= 15.0)
                {
                    hitThenTurn++;
                }
            }
        }

        s.addProperty("attacks_crosshair_on_hitbox_lag_compensated", lagOnCount);
        s.addProperty("attacks_crosshair_off_hitbox_lag_compensated", n - lagOnCount);
        s.addProperty("hitbox_angle_lag_compensated_avg_deg", n == 0 ? 0.0 : r(sumLagAngle / n, 1));
        s.addProperty("hitbox_angle_lag_compensated_max_deg", r(maxLagAngle, 1));
        s.addProperty("attacks_with_other_player_in_front_of_crosshair", blocked);
        s.addProperty("avg_other_players_within_6_blocks", n == 0 ? 0.0 : r(sumCrowd / n, 1));
        s.addProperty("avg_victim_speed_blocks_per_tick", n == 0 ? 0.0 : r(sumVictimSpeed / n, 2));
        s.addProperty("attacks_hit_first_then_turned_to_victim_within_3_ticks", hitThenTurn);
        addAimStats(s, list);
        return s;
    }

    private static final double TRACK_MIN_ERR = 1.5;
    private static final double TRACK_MAX_ERR = 30.0;
    private static final double TRACK_MIN_TURN = 0.05;

    /**
     * Aim-assist signals. Aim assist pulls the view toward the target by a fixed share of the remaining error every tick,
     * so while tracking, (rotation this tick) / (error last tick) is positive and nearly constant. A human's share varies a lot,
     * and often overshoots (negative). Also: how smooth the turning is, and whether the mouse deltas are multiples of one step.
     */
    static void addAimStats(JsonObject s, List<Frame> list)
    {
        List<Double> yawRatios = new ArrayList<>();
        List<Double> pitchRatios = new ArrayList<>();
        List<Double> accel = new ArrayList<>();

        for (int i = 1; i < list.size(); i++)
        {
            Frame p = list.get(i - 1);
            Frame f = list.get(i);

            if (f.tick() - p.tick() != 1 || f.target() == null || !f.target().equals(p.target()) || f.dist() < MIN_RELIABLE_DISTANCE || f.dist() > 6.0)
            {
                continue;
            }

            double ye = p.yawErr();
            double pe = p.pitchErr();

            if (Math.abs(ye) >= TRACK_MIN_ERR && Math.abs(ye) <= TRACK_MAX_ERR && Math.abs(f.dYaw()) >= TRACK_MIN_TURN)
            {
                yawRatios.add(clamp(f.dYaw() / ye, -1.0, 2.0));
            }

            if (Math.abs(pe) >= TRACK_MIN_ERR && Math.abs(pe) <= TRACK_MAX_ERR && Math.abs(f.dPitch()) >= TRACK_MIN_TURN)
            {
                pitchRatios.add(clamp(f.dPitch() / pe, -1.0, 2.0));
            }

            if (i >= 2)
            {
                Frame pp = list.get(i - 2);

                if (p.tick() - pp.tick() == 1 && p.target() != null && Math.abs(f.dYaw()) >= TRACK_MIN_TURN)
                {
                    accel.add((double)(f.dYaw() - 2.0F * p.dYaw() + pp.dYaw()));
                }
            }
        }

        double[] y = ratioStats(yawRatios);
        double[] pt = ratioStats(pitchRatios);
        s.addProperty("aim_yaw_track_samples", yawRatios.size());
        s.addProperty("aim_yaw_track_toward_target_fraction", r(y[0], 2));
        s.addProperty("aim_yaw_track_ratio_mean", r(y[1], 3));
        s.addProperty("aim_yaw_track_ratio_cv", r(y[2], 2));
        s.addProperty("aim_pitch_track_samples", pitchRatios.size());
        s.addProperty("aim_pitch_track_toward_target_fraction", r(pt[0], 2));
        s.addProperty("aim_pitch_track_ratio_mean", r(pt[1], 3));
        s.addProperty("aim_pitch_track_ratio_cv", r(pt[2], 2));
        s.addProperty("aim_yaw_accel_samples", accel.size());
        s.addProperty("aim_yaw_accel_stddev_deg", accel.size() < 2 ? -1.0 : r(stddev(accel), 3));

        List<Double> pitchDeltas = new ArrayList<>();

        for (Frame f : list)
        {
            double d = Math.abs(f.dPitch());

            if (d > 0.0006 && d < 60.0)
            {
                pitchDeltas.add(d);
            }
        }

        s.addProperty("pitch_delta_samples", pitchDeltas.size());
        s.addProperty("pitch_delta_gcd_step_deg", pitchDeltas.size() < 20 ? -1.0 : r(gcd(pitchDeltas), 4));
    }

    /** {share of values above 0, mean, coefficient of variation (stddev / |mean|)} */
    private static double[] ratioStats(List<Double> values)
    {
        if (values.isEmpty())
        {
            return new double[] {0.0, 0.0, 0.0};
        }

        int pos = 0;
        double sum = 0.0;

        for (double v : values)
        {
            if (v > 0.0)
            {
                pos++;
            }

            sum += v;
        }

        double mean = sum / values.size();
        double sd = stddev(values);
        return new double[] {(double)pos / values.size(), mean, Math.abs(mean) < 1.0E-6 ? 99.0 : sd / Math.abs(mean)};
    }

    private static double stddev(List<Double> values)
    {
        double mean = 0.0;

        for (double v : values)
        {
            mean += v;
        }

        mean /= values.size();
        double var = 0.0;

        for (double v : values)
        {
            var += (v - mean) * (v - mean);
        }

        return Math.sqrt(var / values.size());
    }

    /** Euclid with a tolerance. Genuine mouse input moves in multiples of one sensitivity step; 0 means no common step. */
    private static double gcd(List<Double> values)
    {
        double g = values.get(0);

        for (int i = 1; i < values.size() && i < 80; i++)
        {
            double a = Math.max(g, values.get(i));
            double b = Math.min(g, values.get(i));

            while (b > 0.0006)
            {
                double rest = a % b;
                a = b;
                b = rest;
            }

            g = a;

            if (g <= 0.0006)
            {
                return 0.0;
            }
        }

        return g;
    }

    /** Result of the tracking test used by the aim-assist check: {samples, towardFraction, ratioMean, ratioCv}. */
    static double[] yawTrack(JsonObject stats)
    {
        return new double[] {stats.get("aim_yaw_track_samples").getAsDouble(), stats.get("aim_yaw_track_toward_target_fraction").getAsDouble(), stats.get("aim_yaw_track_ratio_mean").getAsDouble(), stats.get("aim_yaw_track_ratio_cv").getAsDouble()};
    }

    static double[] pitchTrack(JsonObject stats)
    {
        return new double[] {stats.get("aim_pitch_track_samples").getAsDouble(), stats.get("aim_pitch_track_toward_target_fraction").getAsDouble(), stats.get("aim_pitch_track_ratio_mean").getAsDouble(), stats.get("aim_pitch_track_ratio_cv").getAsDouble()};
    }
}
