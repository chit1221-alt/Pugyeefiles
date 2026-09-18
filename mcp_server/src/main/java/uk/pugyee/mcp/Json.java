/*
 * Copyright (C) 2014-2026 Arpit Khurana <arpitkh96@gmail.com>, Vishal Nehra <vishalmeham2@gmail.com>,
 * Emmanuel Messulam<emmanuelbendavid@gmail.com>, Raymond Lai <airwave209gt at gmail.com> and Contributors.
 *
 * This file is part of Amaze File Manager.
 *
 * Amaze File Manager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package uk.pugyee.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;

/** Shared strict JSON boundary for both the HTTP protocol and JSON files. */
public final class Json {
  public static final Gson CODEC = new GsonBuilder().setStrictness(Strictness.STRICT).create();
  public static final Gson PRETTY =
      new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  private Json() {}

  public static JsonElement parse(String text) {
    // Bound nesting before Gson builds a tree, including inside otherwise small requests.
    int depth = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (char c : text.toCharArray()) {
      if (quoted) {
        if (escaped) escaped = false;
        else if (c == '\\') escaped = true;
        else if (c == '"') quoted = false;
      } else if (c == '"') quoted = true;
      else if (c == '{' || c == '[') {
        if (++depth > 64) throw new IllegalArgumentException("JSON nesting exceeds 64 levels");
      } else if (c == '}' || c == ']') depth--;
    }
    JsonElement result = CODEC.fromJson(text, JsonElement.class);
    if (result == null) throw new IllegalArgumentException("Empty JSON");
    return result;
  }

  public static String string(JsonObject object, String key) {
    JsonElement value = object.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw new IllegalArgumentException("Expected string: " + key);
    }
    return value.getAsString();
  }
}
