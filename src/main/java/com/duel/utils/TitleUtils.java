package com.duel.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;

public class TitleUtils {

    private static final Title.Times FAST   = Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(800), Duration.ofMillis(200));
    private static final Title.Times NORMAL = Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(500));
    private static final Title.Times RESULT = Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3000), Duration.ofMillis(700));

    public static void sendCountdown(Player player, int seconds) {
        NamedTextColor color = seconds <= 2 ? NamedTextColor.RED : NamedTextColor.YELLOW;
        Component title = Component.text(String.valueOf(seconds))
                .color(color)
                .decorate(TextDecoration.BOLD);
        Component sub = Component.text("Get ready!")
                .color(NamedTextColor.GRAY);
        player.showTitle(Title.title(title, sub, FAST));
    }

    public static void sendGo(Player player) {
        Component title = Component.text("GO!")
                .color(NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD);
        Component sub = Component.text("Fight!")
                .color(NamedTextColor.YELLOW);
        player.showTitle(Title.title(title, sub, NORMAL));
    }

    public static void sendWinner(Player player) {
        Component title = Component.text("VICTORY")
                .color(NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD);
        Component sub = Component.text("You dominated the arena! ★")
                .color(NamedTextColor.YELLOW);
        player.showTitle(Title.title(title, sub, RESULT));
    }

    /** Round result between rounds of a multi-round match. */
    public static void sendRoundResult(Player player, boolean won, int selfScore, int oppScore, int round, int total) {
        Component title = Component.text(won ? "ROUND WON" : "ROUND LOST")
                .color(won ? NamedTextColor.GREEN : NamedTextColor.RED)
                .decorate(TextDecoration.BOLD);
        Component sub = Component.text(selfScore + " - " + oppScore)
                .color(NamedTextColor.YELLOW)
                .append(Component.text("   Round " + round + "/" + total)
                        .color(NamedTextColor.GRAY));
        player.showTitle(Title.title(title, sub, NORMAL));
    }

    public static void sendLoser(Player player) {
        Component title = Component.text("\u2620 DEFEAT")
                .color(NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD);
        Component sub = Component.text("Better luck next time...")
                .color(NamedTextColor.RED);
        player.showTitle(Title.title(title, sub, RESULT));
    }
}
