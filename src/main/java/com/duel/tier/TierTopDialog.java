package com.duel.tier;

import com.duel.DuelPlugin;
import com.duel.models.Arena;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TierTopDialog {

    private static final int RANK_PX = 26;
    private static final int NAME_PX = 108;
    private static final int BODY_WIDTH = 330;
    private static final TextColor SILVER = TextColor.color(0xC0C0C0);
    private static final TextColor BRONZE = TextColor.color(0xCD7F32);

    private TierTopDialog() {}

    public static Arena defaultArena(DuelPlugin plugin, Player player) {
        TierManager tm = plugin.getTierManager();
        TierProfile best = tm.getBestPlacedProfile(player.getUniqueId());
        if (best != null) {
            Arena a = plugin.getArenaManager().getArena(best.arenaId);
            if (a != null) return a;
        }
        List<Arena> all = sortedArenas(plugin);
        return all.isEmpty() ? null : all.get(0);
    }

    private static List<Arena> sortedArenas(DuelPlugin plugin) {
        List<Arena> all = new ArrayList<>(plugin.getArenaManager().getAllArenas());
        all.sort((x, y) -> x.getId().compareToIgnoreCase(y.getId()));
        return all;
    }

    private static final int MIN_DIALOG_PROTOCOL = 771; // 1.21.6

    /** true إذا كلاينت اللاعب 1.21.6+ (يعتمد على ViaVersion إذا موجود، وإلا نفترض إنه يدعم). */
    public static boolean supportsDialog(Player player) {
        try {
            Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = via.getMethod("getAPI").invoke(null);
            Object ver = api.getClass().getMethod("getPlayerVersion", UUID.class).invoke(api, player.getUniqueId());
            return ((Number) ver).intValue() >= MIN_DIALOG_PROTOCOL;
        } catch (ClassNotFoundException e) {
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean isBedrock(Player player) {
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            Object res = api.getMethod("isFloodgatePlayer", UUID.class).invoke(inst, player.getUniqueId());
            return Boolean.TRUE.equals(res);
        } catch (Throwable t) {
            return player.getUniqueId().getMostSignificantBits() == 0L;
        }
    }

    public static void open(DuelPlugin plugin, Player player, String arenaId) {
        if (isBedrock(player) || !supportsDialog(player)) {
            com.duel.gui.TierTopGUI.open(plugin, player, arenaId);
            return;
        }
        TierManager tm = plugin.getTierManager();
        if (!tm.isEnabled()) {
            player.sendMessage(Component.text("Tier system is disabled.", NamedTextColor.RED));
            return;
        }
        Arena arena = arenaId == null ? null : plugin.getArenaManager().getArena(arenaId);
        if (arena == null) arena = defaultArena(plugin, player);
        if (arena == null) {
            player.sendMessage(Component.text("No arenas available.", NamedTextColor.RED));
            return;
        }
        final Arena a = arena;
        tm.getRankAsync(player.getUniqueId(), a.getId(), rank -> {
            if (!player.isOnline()) return;
            try {
                show(plugin, player, a, rank);
            } catch (Throwable t) {
                plugin.getLogger().warning("[TierTop] dialog failed: " + t);
            }
        });
    }

    private static void show(DuelPlugin plugin, Player player, Arena arena, int myRank) {
        TierManager tm = plugin.getTierManager();
        Component icon = TierIcon.component(plugin, arena);

        Component title = Component.text()
                .append(icon)
                .append(Component.space())
                .append(Component.text(arena.getId().toUpperCase(), NamedTextColor.AQUA, TextDecoration.BOLD))
                .build();

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(
                Component.text("Season " + tm.getSeason() + "  \u2022  Top 10 by rating", NamedTextColor.GRAY),
                BODY_WIDTH));

        List<TierManager.TopEntry> top = tm.getTop(arena.getId());
        if (top.isEmpty()) {
            body.add(DialogBody.plainMessage(Component.text("No ranked matches yet.", NamedTextColor.GRAY), BODY_WIDTH));
        } else {
            int i = 1;
            for (TierManager.TopEntry e : top) {
                body.add(DialogBody.plainMessage(
                        row(plugin, i, e.name(), e.uuid(), e.tier(), e.rating(), icon, false),
                        BODY_WIDTH));
                i++;
            }
        }

        TierProfile mine = tm.getFresh(player.getUniqueId(), player.getName(), arena.getId());
        Component mineRow = row(plugin, myRank, player.getName(), player.getUniqueId(), mine.tier, mine.rating, icon, true);
        body.add(DialogBody.plainMessage(
                Component.text("\u2500".repeat(30), NamedTextColor.DARK_GRAY)
                        .append(Component.newline())
                        .append(mineRow),
                BODY_WIDTH));

        List<ActionButton> buttons = new ArrayList<>();
        ClickCallback.Options opts = ClickCallback.Options.builder()
                .uses(ClickCallback.UNLIMITED_USES)
                .lifetime(Duration.ofMinutes(10))
                .build();
        for (Arena other : sortedArenas(plugin)) {
            if (other.getId().equalsIgnoreCase(arena.getId())) continue;
            String shownTier = tm.display(player.getUniqueId(), other.getId()).replace('&', '§');
            Component label = Component.text()
                    .append(TierIcon.component(plugin, other))
                    .append(Component.space())
                    .append(Component.text(other.getId(), NamedTextColor.WHITE))
                    .build();
            Component tip = Component.text()
                    .append(Component.text("Your tier: ", NamedTextColor.GRAY))
                    .append(legacy(shownTier))
                    .append(Component.newline())
                    .append(Component.text("Click to open leaderboard", NamedTextColor.YELLOW))
                    .build();
            final String targetId = other.getId();
            buttons.add(ActionButton.builder(label)
                    .tooltip(tip)
                    .width(150)
                    .action(DialogAction.customClick((view, audience) -> {
                        if (audience instanceof Player p) {
                            Bukkit.getScheduler().runTask(plugin, () -> open(plugin, p, targetId));
                        }
                    }, opts))
                    .build());
        }

        ActionButton exit = ActionButton.builder(Component.text("Close", NamedTextColor.RED))
                .width(150)
                .build();

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body)
                        .build())
                .type(buttons.isEmpty()
                        ? DialogType.notice(exit)
                        : DialogType.multiAction(buttons, exit, 2)));

        player.showDialog(dialog);
    }

    private static Component row(DuelPlugin plugin, int rank, String name, UUID uuid, Tier tier, int rating,
                                 Component icon, boolean me) {
        String rankText = rank > 0 ? "#" + rank : "\u2014";
        TextColor rankColor = me ? NamedTextColor.GREEN
                : rank == 1 ? NamedTextColor.GOLD
                : rank == 2 ? SILVER
                : rank == 3 ? BRONZE
                : NamedTextColor.GRAY;
        TextColor nameColor = me ? NamedTextColor.GREEN : NamedTextColor.WHITE;

        Component flag = null;
        if (plugin.getTabFlagManager() != null && plugin.getCountryManager() != null) {
            flag = plugin.getTabFlagManager().flagHead(plugin.getCountryManager().getCountryCode(uuid));
        }
        if (flag == null) flag = Component.text("  ");

        Component out = Component.text()
                .append(Component.text(pad(rankText, RANK_PX), rankColor))
                .append(flag)
                .append(Component.space())
                .append(Component.text(pad(name, NAME_PX), nameColor))
                .append(icon)
                .append(Component.space())
                .append(legacy(tier.chat()))
                .append(Component.text("  " + rating, NamedTextColor.DARK_GRAY))
                .build();
        return out;
    }

    private static Component legacy(String section) {
        return LegacyComponentSerializer.builder().character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build().deserialize(section)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static int charPx(char c) {
        return switch (c) {
            case 'i', '!', '.', ',', ':', ';', '|' -> 2;
            case 'l', '\'', '`' -> 3;
            case 't', 'I', '[', ']', ' ' -> 4;
            case 'f', 'k', '(', ')', '{', '}', '*', '<', '>', '"' -> 5;
            case '@', '~' -> 7;
            default -> 6;
        };
    }

    private static String pad(String s, int targetPx) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += charPx(s.charAt(i));
        int missing = targetPx - w;
        int spaces = Math.max(1, Math.round(missing / 4.0f));
        return s + " ".repeat(spaces);
    }
}
