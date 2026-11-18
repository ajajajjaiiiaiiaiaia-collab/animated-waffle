package com.duel;

import com.duel.managers.DuelManager;
import com.duel.managers.QueueManager;
import com.duel.managers.StatsManager;
import com.duel.managers.StatsManager.PlayerStats;
import com.duel.managers.StatsManager.TopType;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Relational;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * PlaceholderAPI expansion for DuelPlugin — STARK NETWORK
 *
 * ═══════════════════════════════════════════════════
 *  GENERAL
 * ═══════════════════════════════════════════════════
 *  %duel_active%                        → عدد المبارزات النشطة الآن
 *  %duel_queued%                        → عدد اللاعبين في الانتظار
 *
 * ═══════════════════════════════════════════════════
 *  PERSONAL STATS  (للاعب الحالي — كل الأريناز مجموعة)
 * ═══════════════════════════════════════════════════
 *  %duel_wins_me%                       → عدد انتصارات اللاعب الحالي
 *  %duel_points_me%                     → عدد نقاط اللاعب الحالي
 *
 * ═══════════════════════════════════════════════════
 *  TOP — GLOBAL  (كل الأريناز مجموعة)
 * ═══════════════════════════════════════════════════
 *  %duel_top_wins_<rank>%               → اسم اللاعب رقم <rank> في الـ wins     (1-10)
 *  %duel_top_wins_<rank>_score%         → عدد wins تاعه
 *  %duel_top_losses_<rank>%             → اسم اللاعب رقم <rank> في الـ losses
 *  %duel_top_losses_<rank>_score%       → عدد losses تاعه
 *  %duel_top_points_<rank>%             → اسم اللاعب رقم <rank> في الـ points
 *  %duel_top_points_<rank>_score%       → عدد points تاعه
 *  %duel_top_streak_<rank>%             → اسم اللاعب رقم <rank> في الـ streak
 *  %duel_top_streak_<rank>_score%       → عدد streak تاعه
 *
 * ═══════════════════════════════════════════════════
 *  TOP — PER ARENA  (أرينا معينة)
 * ═══════════════════════════════════════════════════
 *  %duel_top_wins_<arena>_<rank>%       → اسم اللاعب رقم <rank> في أرينا <arena>
 *  %duel_top_wins_<arena>_<rank>_score% → عدد wins تاعه في الأرينا
 *  (نفس الصيغة لـ losses / points / streak)
 *
 * ═══════════════════════════════════════════════════
 *  TOP — ME  (مركز واسم وعدد اللاعب نفسه اللي يشوف الـ scoreboard/tab)
 * ═══════════════════════════════════════════════════
 *  %duel_top_wins_me%                   → اسم اللاعب (نفس شكل top_wins_<rank>)
 *  %duel_top_wins_me_score%             → عدد wins تاعه
 *  %duel_top_wins_me_rank%              → مركزه (رقم) أو -- إذا ما لعب
 *  %duel_top_wins_<arena>_me[_score|_rank]  → نفسها لأرينا معينة
 *  (نفس الصيغة لـ losses / points / streak)
 *
 *  مثال:
 *    %duel_top_wins_1%            → Steve        (global rank 1)
 *    %duel_top_wins_1_score%      → 42
 *    %duel_top_points_sword_1%    → Alex          (rank 1 in arena "sword")
 *    %duel_top_streak_sword_3_score% → 7
 */
public class DuelPlaceholders extends PlaceholderExpansion implements Relational {

    private final DuelPlugin plugin;

    public DuelPlaceholders(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String getIdentifier() { return "duel"; }
    @Override public @NotNull String getAuthor()     { return "STARK NETWORK"; }
    @Override public @NotNull String getVersion()    { return plugin.getDescription().getVersion(); }
    @Override public boolean persist()               { return true; }
    @Override public boolean canRegister()           { return true; }

    // ─────────────────────────────────────────────────────────────────────────
    //  Main handler
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {

        DuelManager  dm = plugin.getDuelManager();
        QueueManager qm = plugin.getQueueManager();
        StatsManager sm = plugin.getStatsManager();

        String lower = params.toLowerCase();

        // ── General ───────────────────────────────────────────────────────────
        if (lower.equals("active")) return String.valueOf(dm.getAllActiveSessions().size());
        if (lower.equals("queued")) return String.valueOf(qm.getTotalQueued());

        // ── Tiers (نظام التيرات) ──────────────────────────────────────────────
        if (lower.startsWith("tier_")) {
            String r = tierPlaceholder(player, lower.substring(5));
            if (r != null) return r;
        }

        // ── Party team colour (for TAB / nametag plugins) ─────────────────────
        // %duel_team_color% → "&9" (Blue) / "&c" (Red) while in a party team match, else ""
        // %duel_team%       → "Blue" / "Red" while in a party team match, else ""
        if (lower.equals("team_color") || lower.equals("team")) {
            if (player == null) return "";
            com.duel.models.PartyDuelSession ps = plugin.getPartyDuelManager().getSession(player);
            if (ps == null || ps.isFFA() || ps.getState() == com.duel.models.PartyDuelSession.State.ENDED) return "";
            int t = ps.getTeamOf(player);
            if (t == 1) return lower.equals("team") ? "Blue" : "&9";
            if (t == 2) return lower.equals("team") ? "Red"  : "&c";
            return "";
        }

        // ── Personal stats ────────────────────────────────────────────────────
        // %duel_wins_me%   → total wins for the requesting player (across all arenas)
        // %duel_points_me% → total points for the requesting player (across all arenas)
        if (lower.equals("wins_me") || lower.equals("points_me")) {
            if (player == null) return "0";
            StatsManager.PlayerStats stats = sm.getPlayerStats(player.getUniqueId());
            if (stats == null) return "0";
            return lower.equals("wins_me")
                    ? String.valueOf(stats.wins())
                    : String.valueOf(stats.points());
        }

        // ── Country / flag ───────────────────────────────────────────────────
        // %duel_country%  → "EG", "US", "XX" (unknown) or "" if player is null
        if (lower.equals("country")) {
            if (player == null) return "";
            String code = plugin.getCountryManager().getCountryCode(player.getUniqueId());
            return code != null ? code : "..."; // lookup still in progress
        }
        // %duel_flaghead% → MiniMessage "<head:TEXTURE>" for TAB plugin (1.21.9+ clients only)
        if (lower.equals("flaghead")) {
            if (player == null) return "";
            String code = plugin.getCountryManager().getCountryCode(player.getUniqueId());
            if (code == null || code.equals("XX")) return "";
            return plugin.getTabFlagManager().flagHeadMiniMessage(code);
        }

        // ── Top placeholders ─────────────────────────────────────────────────
        // Format options:
        //   top_<type>_<rank>               → global name
        //   top_<type>_<rank>_score         → global score
        //   top_<type>_<arena>_<rank>       → per-arena name
        //   top_<type>_<arena>_<rank>_score → per-arena score

        if (!lower.startsWith("top_")) return null;

        // Remove "top_" prefix → e.g. "wins_1" / "wins_1_score" / "wins_sword_1" / "wins_sword_1_score"
        String rest = lower.substring(4); // after "top_"

        // Detect type (wins / losses / points / streak)
        TopType type = null;
        String  afterType = null;

        for (TopType t : TopType.values()) {
            String tName = t.name().toLowerCase(); // wins / losses / streak / points
            if (rest.startsWith(tName + "_")) {
                type      = t;
                afterType = rest.substring(tName.length() + 1); // strip "wins_"
                break;
            }
        }

        if (type == null || afterType == null || afterType.isEmpty()) return null;

        // afterType is now one of:
        //   "<rank>"              → global name
        //   "<rank>_score"        → global score
        //   "<arena>_<rank>"      → per-arena name
        //   "<arena>_<rank>_score"→ per-arena score

        // ── "me" variants: me / me_score / me_rank  (global)  or  <arena>_me[...] ──
        {
            boolean meScore = afterType.endsWith("_score");
            boolean meRank  = afterType.endsWith("_rank");
            String core = meScore ? afterType.substring(0, afterType.length() - 6)
                        : meRank  ? afterType.substring(0, afterType.length() - 5)
                        : afterType;
            String meArena = null;
            boolean isMe = false;
            if (core.equals("me")) {
                isMe = true;
            } else if (core.endsWith("_me") && core.length() > 3) {
                isMe = true;
                meArena = core.substring(0, core.length() - 3);
            }
            if (isMe) {
                if (player == null) return meRank ? "--" : meScore ? "0" : "";
                if (meRank) {
                    int r = sm.getRank(player.getUniqueId(), meArena, type);
                    return r > 0 ? String.valueOf(r) : "--";
                }
                if (!meScore) return player.getName();
                PlayerStats mine = sm.getStatsFor(player.getUniqueId(), meArena);
                if (mine == null) return "0";
                return String.valueOf(switch (type) {
                    case WINS   -> mine.wins();
                    case LOSSES -> mine.losses();
                    case STREAK -> mine.bestStreak();
                    case POINTS -> mine.points();
                });
            }
        }

        // Try global first: afterType starts with a digit
        if (Character.isDigit(afterType.charAt(0))) {
            boolean wantScore = afterType.endsWith("_score");
            String  rankStr   = wantScore ? afterType.substring(0, afterType.length() - 6) : afterType;
            // strip trailing underscore if present
            if (rankStr.endsWith("_")) rankStr = rankStr.substring(0, rankStr.length() - 1);
            int rank = parseRank(rankStr);
            if (rank < 0) return null;

            List<PlayerStats> top = sm.getTopGlobal(type);
            return getValue(top, rank, wantScore, type);
        }

        // Per-arena: afterType = "<arena>_<rank>" or "<arena>_<rank>_score"
        boolean wantScore = afterType.endsWith("_score");
        String  withoutScore = wantScore ? afterType.substring(0, afterType.length() - 6) : afterType;
        if (withoutScore.endsWith("_")) withoutScore = withoutScore.substring(0, withoutScore.length() - 1);

        // Last segment after final "_" should be the rank number
        int lastUnderscore = withoutScore.lastIndexOf('_');
        if (lastUnderscore < 0) return null;

        String arenaId = withoutScore.substring(0, lastUnderscore);
        String rankStr = withoutScore.substring(lastUnderscore + 1);

        if (arenaId.isEmpty() || !Character.isDigit(rankStr.charAt(0))) return null;

        int rank = parseRank(rankStr);
        if (rank < 0) return null;

        List<PlayerStats> top = sm.getTop10(arenaId, type);
        return getValue(top, rank, wantScore, type);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Parse rank string (1-10) → 0-based index, or -1 if invalid */
    private int parseRank(String s) {
        try {
            int r = Integer.parseInt(s.trim());
            return (r >= 1 && r <= 10) ? r - 1 : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Extract name or score from the list at index, return "--" if out of bounds */
    private String getValue(List<PlayerStats> list, int index, boolean wantScore, TopType type) {
        if (list == null || index >= list.size()) return "--";
        PlayerStats ps = list.get(index);

        if (!wantScore) return ps.name();

        return String.valueOf(switch (type) {
            case WINS   -> ps.wins();
            case LOSSES -> ps.losses();
            case STREAK -> ps.bestStreak();
            case POINTS -> ps.points();
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Relational: %rel_duel_color%  (للاستعمال في TAB: tagprefix / tabprefix)
    //  viewer = اللي يشوف | target = اللي ينعرض اسمه
    //  1v1:        الـ viewer يشوف نفسه أزرق (&9) وخصمه أحمر (&c)
    //  Party Team: تيم 1 = &9 ، تيم 2 = &c (بس لو الـ viewer داخل نفس الماتش)
    //  أي أحد خارج الماتش → "" (يشوف الاسم الطبيعي)
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public String onPlaceholderRequest(Player viewer, Player target, String identifier) {
        if (viewer == null || target == null) return "";
        if (!identifier.equalsIgnoreCase("color")) return "";

        // 1v1
        com.duel.models.DuelSession ds = plugin.getDuelManager().getSession(target);
        if (ds != null && ds.getState() != com.duel.models.DuelSession.State.ENDED) {
            boolean viewerInside = viewer.equals(ds.getPlayer1()) || viewer.equals(ds.getPlayer2());
            if (!viewerInside) return "";
            return viewer.equals(target) ? "&9" : "&c";
        }

        // Party Team
        com.duel.models.PartyDuelSession ps = plugin.getPartyDuelManager().getSession(target);
        if (ps != null && !ps.isFFA() && ps.getState() != com.duel.models.PartyDuelSession.State.ENDED) {
            if (ps.getTeamOf(viewer) == -1) return "";
            int t = ps.getTeamOf(target);
            if (t == 1) return "&9";
            if (t == 2) return "&c";
        }
        return "";
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Tier placeholders
    //   %duel_tier_best%                → أعلى تير للاعب (ملوّن &) / _plain بدون لون
    //   %duel_tier_current%             → تير الأرينا اللي يلعب فيها الحين (أو الأعلى)
    //   %duel_tier_<arena>%             → تيره بالأرينا (ملوّن) / _plain / _rating / _peak / _challenge / _defense
    //   %duel_tier_top_<arena>_<rank>%  → اسم / _score = التقييم / _tier = التير
    //   %duel_tier_icon%                → أيقونة كيت أعلى تير + التير (للتاب) مثل [سيف] HT1
    //   %duel_tier_icon_<arena>%        → أيقونة الأرينا + تيره فيها
    //   %duel_tier_current_icon%        → نفس icon لكن للأرينا اللي يلعب فيها الحين (أو الأعلى)
    // ─────────────────────────────────────────────────────────────────────────
    private String tierPlaceholder(Player player, String p) {
        com.duel.tier.TierManager tm = plugin.getTierManager();
        if (tm == null) return null;

        if (p.startsWith("top_")) {
            String rest = p.substring(4);
            String mode = "name";
            if (rest.endsWith("_score")) { mode = "score"; rest = rest.substring(0, rest.length() - 6); }
            else if (rest.endsWith("_tier")) { mode = "tier"; rest = rest.substring(0, rest.length() - 5); }
            int us = rest.lastIndexOf('_');
            if (us <= 0) return null;
            int rank;
            try { rank = Integer.parseInt(rest.substring(us + 1)); } catch (NumberFormatException e) { return null; }
            String arena = rest.substring(0, us);
            List<com.duel.tier.TierManager.TopEntry> top = tm.getTop(arena);
            if (rank < 1 || rank > top.size()) return mode.equals("name") ? "---" : (mode.equals("score") ? "0" : "");
            com.duel.tier.TierManager.TopEntry e = top.get(rank - 1);
            return switch (mode) {
                case "score" -> String.valueOf(e.rating());
                case "tier"  -> e.tier().colored();
                default      -> e.name();
            };
        }

        if (player == null) return "";
        java.util.UUID id = player.getUniqueId();

        if (p.equals("icon")) {
            com.duel.tier.TierProfile bp = tm.getBestPlacedProfile(id);
            if (bp == null) {
                java.util.List<com.duel.models.Arena> all = new java.util.ArrayList<>(plugin.getArenaManager().getAllArenas());
                all.sort((x, y) -> x.getId().compareToIgnoreCase(y.getId()));
                com.duel.models.Arena first = all.isEmpty() ? null : all.get(0);
                if (tm.unrankedStart()) return com.duel.tier.TierIcon.withLabel(plugin, first, tm.unrankedLabel());
                return com.duel.tier.TierIcon.withTier(plugin, first, com.duel.tier.Tier.LT5);
            }
            return com.duel.tier.TierIcon.withTier(plugin, plugin.getArenaManager().getArena(bp.arenaId), bp.tier);
        }
        if (p.equals("current_icon")) {
            com.duel.models.DuelSession cs = plugin.getDuelManager().getSession(player);
            if (cs == null) return tierPlaceholder(player, "icon");
            if (tm.isUnranked(id, cs.getArena().getId()))
                return com.duel.tier.TierIcon.withLabel(plugin, cs.getArena(), tm.unrankedLabel());
            return com.duel.tier.TierIcon.withTier(plugin, cs.getArena(), tm.getTier(id, cs.getArena().getId()));
        }
        if (p.startsWith("icon_")) {
            com.duel.models.Arena ia = plugin.getArenaManager().getArena(p.substring(5));
            if (ia == null) return null;
            if (tm.isUnranked(id, ia.getId()))
                return com.duel.tier.TierIcon.withLabel(plugin, ia, tm.unrankedLabel());
            return com.duel.tier.TierIcon.withTier(plugin, ia, tm.getTier(id, ia.getId()));
        }

        if (p.equals("best"))       return tm.displayBest(id);
        if (p.equals("best_plain")) { return (tm.unrankedStart() && tm.getBestPlacedProfile(id) == null) ? "Unranked" : tm.getBestTier(id).name(); }
        if (p.equals("current")) {
            com.duel.models.DuelSession s = plugin.getDuelManager().getSession(player);
            return s != null ? tm.display(id, s.getArena().getId()) : tm.displayBest(id);
        }

        String mode = "colored";
        String arena = p;
        for (String suf : new String[]{"_plain", "_rating", "_peak", "_challenge", "_defense"}) {
            if (p.endsWith(suf)) { mode = suf.substring(1); arena = p.substring(0, p.length() - suf.length()); break; }
        }
        if (plugin.getArenaManager().getArena(arena) == null) return null;
        com.duel.tier.TierProfile prof = tm.getFresh(id, player.getName(), arena);
        return switch (mode) {
            case "plain"     -> tm.isUnranked(id, arena) ? "Unranked" : prof.tier.name();
            case "rating"    -> String.valueOf(prof.rating);
            case "peak"      -> tm.isUnranked(id, arena) ? tm.unrankedLabel() : prof.peakTier.colored();
            case "challenge" -> String.valueOf(prof.challengePending);
            case "defense"   -> String.valueOf(prof.defensePending);
            default          -> tm.isUnranked(id, arena) ? tm.unrankedLabel() : prof.tier.colored();
        };
    }
}
