package com.starkmc.moderation.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

public class DataManager {
   private final HikariDataSource pool;
   private volatile Map<String, DataManager.PunishmentEntry> banCache = new ConcurrentHashMap<>();
   private volatile Map<String, DataManager.PunishmentEntry> muteCache = new ConcurrentHashMap<>();
   private volatile Map<String, DataManager.PunishmentEntry> freezeCache = new ConcurrentHashMap<>();
   private volatile Map<String, DataManager.PunishmentEntry> blacklistCache = new ConcurrentHashMap<>();
   /** Bumped on every local write (and when its DB write finishes) so a stale cache reload never overwrites it. */
   private final AtomicLong localMods = new AtomicLong();
   private final AtomicInteger frozenCount = new AtomicInteger();
   private final AtomicInteger mutedCount = new AtomicInteger();
   private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
      Thread t = new Thread(r, "StarkMod-DB");
      t.setDaemon(true);
      return t;
   });

   public boolean anyFrozen() {
      return this.frozenCount.get() > 0;
   }

   public boolean anyMuted() {
      return this.mutedCount.get() > 0;
   }

   public DataManager(FileConfiguration cfg) {
      String host = cfg.getString("mysql.host", "localhost");
      int port = cfg.getInt("mysql.port", 3306);
      String db = cfg.getString("mysql.database", "starkmod");
      String user = cfg.getString("mysql.username", "root");
      String pass = cfg.getString("mysql.password", "");
      HikariConfig hc = new HikariConfig();
      hc.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db + "?useSSL=false&autoReconnect=true&characterEncoding=utf8");
      hc.setUsername(user);
      hc.setPassword(pass);
      hc.setMaximumPoolSize(10);
      hc.setMinimumIdle(2);
      hc.setConnectionTimeout(10000L);
      hc.setIdleTimeout(600000L);
      hc.setMaxLifetime(1800000L);
      hc.setPoolName("StarkMod-Pool");
      this.pool = new HikariDataSource(hc);
      this.createTables();
      this.loadCache();
      this.scheduler.scheduleAtFixedRate(this::loadCache, 5L, 5L, TimeUnit.SECONDS);
   }

   private void createTables() {
      try {
         try (
            Connection c = this.pool.getConnection();
            Statement s = c.createStatement();
         ) {
            s.execute(
               "    CREATE TABLE IF NOT EXISTS sm_punishments (\n        type    VARCHAR(10)  NOT NULL,\n        player  VARCHAR(36)  NOT NULL,\n        expires BIGINT       NOT NULL DEFAULT -1,\n        reason  VARCHAR(512) NOT NULL DEFAULT 'no reason',\n        by_who  VARCHAR(36)  NOT NULL DEFAULT 'Console',\n        at_ms   BIGINT       NOT NULL,\n        PRIMARY KEY (type, player)\n    ) CHARACTER SET utf8mb4\n"
            );
            s.execute(
               "    CREATE TABLE IF NOT EXISTS sm_warns (\n        id      INT          NOT NULL AUTO_INCREMENT,\n        player  VARCHAR(36)  NOT NULL,\n        reason  VARCHAR(512) NOT NULL DEFAULT 'no reason',\n        by_who  VARCHAR(36)  NOT NULL DEFAULT 'Console',\n        at_ms   BIGINT       NOT NULL,\n        PRIMARY KEY (id),\n        INDEX idx_warn_player (player)\n    ) CHARACTER SET utf8mb4\n"
            );
            s.execute(
               "    CREATE TABLE IF NOT EXISTS sm_kicks (\n        id      INT          NOT NULL AUTO_INCREMENT,\n        player  VARCHAR(36)  NOT NULL,\n        reason  VARCHAR(512) NOT NULL DEFAULT 'no reason',\n        by_who  VARCHAR(36)  NOT NULL DEFAULT 'Console',\n        at_ms   BIGINT       NOT NULL,\n        PRIMARY KEY (id),\n        INDEX idx_kick_player (player)\n    ) CHARACTER SET utf8mb4\n"
            );
            s.execute(
               "CREATE TABLE IF NOT EXISTS sm_actions (\n id BIGINT NOT NULL AUTO_INCREMENT,\n type VARCHAR(10) NOT NULL,\n staff VARCHAR(36) NOT NULL,\n target VARCHAR(36) NOT NULL,\n at_ms BIGINT NOT NULL,\n PRIMARY KEY (id),\n INDEX idx_act_type_staff (type, staff)\n ) CHARACTER SET utf8mb4"
            );
            s.execute(
               "CREATE TABLE IF NOT EXISTS sm_logins (\n id BIGINT NOT NULL AUTO_INCREMENT,\n discord_id VARCHAR(32) NOT NULL,\n name VARCHAR(64) NOT NULL,\n avatar VARCHAR(255) NOT NULL DEFAULT '',\n ip VARCHAR(64) NOT NULL DEFAULT '',\n at_ms BIGINT NOT NULL,\n status VARCHAR(16) NOT NULL DEFAULT 'ok',\n note VARCHAR(64) NOT NULL DEFAULT '',\n PRIMARY KEY (id),\n INDEX idx_login_at (at_ms)\n ) CHARACTER SET utf8mb4"
            );
            // upgrade tables created before the status/note columns existed (fails harmlessly if already there)
            for (String alter : new String[] {
               "ALTER TABLE sm_logins ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ok'",
               "ALTER TABLE sm_logins ADD COLUMN note VARCHAR(64) NOT NULL DEFAULT ''"
            }) {
               try {
                  s.execute(alter);
               } catch (SQLException ignored) {
                  // column already exists
               }
            }
            // one-time seed from punishments that are still active (history was never stored before)
            try (java.sql.ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM sm_actions")) {
               if (rs.next() && rs.getInt(1) == 0) {
                  s.executeUpdate(
                     "INSERT INTO sm_actions (type, staff, target, at_ms) "
                        + "SELECT type, REPLACE(by_who, '@web', ''), player, at_ms FROM sm_punishments "
                        + "WHERE type IN ('ban','mute') AND by_who NOT IN ('Console','API','')"
                  );
               }
            }
            // one-time seed of warn counts from existing warnings
            try (java.sql.ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM sm_actions WHERE type='warn'")) {
               if (rs.next() && rs.getInt(1) == 0) {
                  s.executeUpdate(
                     "INSERT INTO sm_actions (type, staff, target, at_ms) "
                        + "SELECT 'warn', REPLACE(by_who, '@web', ''), player, at_ms FROM sm_warns "
                        + "WHERE by_who NOT IN ('Console','API','')"
                  );
               }
            }
         }
      } catch (SQLException var9) {
         throw new RuntimeException("StarkMod DB init failed", var9);
      }
   }

   public void load() {
      this.scheduler.execute(this::loadCache);
   }

   private void loadCache() {
      final long modsAtStart = this.localMods.get();
      try (
         Connection c = this.pool.getConnection();
         PreparedStatement ps = c.prepareStatement("SELECT type, player, expires, reason, by_who, at_ms FROM sm_punishments");
      ) {
         ResultSet rs = ps.executeQuery();
         Map<String, DataManager.PunishmentEntry> newBans = new ConcurrentHashMap<>();
         Map<String, DataManager.PunishmentEntry> newMutes = new ConcurrentHashMap<>();
         Map<String, DataManager.PunishmentEntry> newFreezes = new ConcurrentHashMap<>();
         Map<String, DataManager.PunishmentEntry> newBlacklist = new ConcurrentHashMap<>();
         long now = System.currentTimeMillis();

         while (rs.next()) {
            String type = rs.getString(1);
            String player = rs.getString(2);
            long exp = rs.getLong(3);
            if ("blacklist".equals(type)) {
               newBlacklist.put(player, new DataManager.PunishmentEntry(-1L, rs.getString(4), rs.getString(5), rs.getLong(6)));
            } else if (exp == -1L || exp > now) {
               DataManager.PunishmentEntry e = new DataManager.PunishmentEntry(exp, rs.getString(4), rs.getString(5), rs.getLong(6));
               switch (type) {
                  case "ban":
                     newBans.put(player, e);
                     break;
                  case "mute":
                     newMutes.put(player, e);
                     break;
                  case "freeze":
                     newFreezes.put(player, e);
               }
            }
         }

         // A local write happened while we were reading: this snapshot may be stale, try again next cycle.
         if (this.localMods.get() != modsAtStart) {
            return;
         }

         // Swap whole maps atomically; never clear() live caches (that briefly un-bans everyone).
         this.banCache = newBans;
         this.muteCache = newMutes;
         this.blacklistCache = newBlacklist;
         this.freezeCache = newFreezes;
         this.frozenCount.set(newFreezes.size());
         this.mutedCount.set(newMutes.size());
      } catch (SQLException var20) {
         var20.printStackTrace();
      }
   }

   public void shutdown() {
      // Let queued ban/mute/unban writes finish instead of dropping them on restart.
      this.scheduler.shutdown();
      try {
         if (!this.scheduler.awaitTermination(5L, TimeUnit.SECONDS)) {
            this.scheduler.shutdownNow();
         }
      } catch (InterruptedException e) {
         this.scheduler.shutdownNow();
         Thread.currentThread().interrupt();
      }
      if (this.pool != null && !this.pool.isClosed()) {
         this.pool.close();
      }
   }

   private void upsertAsync(String type, String player, long expires, String reason, String by) {
      this.localMods.incrementAndGet();
      this.scheduler
         .execute(
            () -> {
               try (
                  Connection c = this.pool.getConnection();
                  PreparedStatement ps = c.prepareStatement(
                     "   INSERT INTO sm_punishments (type, player, expires, reason, by_who, at_ms)\n   VALUES (?, ?, ?, ?, ?, ?)\n   ON DUPLICATE KEY UPDATE expires=VALUES(expires), reason=VALUES(reason),\n                           by_who=VALUES(by_who), at_ms=VALUES(at_ms)\n"
                  );
               ) {
                  ps.setString(1, type);
                  ps.setString(2, player.toLowerCase());
                  ps.setLong(3, expires);
                  ps.setString(4, reason);
                  ps.setString(5, by);
                  ps.setLong(6, System.currentTimeMillis());
                  ps.executeUpdate();
               } catch (SQLException var15) {
                  var15.printStackTrace();
               } finally {
                  this.localMods.incrementAndGet();
               }
            }
         );
   }

   private void deleteAsync(String type, String player) {
      this.localMods.incrementAndGet();
      this.scheduler.execute(() -> {
         try (
            Connection c = this.pool.getConnection();
            PreparedStatement ps = c.prepareStatement("DELETE FROM sm_punishments WHERE type=? AND player=?");
         ) {
            ps.setString(1, type);
            ps.setString(2, player.toLowerCase());
            ps.executeUpdate();
         } catch (SQLException var11) {
            var11.printStackTrace();
         } finally {
            this.localMods.incrementAndGet();
         }
      });
   }

   public boolean isBanned(String name) {
      DataManager.PunishmentEntry e = this.banCache.get(name.toLowerCase());
      if (e == null) {
         return false;
      } else if (e.isActive()) {
         return true;
      } else {
         this.banCache.remove(name.toLowerCase());
         this.deleteAsync("ban", name);
         return false;
      }
   }

   public void ban(String name, long durationMs, String reason, String by) {
      String key = name.toLowerCase();
      long now = System.currentTimeMillis();
      long exp = durationMs == -1L ? -1L : now + durationMs;
      this.banCache.put(key, new DataManager.PunishmentEntry(exp, reason, by, now));
      this.upsertAsync("ban", name, exp, reason, by);
      this.recordAction("ban", by, name);
   }

   public void unban(String name, String by) {
      this.unban(name);
      this.recordAction("unban", by, name);
   }

   public void unban(String name) {
      this.banCache.remove(name.toLowerCase());
      this.deleteAsync("ban", name);
   }

   // ---- Blacklist: permanent, separate from bans (never touched by unban / unbanall) ----
   public boolean isBlacklisted(String name) {
      return this.blacklistCache.containsKey(name.toLowerCase());
   }

   public void blacklist(String name, String reason, String by) {
      long now = System.currentTimeMillis();
      this.blacklistCache.put(name.toLowerCase(), new DataManager.PunishmentEntry(-1L, reason, by, now));
      this.upsertAsync("blacklist", name, -1L, reason, by);
   }

   public void unblacklist(String name) {
      this.blacklistCache.remove(name.toLowerCase());
      this.deleteAsync("blacklist", name);
   }

   public String getBlacklistReason(String n) {
      DataManager.PunishmentEntry e = this.blacklistCache.get(n.toLowerCase());
      return e != null ? e.reason() : "ɴᴏ ʀᴇᴀꜱᴏɴ";
   }

   public String getBlacklistBy(String n) {
      DataManager.PunishmentEntry e = this.blacklistCache.get(n.toLowerCase());
      return e != null ? e.by() : "Console";
   }

   public long getBlacklistAt(String n) {
      DataManager.PunishmentEntry e = this.blacklistCache.get(n.toLowerCase());
      return e != null ? e.at() : System.currentTimeMillis();
   }

   public Set<String> getBlacklistedPlayers() {
      return new java.util.TreeSet<>(this.blacklistCache.keySet());
   }

   public int getActiveBanCount() {
      long now = System.currentTimeMillis();
      return (int)this.banCache.values().stream().filter(e -> e.expires() == -1L || e.expires() > now).count();
   }

   /** Removes every active ban. Returns how many bans were active. */
   public int unbanAll() {
      int count = this.getActiveBanCount();
      this.localMods.incrementAndGet();
      this.banCache.clear();
      this.scheduler.execute(() -> {
         try (
            Connection c = this.pool.getConnection();
            PreparedStatement ps = c.prepareStatement("DELETE FROM sm_punishments WHERE type='ban'");
         ) {
            ps.executeUpdate();
         } catch (SQLException var11) {
            var11.printStackTrace();
         } finally {
            this.banCache.clear();
            this.localMods.incrementAndGet();
         }
      });
      return count;
   }

   public int getActiveMuteCount() {
      long now = System.currentTimeMillis();
      return (int)this.muteCache.values().stream().filter(e -> e.expires() == -1L || e.expires() > now).count();
   }

   public int getBlacklistCount() {
      return this.blacklistCache.size();
   }

   private void deleteAllOfType(String type, Map<String, DataManager.PunishmentEntry> cache) {
      this.localMods.incrementAndGet();
      this.scheduler.execute(() -> {
         try (
            Connection c = this.pool.getConnection();
            PreparedStatement ps = c.prepareStatement("DELETE FROM sm_punishments WHERE type=?");
         ) {
            ps.setString(1, type);
            ps.executeUpdate();
         } catch (SQLException e) {
            e.printStackTrace();
         } finally {
            cache.clear();
            this.localMods.incrementAndGet();
         }
      });
   }

   public int getFrozenCount() {
      return this.getFrozenPlayers().size();
   }

   /** Removes every freeze (active or expired). Returns how many were active. */
   public int unfreezeAll() {
      int count = this.getFrozenCount();
      this.freezeCache.clear();
      this.frozenCount.set(0);
      this.deleteAllOfType("freeze", this.freezeCache);
      return count;
   }

   /** Removes every mute (active or expired). Returns how many were active. */
   public int unmuteAll() {
      int count = this.getActiveMuteCount();
      this.muteCache.clear();
      this.mutedCount.set(0);
      this.deleteAllOfType("mute", this.muteCache);
      return count;
   }

   /** Removes every blacklist entry. Returns how many there were. */
   public int unblacklistAll() {
      int count = this.getBlacklistCount();
      this.blacklistCache.clear();
      this.deleteAllOfType("blacklist", this.blacklistCache);
      return count;
   }

   /** Deletes every warning of every player (blocking: call off the main thread). Returns how many were deleted. */
   public int clearAllWarns() {
      try (
         Connection c = this.pool.getConnection();
         PreparedStatement ps = c.prepareStatement("DELETE FROM sm_warns");
      ) {
         return ps.executeUpdate();
      } catch (SQLException e) {
         e.printStackTrace();
         return 0;
      }
   }

   public long getBanExpiry(String n) {
      DataManager.PunishmentEntry e = this.banCache.get(n.toLowerCase());
      return e != null ? e.expires() : 0L;
   }

   public String getBanReason(String n) {
      DataManager.PunishmentEntry e = this.banCache.get(n.toLowerCase());
      return e != null ? e.reason() : "ɴᴏ ʀᴇᴀꜱᴏɴ";
   }

   public String getBanBy(String n) {
      DataManager.PunishmentEntry e = this.banCache.get(n.toLowerCase());
      return e != null ? e.by() : "Console";
   }

   public long getBanAt(String n) {
      DataManager.PunishmentEntry e = this.banCache.get(n.toLowerCase());
      return e != null ? e.at() : 0L;
   }

   public DataManager.PunishmentEntry getBanEntry(String n) {
      return this.banCache.get(n.toLowerCase());
   }

   public boolean isMuted(String name) {
      DataManager.PunishmentEntry e = this.muteCache.get(name.toLowerCase());
      if (e == null) {
         return false;
      } else if (e.isActive()) {
         return true;
      } else {
         this.muteCache.remove(name.toLowerCase());
         this.mutedCount.decrementAndGet();
         this.deleteAsync("mute", name);
         return false;
      }
   }

   public void mute(String name, long durationMs, String reason, String by) {
      String key = name.toLowerCase();
      boolean hadMute = this.muteCache.containsKey(key);
      long now = System.currentTimeMillis();
      long exp = durationMs == -1L ? -1L : now + durationMs;
      this.muteCache.put(key, new DataManager.PunishmentEntry(exp, reason, by, now));
      if (!hadMute) {
         this.mutedCount.incrementAndGet();
      }

      this.upsertAsync("mute", name, exp, reason, by);
      this.recordAction("mute", by, name);
   }

   public void unmute(String name, String by) {
      this.unmute(name);
      this.recordAction("unmute", by, name);
   }

   // ---- Staff action history (feeds the web "Tops" page) ----
   public static String staffKey(String by) {
      if (by == null) {
         return "";
      }
      String n = by.trim();
      if (n.endsWith("@web")) {
         n = n.substring(0, n.length() - 4);
      }
      return n.length() > 36 ? n.substring(0, 36) : n;
   }

   private static boolean countsAsStaff(String staff) {
      return !staff.isEmpty() && !staff.equalsIgnoreCase("Console") && !staff.equalsIgnoreCase("API");
   }

   public void recordAction(String type, String by, String target) {
      String staff = staffKey(by);
      if (!countsAsStaff(staff)) {
         return;
      }
      this.scheduler.execute(() -> {
         try (
            Connection c = this.pool.getConnection();
            PreparedStatement ps = c.prepareStatement("INSERT INTO sm_actions (type, staff, target, at_ms) VALUES (?, ?, ?, ?)");
         ) {
            ps.setString(1, type);
            ps.setString(2, staff);
            ps.setString(3, target.toLowerCase());
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
         } catch (SQLException e) {
            e.printStackTrace();
         }
      });
   }

   public record TopEntry(String staff, int count) {
   }

   /** Top staff for one action type, case-insensitive merge, highest first. */
   public List<TopEntry> getTops(String type, int limit) {
      List<TopEntry> out = new java.util.ArrayList<>();
      try (
         Connection c = this.pool.getConnection();
         PreparedStatement ps = c.prepareStatement(
            "SELECT MIN(staff) AS s, COUNT(*) AS n FROM sm_actions WHERE type=? GROUP BY LOWER(staff) ORDER BY n DESC, s ASC LIMIT ?"
         );
      ) {
         ps.setString(1, type);
         ps.setInt(2, limit);
         try (java.sql.ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
               out.add(new TopEntry(rs.getString(1), rs.getInt(2)));
            }
         }
      } catch (SQLException e) {
         e.printStackTrace();
      }
      return out;
   }

   /** Per-type action counts for one staff member. */
   public Map<String, Integer> getStaffCounts(String staff) {
      Map<String, Integer> out = new java.util.LinkedHashMap<>();
      for (String t : new String[] {"ban", "mute", "unban", "unmute", "warn"}) {
         out.put(t, 0);
      }
      try (
         Connection c = this.pool.getConnection();
         PreparedStatement ps = c.prepareStatement("SELECT type, COUNT(*) FROM sm_actions WHERE LOWER(staff)=LOWER(?) GROUP BY type");
      ) {
         ps.setString(1, staffKey(staff));
         try (java.sql.ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
               out.put(rs.getString(1), rs.getInt(2));
            }
         }
      } catch (SQLException e) {
         e.printStackTrace();
      }
      return out;
   }

   public void unmute(String name) {
      if (this.muteCache.remove(name.toLowerCase()) != null) {
         this.mutedCount.decrementAndGet();
      }

      this.deleteAsync("mute", name);
   }

   public long getMuteExpiry(String n) {
      DataManager.PunishmentEntry e = this.muteCache.get(n.toLowerCase());
      return e != null ? e.expires() : 0L;
   }

   public String getMuteReason(String n) {
      DataManager.PunishmentEntry e = this.muteCache.get(n.toLowerCase());
      return e != null ? e.reason() : "ɴᴏ ʀᴇᴀꜱᴏɴ";
   }

   public String getMuteBy(String n) {
      DataManager.PunishmentEntry e = this.muteCache.get(n.toLowerCase());
      return e != null ? e.by() : "Console";
   }

   public DataManager.PunishmentEntry getMuteEntry(String n) {
      return this.muteCache.get(n.toLowerCase());
   }

   public boolean isFrozen(String name) {
      DataManager.PunishmentEntry e = this.freezeCache.get(name.toLowerCase());
      if (e == null) {
         return false;
      } else if (e.isActive()) {
         return true;
      } else {
         this.freezeCache.remove(name.toLowerCase());
         this.frozenCount.decrementAndGet();
         this.deleteAsync("freeze", name);
         return false;
      }
   }

   public void freeze(String name, long durationMs, String by) {
      String key = name.toLowerCase();
      boolean hadFreeze = this.freezeCache.containsKey(key);
      long now = System.currentTimeMillis();
      long exp = durationMs == -1L ? -1L : now + durationMs;
      this.freezeCache.put(key, new DataManager.PunishmentEntry(exp, "N/A", by, now));
      if (!hadFreeze) {
         this.frozenCount.incrementAndGet();
      }

      this.upsertAsync("freeze", name, exp, "N/A", by);
   }

   public void unfreeze(String name) {
      if (this.freezeCache.remove(name.toLowerCase()) != null) {
         this.frozenCount.decrementAndGet();
      }

      this.deleteAsync("freeze", name);
   }

   public long getFreezeExpiry(String n) {
      DataManager.PunishmentEntry e = this.freezeCache.get(n.toLowerCase());
      return e != null ? e.expires() : -1L;
   }

   public String getFreezeBy(String n) {
      DataManager.PunishmentEntry e = this.freezeCache.get(n.toLowerCase());
      return e != null ? e.by() : "Console";
   }

   public Set<String> getFrozenPlayers() {
      long now = System.currentTimeMillis();
      Set<String> set = new HashSet<>();
      this.freezeCache.forEach((k, v) -> {
         if (v.expires() == -1L || v.expires() > now) {
            set.add(k);
         }
      });
      return Collections.unmodifiableSet(set);
   }

   /**
    * Runs {@code work} (DB access) on the DB thread, then hands the result to {@code done} on the MAIN thread.
    * If the work throws, {@code done} receives null (callers must handle it).
    */
   public <T> void async(Plugin plugin, Supplier<T> work, Consumer<T> done) {
      try {
         this.scheduler.execute(() -> {
            T result = null;
            try {
               result = work.get();
            } catch (Throwable t) {
               plugin.getLogger().warning("DB task failed: " + t);
            }
            final T r = result;
            if (plugin.isEnabled()) {
               Bukkit.getScheduler().runTask(plugin, () -> done.accept(r));
            }
         });
      } catch (java.util.concurrent.RejectedExecutionException ignored) {
         // shutting down
      }
   }

   /** Blocking: call only from {@link #async}. */
   public int warn(String name, String reason, String by) {
      ((Runnable) () -> {
         try (
            Connection c = this.pool.getConnection();
            PreparedStatement ps = c.prepareStatement("INSERT INTO sm_warns (player, reason, by_who, at_ms) VALUES (?, ?, ?, ?)");
         ) {
            ps.setString(1, name.toLowerCase());
            ps.setString(2, reason);
            ps.setString(3, by);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
         } catch (SQLException var12) {
            var12.printStackTrace();
         }
      }).run();
      this.recordAction("warn", by, name);
      return this.getWarnCount(name) + 1;
   }

   public boolean removeWarn(String name, int id) {
      try {
         boolean var5;
         try (
            Connection c = this.pool.getConnection();
            PreparedStatement ps = c.prepareStatement("DELETE FROM sm_warns WHERE id=? AND player=?");
         ) {
            ps.setInt(1, id);
            ps.setString(2, name.toLowerCase());
            var5 = ps.executeUpdate() > 0;
         }

         return var5;
      } catch (SQLException var11) {
         var11.printStackTrace();
         return false;
      }
   }

   public int clearWarns(String name) {
      int count = this.getWarnCount(name);
      ((Runnable) () -> {
         try (
            Connection c = this.pool.getConnection();
            PreparedStatement ps = c.prepareStatement("DELETE FROM sm_warns WHERE player=?");
         ) {
            ps.setString(1, name.toLowerCase());
            ps.executeUpdate();
         } catch (SQLException var10) {
            var10.printStackTrace();
         }
      }).run();
      return count;
   }

   public List<WarnEntry> getWarns(String name) {
      List<WarnEntry> list = new ArrayList<>();

      try (
         Connection c = this.pool.getConnection();
         PreparedStatement ps = c.prepareStatement("SELECT id, reason, by_who, at_ms FROM sm_warns WHERE player=? ORDER BY id ASC");
      ) {
         ps.setString(1, name.toLowerCase());
         ResultSet rs = ps.executeQuery();

         while (rs.next()) {
            list.add(new WarnEntry(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
         }
      } catch (SQLException var11) {
         var11.printStackTrace();
      }

      return Collections.unmodifiableList(list);
   }

   /** Total warnings across all players (blocking: call off the main thread). */
   public int getTotalWarnCount() {
      try (
         Connection c = this.pool.getConnection();
         PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM sm_warns");
         ResultSet rs = ps.executeQuery();
      ) {
         return rs.next() ? rs.getInt(1) : 0;
      } catch (SQLException e) {
         e.printStackTrace();
         return 0;
      }
   }

   // ---- Web panel login history (feeds the "Logs" page) ----
   public record LoginEntry(String id, String name, String avatar, String ip, long at, String status, String note) {
   }

   public void recordLogin(String discordId, String name, String avatar, String ip, String status, String note) {
      this.scheduler.execute(() -> {
         try (
            Connection c = this.pool.getConnection();
            PreparedStatement ps = c.prepareStatement("INSERT INTO sm_logins (discord_id, name, avatar, ip, at_ms, status, note) VALUES (?, ?, ?, ?, ?, ?, ?)");
         ) {
            ps.setString(1, discordId.length() > 32 ? discordId.substring(0, 32) : discordId);
            ps.setString(2, name.length() > 64 ? name.substring(0, 64) : name);
            ps.setString(3, avatar.length() > 255 ? "" : avatar);
            ps.setString(4, ip.length() > 64 ? ip.substring(0, 64) : ip);
            ps.setLong(5, System.currentTimeMillis());
            ps.setString(6, status);
            ps.setString(7, note.length() > 64 ? note.substring(0, 64) : note);
            ps.executeUpdate();
         } catch (SQLException e) {
            e.printStackTrace();
         }
      });
   }

   /** Newest first. Blocking. */
   public List<LoginEntry> getLogins(int limit) {
      List<LoginEntry> out = new java.util.ArrayList<>();
      try (
         Connection c = this.pool.getConnection();
         PreparedStatement ps = c.prepareStatement("SELECT discord_id, name, avatar, ip, at_ms, status, note FROM sm_logins ORDER BY id DESC LIMIT ?");
      ) {
         ps.setInt(1, limit);
         try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
               out.add(new LoginEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7)));
            }
         }
      } catch (SQLException e) {
         e.printStackTrace();
      }
      return out;
   }

   /** All dashboard dates use Saudi Arabia time (UTC+3, no DST). */
   public static final java.time.ZoneId ZONE = java.time.ZoneId.of("Asia/Riyadh");

   /** Top staff per type (ban/mute/warn) inside [from, to) in epoch millis. Blocking. */
   public Map<String, List<TopEntry>> getDayTops(long from, long to, int limit) {
      Map<String, List<TopEntry>> out = new LinkedHashMap<>();
      for (String t : new String[] {"ban", "mute", "warn"}) {
         out.put(t, new java.util.ArrayList<>());
      }
      try (
         Connection c = this.pool.getConnection();
         PreparedStatement ps = c.prepareStatement(
            "SELECT type, MIN(staff) AS s, COUNT(*) AS n FROM sm_actions WHERE at_ms>=? AND at_ms<? AND type IN ('ban','mute','warn') GROUP BY type, LOWER(staff) ORDER BY n DESC, s ASC"
         );
      ) {
         ps.setLong(1, from);
         ps.setLong(2, to);
         try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
               List<TopEntry> l = out.get(rs.getString(1));
               if (l != null && l.size() < limit) {
                  l.add(new TopEntry(rs.getString(2), rs.getInt(3)));
               }
            }
         }
      } catch (SQLException e) {
         e.printStackTrace();
      }
      return out;
   }

   /** Ban/mute/warn counts per day for the last {@code days} days (index 0 = oldest). Blocking. */
   public Map<String, int[]> getActionsByDay(int days) {
      Map<String, int[]> out = new LinkedHashMap<>();
      for (String t : new String[] {"ban", "mute", "warn"}) {
         out.put(t, new int[days]);
      }
      java.time.ZoneId zone = ZONE;
      java.time.LocalDate today = java.time.LocalDate.now(zone);
      long since = today.minusDays(days - 1L).atStartOfDay(zone).toInstant().toEpochMilli();
      try (
         Connection c = this.pool.getConnection();
         PreparedStatement ps = c.prepareStatement("SELECT type, at_ms FROM sm_actions WHERE at_ms>=? AND type IN ('ban','mute','warn')");
      ) {
         ps.setLong(1, since);
         try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
               long day = java.time.Instant.ofEpochMilli(rs.getLong(2)).atZone(zone).toLocalDate().toEpochDay();
               int idx = (int) (days - 1 - (today.toEpochDay() - day));
               int[] arr = out.get(rs.getString(1));
               if (arr != null && idx >= 0 && idx < days) {
                  arr[idx]++;
               }
            }
         }
      } catch (SQLException e) {
         e.printStackTrace();
      }
      return out;
   }

   public int getWarnCount(String name) {
      try {
         int var5;
         try (
            Connection c = this.pool.getConnection();
            PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM sm_warns WHERE player=?");
         ) {
            ps.setString(1, name.toLowerCase());
            ResultSet rs = ps.executeQuery();
            var5 = rs.next() ? rs.getInt(1) : 0;
         }

         return var5;
      } catch (SQLException var10) {
         var10.printStackTrace();
         return 0;
      }
   }

   public int kick(String name, String reason, String by) {
      ((Runnable) () -> {
         try (
            Connection c = this.pool.getConnection();
            PreparedStatement ps = c.prepareStatement("INSERT INTO sm_kicks (player, reason, by_who, at_ms) VALUES (?, ?, ?, ?)");
         ) {
            ps.setString(1, name.toLowerCase());
            ps.setString(2, reason);
            ps.setString(3, by);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
         } catch (SQLException var12) {
            var12.printStackTrace();
         }
      }).run();
      return this.getKickCount(name) + 1;
   }

   public List<KickEntry> getKicks(String name) {
      List<KickEntry> list = new ArrayList<>();

      try (
         Connection c = this.pool.getConnection();
         PreparedStatement ps = c.prepareStatement("SELECT id, reason, by_who, at_ms FROM sm_kicks WHERE player=? ORDER BY id ASC");
      ) {
         ps.setString(1, name.toLowerCase());
         ResultSet rs = ps.executeQuery();

         while (rs.next()) {
            list.add(new KickEntry(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
         }
      } catch (SQLException var11) {
         var11.printStackTrace();
      }

      return Collections.unmodifiableList(list);
   }

   public int getKickCount(String name) {
      try {
         int var5;
         try (
            Connection c = this.pool.getConnection();
            PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM sm_kicks WHERE player=?");
         ) {
            ps.setString(1, name.toLowerCase());
            ResultSet rs = ps.executeQuery();
            var5 = rs.next() ? rs.getInt(1) : 0;
         }

         return var5;
      } catch (SQLException var10) {
         var10.printStackTrace();
         return 0;
      }
   }

   public Map<String, DataManager.PunishmentEntry> getAllBanned() {
      return Collections.unmodifiableMap(new LinkedHashMap<>(this.banCache));
   }

   public Map<String, DataManager.PunishmentEntry> getAllMuted() {
      return Collections.unmodifiableMap(new LinkedHashMap<>(this.muteCache));
   }

   public Map<String, List<WarnEntry>> getAllWarnsGrouped() {
      Map<String, List<WarnEntry>> map = new LinkedHashMap<>();

      try (
         Connection c = this.pool.getConnection();
         PreparedStatement ps = c.prepareStatement("SELECT player, id, reason, by_who, at_ms FROM sm_warns ORDER BY player, id ASC");
      ) {
         ResultSet rs = ps.executeQuery();

         while (rs.next()) {
            map.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new WarnEntry(rs.getInt(2), rs.getString(3), rs.getString(4), rs.getLong(5)));
         }
      } catch (SQLException var10) {
         var10.printStackTrace();
      }

      map.replaceAll((k, v) -> Collections.unmodifiableList((List<? extends WarnEntry>)v));
      return Collections.unmodifiableMap(map);
   }

   public List<WarnEntry> getAllWarns() {
      List<WarnEntry> list = new ArrayList<>();

      try (
         Connection c = this.pool.getConnection();
         PreparedStatement ps = c.prepareStatement("SELECT id, reason, by_who, at_ms FROM sm_warns ORDER BY id ASC");
      ) {
         ResultSet rs = ps.executeQuery();

         while (rs.next()) {
            list.add(new WarnEntry(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
         }
      } catch (SQLException var10) {
         var10.printStackTrace();
      }

      return Collections.unmodifiableList(list);
   }

   public Map<String, List<KickEntry>> getAllKicksGrouped() {
      Map<String, List<KickEntry>> map = new LinkedHashMap<>();

      try (
         Connection c = this.pool.getConnection();
         PreparedStatement ps = c.prepareStatement("SELECT player, id, reason, by_who, at_ms FROM sm_kicks ORDER BY player, id ASC");
      ) {
         ResultSet rs = ps.executeQuery();

         while (rs.next()) {
            map.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new KickEntry(rs.getInt(2), rs.getString(3), rs.getString(4), rs.getLong(5)));
         }
      } catch (SQLException var10) {
         var10.printStackTrace();
      }

      map.replaceAll((k, v) -> Collections.unmodifiableList((List<? extends KickEntry>)v));
      return Collections.unmodifiableMap(map);
   }

   public record PunishmentEntry(long expires, String reason, String by, long at) {
      public boolean isActive() {
         return this.expires == -1L || System.currentTimeMillis() < this.expires;
      }
   }
}
