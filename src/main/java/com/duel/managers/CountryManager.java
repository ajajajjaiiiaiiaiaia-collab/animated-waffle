package com.duel.managers;

import com.duel.DuelPlugin;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks up a player's country (ISO 3166-1 alpha-2 code, e.g. "EG", "US") from
 * their IP address on join, using the free ip-api.com endpoint (no API key
 * needed). Results are cached in memory for the session.
 *
 * NOTE: ip-api.com's free tier is rate-limited to ~45 requests/minute from
 * the SAME server IP. That's plenty for small/medium networks, but on a
 * high-traffic network with lots of joins per minute you may want to switch
 * to an offline database (MaxMind GeoLite2) instead — ask if you want that
 * version built.
 */
public class CountryManager {

    private static final Pattern COUNTRY_CODE_PATTERN =
            Pattern.compile("\"countryCode\"\\s*:\\s*\"([A-Z]{2})\"");
    private static final Pattern TIMEZONE_PATTERN =
            Pattern.compile("\"timezone\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern STATUS_FAIL_PATTERN =
            Pattern.compile("\"status\"\\s*:\\s*\"fail\"");

    private final DuelPlugin plugin;
    private final Map<java.util.UUID, String> countryCache = new ConcurrentHashMap<>();
    private final Map<java.util.UUID, java.time.ZoneId> zoneCache = new ConcurrentHashMap<>();

    private final Map<java.util.UUID, String> persisted = new ConcurrentHashMap<>();

    public CountryManager(DuelPlugin plugin) {
        this.plugin = plugin;
        loadPersisted();
    }

    private void loadPersisted() {
        try (java.sql.Connection conn = plugin.getDatabaseManager().getConnection();
             java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS player_country (uuid TEXT PRIMARY KEY, code TEXT NOT NULL)");
            java.sql.ResultSet rs = st.executeQuery("SELECT uuid, code FROM player_country");
            while (rs.next()) {
                try { persisted.put(java.util.UUID.fromString(rs.getString(1)), rs.getString(2)); }
                catch (IllegalArgumentException ignored) { }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[CountryManager] persisted countries not loaded: " + e.getMessage());
        }
    }

    private void persist(java.util.UUID uuid, String code) {
        if (code == null || code.equals("XX")) return;
        persisted.put(uuid, code);
        try (java.sql.Connection conn = plugin.getDatabaseManager().getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR REPLACE INTO player_country (uuid, code) VALUES (?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, code);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning("[CountryManager] persist failed: " + e.getMessage());
        }
    }

    /** Cached lookup — returns null if not looked up yet or lookup failed. */
    public String getCountryCode(java.util.UUID uuid) {
        String c = countryCache.get(uuid);
        if (c != null && !c.equals("XX")) return c;
        String saved = persisted.get(uuid);
        return saved != null ? saved : c;
    }

    /** Cached timezone from the GeoIP lookup — null if unknown. */
    public java.time.ZoneId getZoneId(java.util.UUID uuid) {
        return zoneCache.get(uuid);
    }

    /** English country name for an ISO code (e.g. "EG" → "Egypt"), or "Unknown". */
    public static String getCountryName(String countryCode) {
        if (countryCode == null || countryCode.length() != 2 || countryCode.equalsIgnoreCase("XX")) return "Unknown";
        String name = new java.util.Locale.Builder().setRegion(countryCode.toUpperCase())
                .build().getDisplayCountry(java.util.Locale.ENGLISH);
        return (name == null || name.isBlank()) ? "Unknown" : name;
    }

    /** Kicks off an async GeoIP lookup for this player and caches the result. */
    public void lookupAsync(Player player) {
        if (!plugin.getConfig().getBoolean("country-flag.enabled", true)) return;

        String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                ? player.getAddress().getAddress().getHostAddress()
                : null;
        if (ip == null || ip.equals("127.0.0.1") || ip.startsWith("192.168.") || ip.startsWith("10.")) {
            // Local/LAN test connection — no public IP to geolocate.
            countryCache.put(player.getUniqueId(), "XX");
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String[] result = fetchCountryInfo(ip);
            String code = result != null ? result[0] : null;
            countryCache.put(player.getUniqueId(), code != null ? code : "XX");
            persist(player.getUniqueId(), code);
            if (result != null && result[1] != null) {
                try { zoneCache.put(player.getUniqueId(), java.time.ZoneId.of(result[1])); }
                catch (Exception ignored) { /* bad zone id — fall back to server time */ }
            }
            // طبّق علم التاب على الـ main thread بعد ما تتحدد الدولة
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (plugin.getTabFlagManager() != null) plugin.getTabFlagManager().apply(player);
            });
        });
    }

    public void clear(java.util.UUID uuid) {
        countryCache.remove(uuid);
        zoneCache.remove(uuid);
    }

    private String[] fetchCountryInfo(String ip) {
        int timeout = plugin.getConfig().getInt("country-flag.api-timeout-ms", 3000);
        try {
            URL url = new URL("http://ip-api.com/json/" + ip + "?fields=status,countryCode,timezone");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }

            if (STATUS_FAIL_PATTERN.matcher(body).find()) return null;

            Matcher m = COUNTRY_CODE_PATTERN.matcher(body);
            if (!m.find()) return null;
            Matcher tz = TIMEZONE_PATTERN.matcher(body);
            return new String[]{ m.group(1), tz.find() ? tz.group(1) : null };
        } catch (Exception e) {
            plugin.getLogger().warning("[CountryManager] GeoIP lookup failed for " + ip + ": " + e.getMessage());
            return null;
        }
    }

    /** Flag emoji sequence for an ISO code (reference only — flags in-game are player heads, see TabFlagManager). */
    public static String toFlagEmoji(String countryCode) {
        if (countryCode == null || countryCode.length() != 2) return "";
        int base = 0x1F1E6;
        int c1 = base + (Character.toUpperCase(countryCode.charAt(0)) - 'A');
        int c2 = base + (Character.toUpperCase(countryCode.charAt(1)) - 'A');
        return new String(Character.toChars(c1)) + new String(Character.toChars(c2));
    }
}
