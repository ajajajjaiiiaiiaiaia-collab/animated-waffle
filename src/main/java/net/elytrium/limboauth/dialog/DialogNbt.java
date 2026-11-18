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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Minimal network-NBT writer (1.20.2+, unnamed root) for Map/List/String/Integer/Boolean trees.
 */
public final class DialogNbt {

  private static final int END = 0;
  private static final int BYTE = 1;
  private static final int INT = 3;
  private static final int STRING = 8;
  private static final int LIST = 9;
  private static final int COMPOUND = 10;

  private DialogNbt() {
  }

  public static void writeRoot(ByteBuf buf, Map<String, Object> root) {
    try {
      DataOutputStream out = new DataOutputStream(new ByteBufOutputStream(buf));
      out.writeByte(COMPOUND);
      writeCompound(out, root);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static int typeOf(Object value) {
    if (value instanceof String) {
      return STRING;
    } else if (value instanceof Integer) {
      return INT;
    } else if (value instanceof Boolean) {
      return BYTE;
    } else if (value instanceof List) {
      return LIST;
    } else if (value instanceof Map) {
      return COMPOUND;
    }

    throw new IllegalArgumentException("Unsupported NBT value: " + value);
  }

  @SuppressWarnings("unchecked")
  private static void writePayload(DataOutputStream out, Object value) throws IOException {
    if (value instanceof String) {
      out.writeUTF((String) value);
    } else if (value instanceof Integer) {
      out.writeInt((Integer) value);
    } else if (value instanceof Boolean) {
      out.writeByte((Boolean) value ? 1 : 0);
    } else if (value instanceof Map) {
      writeCompound(out, (Map<String, Object>) value);
    } else if (value instanceof List) {
      List<Object> list = (List<Object>) value;
      if (list.isEmpty()) {
        out.writeByte(END);
        out.writeInt(0);
      } else {
        int type = typeOf(list.get(0));
        out.writeByte(type);
        out.writeInt(list.size());
        for (Object element : list) {
          if (typeOf(element) != type) {
            throw new IllegalArgumentException("Heterogeneous NBT list");
          }

          writePayload(out, element);
        }
      }
    }
  }

  private static void writeCompound(DataOutputStream out, Map<String, Object> map) throws IOException {
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      out.writeByte(typeOf(entry.getValue()));
      out.writeUTF(entry.getKey());
      writePayload(out, entry.getValue());
    }

    out.writeByte(END);
  }
}
