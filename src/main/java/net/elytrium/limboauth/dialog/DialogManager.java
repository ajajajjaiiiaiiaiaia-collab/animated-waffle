/*
 * Copyright (C) 2021 - 2025 Elytrium
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.elytrium.limboauth.dialog;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.elytrium.limboapi.api.protocol.PacketDirection;
import net.elytrium.limboapi.api.protocol.packets.PacketMapping;
import net.elytrium.limboauth.Settings;

/**
 * Builds and sends the login/register/TOTP dialogs for 1.21.6 - 1.21.11 clients.
 */
public final class DialogManager {

  public static final String SUBMIT_ID = "limboauth:submit";
  public static final String FIELD_PASSWORD = "password";
  public static final String FIELD_REPEAT = "repeat";
  public static final String FIELD_TOTP = "code";

  private DialogManager() {
  }

  public static void register(Limbo limbo) {
    limbo.registerPacket(PacketDirection.CLIENTBOUND, ShowDialogPacket.class, ShowDialogPacket::new, new PacketMapping[] {
        new PacketMapping(0x85, ProtocolVersion.MINECRAFT_1_21_6, ProtocolVersion.MINECRAFT_1_21_7, true),
        new PacketMapping(0x8A, ProtocolVersion.MINECRAFT_1_21_9, ProtocolVersion.MINECRAFT_1_21_11, true)
    });
    limbo.registerPacket(PacketDirection.CLIENTBOUND, ClearDialogPacket.class, ClearDialogPacket::new, new PacketMapping[] {
        new PacketMapping(0x84, ProtocolVersion.MINECRAFT_1_21_6, ProtocolVersion.MINECRAFT_1_21_7, true),
        new PacketMapping(0x89, ProtocolVersion.MINECRAFT_1_21_9, ProtocolVersion.MINECRAFT_1_21_11, true)
    });
    limbo.registerPacket(PacketDirection.SERVERBOUND, ClickActionPacket.class, ClickActionPacket::new, new PacketMapping[] {
        new PacketMapping(0x41, ProtocolVersion.MINECRAFT_1_21_6, ProtocolVersion.MINECRAFT_1_21_11, false)
    });
  }

  public static boolean isSupported(Player player) {
    ProtocolVersion version = player.getProtocolVersion();
    return Settings.IMP.MAIN.DIALOG.ENABLED
        && version.compareTo(ProtocolVersion.MINECRAFT_1_21_6) >= 0
        && version.compareTo(ProtocolVersion.MINECRAFT_1_21_11) <= 0;
  }

  public static void clear(LimboPlayer player) {
    player.writePacketAndFlush(new ClearDialogPacket());
  }

  public static void showLogin(LimboPlayer player, int attemptsLeft) {
    Settings.MAIN.DIALOG s = Settings.IMP.MAIN.DIALOG;
    List<Object> inputs = new ArrayList<>();
    inputs.add(input(FIELD_PASSWORD, s.PASSWORD_LABEL));
    String body = s.LOGIN_BODY.replace("{0}", String.valueOf(attemptsLeft));
    send(player, dialog(s.LOGIN_TITLE, body, inputs, s.LOGIN_BUTTON));
  }

  public static void showRegister(LimboPlayer player) {
    Settings.MAIN.DIALOG s = Settings.IMP.MAIN.DIALOG;
    List<Object> inputs = new ArrayList<>();
    inputs.add(input(FIELD_PASSWORD, s.PASSWORD_LABEL));
    inputs.add(input(FIELD_REPEAT, s.REPEAT_LABEL));
    send(player, dialog(s.REGISTER_TITLE, s.REGISTER_BODY, inputs, s.REGISTER_BUTTON));
  }

  public static void showTotp(LimboPlayer player) {
    Settings.MAIN.DIALOG s = Settings.IMP.MAIN.DIALOG;
    List<Object> inputs = new ArrayList<>();
    inputs.add(input(FIELD_TOTP, s.TOTP_LABEL));
    send(player, dialog(s.TOTP_TITLE, s.TOTP_BODY, inputs, s.TOTP_BUTTON));
  }

  private static void send(LimboPlayer player, Map<String, Object> dialog) {
    player.writePacketAndFlush(new ShowDialogPacket(dialog));
  }

  private static Map<String, Object> text(String text, String color) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("text", text);
    map.put("color", color);
    return map;
  }

  private static Map<String, Object> input(String key, String label) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("type", "minecraft:text");
    map.put("key", key);
    map.put("label", text(label, "#B8C0CC"));
    map.put("width", 260);
    map.put("max_length", 71);
    map.put("initial", "");
    return map;
  }

  private static Map<String, Object> dialog(String title, String body, List<Object> inputs, String button) {
    Map<String, Object> dialog = new LinkedHashMap<>();
    dialog.put("type", "minecraft:notice");
    dialog.put("title", text(title, "#5EEAD4"));
    dialog.put("can_close_with_escape", false);
    dialog.put("pause", false);
    dialog.put("after_action", "none");

    Map<String, Object> message = new LinkedHashMap<>();
    message.put("type", "minecraft:plain_message");
    message.put("contents", text(body, "#E5E7EB"));
    message.put("width", 260);
    List<Object> bodies = new ArrayList<>();
    bodies.add(message);
    dialog.put("body", bodies);
    dialog.put("inputs", inputs);

    Map<String, Object> action = new LinkedHashMap<>();
    action.put("type", "minecraft:dynamic/custom");
    action.put("id", SUBMIT_ID);
    Map<String, Object> actionButton = new LinkedHashMap<>();
    actionButton.put("label", text(button, "#86EFAC"));
    actionButton.put("width", 260);
    actionButton.put("action", action);
    dialog.put("action", actionButton);
    return dialog;
  }
}
