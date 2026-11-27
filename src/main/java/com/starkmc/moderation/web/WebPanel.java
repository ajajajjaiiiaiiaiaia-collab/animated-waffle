package com.starkmc.moderation.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.starkmc.moderation.ModerationPlugin;
import com.starkmc.moderation.data.DataManager;
import com.starkmc.moderation.data.KickEntry;
import com.starkmc.moderation.data.WarnEntry;
import com.starkmc.moderation.util.BanAnimation;
import com.starkmc.moderation.util.DurationUtil;
import com.starkmc.moderation.util.NetworkKick;
import com.starkmc.moderation.util.Screens;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Deque;
import java.util.LinkedHashMap;
import com.starkmc.moderation.util.ProxyPlayers;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/**
 * لوحة تحكم ويب مدمجة داخل البلقن (بدون استضافة خارجية).
 * تشتغل على سيرفر واحد فقط (web.enabled: true) وتتحكم بالباند / البلاك ليست / الكيك.
 * الدخول فقط عبر ديسكورد (OAuth2) ولأصحاب صلاحية Administrator في سيرفر ديسكورد المحدد.
 */
public final class WebPanel {
   private static final Pattern NAME = Pattern.compile("^[\\w.]{2,20}$");
   private static final Set<String> ALL_PERMS = Set.of("view", "ban", "unban", "blacklist", "unblacklist", "kick", "mute", "unmute", "warn", "unwarn", "logs", "control");
   private static final String COOKIE = "sm_session";
   private static final long FAIL_WINDOW_MS = 300_000L;
   private static final int MAX_FAILS = 5;

   private static WebPanel instance;

   private final ModerationPlugin plugin;
   private final DataManager data;
   private final HttpServer server;
   private final ExecutorService executor = Executors.newFixedThreadPool(4);
   private static final String DISCORD_API = "https://discord.com/api/v10";
   private static final long ADMIN_BIT = 0x8L;
   private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
   private final Deque<JsonObject> auditLog = new ConcurrentLinkedDeque<>();
   private final Map<String, Set<String>> overrides = new HashMap<>();
   private final Set<String> blockedIds = new HashSet<>();
   private Set<String> defaultPerms = new HashSet<>();
   private String clientId;
   private String clientSecret;
   private String redirectUri;
   private String guildId;
   private long recheckMs;
   private final Map<String, Session> sessions = new ConcurrentHashMap<>();
   private final Map<String, long[]> fails = new ConcurrentHashMap<>();
   private final Set<String> protectedNames = new HashSet<>();
   private final SecureRandom random = new SecureRandom();
   private final byte[] indexHtml;
   private final long sessionMs;
   private final boolean trustProxy;
   private final boolean secureCookie;

   private record Account(String id, String name, String avatar, Set<String> perms) {
   }

   private static final class Session {
      final String accessToken;
      final long expires;
      volatile Account acc;
      volatile long checked;

      Session(Account acc, String accessToken, long expires) {
         this.acc = acc;
         this.accessToken = accessToken;
         this.expires = expires;
         this.checked = System.currentTimeMillis();
      }
   }

   private static final class ApiException extends Exception {
      final int code;

      ApiException(int code, String msg) {
         super(msg);
         this.code = code;
      }
   }

   public static synchronized void start(ModerationPlugin plugin, DataManager data) {
      shutdown();
      FileConfiguration cfg = plugin.getConfig();
      if (!cfg.getBoolean("web.enabled", false)) {
         return;
      }
      try {
         instance = new WebPanel(plugin, data);
         plugin.getLogger().info("Web panel شغال على البورت " + cfg.getInt("web.port", 8085));
      } catch (Exception e) {
         plugin.getLogger().severe("فشل تشغيل لوحة الويب: " + e.getMessage());
      }
   }

   public static synchronized void shutdown() {
      if (instance != null) {
         instance.server.stop(0);
         instance.executor.shutdownNow();
         instance = null;
      }
   }

   private WebPanel(ModerationPlugin plugin, DataManager data) throws Exception {
      this.plugin = plugin;
      this.data = data;
      FileConfiguration cfg = plugin.getConfig();

      ConfigurationSection dc = cfg.getConfigurationSection("web.discord");
      if (dc == null) {
         throw new IllegalStateException("قسم web.discord مفقود من config.yml");
      }
      this.clientId = dc.getString("client-id", "").trim();
      this.clientSecret = dc.getString("client-secret", "").trim();
      this.redirectUri = dc.getString("redirect-uri", "").trim();
      this.guildId = dc.getString("guild-id", "").trim();
      if (this.clientId.isEmpty() || this.clientSecret.isEmpty() || this.redirectUri.isEmpty() || this.guildId.isEmpty()) {
         throw new IllegalStateException("عبّي web.discord (client-id / client-secret / redirect-uri / guild-id)");
      }
      this.recheckMs = TimeUnit.MINUTES.toMillis(Math.max(1, dc.getInt("recheck-minutes", 10)));
      this.defaultPerms = permSet(cfg.getStringList("web.default-permissions"), true);
      ConfigurationSection ov = cfg.getConfigurationSection("web.overrides");
      if (ov != null) {
         for (String id : ov.getKeys(false)) {
            this.overrides.put(id, permSet(ov.getStringList(id + ".permissions"), false));
         }
      }
      this.blockedIds.addAll(cfg.getStringList("web.blocked-ids"));
      for (String n : cfg.getStringList("web.protected-players")) {
         this.protectedNames.add(n.toLowerCase());
      }
      this.sessionMs = TimeUnit.HOURS.toMillis(Math.max(1, cfg.getInt("web.session-hours", 12)));
      this.trustProxy = cfg.getBoolean("web.trust-proxy", false);
      this.secureCookie = cfg.getBoolean("web.secure-cookie", false);

      try (InputStream in = plugin.getResource("web/index.html")) {
         if (in == null) {
            throw new IllegalStateException("web/index.html مفقود من الجار");
         }
         this.indexHtml = in.readAllBytes();
      }

      int port = cfg.getInt("web.port", 8085);
      String bind = cfg.getString("web.bind", "0.0.0.0");
      this.server = HttpServer.create(new InetSocketAddress(bind, port), 0);
      this.server.setExecutor(this.executor);
      this.server.createContext("/", this::handle);
      this.server.start();
   }

   // ───────────────────────── routing ─────────────────────────

   private void handle(HttpExchange ex) throws IOException {
      try {
         String path = ex.getRequestURI().getPath();
         String method = ex.getRequestMethod();

         if ("GET".equals(method) && "/auth/login".equals(path)) {
            authLogin(ex);
            return;
         }
         if ("GET".equals(method) && "/auth/callback".equals(path)) {
            authCallback(ex);
            return;
         }
         if (!path.startsWith("/api/")) {
            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
               sendHtml(ex);
            } else {
               sendJson(ex, 404, error("not found"));
            }
            return;
         }

         boolean post = "POST".equals(method);
         if (!post && !"GET".equals(method)) {
            sendJson(ex, 405, error("method not allowed"));
            return;
         }
         if (post && !"StarkPanel".equals(ex.getRequestHeaders().getFirst("X-Requested-With"))) {
            sendJson(ex, 403, error("forbidden"));
            return;
         }
         JsonObject body = post ? readBody(ex) : new JsonObject();

         String token = token(ex);
         Session s = token == null ? null : this.sessions.get(token);
         long nowMs = System.currentTimeMillis();
         if (s != null && (s.expires < nowMs || !stillValid(s, nowMs))) {
            this.sessions.remove(token);
            s = null;
         }
         Account acc = s == null ? null : s.acc;
         if (acc == null) {
            sendJson(ex, 401, error("unauthorized"));
            return;
         }

         JsonObject out;
         switch (path) {
            case "/api/me" -> out = me(acc);
            case "/api/logout" -> {
               this.sessions.remove(token);
               out = ok();
            }
            case "/api/player" -> {
               need(acc, "view");
               out = player(playerName(query(ex, "name")));
            }
            case "/api/dashboard" -> {
               need(acc, "view");
               out = dashboard();
            }
            case "/api/tops" -> {
               need(acc, "view");
               out = tops();
            }
            case "/api/logs" -> {
               need(acc, "logs");
               out = logs();
            }
            case "/api/day" -> {
               need(acc, "view");
               out = day(query(ex, "d"));
            }
            case "/api/staff" -> {
               need(acc, "view");
               out = staff(query(ex, "name"));
            }
            case "/api/list" -> {
               need(acc, "view");
               out = list();
            }
            case "/api/ban" -> {
               needPost(post);
               need(acc, "ban");
               out = ban(acc, body);
            }
            case "/api/unban" -> {
               needPost(post);
               need(acc, "unban");
               out = unban(acc, body);
            }
            case "/api/blacklist" -> {
               needPost(post);
               need(acc, "blacklist");
               out = blacklist(acc, body);
            }
            case "/api/unblacklist" -> {
               needPost(post);
               need(acc, "unblacklist");
               out = unblacklist(acc, body);
            }
            case "/api/kick" -> {
               needPost(post);
               need(acc, "kick");
               out = kick(acc, body);
            }
            case "/api/mute" -> {
               needPost(post);
               need(acc, "mute");
               out = mute(acc, body);
            }
            case "/api/unmute" -> {
               needPost(post);
               need(acc, "unmute");
               out = unmute(acc, body);
            }
            case "/api/warn" -> {
               needPost(post);
               need(acc, "warn");
               out = warn(acc, body);
            }
            case "/api/unwarn" -> {
               needPost(post);
               need(acc, "unwarn");
               out = unwarn(acc, body);
            }
            case "/api/control" -> {
               need(acc, "control");
               out = controlInfo();
            }
            case "/api/control/unbanall", "/api/control/unmuteall", "/api/control/unfreezeall", "/api/control/clearwarns", "/api/control/unblacklistall" -> {
               needPost(post);
               need(acc, "control");
               String kind = path.substring("/api/control/".length());
               out = controlRun(acc, kind, body);
               // audit entry: action = kind, reason = number of affected records
               body.addProperty("reason", String.valueOf(out.get("count").getAsInt()));
            }
            default -> throw new ApiException(404, "not found");
         }
         if (post && !"/api/logout".equals(path)) {
            audit(acc, path.substring(5), str(body, "player"), str(body, "reason"));
         }
         sendJson(ex, 200, out);
      } catch (ApiException e) {
         sendJson(ex, e.code, error(e.getMessage()));
      } catch (Exception e) {
         this.plugin.getLogger().warning("[Web] error: " + e);
         try {
            sendJson(ex, 500, error("internal error"));
         } catch (IOException ignored) {
         }
      }
   }

   private static void need(Account acc, String perm) throws ApiException {
      if (!acc.perms().contains(perm)) {
         throw new ApiException(403, "ما عندك صلاحية لهذا الإجراء");
      }
   }

   private static void needPost(boolean post) throws ApiException {
      if (!post) {
         throw new ApiException(405, "method not allowed");
      }
   }

   // ───────────────────────── auth ─────────────────────────

   private static Set<String> permSet(List<String> raw, boolean emptyMeansAll) {
      Set<String> set = new HashSet<>();
      for (String p : raw) {
         set.add(p.toLowerCase());
      }
      if (set.isEmpty() && emptyMeansAll) {
         set.add("all");
      }
      if (set.contains("all")) {
         set.addAll(ALL_PERMS);
      }
      return set;
   }

   private static String enc(String v) {
      return URLEncoder.encode(v, StandardCharsets.UTF_8);
   }

   private static String hex(byte[] raw) {
      StringBuilder sb = new StringBuilder();
      for (byte b : raw) {
         sb.append(String.format("%02x", b));
      }
      return sb.toString();
   }

   private void redirect(HttpExchange ex, String location, String... cookies) throws IOException {
      for (String c : cookies) {
         if (c != null) {
            ex.getResponseHeaders().add("Set-Cookie", c);
         }
      }
      ex.getResponseHeaders().set("Location", location);
      ex.getResponseHeaders().set("Cache-Control", "no-store");
      ex.sendResponseHeaders(302, -1L);
      ex.close();
   }

   private void failed(String ip, long now) {
      this.fails.merge(ip, new long[]{1L, now}, (old, fresh) -> now - old[1] >= FAIL_WINDOW_MS ? fresh : new long[]{old[0] + 1L, old[1]});
   }

   /** الخطوة 1: نحول المستخدم لصفحة ديسكورد (OAuth2). */
   private void authLogin(HttpExchange ex) throws IOException {
      byte[] raw = new byte[24];
      this.random.nextBytes(raw);
      String state = hex(raw);
      String url = "https://discord.com/oauth2/authorize?client_id=" + enc(this.clientId)
         + "&response_type=code&scope=" + enc("identify guilds")
         + "&redirect_uri=" + enc(this.redirectUri) + "&state=" + state + "&prompt=none";
      redirect(ex, url, "sm_state=" + state + "; Path=/auth; Max-Age=600; HttpOnly; SameSite=Lax" + (this.secureCookie ? "; Secure" : ""));
   }

   /** الخطوة 2: ديسكورد يرجّع المستخدم هنا مع code، نتحقق إنه Administrator في السيرفر. */
   private void authCallback(HttpExchange ex) throws IOException {
      String ip = clientIp(ex);
      long now = System.currentTimeMillis();
      long[] f = this.fails.get(ip);
      String clear = "sm_state=; Path=/auth; Max-Age=0; HttpOnly; SameSite=Lax";
      if (f != null && now - f[1] < FAIL_WINDOW_MS && f[0] >= MAX_FAILS) {
         redirect(ex, "/?e=rate", clear);
         return;
      }
      String cookie = cookie(ex, "sm_state");
      String state = query(ex, "state");
      String code = query(ex, "code");
      if (!query(ex, "error").isEmpty()) {
         redirect(ex, "/?e=denied", clear);
         return;
      }
      if (code.isEmpty() || cookie == null || state.isEmpty()
         || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), cookie.getBytes(StandardCharsets.UTF_8))) {
         failed(ip, now);
         redirect(ex, "/?e=state", clear);
         return;
      }
      String dId = "";
      String dName = "";
      String dAv = "";
      try {
         String form = "client_id=" + enc(this.clientId) + "&client_secret=" + enc(this.clientSecret)
            + "&grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(this.redirectUri);
         JsonObject tok = discord("/oauth2/token", null, form).getAsJsonObject();
         String at = str(tok, "access_token");
         JsonObject u = discord("/users/@me", at, null).getAsJsonObject();
         String id = str(u, "id");
         String name = str(u, "global_name");
         if (name.isBlank()) {
            name = str(u, "username");
         }
         String av = str(u, "avatar");
         dId = id;
         dName = name;
         dAv = av.isEmpty() ? "" : "https://cdn.discordapp.com/avatars/" + id + "/" + av + ".png?size=64";
         Account acc = verify(id, name, av.isEmpty() ? "" : "https://cdn.discordapp.com/avatars/" + id + "/" + av + ".png?size=64", at);

         this.fails.remove(ip);
         this.sessions.values().removeIf(s -> s.expires < now);
         byte[] raw = new byte[32];
         this.random.nextBytes(raw);
         String token = hex(raw);
         this.sessions.put(token, new Session(acc, at, now + this.sessionMs));
         this.data.recordLogin(acc.id(), acc.name(), acc.avatar(), ip, "ok", "");
         this.plugin.getLogger().info("[Web] تسجيل دخول: " + acc.name() + " (" + acc.id() + ") من " + ip);
         redirect(ex, "/", clear, COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + (this.sessionMs / 1000L)
            + (this.secureCookie ? "; Secure" : ""));
      } catch (ApiException e) {
         if (e.code != 403) {
            failed(ip, now);
         }
         if (e.code == 403 && !dId.isEmpty()) {
            this.data.recordLogin(dId, dName, dAv, ip, "denied", e.getMessage());
         }
         this.plugin.getLogger().warning("[Web] رفض دخول من " + ip + ": " + e.getMessage());
         redirect(ex, "/?e=" + (e.code == 403 ? e.getMessage() : "fail"), clear);
      } catch (Exception e) {
         this.plugin.getLogger().warning("[Web] خطأ OAuth: " + e);
         redirect(ex, "/?e=fail", clear);
      }
   }

   /** يتأكد إن المستخدم داخل السيرفر ومعه Administrator (أو الأونر) ويحدد صلاحياته. */
   private Account verify(String id, String name, String avatar, String accessToken) throws Exception {
      if (this.blockedIds.contains(id)) {
         throw new ApiException(403, "blocked");
      }
      JsonElement guilds = discord("/users/@me/guilds", accessToken, null);
      boolean admin = false;
      if (guilds.isJsonArray()) {
         for (JsonElement ge : guilds.getAsJsonArray()) {
            JsonObject g = ge.getAsJsonObject();
            if (!this.guildId.equals(str(g, "id"))) {
               continue;
            }
            JsonElement owner = g.get("owner");
            boolean isOwner = owner != null && owner.isJsonPrimitive() && owner.getAsBoolean();
            long bits = 0L;
            try {
               bits = Long.parseUnsignedLong(str(g, "permissions"));
            } catch (NumberFormatException ignored) {
            }
            admin = isOwner || (bits & ADMIN_BIT) != 0L;
         }
      }
      if (!admin) {
         throw new ApiException(403, "admin");
      }
      return new Account(id, name, avatar, this.overrides.getOrDefault(id, this.defaultPerms));
   }

   /** إعادة فحص دورية: لو انسحبت منه صلاحية الأدمن أو طلع من السيرفر تنقطع جلسته. */
   private boolean stillValid(Session s, long now) {
      if (now - s.checked < this.recheckMs) {
         return true;
      }
      try {
         Account a = s.acc;
         s.acc = verify(a.id(), a.name(), a.avatar(), s.accessToken);
         s.checked = now;
         return true;
      } catch (ApiException e) {
         return false;
      } catch (Exception e) {
         return true; // مشكلة شبكة مع ديسكورد: ما نطرد أحد بسببها
      }
   }

   private JsonElement discord(String path, String bearer, String form) throws Exception {
      HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(DISCORD_API + path))
         .timeout(Duration.ofSeconds(10))
         .header("User-Agent", "StarkModeration (https://stark-mc.xyz, 1.3)");
      if (form != null) {
         b.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form));
      } else {
         b.header("Authorization", "Bearer " + bearer).GET();
      }
      HttpResponse<String> r = this.http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      int c = r.statusCode();
      if (c == 400 || c == 401 || c == 403) {
         throw new ApiException(401, "discord rejected (" + c + ")");
      }
      if (c / 100 != 2) {
         throw new IOException("discord http " + c);
      }
      return JsonParser.parseString(r.body());
   }

   private void audit(Account acc, String action, String player, String reason) {
      JsonObject o = new JsonObject();
      o.addProperty("at", System.currentTimeMillis());
      o.addProperty("user", acc.name());
      o.addProperty("id", acc.id());
      o.addProperty("avatar", acc.avatar());
      o.addProperty("avatar", acc.avatar());
      o.addProperty("action", action);
      o.addProperty("player", player);
      o.addProperty("reason", reason);
      this.auditLog.addFirst(o);
      while (this.auditLog.size() > 200) {
         this.auditLog.pollLast();
      }
   }

   private JsonObject dashboard() throws Exception {
      JsonObject o = ok();
      o.addProperty("bans", this.data.getAllBanned().values().stream().filter(DataManager.PunishmentEntry::isActive).count());
      o.addProperty("mutes", this.data.getAllMuted().values().stream().filter(DataManager.PunishmentEntry::isActive).count());
      o.addProperty("blacklist", this.data.getBlacklistedPlayers().size());
      o.addProperty("warns", this.data.getTotalWarnCount());
      JsonObject chart = new JsonObject();
      JsonArray dayArr = new JsonArray();
      java.time.ZoneId zone = DataManager.ZONE;
      java.time.LocalDate today = java.time.LocalDate.now(zone);
      for (int i = 6; i >= 0; i--) {
         dayArr.add(today.minusDays(i).atStartOfDay(zone).toInstant().toEpochMilli());
      }
      chart.add("days", dayArr);
      for (Map.Entry<String, int[]> e : this.data.getActionsByDay(7).entrySet()) {
         JsonArray a = new JsonArray();
         for (int v : e.getValue()) {
            a.add(v);
         }
         chart.add(e.getKey(), a);
      }
      o.add("chart", chart);
      return o;
   }

   private static byte[] sha(String s) throws Exception {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
   }

   private static String cookie(HttpExchange ex, String name) {
      List<String> headers = ex.getRequestHeaders().get("Cookie");
      if (headers == null) {
         return null;
      }
      for (String h : headers) {
         for (String part : h.split(";")) {
            part = part.trim();
            if (part.startsWith(name + "=")) {
               return part.substring(name.length() + 1);
            }
         }
      }
      return null;
   }

   private String token(HttpExchange ex) {
      List<String> headers = ex.getRequestHeaders().get("Cookie");
      if (headers == null) {
         return null;
      }
      for (String h : headers) {
         for (String part : h.split(";")) {
            part = part.trim();
            if (part.startsWith(COOKIE + "=")) {
               return part.substring(COOKIE.length() + 1);
            }
         }
      }
      return null;
   }

   private String clientIp(HttpExchange ex) {
      if (this.trustProxy) {
         String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
         if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
         }
      }
      return ex.getRemoteAddress().getAddress().getHostAddress();
   }

   private JsonObject me(Account acc) {
      JsonObject o = ok();
      o.addProperty("user", acc.name());
      JsonArray perms = new JsonArray();
      acc.perms().stream().sorted().forEach(perms::add);
      o.add("perms", perms);
      return o;
   }

   // ───────────────────────── read ─────────────────────────

   private JsonObject player(String name) throws Exception {
      JsonObject o = ok();
      o.addProperty("player", name);

      DataManager.PunishmentEntry ban = this.data.isBanned(name) ? this.data.getBanEntry(name) : null;
      o.addProperty("banned", ban != null);
      o.add("ban", ban != null ? entry(name, ban) : JsonNull.INSTANCE);

      boolean bl = this.data.isBlacklisted(name);
      o.addProperty("blacklisted", bl);
      o.add(
         "blacklist",
         bl
            ? entry(name, new DataManager.PunishmentEntry(-1L, this.data.getBlacklistReason(name), this.data.getBlacklistBy(name), this.data.getBlacklistAt(name)))
            : JsonNull.INSTANCE
      );

      DataManager.PunishmentEntry mute = this.data.isMuted(name) ? this.data.getMuteEntry(name) : null;
      o.addProperty("muted", mute != null);
      o.add("mute", mute != null ? entry(name, mute) : JsonNull.INSTANCE);

      List<WarnEntry> warns = new ArrayList<>(this.data.getWarns(name));
      JsonObject w = new JsonObject();
      w.addProperty("count", warns.size());
      JsonArray wrecent = new JsonArray();
      for (int i = warns.size() - 1; i >= 0 && wrecent.size() < 20; i--) {
         WarnEntry we = warns.get(i);
         JsonObject wo = new JsonObject();
         wo.addProperty("id", we.id());
         wo.addProperty("reason", we.reason());
         wo.addProperty("by", we.by());
         wo.addProperty("at", we.at());
         wrecent.add(wo);
      }
      w.add("recent", wrecent);
      o.add("warns", w);

      List<KickEntry> kicks = new ArrayList<>(this.data.getKicks(name));
      JsonObject k = new JsonObject();
      k.addProperty("count", kicks.size());
      JsonArray recent = new JsonArray();
      for (int i = kicks.size() - 1; i >= 0 && recent.size() < 10; i--) {
         KickEntry e = kicks.get(i);
         JsonObject ko = new JsonObject();
         ko.addProperty("reason", e.reason());
         ko.addProperty("by", e.by());
         ko.addProperty("at", e.at());
         ko.addProperty("at_iso", Instant.ofEpochMilli(e.at()).toString());
         recent.add(ko);
      }
      k.add("recent", recent);
      o.add("kicks", k);
      return o;
   }

   private static final String[] TOP_TYPES = {"ban", "mute", "unban", "unmute", "warn"};

   private JsonObject tops() throws Exception {
      JsonObject o = ok();
      for (String type : TOP_TYPES) {
         JsonArray arr = new JsonArray();
         for (DataManager.TopEntry e : this.data.getTops(type, 10)) {
            JsonObject r = new JsonObject();
            r.addProperty("name", e.staff());
            r.addProperty("count", e.count());
            arr.add(r);
         }
         o.add(type, arr);
      }
      return o;
   }

   private JsonObject logs() {
      JsonObject o = ok();
      JsonArray arr = new JsonArray();
      for (DataManager.LoginEntry e : this.data.getLogins(100)) {
         JsonObject r = new JsonObject();
         r.addProperty("id", e.id());
         r.addProperty("name", e.name());
         r.addProperty("avatar", e.avatar());
         r.addProperty("ip", e.ip());
         r.addProperty("at", e.at());
         r.addProperty("status", e.status());
         r.addProperty("note", e.note());
         arr.add(r);
      }
      o.add("logins", arr);
      return o;
   }

   private JsonObject day(String raw) throws Exception {
      long start;
      try {
         start = Long.parseLong(raw == null ? "" : raw.trim());
      } catch (NumberFormatException e) {
         throw new ApiException(400, "invalid day");
      }
      java.time.LocalDate d = java.time.Instant.ofEpochMilli(start).atZone(DataManager.ZONE).toLocalDate();
      long from = d.atStartOfDay(DataManager.ZONE).toInstant().toEpochMilli();
      long to = d.plusDays(1).atStartOfDay(DataManager.ZONE).toInstant().toEpochMilli();
      JsonObject o = ok();
      for (Map.Entry<String, List<DataManager.TopEntry>> e : this.data.getDayTops(from, to, 5).entrySet()) {
         JsonArray arr = new JsonArray();
         for (DataManager.TopEntry t : e.getValue()) {
            JsonObject r = new JsonObject();
            r.addProperty("name", t.staff());
            r.addProperty("count", t.count());
            arr.add(r);
         }
         o.add(e.getKey(), arr);
      }
      return o;
   }

   private JsonObject staff(String raw) throws Exception {
      String name = DataManager.staffKey(raw == null ? "" : raw);
      if (name.isEmpty() || !name.matches("[A-Za-z0-9_.\\-]{1,36}")) {
         throw new ApiException(400, "invalid name");
      }
      JsonObject o = ok();
      o.addProperty("name", name);
      int total = 0;
      for (Map.Entry<String, Integer> e : this.data.getStaffCounts(name).entrySet()) {
         o.addProperty(e.getKey(), e.getValue());
         total += e.getValue();
      }
      o.addProperty("total", total);
      return o;
   }

   private JsonObject list() {
      JsonObject o = ok();
      JsonArray bans = new JsonArray();
      this.data
         .getAllBanned()
         .entrySet()
         .stream()
         .filter(e -> e.getValue().isActive())
         .sorted(Comparator.comparingLong((Map.Entry<String, DataManager.PunishmentEntry> e) -> e.getValue().at()).reversed())
         .limit(300)
         .forEach(e -> bans.add(entry(e.getKey(), e.getValue())));
      o.add("bans", bans);

      JsonArray bl = new JsonArray();
      for (String n : this.data.getBlacklistedPlayers()) {
         if (bl.size() >= 300) {
            break;
         }
         bl.add(entry(n, new DataManager.PunishmentEntry(-1L, this.data.getBlacklistReason(n), this.data.getBlacklistBy(n), this.data.getBlacklistAt(n))));
      }
      o.add("blacklist", bl);
      return o;
   }

   private static JsonObject entry(String player, DataManager.PunishmentEntry e) {
      long now = System.currentTimeMillis();
      boolean permanent = e.expires() == -1L;
      JsonObject o = new JsonObject();
      o.addProperty("player", player);
      o.addProperty("by", e.by());
      o.addProperty("reason", e.reason());
      o.addProperty("at", e.at());
      o.addProperty("at_iso", Instant.ofEpochMilli(e.at()).toString());
      o.addProperty("permanent", permanent);
      if (permanent) {
         o.add("expires", JsonNull.INSTANCE);
         o.add("expires_iso", JsonNull.INSTANCE);
         o.add("remaining_ms", JsonNull.INSTANCE);
      } else {
         o.addProperty("expires", e.expires());
         o.addProperty("expires_iso", Instant.ofEpochMilli(e.expires()).toString());
         o.addProperty("remaining_ms", Math.max(0L, e.expires() - now));
      }
      return o;
   }

   // ───────────────────────── actions ─────────────────────────

   private JsonObject ban(Account acc, JsonObject body) throws Exception {
      String name = playerName(str(body, "player"));
      long dur = DurationUtil.parse(str(body, "duration"));
      if (dur == -2L) {
         throw new ApiException(400, "مدة غير صالحة (مثال: 30m, 1h, 7d, permanent)");
      }
      String reason = reason(body, "ban");
      String by = by(acc);

      return sync(() -> {
         if (this.data.isBanned(name)) {
            throw new ApiException(409, "اللاعب مبند أصلاً، فك الباند أول");
         }
         Player target = Bukkit.getPlayerExact(name);
         guard(name, target);
         String real = target != null ? target.getName() : name;

         this.data.ban(real, dur, reason, by);
         this.plugin.getAlltsCommand().invalidateCache(real);
         Component screen = Screens.buildBanScreen(this.plugin.getConfig(), real, reason, by, this.data.getBanAt(real), this.data.getBanExpiry(real));
         if (target != null) {
            BanAnimation.play(this.plugin, target, acc.name(), () -> {
               if (target.isOnline()) {
                  NetworkKick.kick(this.plugin, target, screen);
               }
            });
         } else {
            NetworkKick.kickByName(this.plugin, real, screen);
         }
         broadcast("broadcasts.ban.message", real, DurationUtil.format(dur), reason);
         this.plugin.getLogger().info("[Web] " + acc.name() + " banned " + real + " (" + DurationUtil.format(dur) + "): " + reason);
         return ok();
      });
   }

   private JsonObject unban(Account acc, JsonObject body) throws Exception {
      String name = playerName(str(body, "player"));
      return sync(() -> {
         if (!this.data.isBanned(name)) {
            throw new ApiException(404, "اللاعب مو مبند");
         }
         this.data.unban(name, by(acc));
         this.plugin.getAlltsCommand().invalidateCache(name);
         this.plugin.getLogger().info("[Web] " + acc.name() + " unbanned " + name);
         return ok();
      });
   }

   private JsonObject blacklist(Account acc, JsonObject body) throws Exception {
      String name = playerName(str(body, "player"));
      String reason = reason(body, "blacklist");
      String by = by(acc);

      return sync(() -> {
         if (this.data.isBlacklisted(name)) {
            throw new ApiException(409, "اللاعب في البلاك ليست أصلاً");
         }
         Player target = Bukkit.getPlayerExact(name);
         guard(name, target);
         String real = target != null ? target.getName() : name;

         this.data.blacklist(real, reason, by);
         this.plugin.getAlltsCommand().invalidateCache(real);
         Component screen = Screens.buildBlacklistScreen(this.plugin.getConfig(), real, reason, by, this.data.getBlacklistAt(real));
         if (target != null) {
            NetworkKick.kick(this.plugin, target, screen);
         } else {
            NetworkKick.kickByName(this.plugin, real, screen);
         }
         broadcast("broadcasts.blacklist.message", real, "permanent", reason);
         this.plugin.getLogger().info("[Web] " + acc.name() + " blacklisted " + real + ": " + reason);
         return ok();
      });
   }

   private JsonObject unblacklist(Account acc, JsonObject body) throws Exception {
      String name = playerName(str(body, "player"));
      return sync(() -> {
         if (!this.data.isBlacklisted(name)) {
            throw new ApiException(404, "اللاعب مو في البلاك ليست");
         }
         this.data.unblacklist(name);
         this.plugin.getAlltsCommand().invalidateCache(name);
         this.plugin.getLogger().info("[Web] " + acc.name() + " unblacklisted " + name);
         return ok();
      });
   }

   private JsonObject kick(Account acc, JsonObject body) throws Exception {
      String name = playerName(str(body, "player"));
      String reason = reason(body, "kick");
      String by = by(acc);

      return sync(() -> {
         Player target = Bukkit.getPlayerExact(name);
         if (target == null) {
            ProxyPlayers pp = ProxyPlayers.get();
            String real = pp == null ? null : pp.find(name);
            if (real == null) {
               throw new ApiException(404, "اللاعب مو أونلاين");
            }
            guard(real, null);
            Component remote = Screens.buildKickScreen(this.plugin.getConfig(), real, reason, by);
            if (!NetworkKick.kickByName(this.plugin, real, remote)) {
               throw new ApiException(503, "تعذر إرسال الطرد للبروكسي (لازم لاعب أونلاين على سيرفر اللوحة)");
            }
            this.data.async(this.plugin, () -> this.data.kick(real, reason, by), id -> this.plugin.getAlltsCommand().invalidateCache(real));
            broadcast("broadcasts.kick.message", real, "n/a", reason);
            this.plugin.getLogger().info("[Web] " + acc.name() + " kicked " + real + " (network): " + reason);
            return ok();
         }
         guard(name, target);
         target.kick(Screens.buildKickScreen(this.plugin.getConfig(), target.getName(), reason, by));
         String kickedName = target.getName();
         this.data.async(this.plugin, () -> this.data.kick(kickedName, reason, by), id -> this.plugin.getAlltsCommand().invalidateCache(kickedName));
         broadcast("broadcasts.kick.message", target.getName(), "n/a", reason);
         this.plugin.getLogger().info("[Web] " + acc.name() + " kicked " + target.getName() + ": " + reason);
         return ok();
      });
   }

   private JsonObject mute(Account acc, JsonObject body) throws Exception {
      String name = playerName(str(body, "player"));
      long dur = DurationUtil.parse(str(body, "duration"));
      if (dur == -2L) {
         throw new ApiException(400, "مدة غير صالحة (مثال: 30m, 1h, 7d, permanent)");
      }
      String reason = reason(body, "mute");
      String by = by(acc);

      return sync(() -> {
         if (this.data.isMuted(name)) {
            throw new ApiException(409, "اللاعب مكتوم أصلاً، فك الميوت أول");
         }
         Player target = Bukkit.getPlayerExact(name);
         guard(name, target);
         String real = target != null ? target.getName() : name;

         this.data.mute(real, dur, reason, by);
         this.plugin.getAlltsCommand().invalidateCache(real);
         if (target != null) {
            target.sendMessage(Screens.buildMuteNotice(this.plugin.getConfig(), reason, this.data.getMuteExpiry(real)));
         }
         broadcast("broadcasts.mute.message", real, DurationUtil.format(dur), reason);
         this.plugin.getLogger().info("[Web] " + acc.name() + " muted " + real + " (" + DurationUtil.format(dur) + "): " + reason);
         return ok();
      });
   }

   private JsonObject unmute(Account acc, JsonObject body) throws Exception {
      String name = playerName(str(body, "player"));
      return sync(() -> {
         if (!this.data.isMuted(name)) {
            throw new ApiException(404, "اللاعب مو مكتوم");
         }
         this.data.unmute(name, by(acc));
         this.plugin.getAlltsCommand().invalidateCache(name);
         Player target = Bukkit.getPlayerExact(name);
         if (target != null) {
            target.sendMessage(MiniMessage.miniMessage().deserialize(
               this.plugin.getConfig().getString("messages.unmute-notify", "<green>You have been unmuted.")));
         }
         this.plugin.getLogger().info("[Web] " + acc.name() + " unmuted " + name);
         return ok();
      });
   }

   private JsonObject warn(Account acc, JsonObject body) throws Exception {
      String name = playerName(str(body, "player"));
      String reason = reason(body, "warn");
      String by = by(acc);

      String real = sync(() -> {
         Player target = Bukkit.getPlayerExact(name);
         guard(name, target);
         return target != null ? target.getName() : name;
      });
      // DB insert is blocking: fine here, this runs on the HTTP thread (not the main thread).
      int count = this.data.warn(real, reason, by);

      return sync(() -> {
         this.plugin.getAlltsCommand().invalidateCache(real);
         FileConfiguration cfg = this.plugin.getConfig();
         MiniMessage mm = MiniMessage.miniMessage();
         Player target = Bukkit.getPlayerExact(real);
         if (target != null) {
            String notify = cfg.getString("warn.notify",
                  "<yellow>⚠ You have been warned by <white><admin></white>. <gray>Reason: <white><reason> <gray>| Total: <white><count>")
               .replace("<admin>", acc.name())
               .replace("<reason>", reason)
               .replace("<count>", String.valueOf(count));
            target.sendMessage(mm.deserialize(notify));
         }
         if (cfg.getBoolean("broadcasts.warn.enabled", true)) {
            String raw = cfg.getString("broadcasts.warn.message", "");
            if (!raw.isBlank()) {
               Component c = mm.deserialize(raw.replace("<player>", real).replace("<duration>", "").replace("<reason>", reason).replace("<count>", String.valueOf(count)));
               Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(c));
            }
         }
         this.plugin.getLogger().info("[Web] " + acc.name() + " warned " + real + " (" + count + " total): " + reason);
         return ok();
      });
   }

   private JsonObject unwarn(Account acc, JsonObject body) throws Exception {
      String name = playerName(str(body, "player"));
      int id;
      try {
         id = Integer.parseInt(str(body, "id").trim());
      } catch (NumberFormatException e) {
         throw new ApiException(400, "رقم الإنذار غير صالح");
      }
      if (!this.data.removeWarn(name, id)) {
         throw new ApiException(404, "ما لقيت هذا الإنذار");
      }
      int removedId = id;
      return sync(() -> {
         this.plugin.getAlltsCommand().invalidateCache(name);
         Player target = Bukkit.getPlayerExact(name);
         if (target != null) {
            target.sendMessage(MiniMessage.miniMessage().deserialize(
               this.plugin.getConfig().getString("messages.unwarn-notify", "<green>⚠ Warning #<id> has been removed from your record.")
                  .replace("<id>", String.valueOf(removedId))));
         }
         this.plugin.getLogger().info("[Web] " + acc.name() + " removed warning #" + removedId + " from " + name);
         return ok();
      });
   }

   // ───────────────────────── control (bulk) ─────────────────────────

   private JsonObject controlInfo() throws Exception {
      JsonObject o = ok();
      o.addProperty("bans", this.data.getActiveBanCount());
      o.addProperty("mutes", this.data.getActiveMuteCount());
      o.addProperty("freezes", this.data.getFrozenCount());
      o.addProperty("warns", this.data.getTotalWarnCount());
      o.addProperty("blacklist", this.data.getBlacklistCount());
      return o;
   }

   private JsonObject controlRun(Account acc, String kind, JsonObject body) throws Exception {
      if (!"yes".equals(str(body, "confirm"))) {
         throw new ApiException(400, "لازم تأكيد العملية");
      }
      String who = acc.name();
      int count;
      switch (kind) {
         case "unbanall" -> count = sync(() -> {
            int n = this.data.unbanAll();
            this.plugin.getAlltsCommand().invalidateAll();
            return n;
         });
         case "unmuteall" -> count = sync(() -> {
            List<String> online = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
               if (this.data.isMuted(p.getName())) {
                  online.add(p.getName());
               }
            }
            int n = this.data.unmuteAll();
            this.plugin.getAlltsCommand().invalidateAll();
            String msg = this.plugin.getConfig().getString("messages.unmute-notify", "<green>You have been unmuted.");
            for (String nm : online) {
               Player p = Bukkit.getPlayerExact(nm);
               if (p != null) {
                  p.sendMessage(MiniMessage.miniMessage().deserialize(msg));
               }
            }
            return n;
         });
         case "unfreezeall" -> count = sync(() -> {
            List<String> online = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
               if (this.data.isFrozen(p.getName())) {
                  online.add(p.getName());
               }
            }
            int n = this.data.unfreezeAll();
            String msg = this.plugin.getConfig().getString("messages.unfreeze-notify", "<green>You have been unfrozen.");
            for (String nm : online) {
               Player p = Bukkit.getPlayerExact(nm);
               if (p != null) {
                  p.sendMessage(MiniMessage.miniMessage().deserialize(msg));
               }
            }
            return n;
         });
         case "unblacklistall" -> count = sync(() -> {
            int n = this.data.unblacklistAll();
            this.plugin.getAlltsCommand().invalidateAll();
            return n;
         });
         case "clearwarns" -> {
            // DB delete is blocking: fine here, this runs on the HTTP thread (not the main thread).
            count = this.data.clearAllWarns();
            sync(() -> {
               this.plugin.getAlltsCommand().invalidateAll();
               return null;
            });
         }
         default -> throw new ApiException(404, "not found");
      }
      this.plugin.getLogger().warning("[Web] " + who + " ran control/" + kind + " (" + count + " affected)");
      JsonObject o = ok();
      o.addProperty("count", count);
      return o;
   }

   private void guard(String name, Player target) throws ApiException {
      if (this.protectedNames.contains(name.toLowerCase())
         || target != null && target.hasPermission("starkmod.rank-guard.bypass")) {
         throw new ApiException(403, "هذا اللاعب محمي ما تقدر تعاقبه من الموقع");
      }
   }

   private void broadcast(String cfgKey, String player, String duration, String reason) {
      FileConfiguration cfg = this.plugin.getConfig();
      if (!cfg.getBoolean(cfgKey.replace(".message", ".enabled"), true)) {
         return;
      }
      String raw = cfg.getString(cfgKey, "").replace("<player>", player).replace("<duration>", duration).replace("<reason>", reason);
      if (!raw.isBlank()) {
         Component c = MiniMessage.miniMessage().deserialize(raw);
         Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(c));
      }
   }

   /** ينفذ على الثريد الرئيسي (الكيك والأنيميشن لازم يكونون من الثريد الرئيسي). */
   private <T> T sync(Callable<T> task) throws Exception {
      try {
         return Bukkit.getScheduler().callSyncMethod(this.plugin, task).get(10L, TimeUnit.SECONDS);
      } catch (ExecutionException e) {
         if (e.getCause() instanceof Exception cause) {
            throw cause;
         }
         throw e;
      }
   }

   // ───────────────────────── helpers ─────────────────────────

   private static String by(Account acc) {
      String n = acc.name();
      if (n.length() > 25) {
         n = n.substring(0, 25);
      }
      return n + "@web";
   }

   private String reason(JsonObject body, String type) {
      String r = str(body, "reason").replace("<", "").replace(">", "").replace("\n", " ").trim();
      if (r.isEmpty()) {
         return this.plugin.getConfig().getString("default-reasons." + type, "ɴᴏ ʀᴇᴀꜱᴏɴ");
      }
      return r.length() > 200 ? r.substring(0, 200) : r;
   }

   private static String playerName(String raw) throws ApiException {
      if (raw == null || !NAME.matcher(raw.trim()).matches()) {
         throw new ApiException(400, "اسم لاعب غير صالح");
      }
      return raw.trim();
   }

   private static String str(JsonObject o, String key) {
      JsonElement e = o.get(key);
      if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
         return "";
      }
      return e.getAsString();
   }

   private static String query(HttpExchange ex, String key) {
      String q = ex.getRequestURI().getRawQuery();
      if (q == null) {
         return "";
      }
      for (String part : q.split("&")) {
         if (part.startsWith(key + "=")) {
            return URLDecoder.decode(part.substring(key.length() + 1), StandardCharsets.UTF_8);
         }
      }
      return "";
   }

   private static JsonObject readBody(HttpExchange ex) throws ApiException, IOException {
      String body = new String(ex.getRequestBody().readNBytes(4096), StandardCharsets.UTF_8);
      if (body.isBlank()) {
         return new JsonObject();
      }
      try {
         JsonElement e = JsonParser.parseString(body);
         if (e.isJsonObject()) {
            return e.getAsJsonObject();
         }
      } catch (Exception ignored) {
      }
      throw new ApiException(400, "bad request");
   }

   private static JsonObject ok() {
      JsonObject o = new JsonObject();
      o.addProperty("ok", true);
      return o;
   }

   private static JsonObject error(String msg) {
      JsonObject o = new JsonObject();
      o.addProperty("ok", false);
      o.addProperty("error", msg);
      return o;
   }

   private void sendHtml(HttpExchange ex) throws IOException {
      ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
      ex.getResponseHeaders().set("Cache-Control", "no-store");
      ex.getResponseHeaders().set("X-Frame-Options", "DENY");
      ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
      ex.getResponseHeaders().set(
         "Content-Security-Policy",
         "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' https://minotar.net https://cdn.discordapp.com data:; base-uri 'none'; form-action 'none'"
      );
      ex.sendResponseHeaders(200, this.indexHtml.length);
      try (OutputStream os = ex.getResponseBody()) {
         os.write(this.indexHtml);
      }
   }

   private static void sendJson(HttpExchange ex, int code, JsonObject json) throws IOException {
      byte[] out = json.toString().getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      ex.getResponseHeaders().set("Cache-Control", "no-store");
      ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
      ex.sendResponseHeaders(code, out.length);
      try (OutputStream os = ex.getResponseBody()) {
         os.write(out);
      }
   }
}
