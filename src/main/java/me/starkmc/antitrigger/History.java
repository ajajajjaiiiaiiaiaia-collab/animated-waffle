package me.starkmc.antitrigger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** What this server has seen of a player over time (flags, AI verdicts, bans), kept across restarts. */
final class History
{
    static final class Entry
    {
        String name = "";
        int instant;
        int offtarget;
        int elytra;
        int aimassist;
        int legit;
        int uncertain;
        int cheating;
        int bans;
        long firstFlag;
        long lastFlag;
        long lastBan;
        String lastVerdict = "";
    }

    private static final Gson GSON = new Gson();
    private static final Type TYPE = new TypeToken<Map<String, Entry>>()
    {
    }.getType();

    private final Map<UUID, Entry> map = new ConcurrentHashMap<>();
    private final File file;
    private long started = System.currentTimeMillis();
    private boolean dirty;

    History(File file)
    {
        this.file = file;
    }

    void load()
    {
        if (!this.file.isFile())
        {
            return;
        }

        try
        {
            JsonObject root = GSON.fromJson(Files.readString(this.file.toPath(), StandardCharsets.UTF_8), JsonObject.class);

            if (root == null)
            {
                return;
            }

            if (root.has("started"))
            {
                this.started = root.get("started").getAsLong();
            }

            Map<String, Entry> loaded = GSON.fromJson(root.get("players"), TYPE);

            if (loaded != null)
            {
                for (Map.Entry<String, Entry> e : loaded.entrySet())
                {
                    this.map.put(UUID.fromString(e.getKey()), e.getValue());
                }
            }
        }
        catch (Exception ex)
        {
            // A damaged history file must never stop the plugin: start empty and overwrite it on the next save.
            this.map.clear();
        }
    }

    synchronized void saveIfDirty()
    {
        if (!this.dirty)
        {
            return;
        }

        this.dirty = false;
        Map<String, Entry> out = new HashMap<>();

        for (Map.Entry<UUID, Entry> e : this.map.entrySet())
        {
            out.put(e.getKey().toString(), e.getValue());
        }

        JsonObject root = new JsonObject();
        root.addProperty("started", this.started);
        root.add("players", GSON.toJsonTree(out, TYPE));

        try
        {
            this.file.getParentFile().mkdirs();
            File tmp = new File(this.file.getParentFile(), this.file.getName() + ".tmp");
            Files.writeString(tmp.toPath(), GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), this.file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException ex)
        {
            this.dirty = true;
        }
    }

    private Entry entry(UUID id, String name)
    {
        Entry e = this.map.computeIfAbsent(id, k -> new Entry());
        e.name = name;
        this.dirty = true;
        return e;
    }

    void flag(UUID id, String name, String type)
    {
        Entry e = this.entry(id, name);
        long now = System.currentTimeMillis();

        if (e.firstFlag == 0L)
        {
            e.firstFlag = now;
        }

        e.lastFlag = now;

        switch (type)
        {
            case "instant" -> e.instant++;
            case "offtarget" -> e.offtarget++;
            case "elytratarget" -> e.elytra++;
            case "aimassist" -> e.aimassist++;
            default ->
            {
            }
        }
    }

    void verdict(UUID id, String name, String verdict)
    {
        Entry e = this.entry(id, name);
        String v = verdict == null ? "" : verdict.toLowerCase(java.util.Locale.ROOT);
        e.lastVerdict = v;

        switch (v)
        {
            case "legit" -> e.legit++;
            case "cheating" -> e.cheating++;
            default -> e.uncertain++;
        }
    }

    void ban(UUID id, String name)
    {
        Entry e = this.entry(id, name);
        e.bans++;
        e.lastBan = System.currentTimeMillis();
    }

    JsonObject toJson(UUID id)
    {
        JsonObject o = new JsonObject();
        Entry e = this.map.get(id);
        double trackedDays = (System.currentTimeMillis() - this.started) / 86400000.0;
        o.addProperty("history_tracked_for_days", Evidence.r(trackedDays, 1));

        if (e == null)
        {
            o.addProperty("seen_before", false);
            return o;
        }

        long now = System.currentTimeMillis();
        o.addProperty("seen_before", true);
        JsonObject flags = new JsonObject();
        flags.addProperty("instant_hits", e.instant);
        flags.addProperty("off_crosshair", e.offtarget);
        flags.addProperty("elytra_target", e.elytra);
        flags.addProperty("aim_assist", e.aimassist);
        o.add("flags_total", flags);
        JsonObject ai = new JsonObject();
        ai.addProperty("legit", e.legit);
        ai.addProperty("uncertain", e.uncertain);
        ai.addProperty("cheating", e.cheating);
        ai.addProperty("last_verdict", e.lastVerdict);
        o.add("earlier_ai_verdicts", ai);
        o.addProperty("times_banned_by_antitrigger", e.bans);
        o.addProperty("hours_since_first_flag", e.firstFlag == 0L ? -1.0 : Evidence.r((now - e.firstFlag) / 3600000.0, 1));
        o.addProperty("hours_since_last_flag", e.lastFlag == 0L ? -1.0 : Evidence.r((now - e.lastFlag) / 3600000.0, 1));
        o.addProperty("hours_since_last_ban", e.lastBan == 0L ? -1.0 : Evidence.r((now - e.lastBan) / 3600000.0, 1));
        return o;
    }
}
