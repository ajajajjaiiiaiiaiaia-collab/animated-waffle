package com.duel.models;

import org.bukkit.entity.Player;

import java.util.*;

/**
 * يمثّل جلسة دول بين بارتيَّيْن.
 *
 * آلية اللعب:
 *  - كل اللاعبين يرسبنون في مواقع عشوائية داخل حدود العالم (border).
 *  - أي لاعب يموت يصير spectator تلقائيًّا (ويُحذف من القائمة المتبقّية).
 *  - آخر لاعب يبقى من فريقه يُعلَن فوز فريقه.
 *  - يتم اختيار "الفائز الفردي" كـ أول لاعب بقي من الفريق الرابح.
 */
public class PartyDuelSession {

    public enum State { COUNTDOWN, FIGHTING, ENDED }

    private final UUID sessionId = UUID.randomUUID();

    /** الفريق 1: قائد + أعضاء */
    private final List<Player> team1;
    /** الفريق 2: قائد + أعضاء */
    private final List<Player> team2;

    private final Arena  arena;
    private final String copiedWorldName;

    /** بلوكات انبنت أثناء المباراة (عشان إعداد تكسير المبني) */
    private final Set<org.bukkit.Location> placedBlocks = new HashSet<>();

    /** اللاعبون الأحياء حاليًّا (يُحذف منها عند الموت) */
    private final Set<UUID> alivePlayers = new LinkedHashSet<>();

    /** اللاعبون الذين ماتوا وصاروا spectators */
    private final Set<UUID> deadPlayers = new LinkedHashSet<>();

    /** أموات طلعوا للسبون بدري (/spectate أو /leave) قبل نهاية المباراة */
    private final Set<UUID> leftEarly = new HashSet<>();
    public void markLeftEarly(UUID u) { leftEarly.add(u); }
    public boolean hasLeftEarly(Player p) { return leftEarly.contains(p.getUniqueId()); }

    private State  state = State.COUNTDOWN;
    private long   startTime = System.currentTimeMillis();
    private Player winner;
    private int    winnerTeam = -1;
    private org.bukkit.scoreboard.Scoreboard scoreboard = null; // لألوان الفريقين في TAB

    public PartyDuelSession(List<Player> team1, List<Player> team2, Arena arena, String copiedWorldName) {
        this.team1           = new ArrayList<>(team1);
        this.team2           = new ArrayList<>(team2);
        this.arena           = arena;
        this.copiedWorldName = copiedWorldName;

        for (Player p : team1) alivePlayers.add(p.getUniqueId());
        for (Player p : team2) alivePlayers.add(p.getUniqueId());
    }

    // ── State ───────────────────────────────────────

    public long   getStartTime()         { return startTime; }
    public void   markStarted()          { this.startTime = System.currentTimeMillis(); }

    public void    trackPlacedBlock(org.bukkit.Location loc) { placedBlocks.add(loc.getBlock().getLocation()); }
    public boolean isPlacedBlock(org.bukkit.Location loc)    { return placedBlocks.contains(loc.getBlock().getLocation()); }
    public void    untrackBlock(org.bukkit.Location loc)     { placedBlocks.remove(loc.getBlock().getLocation()); }

    public UUID   getSessionId()         { return sessionId; }
    public Arena  getArena()             { return arena; }
    public String getCopiedWorldName()   { return copiedWorldName; }
    public State  getState()             { return state; }
    public void   setState(State state)  { this.state = state; }
    public Player getWinner()            { return winner; }
    public int    getWinnerTeam()        { return winnerTeam; }

    public List<Player> getTeam1()       { return Collections.unmodifiableList(team1); }
    public List<Player> getTeam2()       { return Collections.unmodifiableList(team2); }

    public org.bukkit.scoreboard.Scoreboard getScoreboard() { return scoreboard; }
    public void setScoreboard(org.bukkit.scoreboard.Scoreboard sb) { this.scoreboard = sb; }

    public List<Player> getAllPlayers() {
        List<Player> all = new ArrayList<>(team1);
        all.addAll(team2);
        return all;
    }

    // ── Alive / Dead tracking ───────────────────────

    public boolean isAlive(Player p) {
        return alivePlayers.contains(p.getUniqueId());
    }

    /**
     * يُسجّل موت لاعب.
     * @return رقم الفريق الرابح (1 أو 2) إذا انتهت المباراة، أو -1 لو مستمرة.
     */
    public int registerDeath(Player dead) {
        alivePlayers.remove(dead.getUniqueId());
        deadPlayers.add(dead.getUniqueId());

        // افحص إذا فريق كامل ميت
        boolean team1Alive = team1.stream().anyMatch(p -> alivePlayers.contains(p.getUniqueId()));
        boolean team2Alive = team2.stream().anyMatch(p -> alivePlayers.contains(p.getUniqueId()));

        if (!team1Alive && !team2Alive) {
            // تعادل — لا يحصل عادةً لكن نتعامل معه: الفريق الثاني فائز
            winnerTeam = 2;
            winner = team2.get(0);
            state = State.ENDED;
            return winnerTeam;
        }
        if (!team1Alive) {
            winnerTeam = 2;
            winner = team2.stream()
                    .filter(p -> alivePlayers.contains(p.getUniqueId()))
                    .findFirst().orElse(team2.get(0));
            state = State.ENDED;
            return winnerTeam;
        }
        if (!team2Alive) {
            winnerTeam = 1;
            winner = team1.stream()
                    .filter(p -> alivePlayers.contains(p.getUniqueId()))
                    .findFirst().orElse(team1.get(0));
            state = State.ENDED;
            return winnerTeam;
        }
        return -1; // مستمرة
    }

    public Set<UUID> getAlivePlayers()  { return Collections.unmodifiableSet(alivePlayers); }
    public Set<UUID> getDeadPlayers()   { return Collections.unmodifiableSet(deadPlayers); }

    /** فريق اللاعب: 1 أو 2 أو -1 إذا مش موجود */
    public int getTeamOf(Player p) {
        if (team1.stream().anyMatch(m -> m.getUniqueId().equals(p.getUniqueId()))) return 1;
        if (team2.stream().anyMatch(m -> m.getUniqueId().equals(p.getUniqueId()))) return 2;
        return -1;
    }

    /** True إذا كانت الجلسة FFA (كل الأعضاء في team1، team2 فارغة) */
    public boolean isFFA() {
        return team2 == null || team2.isEmpty();
    }

    public boolean hasPlayer(Player p) {
        return getTeamOf(p) != -1;
    }

    public List<Player> getLosingTeam() {
        return winnerTeam == 1 ? team2 : team1;
    }

    public List<Player> getWinningTeam() {
        return winnerTeam == 1 ? team1 : team2;
    }
}
