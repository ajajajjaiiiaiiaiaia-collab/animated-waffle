package com.duel.party;

import com.duel.DuelPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class Party {

    // ── إصلاح 1: نستخدم instance list في PartyManager بدل static ──
    // لا نزال نحتفظ بـ static للـ getByLeader/getByPlayer لكن نمسحها عند onDisable
    private static final List<Party> parties = new ArrayList<>();

    private final Player leader;
    private final List<UUID> members;
    private final List<PartyInvite> invites;
    private PartyPrivacy privacy;

    // ── نظام التيمات (Party FFA → Team) — يبقى محفوظ بعد المباراة لين يسوي القائد /team disband ──
    private final List<UUID> teamTop    = new ArrayList<>(); // التيم الأزرق (فوق)
    private final List<UUID> teamBottom = new ArrayList<>(); // التيم الأحمر (تحت)

    // ── إصلاح 5: تتبع طلبات الـ leave pending للقائد ──
    private final Set<UUID> pendingLeaveConfirm = new HashSet<>();

    public Party(Player leader) {
        this.leader  = leader;
        this.members = new ArrayList<>();
        this.invites = new ArrayList<>();
        this.privacy = PartyPrivacy.CLOSED;
        this.members.add(leader.getUniqueId());
        parties.add(this);
    }

    // ── Static helpers ────────────────────────

    public static List<Party> getParties() { return parties; }

    /** إصلاح 1: مسح القائمة عند reload/disable */
    public static void clearAll() { parties.clear(); }

    public static Party getByPlayer(Player player) {
        for (Party p : parties) {
            if (p.containsPlayer(player.getUniqueId())) return p;
        }
        return null;
    }

    public static Party getByLeader(Player player) {
        for (Party p : parties) {
            if (p.leader.equals(player)) return p;
        }
        return null;
    }

    // ── Invite system ─────────────────────────

    public boolean containsPlayer(UUID uuid) {
        return members.contains(uuid);
    }

    public PartyInvite getInvite(UUID uuid) {
        Iterator<PartyInvite> it = invites.iterator();
        while (it.hasNext()) {
            PartyInvite inv = it.next();
            if (inv.getUuid().equals(uuid)) {
                if (inv.hasExpired()) { it.remove(); return null; }
                return inv;
            }
        }
        return null;
    }

    public void invite(Player target) {
        invites.add(new PartyInvite(target.getUniqueId()));
        broadcast(color("&6[Party] &e" + leader.getName() + " &finvited you to their party. &a/party join " + leader.getName()), target);
        broadcast(color("&6[Party] &7You invited &e" + target.getName() + " &7to the party."), leader);
    }

    public void join(Player player) {
        // FIX: انضمام لبارتي يلغي الكيو والدعوات المعلّقة (ما يصير بارتي + كيو 1v1 مع بعض)
        DuelPlugin.getInstance().getQueueManager().removeFromQueue(player);
        DuelPlugin.getInstance().getDuelRequestManager().cancelAllFor(player);
        invites.removeIf(inv -> inv.getUuid().equals(player.getUniqueId()));
        members.add(player.getUniqueId());
        broadcastAll(color("&6[Party] &e" + player.getName() + " &7joined the party."));
        DuelPlugin.getInstance().getPartyManager().updatePlayerParty(player, this);
        for (Player p : getOnlineMembers()) {
            DuelPlugin.getInstance().getPartyManager().refreshHotbar(p);
        }
    }

    public void leave(Player player, boolean kicked) {
        pendingLeaveConfirm.remove(player.getUniqueId());
        // لو العضو داخل ماتش → يُقصى ويرجع للسبون (ما يبقى يلعب وهو برا البارتي)
        DuelPlugin.getInstance().getPartyDuelManager().eliminateAndRelease(player, kicked ? "was kicked from the party" : "left the party");
        members.remove(player.getUniqueId());
        removeFromTeams(player.getUniqueId());
        String action = kicked ? "was kicked from" : "left";
        broadcastAll(color("&6[Party] &e" + player.getName() + " &7" + action + " the party."));
        DuelPlugin.getInstance().getPartyManager().updatePlayerParty(player, null);
        // لو الأعضاء الباقيين 0 (القائد خرج وكان لوحده) — نفكك البارتي
        if (members.isEmpty()) {
            disband();
            return;
        }
        for (Player p : getOnlineMembers()) {
            DuelPlugin.getInstance().getPartyManager().refreshHotbar(p);
        }
        syncArrangementGui();
    }

    /** لو القائد فاتح GUI الترتيب ولاعب طلع (disconnect) → نحدّث الـ GUI مباشرة */
    private void syncArrangementGui() {
        if (!leader.isOnline()) return;
        org.bukkit.inventory.InventoryHolder h = leader.getOpenInventory().getTopInventory().getHolder();
        if (h instanceof com.duel.gui.PartyTeamGUI g) g.syncMembers();
        else if (h instanceof com.duel.gui.PartyTeamPagedGUI g) g.syncMembers();
    }

    public void disband() {
        // لو البارتي داخل ماتش (القائد طلع/فكّ البارتي) → ننهي الجلسة والكل يرجع للسبون
        DuelPlugin.getInstance().getPartyDuelManager().abortMatchesOf(
                new ArrayList<>(members), "&6[Party] &cThe party leader left — the match has ended.");
        parties.remove(this);
        pendingLeaveConfirm.clear();
        broadcastAll(color("&6[Party] &cThe party has been disbanded."));
        List<Player> online = getOnlineMembers();
        for (Player p : online) {
            DuelPlugin.getInstance().getPartyManager().updatePlayerParty(p, null);
        }
    }

    // ── إصلاح 5: القائد يحتاج تأكيد قبل leave ──

    /**
     * يُستدعى لما القائد يكتب /party leave.
     * المرة الأولى: يطلب تأكيد.
     * المرة الثانية (خلال 10 ثوان): يفكك البارتي.
     */
    public boolean requestLeaderLeave(Player leader) {
        if (pendingLeaveConfirm.contains(leader.getUniqueId())) {
            // تأكيد — نفكك البارتي
            pendingLeaveConfirm.remove(leader.getUniqueId());
            disband();
            return true;
        }
        // طلب أول — نطلب تأكيد
        pendingLeaveConfirm.add(leader.getUniqueId());
        leader.sendMessage(color("&6[Party] &cAs the leader, leaving will disband the party for everyone!"));
        leader.sendMessage(color("&6[Party] &eType &c/party leave &eagain within 10 seconds to confirm."));
        // نمسح التأكيد بعد 10 ثوان
        DuelPlugin.getInstance().getServer().getScheduler().runTaskLater(
            DuelPlugin.getInstance(),
            () -> pendingLeaveConfirm.remove(leader.getUniqueId()),
            200L
        );
        return false;
    }

    // ── Teams ─────────────────────────────────

    /**
     * true لو القائد فاتح GUI ترتيب التيمات (العادي أو بصفحات) —
     * خلال هالوقت ممنوع الانضمام والخروج/الكيك عشان الترتيب ما يتبدّل تحت إيده.
     * (محسوبة من الـ GUI المفتوح فعلياً، فما تعلق لو انقفل بأي طريقة)
     */
    public boolean isArranging() {
        if (!leader.isOnline()) return false;
        org.bukkit.inventory.InventoryHolder h = leader.getOpenInventory().getTopInventory().getHolder();
        return h instanceof com.duel.gui.PartyTeamGUI || h instanceof com.duel.gui.PartyTeamPagedGUI;
    }

    /** True لو أي عضو من البارتي داخل ماتش بارتي (FFA / Teams / بارتي ضد بارتي) */
    public boolean isInMatch() {
        com.duel.managers.PartyDuelManager pdm = DuelPlugin.getInstance().getPartyDuelManager();
        for (UUID u : members) {
            if (pdm.isInPartyDuel(u)) return true;
        }
        return false;
    }

    public boolean hasTeams()           { return !teamTop.isEmpty() || !teamBottom.isEmpty(); }
    public List<UUID> getTeamTop()      { return teamTop; }
    public List<UUID> getTeamBottom()   { return teamBottom; }

    public void setTeams(List<UUID> top, List<UUID> bottom) {
        teamTop.clear();    teamTop.addAll(top);
        teamBottom.clear(); teamBottom.addAll(bottom);
    }

    public void clearTeams() {
        teamTop.clear();
        teamBottom.clear();
    }

    /** يشيل اللاعب من التيمات (عند الخروج/الكيك) — باقي الترتيب يبقى كما هو */
    public void removeFromTeams(UUID uuid) {
        teamTop.remove(uuid);
        teamBottom.remove(uuid);
    }

    public void setPrivacy(PartyPrivacy privacy) {
        this.privacy = privacy;
        broadcastAll(color("&6[Party] &7Privacy set to &e" + privacy.getReadable() + "&7."));
    }

    // ── Getters ───────────────────────────────

    public Player getLeader()            { return leader; }
    public List<UUID> getMembers()       { return members; }
    public List<PartyInvite> getInvites(){ return invites; }
    public PartyPrivacy getPrivacy()     { return privacy; }
    public int getSize()                 { return members.size(); }

    public List<Player> getOnlineMembers() {
        List<Player> list = new ArrayList<>();
        for (UUID uuid : members) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) list.add(p);
        }
        return list;
    }

    public void sendInfo(Player to) {
        StringBuilder sb = new StringBuilder();
        for (UUID uuid : members) {
            Player p = Bukkit.getPlayer(uuid);
            String name = p != null ? p.getName() : ChatColor.GRAY + "(offline:" + uuid.toString().substring(0,8) + ")";
            sb.append(ChatColor.RESET).append(name).append(ChatColor.GRAY).append(", ");
        }
        String names = sb.length() > 2 ? sb.substring(0, sb.length() - 2) : "";

        to.sendMessage(color("&8&m--------------------"));
        to.sendMessage(color("&6&lParty Information"));
        to.sendMessage(color("&eLeader: &f" + leader.getName()));
        to.sendMessage(color("&eMembers (" + members.size() + "): &f" + names));
        to.sendMessage(color("&ePrivacy: &f" + privacy.getReadable()));
        to.sendMessage(color("&8&m--------------------"));
    }

    public void sendChat(Player sender, String message) {
        broadcastAll(color("&6[Party] &r" + sender.getName() + ": &f" + message));
    }

    // ── Messaging ─────────────────────────────

    public void broadcastAll(String msg) {
        for (Player p : getOnlineMembers()) p.sendMessage(msg);
    }

    private void broadcast(String msg, Player... targets) {
        for (Player p : targets) if (p != null && p.isOnline()) p.sendMessage(msg);
    }

    // ── Init (expire task) ────────────────────

    public static void init(DuelPlugin plugin) {
        new BukkitRunnable() {
            @Override public void run() {
                // إصلاح 1: نمسح البارتيات اللي قائدها offline
                parties.removeIf(party -> !party.leader.isOnline() && party.getOnlineMembers().isEmpty());
                parties.forEach(party -> party.invites.removeIf(PartyInvite::hasExpired));
            }
        }.runTaskTimer(plugin, 40L, 40L);
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
