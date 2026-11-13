package me.rosillogames.eggwars.managers;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import me.rosillogames.eggwars.EggWars;
import me.rosillogames.eggwars.arena.Arena;
import me.rosillogames.eggwars.commands.CmdPlay;
import me.rosillogames.eggwars.enums.ArenaStatus;
import me.rosillogames.eggwars.language.TranslationUtils;
import me.rosillogames.eggwars.utils.WorldController;

/**
 * Hypixel-style auto scaling: when every arena of a gamemode ("2x2", "4x1"...) is full
 * or in game, a fresh arena is cloned from a template arena of the same gamemode and the
 * waiting players are sent into it. Idle auto-created arenas are deleted again.
 *
 * Auto arenas are recognised only by their folder id ("<template>_autoscale_<n>"), so
 * leftovers from a crash are cleaned up by the same task after a restart.
 *
 * Lag control:
 *  - every file copy / delete runs on ONE low priority background thread, never on the main thread;
 *  - the world save is pre-copied into the temp world folder in the background, so the main
 *    thread only has to load the world (the part Bukkit does not allow off-thread);
 *  - arenas are created one at a time with a short pause in between, even across gamemodes;
 *  - arenas are deleted one per cleanup pass, unloaded without saving, files removed in the background.
 */
public class AutoArenaManager
{
    public static final String ID_MARK = "_autoscale_";
    private static final Pattern AUTO_ID = Pattern.compile(".*" + ID_MARK + "\\d+$");
    /** Ticks to wait between two arena creations so the main thread can breathe. */
    private static final long CREATION_SPACING_TICKS = 10L;

    /** gamemode signature -> players waiting for the arena that is being created. */
    private static final Map<String, List<UUID>> pending = new HashMap<String, List<UUID>>();
    /** auto arena id -> millis since it became empty. */
    private static final Map<String, Long> idleSince = new HashMap<String, Long>();
    private static final Deque<Job> queue = new ArrayDeque<Job>();
    private static boolean running = false;
    private static BukkitTask cleanupTask = null;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r ->
    {
        Thread thread = new Thread(r, "EggWars-AutoArena-IO");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private static class Job
    {
        final String signature;
        final String id;
        final String name;
        final File templateFolder;
        final File cloneFolder;

        Job(String signature, String id, String name, File templateFolder, File cloneFolder)
        {
            this.signature = signature;
            this.id = id;
            this.name = name;
            this.templateFolder = templateFolder;
            this.cloneFolder = cloneFolder;
        }
    }

    public static boolean isAuto(Arena arena)
    {
        return AUTO_ID.matcher(arena.getId()).matches();
    }

    /**
     * Called by /play when no free lobby arena exists for the signature.
     * @return true if an arena is (being) created and the player was queued for it.
     */
    public static boolean requestArena(Player player, String signature)
    {
        if (!EggWars.config.autoScale)
        {
            return false;
        }

        List<UUID> waiting = pending.get(signature);

        if (waiting != null)
        {//an arena for this mode is already being created, just wait for it
            if (!waiting.contains(player.getUniqueId()))
            {
                waiting.add(player.getUniqueId());
            }

            TranslationUtils.sendMessage("commands.play.creating", player);
            return true;
        }

        int count = 0;
        Arena template = null;

        for (Arena arena : EggWars.getArenaManager().getArenas())
        {
            if (!arena.isSetup() || !CmdPlay.signatureOf(arena).equalsIgnoreCase(signature))
            {
                continue;
            }

            ++count;

            if (template == null && !isAuto(arena) && !arena.getStatus().equals(ArenaStatus.SETTING))
            {
                template = arena;
            }
        }

        //autoScaleMax <= 0 means no limit
        if (template == null || (EggWars.config.autoScaleMax > 0 && count >= EggWars.config.autoScaleMax))
        {
            return false;
        }

        int n = 1;

        while (EggWars.getArenaManager().getArenaById(template.getId() + ID_MARK + n) != null || new File(EggWars.arenasFolder, template.getId() + ID_MARK + n).exists())
        {
            ++n;
        }

        String newId = template.getId() + ID_MARK + n;
        List<UUID> list = new ArrayList<UUID>();
        list.add(player.getUniqueId());
        pending.put(signature, list);
        TranslationUtils.sendMessage("commands.play.creating", player);
        queue.add(new Job(signature, newId, template.getName() + " #" + n, template.arenaFolder, new File(EggWars.arenasFolder, newId)));
        startNext();
        return true;
    }

    /** Starts the next queued creation, only one runs at a time. */
    private static void startNext()
    {
        if (running)
        {
            return;
        }

        final Job job = queue.poll();

        if (job == null)
        {
            return;
        }

        running = true;
        final File worldContainer = Bukkit.getWorldContainer();

        IO.execute(() ->
        {
            boolean ok = false;

            try
            {
                ok = prepareFiles(job, worldContainer);
            }
            catch (Exception ex)
            {
                EggWars.instance.getLogger().warning("Could not copy auto arena " + job.name + ": " + ex);
            }

            final boolean copied = ok;

            try
            {
                Bukkit.getScheduler().runTask(EggWars.instance, () ->
                {
                    try
                    {
                        finishCreation(job, copied);
                    }
                    finally
                    {
                        running = false;
                        Bukkit.getScheduler().runTaskLater(EggWars.instance, AutoArenaManager::startNext, CREATION_SPACING_TICKS);
                    }
                });
            }
            catch (IllegalStateException ex)
            {//plugin is being disabled
            }
        });
    }

    /** Background thread: copies the arena folder and pre-copies the world into the temp world folder. */
    private static boolean prepareFiles(Job job, File worldContainer)
    {
        if (!WorldController.copyFiles(job.templateFolder, job.cloneFolder))
        {
            return false;
        }

        String tmpName = WorldController.formatTmpWorldName(job.id);
        File tmpWorld = new File(worldContainer, tmpName);
        WorldController.deleteFiles(tmpWorld);

        if (!WorldController.copyFiles(new File(job.cloneFolder, "world"), tmpWorld))
        {
            return false;
        }

        WorldController.markWorldPrepared(tmpName);
        return true;
    }

    /** Main thread: loads the world (the only heavy part left) and sends the waiting players in. */
    private static void finishCreation(Job job, boolean copied)
    {
        List<UUID> waiting = pending.remove(job.signature);
        boolean created = false;

        if (copied)
        {
            try
            {
                EggWars.getArenaManager().addArena(new Arena(job.cloneFolder, job.name));
                created = true;
            }
            catch (Exception ex)
            {
                EggWars.instance.getLogger().warning("Could not load auto arena " + job.name + ": " + ex);
            }
        }

        if (!created)
        {
            String tmpName = WorldController.formatTmpWorldName(job.id);
            WorldController.unmarkWorldPrepared(tmpName);
            deleteInBackground(new File(Bukkit.getWorldContainer(), tmpName), job.cloneFolder);
        }

        if (waiting == null)
        {
            return;
        }

        for (UUID uuid : waiting)
        {
            Player player = Bukkit.getPlayer(uuid);

            if (player == null || !player.isOnline())
            {
                continue;
            }

            if (created)
            {//goes through the normal flow, so overflow players trigger another arena
                CmdPlay.play(player, job.signature);
            }
            else
            {
                TranslationUtils.sendMessage("commands.play.creation_failed", player);
            }
        }
    }

    public static void startCleanup()
    {
        if (cleanupTask != null)
        {
            return;
        }

        cleanupTask = new BukkitRunnable()
        {
            public void run()
            {
                cleanupIdle();
            }
        }.runTaskTimer(EggWars.instance, 200L, 100L);
    }

    private static void cleanupIdle()
    {
        long now = System.currentTimeMillis();
        long limit = Math.max(10, EggWars.config.autoScaleIdleSeconds) * 1000L;
        boolean removedOne = false;

        for (Arena arena : new ArrayList<Arena>(EggWars.getArenaManager().getArenas()))
        {
            if (!isAuto(arena))
            {
                continue;
            }

            boolean idle = arena.getStatus().isLobby() && arena.getPlayers().isEmpty() && !pending.containsKey(CmdPlay.signatureOf(arena));

            if (!idle)
            {
                idleSince.remove(arena.getId());
                continue;
            }

            Long since = idleSince.get(arena.getId());

            if (since == null)
            {
                idleSince.put(arena.getId(), now);
            }
            else if (now - since >= limit && !removedOne)
            {//only one arena per pass, the rest wait for the next pass (5 seconds later)
                idleSince.remove(arena.getId());
                removeArena(arena);
                removedOne = true;
            }
        }
    }

    private static void removeArena(Arena arena)
    {
        EggWars.getArenaManager().removeArena(arena);
        arena.setStatus(ArenaStatus.SETTING);
        World world = arena.getWorld();
        String tmpName = WorldController.formatTmpWorldName(arena.getId());
        boolean unloaded = true;

        if (world != null)
        {
            for (Entity entity : world.getEntities())
            {
                entity.remove();
            }

            world.removePluginChunkTickets(EggWars.instance);
            //no save: the world is thrown away, saving it would only cost time
            unloaded = Bukkit.unloadWorld(world, false);
        }

        WorldController.unmarkWorldPrepared(tmpName);

        if (unloaded)
        {
            deleteInBackground(new File(Bukkit.getWorldContainer(), tmpName), arena.arenaFolder);
        }
        else
        {//never delete files of a world that is still loaded
            EggWars.instance.getLogger().warning("Could not unload world of auto arena " + arena.getName() + ", its files were kept.");
            deleteInBackground(arena.arenaFolder);
        }
    }

    private static void deleteInBackground(final File... files)
    {
        IO.execute(() ->
        {
            for (File file : files)
            {
                WorldController.deleteFiles(file);
            }
        });
    }
}
