package com.duel.party.command;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import com.duel.party.Party;
import com.duel.party.PartyDuelRequest;
import com.duel.party.PartyPrivacy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class PartyCommand implements CommandExecutor, TabCompleter {

    private final DuelPlugin plugin;

    /** targetPartyLeaderUUID → طلب الدول المعلّق */
    private final Map<UUID, PartyDuelRequest> pendingRequests = new HashMap<>();

    public PartyCommand(DuelPlugin plugin) {
        this.plugin = plugin;

        // نظّف الطلبات المنتهية كل 10 ثوان
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                () -> pendingRequests.entrySet().removeIf(e -> e.getValue().hasExpired()),
                200L, 200L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(player);
            return true;
        }

        // ── Party Maintenance Check ──
        if (plugin.getConfig().getBoolean("party.maintenance", false)
                && !player.hasPermission("duelplugin.admin")) {
            player.sendMessage(color("&8[&6Party&8] &cThe party system is currently under maintenance. Please try again later."));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create"      -> handleCreate(player);
            case "disband"     -> handleDisband(player);
            case "invite"      -> handleInvite(player, args);
            case "join"        -> handleJoin(player, args);
            case "leave"       -> handleLeave(player);
            case "kick"        -> handleKick(player, args);
            case "info"        -> handleInfo(player);
            case "open"        -> handlePrivacy(player, PartyPrivacy.OPEN);
            case "close"       -> handlePrivacy(player, PartyPrivacy.CLOSED);
            case "chat"        -> handleChat(player, args);
            case "duel"        -> handleDuel(player, args);
            case "duelaccept"  -> handleDuelAccept(player);
            case "dueldecline" -> handleDuelDecline(player);
            case "ffa"         -> handleFFA(player, args);
            default            -> sendHelp(player);
        }
        return true;
    }

    // ── Party duel ────────────────────────────────────

    /**
     * /party ffa [arenaId]
     * القائد يبدأ FFA داخل البارتي — كل عضو ضد الكل.
     */
    private void handleFFA(Player player, String[] args) {
        Party myParty = plugin.getPartyManager().getParty(player);
        if (myParty == null) { player.sendMessage(color("&cYou are not in a party.")); return; }
        if (!myParty.getLeader().equals(player)) { player.sendMessage(color("&cOnly the leader can start the FFA.")); return; }
        if (myParty.getSize() < 2) { player.sendMessage(color("&cThe party needs at least two players.")); return; }

        // تحديد الأرينا
        Arena arena = null;
        if (args.length >= 2) {
            arena = plugin.getArenaManager().getArena(args[1]);
            if (arena == null) { player.sendMessage(color("&cArena &e" + args[1] + " &cdoes not exist.")); return; }
        } else {
            Collection<Arena> all = plugin.getArenaManager().getAllArenas();
            if (all.isEmpty()) { player.sendMessage(color("&cThere are no configured arenas.")); return; }
            arena = all.stream().skip(new Random().nextInt(all.size())).findFirst().orElse(all.iterator().next());
        }

        // لو القائد مسوّي تيمات (من GUI: Party FFA → Team) يصير ماتش تيمات، وإلا FFA عادي
        plugin.getPartyDuelManager().startPartyMatch(myParty, arena);
    }

    /**
     * /party duel <leaderOfOtherParty> [arenaId]
     * يستدعيها قائد البارتي لتحدّي بارتي أخرى.
     */
    private void handleDuel(Player player, String[] args) {
        Party myParty = plugin.getPartyManager().getParty(player);
        if (myParty == null) { player.sendMessage(color("&cYou are not in a party.")); return; }
        if (!myParty.getLeader().equals(player)) { player.sendMessage(color("&cOnly the leader can start a party duel.")); return; }
        if (myParty.getSize() < 2) { player.sendMessage(color("&cThe party needs at least two players.")); return; }

        if (args.length < 2) { player.sendMessage(color("&cUsage: /party duel <opposing party leader> [arena]")); return; }

        Player targetLeader = Bukkit.getPlayerExact(args[1]);
        if (targetLeader == null) { player.sendMessage(color("&cPlayer &e" + args[1] + " &cis not online.")); return; }
        if (targetLeader.equals(player)) { player.sendMessage(color("&cYou cannot challenge yourself.")); return; }

        Party targetParty = Party.getByLeader(targetLeader);
        if (targetParty == null) { player.sendMessage(color("&c&e" + targetLeader.getName() + " &cis not the leader of any party.")); return; }
        if (targetParty.equals(myParty)) { player.sendMessage(color("&cYou cannot challenge your own party.")); return; }

        // تأكد من وجود أرينا
        Arena arena = null;
        if (args.length >= 3) {
            arena = plugin.getArenaManager().getArena(args[2]);
            if (arena == null) { player.sendMessage(color("&cArena &e" + args[2] + " &cdoes not exist.")); return; }
        } else {
            // اختر أرينا عشوائية
            Collection<Arena> allArenas = plugin.getArenaManager().getAllArenas();
            if (allArenas.isEmpty()) { player.sendMessage(color("&cThere are no configured arenas.")); return; }
            arena = allArenas.iterator().next();
        }

        // أرسل الطلب مع حفظ الأرينا المختارة
        final Arena finalArena = arena;
        PartyDuelRequest request = new PartyDuelRequest(myParty, targetParty, finalArena);
        pendingRequests.put(targetLeader.getUniqueId(), request);

        // إخبار الطرفين
        myParty.broadcastAll(color("&6[Party Duel] &7You sent a challenge to &e" + targetLeader.getName() + "&7's party | Expires in 30 seconds."));
        targetParty.broadcastAll(color("&6[Party Duel] &7" + player.getName() + "&7's party has challenged you! Use &a/party duelaccept &7or &c/party dueldecline"));

        // إلغاء تلقائي بعد 30 ثانية
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PartyDuelRequest r = pendingRequests.get(targetLeader.getUniqueId());
            if (r != null && r.hasExpired()) {
                pendingRequests.remove(targetLeader.getUniqueId());
                if (player.isOnline())        player.sendMessage(color("&c[Party Duel] The challenge has expired."));
                if (targetLeader.isOnline())  targetLeader.sendMessage(color("&c[Party Duel] The challenge has ended."));
            }
        }, 600L); // 30 ثانية
    }

    private void handleDuelAccept(Player player) {
        Party myParty = plugin.getPartyManager().getParty(player);
        if (myParty == null) { player.sendMessage(color("&cYou are not in a party.")); return; }
        if (!myParty.getLeader().equals(player)) { player.sendMessage(color("&cOnly the leader can accept the challenge.")); return; }

        PartyDuelRequest request = pendingRequests.remove(player.getUniqueId());
        if (request == null || request.hasExpired()) {
            player.sendMessage(color("&cThere is no pending challenge, or it has expired."));
            return;
        }

        Party senderParty = request.getSenderParty();

        // استخدم الأرينا التي اختارها المتحدي
        Arena arena = request.getArena();
        if (arena == null) {
            player.sendMessage(color("&cThe requested arena is no longer available."));
            return;
        }

        // ابدأ الدول
        plugin.getPartyDuelManager().startPartyDuel(
                senderParty.getOnlineMembers(),
                myParty.getOnlineMembers(),
                arena
        );
    }

    private void handleDuelDecline(Player player) {
        Party myParty = plugin.getPartyManager().getParty(player);
        if (myParty == null) { player.sendMessage(color("&cYou are not in a party.")); return; }
        if (!myParty.getLeader().equals(player)) { player.sendMessage(color("&cOnly the leader can decline the challenge.")); return; }

        PartyDuelRequest request = pendingRequests.remove(player.getUniqueId());
        if (request == null) { player.sendMessage(color("&cThere is no pending challenge.")); return; }

        myParty.broadcastAll(color("&c[Party Duel] You declined the challenge."));
        request.getSenderParty().broadcastAll(color("&c[Party Duel] The other party declined your challenge."));
    }

    // ── Subcommand handlers (original) ───────────────────

    private void handleCreate(Player player) {
        if (plugin.getPartyManager().isInParty(player)) {
            player.sendMessage(color("&cYou are already in a party."));
            return;
        }
        if (plugin.getDuelManager().isInDuel(player)) {
            player.sendMessage(color("&cYou cannot create a party while in a duel."));
            return;
        }
        // FIX: اللاعب في الكيو أو عنده دعوة دول معلّقة يطلع منها أول — وإلا يبقى بالكيو وهو في بارتي
        if (plugin.getQueueManager().isQueued(player)) plugin.getQueueManager().removeFromQueue(player);
        plugin.getDuelRequestManager().cancelAllFor(player);
        Party party = new Party(player);
        plugin.getPartyManager().updatePlayerParty(player, party);
        player.sendMessage(color("&6[Party] &aParty created! Use &e/party invite <player> &ato invite someone."));
    }

    private void handleDisband(Player player) {
        Party party = plugin.getPartyManager().getParty(player);
        if (party == null) { player.sendMessage(color("&cYou are not in a party.")); return; }
        if (!party.getLeader().equals(player)) { player.sendMessage(color("&cOnly the leader can disband the party.")); return; }
        party.disband();
    }

    private void handleInvite(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(color("&cUsage: /party invite <player>")); return; }

        Party party = plugin.getPartyManager().getParty(player);
        if (party == null) { player.sendMessage(color("&cYou are not in a party.")); return; }
        if (!party.getLeader().equals(player)) { player.sendMessage(color("&cOnly the leader can invite players.")); return; }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { player.sendMessage(color("&cPlayer &e" + args[1] + " &cis not online.")); return; }
        if (target.equals(player)) { player.sendMessage(color("&cYou cannot invite yourself.")); return; }
        if (party.containsPlayer(target.getUniqueId())) { player.sendMessage(color("&cThat player is already in the party.")); return; }
        if (party.getInvite(target.getUniqueId()) != null) { player.sendMessage(color("&cThat player already has a pending invite.")); return; }
        if (party.isInMatch()) { player.sendMessage(color("&c[Party] Your party is currently in a match — you can invite players after it ends.")); return; }
        if (plugin.getDuelManager().isInDuel(target)) { player.sendMessage(color("&cThat player is currently in a duel.")); return; }

        party.invite(target);
    }

    private void handleJoin(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(color("&cUsage: /party join <leader>")); return; }

        if (plugin.getPartyManager().isInParty(player)) { player.sendMessage(color("&cYou are already in a party.")); return; }
        if (plugin.getDuelManager().isInDuel(player)) { player.sendMessage(color("&cYou cannot join a party while in a duel.")); return; }

        Player leader = Bukkit.getPlayerExact(args[1]);
        if (leader == null) { player.sendMessage(color("&cPlayer &e" + args[1] + " &cis not online.")); return; }

        Party party = Party.getByLeader(leader);
        if (party == null) { player.sendMessage(color("&cThat player is not the leader of any party.")); return; }

        if (party.isInMatch()) { player.sendMessage(color("&c[Party] That party is currently in a match — you can't join right now. Try again after it ends.")); return; }

        if (party.isArranging()) { player.sendMessage(color("&c[Party] The leader is arranging the teams — you can't join right now. Try again in a moment.")); return; }

        if (party.getPrivacy() == PartyPrivacy.CLOSED) {
            if (party.getInvite(player.getUniqueId()) == null) {
                player.sendMessage(color("&cYou have not received an invite to this party."));
                return;
            }
        }

        if (party.getSize() >= 32 && !player.hasPermission("duelplugin.party.unlimited")) { player.sendMessage(color("&cThe party is full (max 32).")); return; }

        party.join(player);
    }

    private void handleLeave(Player player) {
        Party party = plugin.getPartyManager().getParty(player);
        if (party == null) { player.sendMessage(color("&cYou are not in a party.")); return; }
        if (party.isArranging()) { player.sendMessage(color("&c[Party] The leader is arranging the teams — you can't leave right now.")); return; }

        // إصلاح 5: القائد يحتاج تأكيد — يطلب requestLeaderLeave مرتين
        if (party.getLeader().equals(player)) {
            party.requestLeaderLeave(player);
        } else {
            party.leave(player, false);
        }
    }

    private void handleKick(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(color("&cUsage: /party kick <player>")); return; }

        Party party = plugin.getPartyManager().getParty(player);
        if (party == null) { player.sendMessage(color("&cYou are not in a party.")); return; }
        if (!party.getLeader().equals(player)) { player.sendMessage(color("&cOnly the leader can kick players.")); return; }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { player.sendMessage(color("&cPlayer &e" + args[1] + " &cis not online.")); return; }
        if (target.equals(player)) { player.sendMessage(color("&cYou cannot kick yourself.")); return; }
        if (!party.containsPlayer(target.getUniqueId())) { player.sendMessage(color("&cThat player is not in your party.")); return; }
        if (party.isArranging()) { player.sendMessage(color("&c[Party] Close the teams menu first — you can't kick while arranging teams.")); return; }

        party.leave(target, true);
    }

    private void handleInfo(Player player) {
        Party party = plugin.getPartyManager().getParty(player);
        if (party == null) { player.sendMessage(color("&cYou are not in a party.")); return; }
        party.sendInfo(player);
    }

    private void handlePrivacy(Player player, PartyPrivacy privacy) {
        Party party = plugin.getPartyManager().getParty(player);
        if (party == null) { player.sendMessage(color("&cYou are not in a party.")); return; }
        if (!party.getLeader().equals(player)) { player.sendMessage(color("&cOnly the leader can change the privacy setting.")); return; }
        party.setPrivacy(privacy);
    }

    private void handleChat(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(color("&cUsage: /party chat <message>")); return; }
        Party party = plugin.getPartyManager().getParty(player);
        if (party == null) { player.sendMessage(color("&cYou are not in a party.")); return; }
        String msg = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        party.sendChat(player, msg);
    }

    // ── Help ──────────────────────────────────

    private void sendHelp(Player player) {
        player.sendMessage(color("&8&m--------------------"));
        player.sendMessage(color("&6&lParty Commands"));
        player.sendMessage(color("&e/party create &7- Create a party"));
        player.sendMessage(color("&e/party disband &7- Disband the party"));
        player.sendMessage(color("&e/party invite <player> &7- Invite a player"));
        player.sendMessage(color("&e/party join <leader> &7- Join a party"));
        player.sendMessage(color("&e/party leave &7- Leave the party"));
        player.sendMessage(color("&e/party kick <player> &7- Kick a member"));
        player.sendMessage(color("&e/party info &7- Party information"));
        player.sendMessage(color("&e/party open|close &7- Party privacy"));
        player.sendMessage(color("&e/party chat <msg> &7- Party chat"));
        player.sendMessage(color("&6&m----------- Duel -----------"));
        player.sendMessage(color("&e/party ffa [arena] &7- FFA within the party (or team match if teams are set)"));
        player.sendMessage(color("&e/team disband &7- Disband the teams"));
        player.sendMessage(color("&e/party duel <party leader> &7- Challenge another party"));
        player.sendMessage(color("&e/party duelaccept &7- Accept a challenge"));
        player.sendMessage(color("&e/party dueldecline &7- Decline a challenge"));
        player.sendMessage(color("&8&m--------------------"));
    }

    // ── Tab complete ──────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("create","disband","invite","join","leave","kick","info","open","close","chat",
                           "ffa","duel","duelaccept","dueldecline","help")
                    .stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("invite") || sub.equals("kick") || sub.equals("join") || sub.equals("duel")) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .filter(n -> !n.equals(sender.getName()))
                        .collect(Collectors.toList());
            }
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("ffa")) {
            return plugin.getArenaManager().getAllArenas().stream()
                    .map(a -> a.getId())
                    .filter(id -> id.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("duel")) {
            return plugin.getArenaManager().getAllArenas().stream()
                    .map(a -> a.getId())
                    .filter(id -> id.toLowerCase().startsWith(args[2].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
