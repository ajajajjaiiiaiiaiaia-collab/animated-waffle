package com.duel.managers;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import com.duel.utils.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.*;

/**
 * Multi-step arena setup (Flow المحسّن):
 * ─────────────────────────────────────────────────────────────
 * 1. WORLD_COUNT    — الأدمن يكتب عدد العوالم
 * 2. WORLD_NAME     — يكتب اسم العالم الحالي (واحد تلو الآخر)
 *                     ← بعد كل اسم ينتقل فوراً لذلك العالم
 * 3. SPAWN_SELECT   — يحدد P1 ثم P2 بالفأس الذهبي
 *                     ← بعد P2 ينتقل تلقائياً للعالم التالي (Step 2)
 *                     ← بعد آخر عالم → finishSetup
 * ─────────────────────────────────────────────────────────────
 */
public class ConversationManager implements Listener {

    private final DuelPlugin plugin;

    enum Step { WORLD_COUNT, WORLD_NAME, SPAWN_SELECT }

    // حالة كل أدمن
    private final Map<UUID, Step>         step          = new HashMap<>();
    private final Map<UUID, Arena>        arenaMap      = new HashMap<>();
    private final Map<UUID, Integer>      totalWorlds   = new HashMap<>();  // العدد الكلي المطلوب
    private final Map<UUID, List<String>> doneWorlds    = new HashMap<>();  // العوالم اللي خلصنا منها
    private final Map<UUID, Integer>      spawnIndex    = new HashMap<>();  // 0 = P1, 1 = P2
    private final Map<UUID, String>       currentWorld  = new HashMap<>();  // العالم الحالي اللي شغال عليه

    public ConversationManager(DuelPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ─────────────────────────────────────────
    //  Entry point — يُستدعى من ArenaEditorGUI بعد الحفظ
    // ─────────────────────────────────────────

    public void startWorldSetup(Player admin, Arena arena) {
        UUID uid = admin.getUniqueId();
        // امسح بيانات العوالم القديمة حتى لا تتراكم عند إعادة الإعداد
        arena.getWorlds().clear();
        arena.getSpawnPoints().clear();
        arenaMap.put(uid, arena);
        doneWorlds.put(uid, new ArrayList<>());
        step.put(uid, Step.WORLD_COUNT);

        admin.sendMessage(color("&7━━━━━━━━━━━━━━━━━━━━━━━━"));
        admin.sendMessage(color("&b⚙ Arena Setup &8— &e" + arena.getId()));
        admin.sendMessage(color("&7كم عالم يملك هذه الأرينا؟ &8(اكتب رقم)"));
    }

    // ─────────────────────────────────────────
    //  Chat listener
    // ─────────────────────────────────────────

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uid = player.getUniqueId();
        if (!step.containsKey(uid)) return;
        event.setCancelled(true);
        String msg = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Step current = step.get(uid);
            if      (current == Step.WORLD_COUNT) handleWorldCount(player, msg);
            else if (current == Step.WORLD_NAME)  handleWorldName(player, msg);
        });
    }

    // خطوة 1: عدد العوالم
    private void handleWorldCount(Player admin, String msg) {
        UUID uid = admin.getUniqueId();
        int count;
        try {
            count = Integer.parseInt(msg);
            if (count <= 0 || count > 20) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            admin.sendMessage(color("&cرقم غير صحيح! أدخل رقماً بين 1 و 20."));
            return;
        }
        totalWorlds.put(uid, count);
        step.put(uid, Step.WORLD_NAME);

        admin.sendMessage(color("&a✔ ممتاز! اكتب اسم العالم &e#1&a:"));
    }

    // خطوة 2: اسم العالم الحالي ← ثم ينتقل له فوراً
    private void handleWorldName(Player admin, String msg) {
        UUID uid = admin.getUniqueId();
        String worldName = msg.trim();

        // سجّل العالم الحالي
        currentWorld.put(uid, worldName);

        // حاول تحمّل العالم إذا مو محمّل
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            admin.sendMessage(color("&eجاري تحميل العالم &f" + worldName + " &e..."));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv load " + worldName);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                World loaded = Bukkit.getWorld(worldName);
                if (loaded == null) {
                    admin.sendMessage(color("&c✘ ما قدرت أحمل العالم '&e" + worldName
                            + "&c'. تأكد إنه موجود في Multiverse."));
                    admin.sendMessage(color("&7اكتب اسم العالم مرة ثانية:"));
                    // نبقى في Step.WORLD_NAME — الأدمن يعيد الكتابة
                } else {
                    step.put(uid, Step.SPAWN_SELECT);
                    spawnIndex.put(uid, 0);
                    teleportAndStartSpawns(admin);
                }
            }, 40L);
            return;
        }

        // العالم محمّل — انتقل مباشرة لمرحلة تحديد الـ Spawns
        step.put(uid, Step.SPAWN_SELECT);
        spawnIndex.put(uid, 0);
        teleportAndStartSpawns(admin);
    }

    // ─────────────────────────────────────────
    //  Spawn Selection
    // ─────────────────────────────────────────

    /**
     * ينقل الأدمن للعالم الحالي ويعطيه الفأس لتحديد الـ Spawn
     */
    private void teleportAndStartSpawns(Player admin) {
        UUID uid = admin.getUniqueId();
        String worldName = currentWorld.get(uid);
        World world = Bukkit.getWorld(worldName);
        List<String> done = doneWorlds.get(uid);
        int total = totalWorlds.get(uid);
        int worldNum = done.size() + 1;           // رقم العالم الحالي (1-based)
        int sIdx = spawnIndex.getOrDefault(uid, 0);
        String playerLabel = sIdx == 0
                ? "&a✦ Player 1 &7(P1)"
                : "&c✦ Player 2 &7(P2)";

        // نقل الأدمن + نظّف ويعطيه الفأس
        admin.teleport(world.getSpawnLocation());
        admin.getInventory().clear();
        admin.getInventory().addItem(ItemUtils.getSpawnAxe(worldName));

        admin.sendMessage(color("&7━━━━━━━━━━━━━━━━━━━━━━━━"));
        admin.sendMessage(color("&bعالم &e" + worldName
                + " &8(" + worldNum + "/" + total + ")"
                + " &8— تحديد نقطة ظهور " + playerLabel));
        admin.sendMessage(color("&7روح للمكان اللي تبي فيه &e"
                + (sIdx == 0 ? "P1" : "P2")
                + " &7وكليك يمين بالفأس الذهبي."));
    }

    @EventHandler
    public void onAxeClick(PlayerInteractEvent event) {
        Player admin = event.getPlayer();
        UUID uid = admin.getUniqueId();
        if (!step.containsKey(uid) || step.get(uid) != Step.SPAWN_SELECT) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        var action = event.getAction();
        if (action != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;

        var item = admin.getInventory().getItemInMainHand();
        if (item.getType() != org.bukkit.Material.GOLDEN_AXE) return;
        event.setCancelled(true);

        Arena arena        = arenaMap.get(uid);
        String worldName   = currentWorld.get(uid);
        int sIdx           = spawnIndex.getOrDefault(uid, 0);
        String label       = sIdx == 0 ? "P1" : "P2";

        // حفظ نقطة الـ Spawn
        org.bukkit.Location spawnLoc = admin.getLocation().clone();
        spawnLoc.setPitch(0f); // رأس مستقيم للاعبين عند الظهور
        arena.addSpawn(worldName, spawnLoc);
        admin.getInventory().removeItem(item);
        admin.sendMessage(color("&a✔ تم تحديد نقطة ظهور &e" + label
                + " &aللعالم &e" + worldName + "&a!"));

        if (sIdx == 0) {
            // انتهى P1 ← اطلب P2
            spawnIndex.put(uid, 1);
            Bukkit.getScheduler().runTaskLater(plugin, () -> teleportAndStartSpawns(admin), 5L);
        } else {
            // انتهى P2 ← هذا العالم خلص، انتقل للتالي
            spawnIndex.put(uid, 0);
            List<String> done = doneWorlds.get(uid);
            done.add(worldName);
            arena.getWorlds().add(worldName);

            // طبّق إعدادات الصعوبة على هذا العالم
            applyDifficulty(arena, worldName);

            int total = totalWorlds.get(uid);
            if (done.size() >= total) {
                // كل العوالم خلصت
                finishSetup(admin);
            } else {
                // اطلب اسم العالم التالي
                int next = done.size() + 1;
                step.put(uid, Step.WORLD_NAME);
                admin.sendMessage(color("&7━━━━━━━━━━━━━━━━━━━━━━━━"));
                admin.sendMessage(color("&a✔ خلصنا من عالم &e" + worldName + " &a("
                        + done.size() + "/" + total + ")"));
                admin.sendMessage(color("&7اكتب اسم العالم &e#" + next + "&7:"));
            }
        }
    }

    // ─────────────────────────────────────────
    //  Finish
    // ─────────────────────────────────────────

    private void finishSetup(Player admin) {
        UUID uid = admin.getUniqueId();
        Arena arena = arenaMap.get(uid);
        plugin.getArenaManager().saveArena(arena);
        cleanup(uid);

        admin.sendMessage(color("&7━━━━━━━━━━━━━━━━━━━━━━━━"));
        admin.sendMessage(color("&a✔✔ الأرينا &e" + arena.getId() + " &atm إعدادها بالكامل!"));
        admin.sendMessage(color("&7العوالم: &f" + arena.getWorlds().size()
                + " &7| نقاط الظهور: &f" + arena.getSpawnPoints().size()));
        admin.sendMessage(color("&7تكسير: &f" + (arena.isAllowBreaking()  ? "نعم" : "لا")
                + " &7| بناء: &f"          + (arena.isAllowBuilding()  ? "نعم" : "لا")
                + " &7| الصعوبة: &f"       + arena.getDifficulty()));
        admin.sendMessage(color("&7استخدم &f/GUI KIT " + arena.getId() + " &7لضبط الكيت."));
        admin.sendMessage(color("&7━━━━━━━━━━━━━━━━━━━━━━━━"));
    }

    private void applyDifficulty(Arena arena, String worldName) {
        org.bukkit.Difficulty diff = switch (arena.getDifficulty()) {
            case "easy" -> org.bukkit.Difficulty.EASY;
            case "hard" -> org.bukkit.Difficulty.HARD;
            default     -> org.bukkit.Difficulty.NORMAL;
        };
        World w = Bukkit.getWorld(worldName);
        if (w != null) w.setDifficulty(diff);
    }

    private void cleanup(UUID uid) {
        step.remove(uid);
        arenaMap.remove(uid);
        totalWorlds.remove(uid);
        doneWorlds.remove(uid);
        spawnIndex.remove(uid);
        currentWorld.remove(uid);
    }

    public boolean isInConversation(Player player) {
        return step.containsKey(player.getUniqueId());
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
