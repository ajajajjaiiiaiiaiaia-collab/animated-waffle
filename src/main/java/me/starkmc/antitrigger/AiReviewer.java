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
        What legitimate play looks like and what causes false alarms:
        - High ping or low TPS make the server-side ray lag behind the real crosshair; judge ping and tps before trusting off-target attacks.
        - Knockback pushes the target back into the crosshair, creating many fast hits at close range.
        - Strafing and close fights produce natural big rotation changes; smooth, gradual angle error reduction is normal.
        - A handful of attacks is weak evidence. A banned innocent player is much worse than a missed cheater, so be conservative.
        Reply with ONLY one JSON object and nothing else:
        {"verdict":"cheating"|"legit"|"uncertain","confidence":0.0-1.0,"cheat_type":"triggerbot"|"killaura"|"aim_assist"|"autoclicker"|"reach"|"other"|"none","reasons":["short concrete reason citing numbers from the evidence", "..."]}
        Use "cheating" only when several independent signals agree and lag or knockback cannot explain them.
        """;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    Verdict review(String apiKey, String model, int timeoutSeconds, String evidenceJson)
    {
        try
        {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("max_tokens", 900);
            body.addProperty("system", SYSTEM);
            JsonArray messages = new JsonArray();
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.addProperty("content", "Evidence (JSON):\n" + evidenceJson + "\n\nReply with the JSON verdict only.");
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

            if (response.statusCode() != 200)
            {
                String text = response.body() == null ? "" : response.body();
                return failure("HTTP " + response.statusCode() + " " + (text.length() > 200 ? text.substring(0, 200) : text));
            }

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

            String reply = out.toString();
            int from = reply.indexOf('{');
            int to = reply.lastIndexOf('}');

            if (from < 0 || to < from)
            {
                return failure("reply had no JSON");
            }

            JsonObject v = JsonParser.parseString(reply.substring(from, to + 1)).getAsJsonObject();
            String verdict = v.has("verdict") ? v.get("verdict").getAsString() : "uncertain";
            double confidence = v.has("confidence") ? v.get("confidence").getAsDouble() : 0.0;
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

            return failure(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private static Verdict failure(String error)
    {
        return new Verdict("uncertain", 0.0, "none", List.of(), error);
    }
}
