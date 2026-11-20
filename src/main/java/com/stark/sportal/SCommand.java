package com.stark.sportal;

import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class SCommand implements CommandExecutor, TabCompleter {

    private final SPortalPlugin plugin;
    private final PortalManager manager;

    private static final List<String> ROOTS = List.of("portal");
    private static final List<String> SUBS = List.of(
            "startselect", "box", "finish", "cancel", "create", "settype", "setdest", "remove", "list", "info"
    );
    private static final List<String> TYPES = List.of("collision", "touch", "jump", "all");

    public SCommand(SPortalPlugin plugin, PortalManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("portal")) {
            sender.sendMessage("§cUsage: /s portal <startselect|box|finish|cancel|create|settype|setdest|remove|list|info>");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§cUsage: /s portal <startselect|box|finish|cancel|create|settype|setdest|remove|list|info>");
            return true;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "startselect":
                return cmdStartSelect(sender, args);
            case "box":
                return cmdBox(sender);
            case "finish":
                return cmdFinish(sender, args);
            case "cancel":
                return cmdCancel(sender, args);
            case "create":
                return cmdCreate(sender, args);
            case "settype":
                return cmdSetType(sender, args);
            case "setdest":
                return cmdSetDest(sender, args);
            case "remove":
                return cmdRemove(sender, args);
            case "list":
                return cmdList(sender, args);
            case "info":
                return cmdInfo(sender, args);
            default:
                sender.sendMessage("§cUnknown subcommand.");
                return true;
        }
    }

    // ---------- Subcommand implementations ----------

    private boolean cmdStartSelect(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cPlayers only.");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /s portal startselect <name>");
            return true;
        }
        String name = args[2];
        World world = p.getWorld();

        plugin.getHighlighter().clear(p);
        plugin.getPendingSelections().remove(p.getUniqueId());
        manager.startSelection(p, name.toLowerCase(Locale.ROOT), world);
        p.sendMessage("§aSelection started for portal '" + name + "'. Hit blocks (left or right click) to add them (they glow); hit again to remove. Use /s portal finish when done.");
        return true;
    }

    private boolean cmdBox(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cPlayers only.");
            return true;
        }
        if (!manager.hasSession(p)) {
            sender.sendMessage("§cStart a selection first: /s portal startselect <name>");
            return true;
        }
        SelectionSession ss = manager.getSession(p);
        boolean on = !ss.isBoxMode();
        ss.setBoxMode(on);
        ss.setCorner(null);
        plugin.getHighlighter().clearCorner(p);
        if (on) {
            sender.sendMessage("§aBox mode ON: right-click two opposite corners to fill everything between them. Left-click (break) toggles a single block. Run /s portal box again to turn off.");
        } else {
            sender.sendMessage("§aBox mode OFF: click blocks one by one.");
        }
        return true;
    }

    private boolean cmdFinish(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cPlayers only.");
            return true;
        }
        if (!manager.hasSession(p)) {
            sender.sendMessage("§cYou have no active selection.");
            return true;
        }
        SelectionSession session = manager.finishSelection(p);
        if (session.size() == 0) {
            sender.sendMessage("§cSelection had 0 blocks and was discarded.");
            return true;
        }
        plugin.getPendingSelections().put(p.getUniqueId(), session);
        sender.sendMessage("§aSelection finished: " + session.size() + " block(s) saved for portal '" + session.getPortalName()
                + "'. Now run /s portal create " + session.getPortalName() + " <collision|touch|jump|all>");
        return true;
    }

    private boolean cmdCancel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cPlayers only.");
            return true;
        }
        boolean had = manager.hasSession(p) || plugin.getPendingSelections().containsKey(p.getUniqueId());
        if (had) {
            manager.cancelSelection(p);
            plugin.getPendingSelections().remove(p.getUniqueId());
            plugin.getHighlighter().clear(p);
            sender.sendMessage("§aSelection cancelled.");
        } else {
            sender.sendMessage("§cNo active selection to cancel.");
        }
        return true;
    }

    private boolean cmdCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cPlayers only.");
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /s portal create <name> <collision|touch|jump|all>");
            return true;
        }
        String name = args[2].toLowerCase(Locale.ROOT);
        PortalType type = PortalType.fromString(args[3]);
        if (type == null) {
            sender.sendMessage("§cInvalid type. Use one of: collision, touch, jump, all");
            return true;
        }

        SelectionSession session = plugin.getPendingSelections().remove(p.getUniqueId());
        if (session == null || !session.getPortalName().equals(name)) {
            sender.sendMessage("§cNo finished selection found for '" + name + "'. Run /s portal startselect " + name + " first, then /s portal finish.");
            return true;
        }

        if (manager.exists(name)) {
            sender.sendMessage("§cA portal named '" + name + "' already exists.");
            return true;
        }

        manager.createPortal(name, session.getWorld(), session.getBlocks(), type);
        plugin.getHighlighter().clear(p);
        sender.sendMessage("§aPortal '" + name + "' created with " + session.getBlocks().size() + " block(s), type=" + type.name()
                + ". Now go stand at the destination and run /s portal setdest " + name);
        return true;
    }

    private boolean cmdSetType(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /s portal settype <name> <collision|touch|jump|all>");
            return true;
        }
        String name = args[2];
        if (!manager.exists(name)) {
            sender.sendMessage("§cNo such portal: " + name);
            return true;
        }
        PortalType type = PortalType.fromString(args[3]);
        if (type == null) {
            sender.sendMessage("§cInvalid type. Use one of: collision, touch, jump, all");
            return true;
        }
        manager.setType(name, type);
        sender.sendMessage("§aPortal '" + name + "' type set to " + type.name());
        return true;
    }

    private boolean cmdSetDest(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cPlayers only.");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /s portal setdest <name>");
            return true;
        }
        String name = args[2];
        if (!manager.exists(name)) {
            sender.sendMessage("§cNo such portal: " + name);
            return true;
        }
        manager.setDestination(name, p.getLocation());
        sender.sendMessage("§aDestination for '" + name + "' set to your current position. Portal is now active.");
        return true;
    }

    private boolean cmdRemove(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /s portal remove <name>");
            return true;
        }
        String name = args[2];
        if (manager.removePortal(name)) {
            sender.sendMessage("§aPortal '" + name + "' removed.");
        } else {
            sender.sendMessage("§cNo such portal: " + name);
        }
        return true;
    }

    private boolean cmdList(CommandSender sender, String[] args) {
        if (manager.all().isEmpty()) {
            sender.sendMessage("§7No portals created yet.");
            return true;
        }
        sender.sendMessage("§aPortals (" + manager.all().size() + "):");
        for (Portal portal : manager.all()) {
            sender.sendMessage(" §7- §f" + portal.getName() + " §7[" + portal.getType() + "] world=" + portal.getWorld()
                    + " blocks=" + portal.getBlocks().size() + " dest=" + (portal.isDestSet() ? "set" : "§cnot set"));
        }
        return true;
    }

    private boolean cmdInfo(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /s portal info <name>");
            return true;
        }
        Portal portal = manager.get(args[2]);
        if (portal == null) {
            sender.sendMessage("§cNo such portal: " + args[2]);
            return true;
        }
        sender.sendMessage("§aPortal: " + portal.getName());
        sender.sendMessage(" §7Type: §f" + portal.getType());
        sender.sendMessage(" §7World: §f" + portal.getWorld());
        sender.sendMessage(" §7Blocks: §f" + portal.getBlocks().size());
        sender.sendMessage(" §7Destination set: §f" + portal.isDestSet());
        return true;
    }

    // ---------- Tab completion ----------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();

        if (args.length == 1) {
            return filter(ROOTS, args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("portal")) {
            return filter(SUBS, args[1]);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("portal")) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("settype") || sub.equals("setdest") || sub.equals("remove") || sub.equals("info")) {
                return filter(manager.all().stream().map(Portal::getName).collect(Collectors.toList()), args[2]);
            }
            if (sub.equals("create")) {
                // for create, suggest the pending selection's name if any
                if (sender instanceof Player p) {
                    SelectionSession pending = plugin.getPendingSelections().get(p.getUniqueId());
                    if (pending != null) {
                        return filter(List.of(pending.getPortalName()), args[2]);
                    }
                }
                return out;
            }
            return out;
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("portal")) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("create") || sub.equals("settype")) {
                return filter(TYPES, args[3]);
            }
        }

        return out;
    }

    private List<String> filter(List<String> options, String current) {
        String lower = current.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower))
                .collect(Collectors.toList());
    }
}
