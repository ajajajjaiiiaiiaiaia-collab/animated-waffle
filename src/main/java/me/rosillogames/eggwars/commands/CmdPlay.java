package me.rosillogames.eggwars.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import me.rosillogames.eggwars.EggWars;
import me.rosillogames.eggwars.arena.Arena;
import me.rosillogames.eggwars.enums.ArenaStatus;
import me.rosillogames.eggwars.language.TranslationUtils;
import me.rosillogames.eggwars.managers.AutoArenaManager;
import me.rosillogames.eggwars.player.EwPlayer;
import me.rosillogames.eggwars.utils.PlayerUtils;

/**
 * Fully dynamic gamemode join command: "/play <signature>", e.g. "/play 2x2".
 *
 * A gamemode is never configured or named by hand. It is derived purely from each
 * arena's own setup data: signature = "<team count>x<max players per team>".
 *
 *   2 teams, max 2 players each  -> "2x2"
 *   4 teams, max 1 player each   -> "4x1"  (free-for-all style, 1v1v1v1)
 *   3 teams, max 2 players each  -> "3x2"  (2v2v2)
 *
 * Because the signature is computed live from arena.getTeams().size() and
 * arena.getMaxTeamPlayers() - both of which are saved in arena.yml and therefore
 * copied verbatim by the existing /ews cloneArena command - any arena cloned from
 * a "2x2" arena is automatically recognised as "2x2" too, with zero extra setup
 * or naming convention required on the arena itself.
 */
public class CmdPlay implements TabExecutor
{
    public static String signatureOf(Arena arena)
    {
        return arena.getTeams().size() + "x" + arena.getMaxTeamPlayers();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args)
    {
        if (!(sender instanceof Player))
        {
            TranslationUtils.sendMessage("commands.error.only_players", sender);
            return true;
        }

        if (!sender.hasPermission("eggwars.command.play"))
        {
            TranslationUtils.sendMessage("commands.error.no_permission", sender);
            return true;
        }

        if (args.length != 1)
        {
            sender.sendMessage("§cUsage: /play <signature>  (e.g. /play 2x2)");
            return true;
        }

        play((Player)sender, args[0].toLowerCase());
        return true;
    }

    /** Joins the player to a free arena of the gamemode, creating one if needed (see AutoArenaManager). */
    public static void play(Player player, String wantedSignature)
    {
        EwPlayer ewPlayer = PlayerUtils.getEwPlayer(player);

        if (ewPlayer.isInArena())
        {
            TranslationUtils.sendMessage("commands.error.in_arena", player);
            return;
        }

        List<Arena> lobbyCandidates = new ArrayList<>();
        List<Arena> inGameCandidates = new ArrayList<>();

        for (Arena arena : EggWars.getArenaManager().getArenas())
        {
            if (!arena.isSetup())
            {
                continue;
            }

            if (!signatureOf(arena).equalsIgnoreCase(wantedSignature))
            {
                continue;
            }

            if (arena.getStatus().isLobby())
            {
                lobbyCandidates.add(arena);
            }
            else if (arena.getStatus().equals(ArenaStatus.IN_GAME))
            {
                inGameCandidates.add(arena);
            }
        }

        /* Prefer the fullest lobby that still has room so games start fast. */
        Collections.shuffle(lobbyCandidates);
        lobbyCandidates.sort((a, b) -> Integer.compare(b.getPlayers().size(), a.getPlayers().size()));

        for (Arena arena : lobbyCandidates)
        {
            if (!arena.isFull())
            {
                arena.joinArena(ewPlayer, false, false);
                return;
            }
        }

        /* Everything is full or in game: spawn a new arena like Hypixel does. */
        if (AutoArenaManager.requestArena(player, wantedSignature))
        {
            return;
        }

        if (EggWars.config.canSpectJoin && !inGameCandidates.isEmpty())
        {
            Collections.shuffle(inGameCandidates);
            inGameCandidates.get(0).joinArena(ewPlayer, false, true);
            return;
        }

        TranslationUtils.sendMessage("commands.error.no_arenas", player);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args)
    {
        List<String> list = new ArrayList<>();

        if (args.length == 1)
        {
            List<String> signatures = new ArrayList<>();

            for (Arena arena : EggWars.getArenaManager().getArenas())
            {
                if (!arena.isSetup())
                {
                    continue;
                }

                String sig = signatureOf(arena);

                if (!signatures.contains(sig) && sig.toLowerCase().startsWith(args[0].toLowerCase()))
                {
                    signatures.add(sig);
                }
            }

            list.addAll(signatures);
        }

        return list;
    }
}
