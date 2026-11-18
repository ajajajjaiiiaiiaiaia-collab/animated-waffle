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
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** لوحة الصدارة العامة: مجموع نقاط التيرات من كل الكيتات (دايلوج 1.21.6+، وإلا نص بالشات). */
public final class LeaderboardDialog {

    private static final int RANK_PX = 26;
    private static final int NAME_PX = 108;
    private static final int BODY_WIDTH = 330;
    private static final TextColor SILVER = TextColor.color(0xC0C0C0);
    private static final TextColor BRONZE = TextColor.color(0xCD7F32);
    private static final TextColor ACCENT = TextColor.color(0x5BD6FF);
    private static final int MAX_KITS_SHOWN = 6;

    private LeaderboardDialog() {}

    public static void open(DuelPlugin plugin, Player player) {
        TierManager tm = plugin.getTierManager();
        if (tm == null || !tm.isEnabled()) {
            player.sendMessage(Component.text("Tier system is disabled.", NamedTextColor.RED));
            return;
        }
        if (TierTopDialog.isBedrock(player) || !TierTopDialog.supportsDialog(player)) {
            chat(plugin, player);
            return;
        }
        try {
            show(plugin, player);
        } catch (Throwable t) {
            plugin.getLogger().warning("[Leaderboard] dialog failed, using chat: " + t);
            chat(plugin, player);
        }
    }

    private static void show(DuelPlugin plugin, Player player) {
        TierManager tm = plugin.getTierManager();
        Component title = Component.text()
                .append(Component.text("\uD83C\uDFC6 ", ACCENT))
                .append(Component.text("LEADERBOARD", NamedTextColor.WHITE, TextDecoration.BOLD))
                .build();

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(
                Component.text("Season " + tm.getSeason() + "  \u2022  Top 10 by tier points", NamedTextColor.GRAY),
                BODY_WIDTH));

        List<TierManager.OverallEntry> all = tm.getOverall();
        if (all.isEmpty()) {
            body.add(DialogBody.plainMessage(Component.text("No ranked players yet.", NamedTextColor.GRAY), BODY_WIDTH));
        } else {
            int shown = Math.min(10, all.size());
            for (int i = 0; i < shown; i++) {
                body.add(DialogBody.plainMessage(row(plugin, plugin.getArenaManager(), i + 1, all.get(i), false), BODY_WIDTH));
            }
        }

        int myPts = tm.overallPoints(player.getUniqueId());
        int myRank = tm.overallRank(player.getUniqueId());
        String rankText = myRank > 0 ? "#" + myRank : "\u2014";
        Component mine = Component.text()
                .append(Component.text("\u2500".repeat(30), NamedTextColor.DARK_GRAY))
                .append(Component.newline())
                .append(Component.text(pad(rankText, RANK_PX), NamedTextColor.GREEN))
                .append(Component.text(pad(player.getName(), NAME_PX + 12), NamedTextColor.GREEN))
                .append(Component.text(myPts + " pts", NamedTextColor.YELLOW))
                .build();
        body.add(DialogBody.plainMessage(mine, BODY_WIDTH));

        ClickCallback.Options opts = ClickCallback.Options.builder()
                .uses(ClickCallback.UNLIMITED_USES)
                .lifetime(Duration.ofMinutes(10))
                .build();
        ActionButton kits = ActionButton.builder(Component.text("Kit Leaderboards", NamedTextColor.AQUA))
                .tooltip(Component.text("Top 10 by rating for each kit", NamedTextColor.GRAY))
                .width(150)
                .action(DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player p) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> TierTopDialog.open(plugin, p, null));
                    }
                }, opts))
                .build();
        ActionButton close = ActionButton.builder(Component.text("Close", NamedTextColor.RED)).width(150).build();

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(List.of(kits), close, 1)));
        player.showDialog(dialog);
    }

    private static Component row(DuelPlugin plugin, com.duel.managers.ArenaManager arenas, int rank,
                                 TierManager.OverallEntry e, boolean me) {
        TextColor rankColor = me ? NamedTextColor.GREEN
                : rank == 1 ? NamedTextColor.GOLD
                : rank == 2 ? SILVER
                : rank == 3 ? BRONZE
                : NamedTextColor.GRAY;
        Component flag = null;
        if (plugin.getTabFlagManager() != null && plugin.getCountryManager() != null) {
            flag = plugin.getTabFlagManager().flagHead(plugin.getCountryManager().getCountryCode(e.uuid()));
        }
        if (flag == null) flag = Component.text("  ");

        net.kyori.adventure.text.TextComponent.Builder b = Component.text()
                .append(Component.text(pad("#" + rank, RANK_PX), rankColor))
                .append(flag)
                .append(Component.space())
                .append(Component.text(pad(e.name(), NAME_PX), me ? NamedTextColor.GREEN : NamedTextColor.WHITE))
                .append(Component.text(e.points() + " pts", NamedTextColor.YELLOW))
                .append(Component.newline())
                .append(Component.text("      ", NamedTextColor.DARK_GRAY));
        int n = 0;
        for (TierManager.KitTier k : e.kits()) {
            if (n++ >= MAX_KITS_SHOWN) { b.append(Component.text(" +" + (e.kits().size() - MAX_KITS_SHOWN), NamedTextColor.DARK_GRAY)); break; }
            Arena a = arenas.getArena(k.arenaId());
            if (a != null) b.append(TierIcon.component(plugin, a)).append(Component.space());
            b.append(legacy(k.tier().chat())).append(Component.text("  ", NamedTextColor.DARK_GRAY));
        }
        return b.build();
    }

    private static void chat(DuelPlugin plugin, Player player) {
        TierManager tm = plugin.getTierManager();
        player.sendMessage(Component.text("\u2500\u2500 Leaderboard (tier points) \u2500\u2500", ACCENT));
        List<TierManager.OverallEntry> all = tm.getOverall();
        if (all.isEmpty()) { player.sendMessage(Component.text("No ranked players yet.", NamedTextColor.GRAY)); return; }
        for (int i = 0; i < Math.min(10, all.size()); i++) {
            TierManager.OverallEntry e = all.get(i);
            player.sendMessage(Component.text("#" + (i + 1) + " ", NamedTextColor.GOLD)
                    .append(Component.text(e.name(), NamedTextColor.WHITE))
                    .append(Component.text("  " + e.points() + " pts", NamedTextColor.YELLOW)));
        }
        int r = tm.overallRank(player.getUniqueId());
        player.sendMessage(Component.text("You: " + (r > 0 ? "#" + r : "\u2014") + "  " + tm.overallPoints(player.getUniqueId()) + " pts", NamedTextColor.GREEN));
    }

    private static Component legacy(String section) {
        return LegacyComponentSerializer.builder().character('\u00a7').hexColors().useUnusualXRepeatedCharacterHexFormat().build()
                .deserialize(section).decoration(TextDecoration.ITALIC, false);
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
