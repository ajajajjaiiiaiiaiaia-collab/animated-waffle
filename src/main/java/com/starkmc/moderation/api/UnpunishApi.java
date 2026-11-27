package com.starkmc.moderation.api;

import com.starkmc.moderation.data.DataManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP API لفك العقوبات من بوت الديسكورد.
 *   POST /unpunish    Header: Authorization: Bearer TOKEN
 *   Body: {"type":"ban|mute|freeze","player":"Name"}
 *   Response: {"ok":true,"removed":true|false}
 *
 * معلومات اللاعب (باند / بلاك ليست / كيك):
 *   GET  /status?player=Name        أو   POST /status  Body: {"player":"Name"}
 *   Header: Authorization: Bearer TOKEN
 *   Response: {"ok":true,"player":"Name","banned":true,"ban":{...},"blacklisted":false,"blacklist":null,"kicks":{...}}
 * يشتغل فقط لو api.enabled: true (سيرفر واحد فقط).
 */
public final class UnpunishApi {

    private static final Set<String> TYPES = Set.of("ban", "mute", "freeze");
    private static final Pattern NAME = Pattern.compile("^[\\w.]{2,20}$");
    private static final Pattern TYPE_RE = Pattern.compile("\"type\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern PLAYER_RE = Pattern.compile("\"player\"\\s*:\\s*\"([^\"]*)\"");

    private static UnpunishApi instance;

    private final Logger log;
    private final DataManager data;
    private final String jdbcUrl, dbUser, dbPass;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    /** يُستدعى من onEnable */
    public static synchronized void start(JavaPlugin plugin, DataManager data) {
        shutdown();
        FileConfiguration cfg = plugin.getConfig();
        Logger log = plugin.getLogger();

        if (!cfg.getBoolean("api.enabled", false)) return;

        String token = cfg.getString("api.token", "");
        if (token == null || token.isBlank() || token.equals("CHANGE_ME")) {
            log.warning("api.enabled = true لكن api.token فاضي أو CHANGE_ME، غيّره عشان يشتغل الـ API.");
            return;
        }
        int port = cfg.getInt("api.port", 19484);
        String url = "jdbc:mysql://" + cfg.getString("mysql.host", "localhost") + ":"
                + cfg.getInt("mysql.port", 3306) + "/" + cfg.getString("mysql.database", "starkmod")
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=8000";
        try {
            instance = new UnpunishApi(log, data, port, token, url,
                    cfg.getString("mysql.username", "root"), cfg.getString("mysql.password", ""));
            log.info("Unpunish API شغال على البورت " + port);
        } catch (Exception e) {
            log.severe("فشل تشغيل الـ API على البورت " + port + ": " + e);
        }
    }

    /** يُستدعى من onDisable */
    public static synchronized void shutdown() {
        if (instance != null) {
            instance.server.stop(0);
            instance.executor.shutdownNow();
            instance = null;
        }
    }

    private UnpunishApi(Logger log, DataManager data, int port, String token,
                        String jdbcUrl, String dbUser, String dbPass) throws IOException {
        this.log = log;
        this.data = data;
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPass = dbPass;

        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(executor);
        this.server.createContext("/unpunish", ex -> handle(ex, expected));
        this.server.createContext("/status", ex -> handleStatus(ex, expected));
        this.server.start();
    }

    private void handle(HttpExchange ex, byte[] expected) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"ok\":false}"); return; }

            String auth = ex.getRequestHeaders().getFirst("Authorization");
            byte[] given = (auth == null ? "" : auth).getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(given, expected)) { send(ex, 401, "{\"ok\":false}"); return; }

            String body = new String(ex.getRequestBody().readNBytes(1024), StandardCharsets.UTF_8);
            Matcher mt = TYPE_RE.matcher(body), mp = PLAYER_RE.matcher(body);
            if (!mt.find() || !mp.find()) { send(ex, 400, "{\"ok\":false}"); return; }

            String type = mt.group(1);
            String player = mp.group(1);
            if (!TYPES.contains(type) || !NAME.matcher(player).matches()) {
                send(ex, 400, "{\"ok\":false}");
                return;
            }

            boolean removed = remove(type, player.toLowerCase());
            log.info("[API] unpunish " + type + " " + player + " -> removed=" + removed);
            send(ex, 200, "{\"ok\":true,\"removed\":" + removed + "}");
        } catch (Exception e) {
            log.warning("[API] error: " + e);
            try { send(ex, 500, "{\"ok\":false}"); } catch (IOException ignored) { }
        }
    }

    /** يحذف من الداتابيس (المصدر الأساسي) ثم ينظف الكاش المحلي فوراً */
    private boolean remove(String type, String player) throws Exception {
        int rows;
        try (Connection c = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM sm_punishments WHERE type = ? AND player = ? AND (expires = -1 OR expires > ?)")) {
            ps.setString(1, type);
            ps.setString(2, player);
            ps.setLong(3, System.currentTimeMillis());
            rows = ps.executeUpdate();
        }

        boolean local = false;
        switch (type) {
            case "ban" -> { if (data.isBanned(player)) { data.unban(player); local = true; } }
            case "mute" -> { if (data.isMuted(player)) { data.unmute(player); local = true; } }
            case "freeze" -> { if (data.isFrozen(player)) { data.unfreeze(player); local = true; } }
            default -> { }
        }
        return rows > 0 || local;
    }

    // ───────────────────────── /status ─────────────────────────

    private void handleStatus(HttpExchange ex, byte[] expected) throws IOException {
        try {
            String method = ex.getRequestMethod();
            if (!"GET".equals(method) && !"POST".equals(method)) { send(ex, 405, "{\"ok\":false}"); return; }

            String auth = ex.getRequestHeaders().getFirst("Authorization");
            byte[] given = (auth == null ? "" : auth).getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(given, expected)) { send(ex, 401, "{\"ok\":false}"); return; }

            String player = null;
            if ("GET".equals(method)) {
                String q = ex.getRequestURI().getRawQuery();
                if (q != null) {
                    for (String part : q.split("&")) {
                        if (part.startsWith("player=")) {
                            player = java.net.URLDecoder.decode(part.substring(7), StandardCharsets.UTF_8);
                        }
                    }
                }
            } else {
                String body = new String(ex.getRequestBody().readNBytes(1024), StandardCharsets.UTF_8);
                Matcher mp = PLAYER_RE.matcher(body);
                if (mp.find()) player = mp.group(1);
            }
            if (player == null || !NAME.matcher(player).matches()) { send(ex, 400, "{\"ok\":false,\"error\":\"invalid player\"}"); return; }

            send(ex, 200, status(player));
        } catch (Exception e) {
            log.warning("[API] status error: " + e);
            try { send(ex, 500, "{\"ok\":false}"); } catch (IOException ignored) { }
        }
    }

    /** يقرأ من الداتابيس مباشرة (المصدر الأساسي) فيشتغل صح من أي سيرفر. */
    private String status(String player) throws Exception {
        String key = player.toLowerCase();
        long now = System.currentTimeMillis();
        String ban = null, blacklist = null;
        StringBuilder kickList = new StringBuilder();
        int kickCount = 0;
        String lastKick = null;

        try (Connection c = DriverManager.getConnection(jdbcUrl, dbUser, dbPass)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT type, expires, reason, by_who, at_ms FROM sm_punishments WHERE player = ? AND type IN ('ban','blacklist')")) {
                ps.setString(1, key);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    String type = rs.getString(1);
                    long exp = rs.getLong(2);
                    if ("blacklist".equals(type)) {
                        blacklist = entryJson(player, -1L, rs.getString(3), rs.getString(4), rs.getLong(5), now);
                    } else if (exp == -1L || exp > now) {
                        ban = entryJson(player, exp, rs.getString(3), rs.getString(4), rs.getLong(5), now);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, reason, by_who, at_ms FROM sm_kicks WHERE player = ? ORDER BY id DESC")) {
                ps.setString(1, key);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    String k = "{\"id\":" + rs.getInt(1)
                            + ",\"player\":" + q(player)
                            + ",\"reason\":" + q(rs.getString(2))
                            + ",\"by\":" + q(rs.getString(3))
                            + ",\"at\":" + rs.getLong(4)
                            + ",\"at_iso\":" + q(Instant.ofEpochMilli(rs.getLong(4)).toString()) + "}";
                    if (kickCount == 0) lastKick = k;
                    if (kickCount < 10) {
                        if (kickList.length() > 0) kickList.append(',');
                        kickList.append(k);
                    }
                    kickCount++;
                }
            }
        }

        return "{\"ok\":true,\"player\":" + q(player)
                + ",\"banned\":" + (ban != null) + ",\"ban\":" + (ban != null ? ban : "null")
                + ",\"blacklisted\":" + (blacklist != null) + ",\"blacklist\":" + (blacklist != null ? blacklist : "null")
                + ",\"kicks\":{\"count\":" + kickCount + ",\"last\":" + (lastKick != null ? lastKick : "null")
                + ",\"recent\":[" + kickList + "]}}";
    }

    private static String entryJson(String player, long expires, String reason, String by, long at, long now) {
        boolean permanent = expires == -1L;
        return "{\"player\":" + q(player)
                + ",\"by\":" + q(by)
                + ",\"reason\":" + q(reason)
                + ",\"at\":" + at
                + ",\"at_iso\":" + q(Instant.ofEpochMilli(at).toString())
                + ",\"permanent\":" + permanent
                + ",\"expires\":" + (permanent ? "null" : String.valueOf(expires))
                + ",\"expires_iso\":" + (permanent ? "null" : q(Instant.ofEpochMilli(expires).toString()))
                + ",\"remaining_ms\":" + (permanent ? "null" : String.valueOf(Math.max(0L, expires - now))) + "}";
    }

    private static String q(String v) {
        if (v == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static void send(HttpExchange ex, int code, String json) throws IOException {
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); }
    }
}
