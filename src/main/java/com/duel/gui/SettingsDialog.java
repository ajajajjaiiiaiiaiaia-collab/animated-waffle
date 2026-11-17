package com.duel.gui;

import com.duel.DuelPlugin;
import com.duel.managers.SettingsManager.Setting;
import com.duel.tier.TierTopDialog;
import com.duel.utils.SoundUtils;
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
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** قائمة الإعدادات بنظام Dialog (1.21.6+). كلاينت أقدم/بيدروك يرجع لقائمة الصندوق SettingsGUI. */
public final class SettingsDialog {

    private static final TextColor ON = TextColor.color(0x55FF8A);
    private static final TextColor OFF = TextColor.color(0xFF6B6B);
    private static final TextColor ACCENT = TextColor.color(0x5BD6FF);

    private record Entry(Setting setting, String icon, String name, String desc) {}

    private static final List<Entry> ENTRIES = List.of(
            new Entry(Setting.HIDE_CHAT, "💬", "Hide Chat", "Hides all player chat messages from your screen."),
            new Entry(Setting.HIDE_SCOREBOARD, "📋", "Hide Scoreboard", "Removes the scoreboard from your screen."),
            new Entry(Setting.HIDE_PLAYERS, "👥", "Hide Players", "Makes all other players invisible to you."),
            new Entry(Setting.BLOCK_DUEL_REQUESTS, "⛔", "Block Duel Requests", "Prevents other players from challenging you to a duel."),
            new Entry(Setting.MUTE_SOUNDS, "🔇", "Mute Duel Sounds", "Disables all duel-related sounds for you."),
            new Entry(Setting.HIDE_ACTIONBAR, "📵", "Hide Queue Actionbar", "Hides the queue status bar shown above your hotbar."),
            new Entry(Setting.HIDE_DEATH_MESSAGES, "💀", "Hide Death Messages", "Hides all player death messages from your chat."),
            new Entry(Setting.HIDE_JOIN_QUIT_MESSAGES, "🚪", "Hide Join/Quit", "Hides player join and quit messages from your chat."),
            new Entry(Setting.AURA_GG, "✨", "Aura gg", "Automatically says \"gg\" in chat when you win a duel.")
    );

    private SettingsDialog() {}

    /** يفتح الدايلوج، أو يرجع false لو الكلاينت ما يدعمه (والمستدعي يفتح قائمة الصندوق). */
    public static boolean open(DuelPlugin plugin, Player player) {
        if (TierTopDialog.isBedrock(player) || !TierTopDialog.supportsDialog(player)) return false;
        try {
            show(plugin, player);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("[Settings] dialog failed, falling back to chest GUI: " + t);
            return false;
        }
    }

    private static void show(DuelPlugin plugin, Player player) {
        var sm = plugin.getSettingsManager();

        int enabled = 0;
        for (Entry e : ENTRIES) if (sm.isEnabled(player.getUniqueId(), e.setting())) enabled++;

        Component title = Component.text()
                .append(Component.text("⚙ ", ACCENT))
                .append(Component.text("Player Settings", NamedTextColor.WHITE, TextDecoration.BOLD))
                .build();

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(
                Component.text("Click an option to toggle it.", NamedTextColor.GRAY)
                        .append(Component.newline())
                        .append(Component.text("● ", ON)).append(Component.text("On   ", NamedTextColor.GRAY))
                        .append(Component.text("● ", OFF)).append(Component.text("Off", NamedTextColor.GRAY))
                        .append(Component.text("    •  " + enabled + "/" + ENTRIES.size() + " enabled", NamedTextColor.DARK_GRAY)),
                320));

        ClickCallback.Options opts = ClickCallback.Options.builder()
                .uses(1)
                .lifetime(Duration.ofMinutes(10))
                .build();

        List<ActionButton> buttons = new ArrayList<>();
        for (Entry e : ENTRIES) {
            boolean on = sm.isEnabled(player.getUniqueId(), e.setting());
            Component label = Component.text()
                    .append(Component.text(on ? "● " : "● ", on ? ON : OFF))
                    .append(Component.text(e.icon() + " " + e.name(), on ? NamedTextColor.WHITE : NamedTextColor.GRAY))
                    .build();
            Component tip = Component.text()
                    .append(Component.text(e.name(), NamedTextColor.WHITE, TextDecoration.BOLD))
                    .append(Component.newline())
                    .append(Component.text(e.desc(), NamedTextColor.GRAY))
                    .append(Component.newline()).append(Component.newline())
                    .append(Component.text("Status: ", NamedTextColor.GRAY))
                    .append(on ? Component.text("✔ Enabled", ON) : Component.text("✘ Disabled", OFF))
                    .append(Component.newline())
                    .append(Component.text("Click to " + (on ? "disable" : "enable"), NamedTextColor.YELLOW))
                    .build();
            final Setting setting = e.setting();
            buttons.add(ActionButton.builder(label)
                    .tooltip(tip)
                    .width(150)
                    .action(DialogAction.customClick((view, audience) -> {
                        if (audience instanceof Player p) {
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                sm.toggle(p, setting);
                                SoundUtils.playGuiClick(p);
                                if (p.isOnline()) open(plugin, p);
                            });
                        }
                    }, opts))
                    .build());
        }

        ActionButton close = ActionButton.builder(Component.text("Close", NamedTextColor.RED))
                .width(150)
                .build();

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(buttons, close, 2)));

        player.showDialog(dialog);
    }
}
