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
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Serverbound play packet "custom_click_action": identifier + optional compound payload.
 * Only a flat compound of string values is accepted (that is all a dialog text input produces); anything else is ignored.
 */
public class ClickActionPacket implements MinecraftPacket {

  private static final int MAX_ENTRIES = 16;

  private String id = "";
  private final Map<String, String> values = new HashMap<>();

  public String getId() {
    return this.id;
  }

  public Map<String, String> getValues() {
    return this.values;
  }

  @Override
  public void decode(ByteBuf buf, ProtocolUtils.Direction direction, ProtocolVersion protocolVersion) {
    this.id = ProtocolUtils.readString(buf, 256);
    if (!buf.isReadable() || !buf.readBoolean()) {
      return;
    }

    if (buf.readByte() != 10) { // not a compound
      buf.skipBytes(buf.readableBytes());
      return;
    }

    for (int i = 0; i < MAX_ENTRIES; ++i) {
      int type = buf.readByte();
      if (type == 0) {
        break;
      }

      String key = readUtf(buf);
      if (type != 8) { // non-string value: stop, we can't skip it safely
        break;
      }

      this.values.put(key, readUtf(buf));
    }

    buf.skipBytes(buf.readableBytes());
  }

  private static String readUtf(ByteBuf buf) {
    int length = buf.readUnsignedShort();
    byte[] bytes = new byte[length];
    buf.readBytes(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  @Override
  public void encode(ByteBuf buf, ProtocolUtils.Direction direction, ProtocolVersion protocolVersion) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int decodeExpectedMaxLength(ByteBuf buf, ProtocolUtils.Direction direction, ProtocolVersion version) {
    return 8192;
  }

  @Override
  public boolean handle(MinecraftSessionHandler handler) {
    return false;
  }
}
