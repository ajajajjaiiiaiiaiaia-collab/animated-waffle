package com.duel.models;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class Arena {

    private final String id;           // e.g. "SwordFight"
    private final List<ItemStack> guiItems = new ArrayList<>();
    // worldName -> [spawn_p1, spawn_p2]
    private final Map<String, List<Location>> spawnPoints = new HashMap<>();
    private final List<String> worlds = new ArrayList<>();
    private String kitName;            // matches a Kit by name

    // World settings — configured during arena setup
    private boolean allowBreaking      = false;
    private boolean allowBuilding      = false;
    private boolean allowBreakPlaced   = false; // تكسير بلوكات مبنية أثناء المباراة
    private String  difficulty         = "normal"; // "easy", "normal", "hard"
    private int     menuSlot           = -1;       // خانة المنيو (-1 = ترتيب تلقائي)

    // worldName -> display name + icon (shown in the /duel map picker)
    private final Map<String, WorldMeta> worldMeta = new HashMap<>();

    public Arena(String id) {
        this.id = id;
    }

    /** Display data of one map (world). Display name is cosmetic only — it never changes the real world name. */
    public static final class WorldMeta {
        private String displayName;   // null = use the world name
        private String icon;          // Material name, null = default icon
        public WorldMeta(String displayName, String icon) { this.displayName = displayName; this.icon = icon; }
        public String getDisplayName()          { return displayName; }
        public void   setDisplayName(String n)  { this.displayName = n; }
        public String getIcon()                 { return icon; }
        public void   setIcon(String i)         { this.icon = i; }
    }

    public Map<String, WorldMeta> getWorldMeta() { return worldMeta; }

    // ── Potion effects given to every player of this arena right after teleport ──
    /** effect key (e.g. "speed") -> setting */
    private final Map<String, PotionSetting> potions = new LinkedHashMap<>();

    public static final class PotionSetting {
        private final String effect;        // namespaced key without namespace: "night_vision"
        private final int amplifier;        // 0 = level 1
        private final int durationSeconds;  // -1 = infinite
        public PotionSetting(String effect, int amplifier, int durationSeconds) {
            this.effect = effect.toLowerCase();
            this.amplifier = Math.max(0, Math.min(255, amplifier));
            this.durationSeconds = durationSeconds;
        }
        public String getEffect()          { return effect; }
        public int getAmplifier()          { return amplifier; }
        public int getDurationSeconds()    { return durationSeconds; }
        public boolean isInfinite()        { return durationSeconds < 0; }
        public org.bukkit.potion.PotionEffectType type() {
            return org.bukkit.potion.PotionEffectType.getByKey(org.bukkit.NamespacedKey.minecraft(effect));
        }
    }

    public Map<String, PotionSetting> getPotions() { return potions; }

    /** يعطي اللاعب كل بوشنات الأرينا فوراً (يستبدل أي تأثير من نفس النوع). */
    public void applyPotions(org.bukkit.entity.Player p) {
        if (p == null || !p.isOnline()) return;
        for (PotionSetting s : potions.values()) {
            try {
                org.bukkit.potion.PotionEffectType t = s.type();
                if (t == null) continue;
                int ticks = s.isInfinite() ? org.bukkit.potion.PotionEffect.INFINITE_DURATION
                                           : s.getDurationSeconds() * 20;
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(t, ticks, s.getAmplifier(), false, true, true));
            } catch (Throwable ignored) {}
        }
    }

    /** يشيل بوشنات الأرينا من اللاعب (عند الرجوع للوبي). */
    public void clearPotions(org.bukkit.entity.Player p) {
        if (p == null || !p.isOnline()) return;
        for (PotionSetting s : potions.values()) {
            try {
                org.bukkit.potion.PotionEffectType t = s.type();
                if (t != null) p.removePotionEffect(t);
            } catch (Throwable ignored) {}
        }
    }

    public WorldMeta getOrCreateWorldMeta(String worldName) {
        return worldMeta.computeIfAbsent(worldName, k -> new WorldMeta(null, null));
    }

    /** Name players see for a map: custom display name, or the world name if none set. */
    public String getWorldDisplayName(String worldName) {
        WorldMeta m = worldMeta.get(worldName);
        return (m != null && m.getDisplayName() != null && !m.getDisplayName().isBlank())
                ? m.getDisplayName() : worldName;
    }

    /** Icon of a map, falls back to a plain map item. */
    public org.bukkit.Material getWorldIcon(String worldName) {
        WorldMeta m = worldMeta.get(worldName);
        if (m != null && m.getIcon() != null) {
            org.bukkit.Material mat = org.bukkit.Material.matchMaterial(m.getIcon());
            if (mat != null && mat.isItem() && !mat.isAir()) return mat;
        }
        return org.bukkit.Material.MAP;
    }

    public String getId()                { return id; }
    public List<ItemStack> getGuiItems() { return guiItems; }
    public Map<String, List<Location>> getSpawnPoints() { return spawnPoints; }
    public List<String> getWorlds()      { return worlds; }
    public String getKitName()           { return kitName; }
    public void setKitName(String k)     { this.kitName = k; }

    public boolean isAllowBreaking()     { return allowBreaking; }
    public void setAllowBreaking(boolean v) { this.allowBreaking = v; }
    public boolean isAllowBuilding()          { return allowBuilding; }
    public void setAllowBuilding(boolean v)   { this.allowBuilding = v; }
    public boolean isAllowBreakPlaced()       { return allowBreakPlaced; }
    public void setAllowBreakPlaced(boolean v){ this.allowBreakPlaced = v; }
    public String getDifficulty()             { return difficulty; }
    public int  getMenuSlot()                 { return menuSlot; }
    public void setMenuSlot(int slot)         { this.menuSlot = slot; }
    public void setDifficulty(String d)  { this.difficulty = d; }

    /**
     * Add or append a spawn for a world. First call = P1 spawn, second = P2 spawn.
     */
    public void addSpawn(String worldName, Location loc) {
        spawnPoints.computeIfAbsent(worldName, k -> new ArrayList<>()).add(loc);
    }

    /**
     * Get spawn for a player (0-based index: 0=P1, 1=P2).
     * Falls back to first spawn if index out of range.
     */
    public Location getSpawn(String worldName, int playerIndex) {
        List<Location> spawns = spawnPoints.get(worldName);
        if (spawns == null || spawns.isEmpty()) return null;
        return spawns.get(Math.min(playerIndex, spawns.size() - 1));
    }

    /** Pick a random world from the configured list */
    public String getRandomWorld() {
        if (worlds.isEmpty()) return null;
        return worlds.get(new Random().nextInt(worlds.size()));
    }

    /** Display item = first GUI item */
    public ItemStack getDisplayItem() {
        return guiItems.isEmpty() ? null : guiItems.get(0);
    }
}
