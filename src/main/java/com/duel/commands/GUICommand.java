package com.duel.commands;

import com.duel.DuelPlugin;
import com.duel.gui.ArenaEditorGUI;
import com.duel.gui.KitEditorGUI;
import com.duel.gui.TopGUI;
import com.duel.managers.HologramManager;
import com.duel.managers.StatsManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class GUICommand implements CommandExecutor, TabCompleter {

    private final DuelPlugin plugin;

    public GUICommand(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (!player.hasPermission("duelplugin.admin")) {
            player.sendMessage(color("&cYou don't have permission."));
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        switch (args[0].toLowerCase()) {

            // /GUI create <arenaId>
            case "create" -> {
                if (args.length < 2) {
                    player.sendMessage(color("&cUsage: /GUI create <arenaName>"));
                    return true;
                }
                String arenaId = args[1];
                if (!arenaId.matches("[a-zA-Z0-9_]+")) {
                    player.sendMessage(color("&cArena name can only contain letters, numbers, and underscores."));
                    return true;
                }
                ArenaEditorGUI.open(plugin, player, arenaId);
                player.sendMessage(color("&aOpening editor for arena &e" + arenaId + "&a..."));
            }

            // /GUI spawn
            case "spawn" -> {
                if (args.length < 2) {
                    player.sendMessage(color("&cUsage: /GUI spawn <arenaName>"));
                    return true;
                }
                String arenaId = args[1];
                var arena = plugin.getArenaManager().getArena(arenaId);
                if (arena == null) {
                    player.sendMessage(color("&cArena &e" + arenaId + "&c not found. Create it first with /GUI create."));
                    return true;
                }
                plugin.getConversationManager().startWorldSetup(player, arena);
            }

            // /GUI KIT <name>
            case "kit" -> {
                if (args.length < 2) {
                    player.sendMessage(color("&cUsage: /GUI KIT <kitName>"));
                    player.sendMessage(color("&7Available arenas: &f" +
                            String.join(", ", plugin.getArenaManager().getAllArenas()
                                    .stream().map(a -> a.getId()).collect(Collectors.toList()))));
                    return true;
                }
                String kitName = args[1];
                KitEditorGUI.open(plugin, player, kitName);
                player.sendMessage(color("&aOpening kit editor for &e" + kitName + "&a..."));
            }

            // /GUI top <arenaId> <wins|losses|streak>
            case "top" -> {
                if (args.length < 3) {
                    player.sendMessage(color("&cUsage: /GUI top <arena|global> <wins|losses|streak|points>"));
                    return true;
                }
                String arenaId = args[1];
                if (!arenaId.equalsIgnoreCase("global") && plugin.getArenaManager().getArena(arenaId) == null) {
                    player.sendMessage(color("&cArena &e" + arenaId + " &cnot found."));
                    return true;
                }
                if (arenaId.equalsIgnoreCase("global")) arenaId = "global";
                StatsManager.TopType topType = switch (args[2].toLowerCase()) {
                    case "wins"   -> StatsManager.TopType.WINS;
                    case "losses" -> StatsManager.TopType.LOSSES;
                    case "streak" -> StatsManager.TopType.STREAK;
                    case "points" -> StatsManager.TopType.POINTS;
                    default -> null;
                };
                if (topType == null) {
                    player.sendMessage(color("&cInvalid type. Use: &ewins&c, &elosses&c, or &estreak&c."));
                    return true;
                }
                // Spawn hologram at player's location
                plugin.getHologramManager().spawnOrUpdate(
                        player.getLocation(), arenaId, topType);
                player.sendMessage(color("&a🏆 Leaderboard hologram spawned for &e"
                        + arenaId + " &a— &e" + args[2] + "&a."));
                player.sendMessage(color("&7Use &f/GUI top &7again at a different spot to move it."));
            }

            // /GUI map <arena> list
            // /GUI map <arena> <world> name <display name...>
            // /GUI map <arena> <world> icon          (uses the item in your main hand)
            case "map" -> handleMap(player, args);

            // /GUI potion <arena> <add|remove|list|clear> ...
            case "potion" -> handlePotion(player, args);

            // /GUI delete <arenaId>
            case "delete" -> {
                if (args.length < 2) {
                    player.sendMessage(color("&cUsage: /GUI delete <arenaName>"));
                    return true;
                }
                String arenaId = args[1];
                if (plugin.getArenaManager().getArena(arenaId) == null) {
                    player.sendMessage(color("&cArena &e" + arenaId + " &cnot found."));
                    return true;
                }
                boolean deleted = plugin.getArenaManager().deleteArena(arenaId);
                if (deleted) {
                    player.sendMessage(color("&a✔ Arena &e" + arenaId + " &ahas been permanently deleted."));
                } else {
                    player.sendMessage(color("&cFailed to delete arena &e" + arenaId + "&c. Check console."));
                }
            }

            default -> sendHelp(player);
        }

        return true;
    }

    // ─────────────────────────────────────────
    //  /GUI map — icon + display name per map (world)
    // ─────────────────────────────────────────

    private void handleMap(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(color("&cUsage: /GUI map <arena> list"));
            player.sendMessage(color("&cUsage: /GUI map <arena> <world> name <display name>"));
            player.sendMessage(color("&cUsage: /GUI map <arena> <world> icon &7(hold the item)"));
            return;
        }
        var arena = plugin.getArenaManager().getArena(args[1]);
        if (arena == null) {
            player.sendMessage(color("&cArena &e" + args[1] + " &cnot found."));
            return;
        }

        if (args[2].equalsIgnoreCase("list")) {
            if (arena.getWorlds().isEmpty()) {
                player.sendMessage(color("&7No worlds configured for &e" + arena.getId()));
                return;
            }
            player.sendMessage(color("&b⚔ Maps of &e" + arena.getId() + "&b:"));
            for (String w : arena.getWorlds()) {
                player.sendMessage(color("&7- &f" + w + " &8→ &d" + arena.getWorldDisplayName(w)
                        + " &8(" + arena.getWorldIcon(w).name() + ")"));
            }
            return;
        }

        String world = args[2];
        if (!arena.getWorlds().contains(world)) {
            player.sendMessage(color("&cWorld &e" + world + " &cis not part of arena &e" + arena.getId() + "&c."));
            player.sendMessage(color("&7Use &f/GUI map " + arena.getId() + " list &7to see its worlds."));
            return;
        }
        if (args.length < 4) {
            player.sendMessage(color("&cUsage: /GUI map " + arena.getId() + " " + world + " <name|icon> ..."));
            return;
        }

        switch (args[3].toLowerCase()) {
            case "name" -> {
                if (args.length < 5) {
                    player.sendMessage(color("&cUsage: /GUI map " + arena.getId() + " " + world + " name <display name>"));
                    return;
                }
                String display = String.join(" ", Arrays.copyOfRange(args, 4, args.length)).trim();
                if (display.length() > 32) {
                    player.sendMessage(color("&cDisplay name is too long (max 32 characters)."));
                    return;
                }
                arena.getOrCreateWorldMeta(world).setDisplayName(display);
                plugin.getArenaManager().saveWorldMeta(arena, world);
                player.sendMessage(color("&a✔ Display name of &e" + world + " &aset to &d" + display
                        + " &7(real world name unchanged)"));
            }
            case "icon" -> {
                var hand = player.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) {
                    player.sendMessage(color("&cHold the item you want as the icon in your main hand."));
                    return;
                }
                arena.getOrCreateWorldMeta(world).setIcon(hand.getType().name());
                plugin.getArenaManager().saveWorldMeta(arena, world);
                player.sendMessage(color("&a✔ Icon of &e" + world + " &aset to &b" + hand.getType().name()));
            }
            default -> player.sendMessage(color("&cUse &ename &cor &eicon&c."));
        }
    }

    // ─────────────────────────────────────────
    //  /GUI potion — بوشنات لكل أرينا (تنعطى فور النقل للأرينا)
    // ─────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private List<String> effectNames() {
        List<String> out = new ArrayList<>();
        for (org.bukkit.potion.PotionEffectType t : org.bukkit.potion.PotionEffectType.values()) {
            if (t != null) out.add(t.getKey().getKey());
        }
        return out;
    }

    private void handlePotion(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(color("&cUsage: /GUI potion <arena> list"));
            player.sendMessage(color("&cUsage: /GUI potion <arena> add <effect> [level] [seconds|infinite]"));
            player.sendMessage(color("&cUsage: /GUI potion <arena> remove <effect>"));
            player.sendMessage(color("&cUsage: /GUI potion <arena> clear"));
            return;
        }
        var arena = plugin.getArenaManager().getArena(args[1]);
        if (arena == null) {
            player.sendMessage(color("&cArena &e" + args[1] + " &cnot found."));
            return;
        }

        switch (args[2].toLowerCase()) {
            case "list" -> {
                if (arena.getPotions().isEmpty()) {
                    player.sendMessage(color("&7No potion effects set for &e" + arena.getId()));
                    return;
                }
                player.sendMessage(color("&b🧪 Potions of &e" + arena.getId() + "&b:"));
                for (var st : arena.getPotions().values()) {
                    player.sendMessage(color("&7- &f" + st.getEffect() + " &8| &dLevel " + (st.getAmplifier() + 1)
                            + " &8| &e" + (st.isInfinite() ? "infinite" : st.getDurationSeconds() + "s")));
                }
            }
            case "add" -> {
                if (args.length < 4) {
                    player.sendMessage(color("&cUsage: /GUI potion " + arena.getId() + " add <effect> [level] [seconds|infinite]"));
                    return;
                }
                String key = args[3].toLowerCase();
                var type = org.bukkit.potion.PotionEffectType.getByKey(org.bukkit.NamespacedKey.minecraft(key));
                if (type == null) {
                    player.sendMessage(color("&cUnknown effect &e" + args[3] + "&c. Use tab-complete to see the names."));
                    return;
                }
                int level = 1;
                if (args.length >= 5) {
                    try { level = Integer.parseInt(args[4]); }
                    catch (NumberFormatException e) {
                        player.sendMessage(color("&cLevel must be a number (1-255)."));
                        return;
                    }
                    if (level < 1 || level > 255) {
                        player.sendMessage(color("&cLevel must be between 1 and 255."));
                        return;
                    }
                }
                int seconds = -1; // infinite
                if (args.length >= 6 && !args[5].equalsIgnoreCase("infinite")) {
                    try { seconds = Integer.parseInt(args[5]); }
                    catch (NumberFormatException e) {
                        player.sendMessage(color("&cSeconds must be a number or &einfinite&c."));
                        return;
                    }
                    if (seconds < 1 || seconds > 86400) {
                        player.sendMessage(color("&cSeconds must be between 1 and 86400 (or infinite)."));
                        return;
                    }
                }
                var st = new com.duel.models.Arena.PotionSetting(type.getKey().getKey(), level - 1, seconds);
                arena.getPotions().put(st.getEffect(), st);
                plugin.getArenaManager().saveArenaPotions(arena);
                player.sendMessage(color("&a✔ &e" + st.getEffect() + " &7(Level " + level + ", "
                        + (st.isInfinite() ? "infinite" : seconds + "s") + ") &aadded to &e" + arena.getId()
                        + "&a. Players get it right after being teleported."));
            }
            case "remove" -> {
                if (args.length < 4) {
                    player.sendMessage(color("&cUsage: /GUI potion " + arena.getId() + " remove <effect>"));
                    return;
                }
                String key = args[3].toLowerCase();
                if (arena.getPotions().remove(key) == null) {
                    player.sendMessage(color("&cEffect &e" + key + " &cis not set for this arena."));
                    return;
                }
                plugin.getArenaManager().saveArenaPotions(arena);
                player.sendMessage(color("&a✔ Removed &e" + key + " &afrom &e" + arena.getId()));
            }
            case "clear" -> {
                arena.getPotions().clear();
                plugin.getArenaManager().saveArenaPotions(arena);
                player.sendMessage(color("&a✔ All potion effects cleared for &e" + arena.getId()));
            }
            default -> player.sendMessage(color("&cUse &elist&c, &eadd&c, &eremove &cor &eclear&c."));
        }
    }

    // ─────────────────────────────────────────
    //  Tab completion
    // ─────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.addAll(Arrays.asList("create", "spawn", "KIT", "top", "map", "potion", "delete"));
            return filter(completions, args[0]);
        }

        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "create", "spawn" -> {
                    // Suggest existing arena names
                    plugin.getArenaManager().getAllArenas()
                            .forEach(a -> completions.add(a.getId()));
                    completions.add("<new_arena_name>");
                }
                case "top", "delete", "map", "potion" -> {
                    plugin.getArenaManager().getAllArenas()
                            .forEach(a -> completions.add(a.getId()));
                    if (args[0].equalsIgnoreCase("top")) completions.add("global");
                }
                case "kit" -> {
                    // Suggest kit names = arena names
                    plugin.getArenaManager().getAllArenas()
                            .forEach(a -> completions.add(a.getId()));
                    plugin.getKitManager().getAllKitNames()
                            .forEach(completions::add);
                }
            }
            return filter(completions, args[1]);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("top")) {
            completions.addAll(Arrays.asList("wins", "losses", "streak", "points"));
            return filter(completions, args[2]);
        }

        if (args[0].equalsIgnoreCase("map")) {
            var arena = plugin.getArenaManager().getArena(args[1]);
            if (arena != null) {
                if (args.length == 3) {
                    completions.add("list");
                    completions.addAll(arena.getWorlds());
                    return filter(completions, args[2]);
                }
                if (args.length == 4) {
                    completions.addAll(Arrays.asList("name", "icon"));
                    return filter(completions, args[3]);
                }
            }
        }

        if (args[0].equalsIgnoreCase("potion")) {
            var arena = plugin.getArenaManager().getArena(args[1]);
            if (arena != null) {
                if (args.length == 3) {
                    completions.addAll(Arrays.asList("add", "remove", "list", "clear"));
                    return filter(completions, args[2]);
                }
                if (args.length == 4 && args[2].equalsIgnoreCase("add")) {
                    completions.addAll(effectNames());
                    return filter(completions, args[3]);
                }
                if (args.length == 4 && args[2].equalsIgnoreCase("remove")) {
                    completions.addAll(arena.getPotions().keySet());
                    return filter(completions, args[3]);
                }
                if (args.length == 5 && args[2].equalsIgnoreCase("add")) {
                    completions.addAll(Arrays.asList("1", "2", "3"));
                    return filter(completions, args[4]);
                }
                if (args.length == 6 && args[2].equalsIgnoreCase("add")) {
                    completions.addAll(Arrays.asList("infinite", "60", "300", "600"));
                    return filter(completions, args[5]);
                }
            }
        }

        return completions;
    }

    private List<String> filter(List<String> list, String prefix) {
        return list.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }

    private void sendHelp(Player player) {
        player.sendMessage(color("&7━━━━━━━━━━━━━━━━━━━━━━━━"));
        player.sendMessage(color("&b⚔ DuelPlugin &8— &7Commands"));
        player.sendMessage(color("&f/GUI create &e<name> &7— Create/edit an arena"));
        player.sendMessage(color("&f/GUI spawn &e<name>  &7— Reconfigure worlds & spawns"));
        player.sendMessage(color("&f/GUI KIT &e<name>    &7— Create/edit a kit for an arena"));
        player.sendMessage(color("&f/GUI top &e<arena|global> <wins|losses|streak|points> &7— View leaderboard"));
        player.sendMessage(color("&f/GUI map &e<arena> <world> <name|icon> &7— Map display name / icon (or &f<arena> list&7)"));
        player.sendMessage(color("&f/GUI potion &e<arena> <add|remove|list|clear> &7— Potion effects given on arena join"));
        player.sendMessage(color("&f/GUI delete &e<name>   &7— Permanently delete an arena"));
        player.sendMessage(color("&7━━━━━━━━━━━━━━━━━━━━━━━━"));
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
