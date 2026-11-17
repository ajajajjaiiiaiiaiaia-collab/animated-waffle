package com.duel.database;

import com.duel.DuelPlugin;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.*;
import java.util.logging.Level;

/**
 * Central SQLite + HikariCP database manager.
 * Single database.db file — all tables inside.
 */
public class DatabaseManager {

    private final DuelPlugin plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        File dbFile = new File(plugin.getDataFolder(), "database.db");

        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        config.setPoolName("DuelPlugin-Pool");
        // SQLite pragmas must be applied via connectionInitSql, not DataSource properties
        config.setConnectionInitSql(
            "PRAGMA journal_mode=WAL; " +
            "PRAGMA synchronous=NORMAL; " +
            "PRAGMA cache_size=10000; " +
            "PRAGMA foreign_keys=ON;"
        );

        dataSource = new HikariDataSource(config);
        createTables();
        createArenaPotionsTable(); // جدول مستقل: يُنشأ دائماً حتى لو قاعدة البيانات قديمة
        createTierTables();
        plugin.getLogger().info("[DuelPlugin] Database connected — database.db");
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    private void createTables() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS arenas (
                    id             TEXT PRIMARY KEY,
                    kit_name       TEXT,
                    data           TEXT,
                    allow_breaking      INTEGER NOT NULL DEFAULT 0,
                    allow_building      INTEGER NOT NULL DEFAULT 0,
                    allow_break_placed  INTEGER NOT NULL DEFAULT 0,
                    difficulty          TEXT    NOT NULL DEFAULT 'normal',
                    menu_slot           INTEGER
                )
            """);
            // Migration: add columns if they don't exist (for existing DBs)
            try { stmt.execute("ALTER TABLE arenas ADD COLUMN allow_breaking INTEGER NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE arenas ADD COLUMN allow_building INTEGER NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE arenas ADD COLUMN allow_break_placed INTEGER NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE arenas ADD COLUMN difficulty TEXT NOT NULL DEFAULT 'normal'");  } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE arenas ADD COLUMN menu_slot INTEGER"); } catch (Exception ignored) {}

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS arena_worlds (
                    arena_id   TEXT NOT NULL,
                    world_name TEXT NOT NULL,
                    spawns     TEXT NOT NULL,
                    PRIMARY KEY (arena_id, world_name),
                    FOREIGN KEY (arena_id) REFERENCES arenas(id) ON DELETE CASCADE
                )
            """);

            // Per-world (map) display metadata: icon + display name shown in the /duel map picker.
            // Kept separate from arena_worlds because saveArena() deletes & re-inserts those rows.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS arena_world_meta (
                    arena_id     TEXT NOT NULL,
                    world_name   TEXT NOT NULL,
                    display_name TEXT,
                    icon         TEXT,
                    PRIMARY KEY (arena_id, world_name),
                    FOREIGN KEY (arena_id) REFERENCES arenas(id) ON DELETE CASCADE
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS barrier_areas (
                    world TEXT PRIMARY KEY,
                    x     REAL NOT NULL,
                    z     REAL NOT NULL,
                    half  REAL NOT NULL
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS kits (
                    name  TEXT PRIMARY KEY,
                    items TEXT NOT NULL
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS player_kits (
                    uuid     TEXT NOT NULL,
                    arena_id TEXT NOT NULL,
                    items    TEXT NOT NULL,
                    PRIMARY KEY (uuid, arena_id)
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS player_settings (
                    uuid                TEXT PRIMARY KEY,
                    hide_chat           INTEGER DEFAULT 0,
                    hide_scoreboard     INTEGER DEFAULT 0,
                    hide_players        INTEGER DEFAULT 0,
                    block_duel_requests INTEGER DEFAULT 0,
                    mute_sounds         INTEGER DEFAULT 0,
                    hide_actionbar      INTEGER DEFAULT 0,
                    hide_death_messages INTEGER DEFAULT 0,
                    hide_join_quit      INTEGER DEFAULT 0,
                    aura_gg             INTEGER DEFAULT 0,
                    hide_rankup_title   INTEGER DEFAULT 0
                )
            """);
            try { stmt.execute("ALTER TABLE player_settings ADD COLUMN aura_gg INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE player_settings ADD COLUMN hide_rankup_title INTEGER DEFAULT 0"); } catch (Exception ignored) {}

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS stats (
                    uuid        TEXT NOT NULL,
                    arena_id    TEXT NOT NULL,
                    name        TEXT NOT NULL,
                    wins        INTEGER DEFAULT 0,
                    losses      INTEGER DEFAULT 0,
                    streak      INTEGER DEFAULT 0,
                    best_streak INTEGER DEFAULT 0,
                    points      INTEGER DEFAULT 0,
                    PRIMARY KEY (uuid, arena_id)
                )
            """);

            // Migration: add points column for existing databases
            try { stmt.execute("ALTER TABLE stats ADD COLUMN points INTEGER DEFAULT 0"); }
            catch (SQLException ignored) { /* column already exists */ }

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_stats_wins        ON stats(arena_id, wins DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_stats_losses      ON stats(arena_id, losses DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_stats_best_streak ON stats(arena_id, best_streak DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_stats_points      ON stats(arena_id, points DESC)");

            plugin.getLogger().info("[DuelPlugin] All tables initialized.");

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to create tables!", e);
        }
    }

    /**
     * بوشنات كل أرينا (تنطيها اللاعبين فور النقل للأرينا).
     * CREATE TABLE IF NOT EXISTS → آمن على القواعد القديمة، ما يمس أي جدول موجود.
     * amplifier: 0 = مستوى 1 / duration_seconds: -1 = بلا نهاية.
     */
    private void createArenaPotionsTable() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS arena_potions (
                    arena_id         TEXT    NOT NULL,
                    effect           TEXT    NOT NULL,
                    amplifier        INTEGER NOT NULL DEFAULT 0,
                    duration_seconds INTEGER NOT NULL DEFAULT -1,
                    PRIMARY KEY (arena_id, effect)
                )
            """);
            plugin.getLogger().info("[DuelPlugin] Table arena_potions ready.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to create arena_potions table!", e);
        }
    }

    /** نظام التيرات: بروفايلات (لكل أرينا)، أرشيف المواسم، وميتا. آمن على القواعد القديمة. */
    private void createTierTables() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS tier_profiles (
                    uuid                TEXT    NOT NULL,
                    arena_id            TEXT    NOT NULL,
                    name                TEXT    NOT NULL,
                    rating              INTEGER NOT NULL DEFAULT 1000,
                    tier                TEXT    NOT NULL DEFAULT 'LT5',
                    peak_tier           TEXT    NOT NULL DEFAULT 'LT5',
                    season_peak_rating  INTEGER NOT NULL DEFAULT 1000,
                    games               INTEGER NOT NULL DEFAULT 0,
                    wins                INTEGER NOT NULL DEFAULT 0,
                    losses              INTEGER NOT NULL DEFAULT 0,
                    challenge_pending   INTEGER NOT NULL DEFAULT 0,
                    challenge_cooldown  INTEGER NOT NULL DEFAULT 0,
                    defense_pending     INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (uuid, arena_id)
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tier_rating ON tier_profiles(arena_id, rating DESC)");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS tier_history (
                    season       INTEGER NOT NULL,
                    uuid         TEXT    NOT NULL,
                    arena_id     TEXT    NOT NULL,
                    name         TEXT    NOT NULL,
                    rating       INTEGER NOT NULL,
                    tier         TEXT    NOT NULL,
                    peak_tier    TEXT    NOT NULL,
                    peak_rating  INTEGER NOT NULL,
                    games        INTEGER NOT NULL,
                    ended_at     INTEGER NOT NULL
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tier_hist ON tier_history(season, arena_id, peak_rating DESC)");
            stmt.execute("CREATE TABLE IF NOT EXISTS tier_meta (meta_key TEXT PRIMARY KEY, meta_value TEXT NOT NULL)");
            plugin.getLogger().info("[DuelPlugin] Tier tables ready.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] Failed to create tier tables!", e);
        }
    }

    public void execute(String sql, Object... params) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[DuelPlugin] SQL error: " + sql, e);
        }
    }
}
