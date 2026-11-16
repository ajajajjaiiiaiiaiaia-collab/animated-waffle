package me.starkmc.antitrigger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Asks the Anthropic Messages API for a second opinion on a flagged player. Blocking: call it off the main thread. */
final class AiReviewer
{
    record Verdict(String verdict, double confidence, String cheatType, List<String> reasons, String error)
    {
    }

    private static final String SYSTEM = """
        You review anti-cheat evidence from a Minecraft Java (Paper 1.21) PvP server and decide whether a player is using a combat cheat.
        You get a JSON document with: the flag that fired, thresholds, summary statistics, a per-tick table (frame_columns + frames, ~20 rows per second) and a list of attacks.
        Frame columns: tick, x, y, z, yaw, pitch, dYaw and dPitch (rotation change since the previous tick, in degrees), sprint, sneak, ground, ping,
        target (the player being fought), distToTarget (blocks to the target's hitbox), angleErrDeg (angle between where the player looks and the target's centre),
        yawErrDeg, pitchErrDeg, lookRayOnTarget (1 when the server-side crosshair ray touched the target this tick).
        What cheats look like:
        - Triggerbot: attacks land the tick the crosshair touches the target (ticks_crosshair_was_on_victim_before_hit of 1 or 2) while the mouse was still (mouse_still_ms_before_hit high), almost never swinging at nothing.
        - Killaura: attacks land while the crosshair is clearly away from the target (large angle_error_deg), or one player hits several targets with no aiming; distance may exceed 3.0 blocks.
        - Aim assist or aimbot: the angle error collapses to near zero within a tick or two (fast snaps ending exactly on the target), holds near zero while the target moves, then the attack follows.
        - Autoclicker: very high or perfectly regular CPS (over about 20).
        - ElytraTarget (elytra cheat): while gliding, the client locks the crosshair onto the nearest player (even 60+ blocks away), fires fireworks automatically at a fixed interval (every 8 to 10 ticks, often by swapping to the firework slot and back), and is usually combined with killaura. In the "elytra" section look for: rocket intervals with a standard deviation near 0 to 1.5 ticks, repeated slot swap -> rocket -> swap cycles, and a crosshair that stays within a few degrees of the nearest player while gliding (aim_lock_ticks).
        What legitimate play looks like and what causes false alarms:
        - AimAssist (subtle aim cheat): the view is pulled toward the target by a roughly constant share of the remaining error every tick, so hits can still be only a few degrees off. In "stats", aim_yaw_track_* / aim_pitch_track_* measure, while tracking, rotation this tick divided by the error last tick: toward_target_fraction near 1.0, a stable ratio_mean (about 0.05 to 0.5) and a low ratio_cv (under about 0.35) suggest aim assist. Humans typically have ratio_cv above 0.6 and often overshoot. aim_yaw_accel_stddev_deg very small means unnaturally smooth turning. pitch_delta_gcd_step_deg is the common step of the pitch changes: a real mouse gives a step of at least about 0.01, 0 means the deltas are not multiples of any sensitivity step (possible modified rotation), but controllers, zoom and some mods also do that, so it is supporting evidence only. Good players track smoothly too: do not call cheating on smoothness alone, require several of these signals together with hits that are consistently very close to the target centre.
        - Prefer the hitbox fields over the centre-based angle: angle_error_deg is measured to the centre of the victim's body, so at 2 to 3 blocks a correct hit on the head or feet already shows up to about 20 degrees. angle_to_hitbox_deg is 0 when the crosshair was on the victim's body. crosshair_on_victim_lag_compensated and angle_to_hitbox_lag_compensated_deg compare the crosshair with where the victim was about one ping ago, which is what the attacker's screen showed. An attack that is on the lag-compensated hitbox is a normal hit, not evidence. Stats attacks_crosshair_off_hitbox_lag_compensated counts only the attacks that were off even after that correction.
        - Context per attack: other_player_in_front_of_crosshair names a second player in the way (the server ray then hits that player instead of the victim, so an off-target reading is not suspicious); other_players_within_6_blocks and victim_speed_blocks_per_tick describe a crowded or fast fight where the server ray is unreliable.
        - attacks_hit_first_then_turned_to_victim_within_3_ticks counts attacks that landed while the crosshair was far from the victim (and not on the lag-compensated hitbox) followed within 3 ticks by a sharp turn onto the victim. This is the ordering of a rotation that follows the attack, seen at tick resolution from the frames, not from raw packets. It is a strong sign of killaura or rotation spoofing when it repeats, and meaningless as a single case.
        - player_history is what this server has recorded before: earlier flags by type, earlier AI verdicts, bans and times. history_tracked_for_days tells you how long the record has existed, so an empty history on a new record proves nothing. Repeated flags over hours and several earlier cheating verdicts raise the probability; a long record of legit or uncertain verdicts lowers it. History alone must never decide: the current evidence has to show the cheat.
        - Holding the right mouse button on a firework rocket repeats it every 4 ticks exactly, which is legitimate and not evidence of ElytraTarget. Skilled elytra fighters also lock their view on an enemy and swap hotbar slots between sword and rockets.
        - An attack with an angle error above 90 degrees, a distance above about 4.2 blocks, or a big difference between ping_min_ms_in_window and ping_max_ms_in_window is almost always a lag spike, reconnect or teleport artifact, not a cheat. Never cite a single outlier like that as evidence. A "cheating" verdict needs several separate attacks that are off target at normal distances while the ping is steady.
        - High ping or low TPS make the server-side ray lag behind the real crosshair; judge ping and tps before trusting off-target attacks.
        - Knockback pushes the target back into the crosshair, creating many fast hits at close range.
        - Strafing and close fights produce natural big rotation changes; smooth, gradual angle error reduction is normal.
        - Attacks closer than 1.5 blocks are left out of the statistics because the angle is meaningless when the target overlaps the attacker (attacks_ignored_closer_than_1_5_blocks says how many).
        - Distances are measured on the server. With ping around 100ms a legitimate attacker can appear up to about 0.5 blocks beyond the real reach limit of 3.0, so only distances clearly above 3.6 count as reach evidence.
        - frames cover only the last frame_window_seconds, attacks cover attack_window_seconds (see seconds_ago on each attack).
        - attacks_scoped_to_single_victim tells you whether every attack in this evidence is against the same victim (true, victim_filter names them) or may include several different victims from separate fights (false). The summary statistics (attacks_recorded, attacks_off_victim_server_ray, etc.) are totals across whatever attacks are included — when attacks_scoped_to_single_victim is false, treat those totals as describing a mix of fights, not one continuous engagement, and say so in your reasons; do not describe them as "X of Y hits in a fight" unless scoped is true. Check the victim field on individual attacks if you need to know which fight a specific one belongs to.
        - Count only what the statistics say. Do not claim "X of Y attacks" with numbers that are not in the evidence, and do not present a cross-fight total as if it were one fight.
        - A handful of attacks is weak evidence, and a handful of attacks spread across different victims is weaker still. A banned innocent player is much worse than a missed cheater, so be conservative. If attacks_scoped_to_single_victim is false and the evidence does not clearly hold up fight-by-fight, prefer "uncertain" over "cheating".
        Reply with ONLY one JSON object and nothing else:
        {"verdict":"cheating"|"legit"|"uncertain","confidence":0.0-1.0,"cheat_type":"triggerbot"|"killaura"|"aim_assist"|"autoclicker"|"reach"|"other"|"none","reasons":["short concrete reason citing numbers from the evidence", "..."]}
        Use "cheating" only when several independent signals agree and lag or knockback cannot explain them.
        """;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    static final class ApiFailure extends IllegalStateException
    {
        final int status;

        ApiFailure(int status, String message)
        {
            super(message);
            this.status = status;
        }
    }

    private volatile List<String> keys = List.of();
    private volatile long exhaustedMs = 30L * 60000L;
    private final Map<String, Long> coolingUntil = new ConcurrentHashMap<>();
    volatile Consumer<String> notice = s ->
    {
    };

    /** Keys are tried in this order; a key that runs out of quota (or is rejected) is skipped for a while, then tried again first. */
    void setKeys(List<String> list, long exhaustedMinutes)
    {
        this.keys = List.copyOf(list);
        this.exhaustedMs = Math.max(1L, exhaustedMinutes) * 60000L;
        this.coolingUntil.keySet().retainAll(list);
    }

    private static String mask(String key)
    {
        return key.length() <= 6 ? "****" : key.substring(0, 4) + "****" + key.substring(key.length() - 2);
    }

    private static boolean keyProblem(int status, String message)
    {
        return status == 429 || status == 401 || status == 403 || (status == 400 && message != null && (message.contains("API key") || message.contains("API_KEY")));
    }

    Verdict review(String provider, String apiKey, String model, int timeoutSeconds, String evidenceJson)
    {
        List<String> pool = this.keys;

        if (pool.isEmpty())
        {
            return this.reviewWith(provider, apiKey, model, timeoutSeconds, evidenceJson, new int[1]);
        }

        Verdict last = null;
        long now = System.currentTimeMillis();
        long soonest = Long.MAX_VALUE;

        for (int i = 0; i < pool.size(); i++)
        {
            String key = pool.get(i);
            Long until = this.coolingUntil.get(key);

            if (until != null && until > now)
            {
                soonest = Math.min(soonest, until);
                continue;
            }

            int[] status = new int[1];
            Verdict v = this.reviewWith(provider, key, model, timeoutSeconds, evidenceJson, status);

            if (v.error() == null || !keyProblem(status[0], v.error()))
            {
                return v;
            }

            long hold = status[0] == 429 ? this.exhaustedMs : Math.max(this.exhaustedMs, 6L * 3600000L);
            this.coolingUntil.put(key, System.currentTimeMillis() + hold);
            soonest = Math.min(soonest, System.currentTimeMillis() + hold);
            last = v;
            this.notice.accept("AI key #" + (i + 1) + " (" + mask(key) + ") " + (status[0] == 429 ? "ran out of quota" : "was rejected") + " (HTTP " + status[0] + "), switching to the next key");
        }

        long minutes = soonest == Long.MAX_VALUE ? 0L : Math.max(1L, (soonest - System.currentTimeMillis()) / 60000L);
        return failure("All " + pool.size() + " AI keys are exhausted or rejected, next one is free in about " + minutes + " min" + (last == null ? "" : " (last error: " + last.error() + ")"));
    }

    boolean hasKeyAvailable()
    {
        List<String> pool = this.keys;

        if (pool.isEmpty())
        {
            return true;
        }

        long now = System.currentTimeMillis();

        for (String key : pool)
        {
            Long until = this.coolingUntil.get(key);

            if (until == null || until <= now)
            {
                return true;
            }
        }

        return false;
    }

    private Verdict reviewWith(String provider, String apiKey, String model, int timeoutSeconds, String evidenceJson, int[] statusOut)
    {
        try
        {
            String userText = "Evidence (JSON):\n" + evidenceJson + "\n\nReply with the JSON verdict only.";
            String reply = "gemini".equals(provider) ? this.callGemini(apiKey, model, timeoutSeconds, userText) : this.callAnthropic(apiKey, model, timeoutSeconds, userText);
            int from = reply.indexOf('{');
            int to = reply.lastIndexOf('}');

            if (from < 0 || to < from)
            {
                return failure("reply had no JSON");
            }

            JsonObject v = JsonParser.parseString(reply.substring(from, to + 1)).getAsJsonObject();
            String verdict = v.has("verdict") ? v.get("verdict").getAsString() : "uncertain";
            double confidence = v.has("confidence") ? v.get("confidence").getAsDouble() : 0.0;

            if (confidence > 1.0 && confidence <= 100.0)
            {
                confidence = confidence / 100.0;
            }

            confidence = Math.max(0.0, Math.min(1.0, confidence));
            String type = v.has("cheat_type") ? v.get("cheat_type").getAsString() : "none";
            List<String> reasons = new ArrayList<>();

            if (v.has("reasons") && v.get("reasons").isJsonArray())
            {
                for (JsonElement r : v.getAsJsonArray("reasons"))
                {
                    reasons.add(r.getAsString());
                }
            }

            return new Verdict(verdict, confidence, type, reasons, null);
        }
        catch (Exception ex)
        {
            if (ex instanceof InterruptedException)
            {
                Thread.currentThread().interrupt();
            }

            if (ex instanceof ApiFailure af)
            {
                statusOut[0] = af.status;
            }

            return failure(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private String callAnthropic(String apiKey, String model, int timeoutSeconds, String userText) throws Exception
    {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", 900);
        body.addProperty("system", SYSTEM);
        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", userText);
        messages.add(message);
        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
            .timeout(Duration.ofSeconds(Math.max(3, timeoutSeconds)))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
        this.requireOk(response);
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        StringBuilder out = new StringBuilder();

        for (JsonElement element : root.getAsJsonArray("content"))
        {
            JsonObject block = element.getAsJsonObject();

            if (block.has("type") && "text".equals(block.get("type").getAsString()) && block.has("text"))
            {
                out.append(block.get("text").getAsString());
            }
        }

        return out.toString();
    }

    private String callGemini(String apiKey, String model, int timeoutSeconds, String userText) throws Exception
    {
        JsonObject body = new JsonObject();
        JsonObject system = new JsonObject();
        JsonArray systemParts = new JsonArray();
        JsonObject systemPart = new JsonObject();
        systemPart.addProperty("text", SYSTEM);
        systemParts.add(systemPart);
        system.add("parts", systemParts);
        body.add("systemInstruction", system);

        JsonArray contents = new JsonArray();
        JsonObject content = new JsonObject();
        content.addProperty("role", "user");
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", userText);
        parts.add(part);
        content.add("parts", parts);
        contents.add(content);
        body.add("contents", contents);

        JsonObject generation = new JsonObject();
        generation.addProperty("maxOutputTokens", 2500);
        generation.addProperty("temperature", 0.1);
        generation.addProperty("responseMimeType", "application/json");
        body.add("generationConfig", generation);

        HttpRequest request = HttpRequest.newBuilder(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"))
            .timeout(Duration.ofSeconds(Math.max(3, timeoutSeconds)))
            .header("x-goog-api-key", apiKey)
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
        this.requireOk(response);
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        StringBuilder out = new StringBuilder();

        if (root.has("candidates") && root.getAsJsonArray("candidates").size() > 0)
        {
            JsonObject candidate = root.getAsJsonArray("candidates").get(0).getAsJsonObject();

            if (candidate.has("content") && candidate.getAsJsonObject("content").has("parts"))
            {
                for (JsonElement element : candidate.getAsJsonObject("content").getAsJsonArray("parts"))
                {
                    JsonObject p = element.getAsJsonObject();

                    if (p.has("text") && !(p.has("thought") && p.get("thought").getAsBoolean()))
                    {
                        out.append(p.get("text").getAsString());
                    }
                }
            }
        }

        return out.toString();
    }

    private void requireOk(HttpResponse<String> response)
    {
        if (response.statusCode() != 200)
        {
            String text = response.body() == null ? "" : response.body();
            throw new ApiFailure(response.statusCode(), "HTTP " + response.statusCode() + " " + (text.length() > 200 ? text.substring(0, 200) : text));
        }
    }

    record Check(int count, boolean modelFound)
    {
    }

    /** Cheap request that only lists models: proves the key and the network work, and that the model name exists. */
    Check checkAccess(String provider, String apiKey, String model, int timeoutSeconds) throws Exception
    {
        HttpRequest request;

        if ("gemini".equals(provider))
        {
            request = HttpRequest.newBuilder(URI.create("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200"))
                .timeout(Duration.ofSeconds(Math.max(3, timeoutSeconds)))
                .header("x-goog-api-key", apiKey)
                .GET()
                .build();
        }
        else
        {
            request = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/models?limit=200"))
                .timeout(Duration.ofSeconds(Math.max(3, timeoutSeconds)))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .GET()
                .build();
        }

        HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
        this.requireOk(response);
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray list = root.has("models") ? root.getAsJsonArray("models") : root.has("data") ? root.getAsJsonArray("data") : new JsonArray();
        int count = 0;
        boolean found = false;

        for (JsonElement element : list)
        {
            JsonObject o = element.getAsJsonObject();
            String id = o.has("name") ? o.get("name").getAsString() : o.has("id") ? o.get("id").getAsString() : "";
            count++;

            if (id.equals(model) || id.equals("models/" + model))
            {
                found = true;
            }
        }

        return new Check(count, found);
    }

    private static Verdict failure(String error)
    {
        return new Verdict("uncertain", 0.0, "none", List.of(), error);
    }
}
