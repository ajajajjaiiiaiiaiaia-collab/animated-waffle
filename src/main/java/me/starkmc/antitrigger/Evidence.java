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
    record AttackRec(int tick, String victim, double dist, boolean onCrosshair, boolean wideRay, int reaction, long mouseStillMs, int cps, double angleErr, double yawErr, double pitchErr, double damage, double cooldown, String ignored)
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
        return s;
    }
}
