package com.duel.models;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class DuelSession {

    public enum State { COUNTDOWN, FIGHTING, ROUND_BREAK, ENDED }

    private final UUID sessionId = UUID.randomUUID();
    private final Player player1;
    private final Player player2;
    private final Arena arena;
    private String copiedWorldName;          // عالم الراوند الحالي (يتغيّر كل راوند)
    private String templateWorld;            // عالم القالب اللي يُنسخ منه كل راوند
    private String nextWorldName;            // عالم الراوند التالي (يُجهّز أثناء الاستراحة)

    private State state = State.COUNTDOWN;
    private long startTime = System.currentTimeMillis();
    private Player winner;
    private org.bukkit.scoreboard.Scoreboard tabScoreboard = null;
    private org.bukkit.scheduler.BukkitTask pingTask = null;
    private org.bukkit.scheduler.BukkitTask countdownTask = null;
    private Player loser;
    private long countdownStartMs = System.currentTimeMillis();

    // تتبع البلوكات المبنية أثناء المباراة — Location → UUID اللاعب اللي بناها
    private final java.util.Map<Location, UUID> placedBlocks = new java.util.HashMap<>();

    // إسبون كل لاعب عند بداية المباراة (للتحقق من حد الارتفاع)
    private final java.util.Map<UUID, Location> playerSpawns = new java.util.HashMap<>();

    // ── نظام الرواندات ──
    private int totalRounds = 1;
    private int baseRounds = 1;
    private int currentRound = 1;
    private final java.util.Map<UUID, Integer> roundScores = new java.util.HashMap<>();
    // بلوكات الماب الأصلية اللي انكسرت — Location → BlockData الأصلية (لإرجاعها بين الرواندات)
    private final java.util.Map<Location, org.bukkit.block.data.BlockData> brokenOriginals = new java.util.HashMap<>();

    // true = مباراة كيو (تحسب على التوبات). /duel و rematch = false
    private boolean ranked = false;
    public boolean isRanked()            { return ranked; }
    public void setRanked(boolean r)     { this.ranked = r; }

    public int getTotalRounds()          { return totalRounds; }
    public void setTotalRounds(int n)    { this.totalRounds = Math.max(1, n); this.baseRounds = this.totalRounds; }
    /** راوند حاسم إضافي عند التعادل — ما يغيّر العدد الأصلي المطلوب (للريماتش). */
    public void extendRounds()           { this.totalRounds++; }
    public int getBaseRounds()           { return baseRounds; }
    public int getCurrentRound()         { return currentRound; }
    public void nextRound()              { this.currentRound++; }
    public boolean isRoundMatch()        { return totalRounds > 1; }
    public int getRoundScore(Player p)   { return roundScores.getOrDefault(p.getUniqueId(), 0); }
    public void addRoundScore(Player p)  { roundScores.merge(p.getUniqueId(), 1, Integer::sum); }

    public void trackBrokenOriginal(Location loc, org.bukkit.block.data.BlockData data) {
        brokenOriginals.putIfAbsent(loc.getBlock().getLocation(), data.clone());
    }

    public java.util.Map<Location, UUID> getPlacedBlocks() { return placedBlocks; }
    public java.util.Map<Location, org.bukkit.block.data.BlockData> getBrokenOriginals() { return brokenOriginals; }

    public void setPlayerSpawn(Player player, Location loc) {
        playerSpawns.put(player.getUniqueId(), loc.clone());
    }

    public Location getPlayerSpawn(Player player) {
        return playerSpawns.get(player.getUniqueId());
    }

    public void trackPlacedBlock(Location loc, Player placer) {
        placedBlocks.put(loc.getBlock().getLocation(), placer.getUniqueId());
    }

    public boolean isBlockPlacedByPlayer(Location loc, Player player) {
        UUID placer = placedBlocks.get(loc.getBlock().getLocation());
        return placer != null && placer.equals(player.getUniqueId());
    }

    public boolean isBlockPlacedByOpponent(Location loc, Player player) {
        UUID placer = placedBlocks.get(loc.getBlock().getLocation());
        if (placer == null) return false;
        return !placer.equals(player.getUniqueId());
    }

    public boolean isPlacedBlock(Location loc) {
        return placedBlocks.containsKey(loc.getBlock().getLocation());
    }

    public void untrackBlock(Location loc) {
        placedBlocks.remove(loc.getBlock().getLocation());
    }

    public DuelSession(Player p1, Player p2, Arena arena, String copiedWorldName) {
        this.player1 = p1;
        this.player2 = p2;
        this.arena = arena;
        this.copiedWorldName = copiedWorldName;
    }

    public UUID getSessionId()         { return sessionId; }
    public Player getPlayer1()         { return player1; }
    public Player getPlayer2()         { return player2; }
    public org.bukkit.scoreboard.Scoreboard getTabScoreboard() { return tabScoreboard; }
    public void setTabScoreboard(org.bukkit.scoreboard.Scoreboard sb) { this.tabScoreboard = sb; }
    public org.bukkit.scheduler.BukkitTask getPingTask() { return pingTask; }
    public void setPingTask(org.bukkit.scheduler.BukkitTask t) { this.pingTask = t; }
    public org.bukkit.scheduler.BukkitTask getCountdownTask() { return countdownTask; }
    public void setCountdownTask(org.bukkit.scheduler.BukkitTask t) { this.countdownTask = t; }
    public Arena getArena()            { return arena; }
    public String getCopiedWorldName() { return copiedWorldName; }
    public void setCopiedWorldName(String name) { this.copiedWorldName = name; }
    public String getTemplateWorld()   { return templateWorld; }
    public void setTemplateWorld(String w) { this.templateWorld = w; }
    public String getNextWorldName()   { return nextWorldName; }
    public void setNextWorldName(String n) { this.nextWorldName = n; }

    /** عالم جديد = نمسح كل تتبع البلوكات (مواقعها كانت بالعالم القديم). */
    public void resetWorldTracking() {
        placedBlocks.clear();
        brokenOriginals.clear();
        worldDirty = false;
    }

    /** تغيير بالعالم ما يتتبعه الـ block tracking (سطل، انفجار، نار...) */
    private boolean worldDirty = false;
    public void markWorldDirty()       { this.worldDirty = true; }
    public boolean isWorldDirty()      { return worldDirty; }
    public State getState()            { return state; }
    public void setState(State state)  { this.state = state; }
    public Player getWinner()          { return winner; }
    public Player getLoser()           { return loser; }

    public void setWinner(Player winner) {
        this.winner = winner;
        this.loser = winner == player1 ? player2 : player1;
    }

    public boolean hasPlayer(Player p) {
        return player1.equals(p) || player2.equals(p);
    }

    public long getCountdownStartMs() { return countdownStartMs; }
    public void markCountdownStart() { this.countdownStartMs = System.currentTimeMillis(); }

    public long getStartTime() { return startTime; }
    public void markStarted() { this.startTime = System.currentTimeMillis(); }

    public Player getOpponent(Player p) {
        return player1.equals(p) ? player2 : player1;
    }
}
