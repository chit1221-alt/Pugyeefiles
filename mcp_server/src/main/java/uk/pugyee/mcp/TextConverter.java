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

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Deliberately small, explicit conversion contract; source files are never modified. */
public final class TextConverter {
  private TextConverter() {}

  public static String format(String path) {
    int dot = path.lastIndexOf('.');
    String format = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    if (!format.equals("txt") && !format.equals("md") && !format.equals("json")) {
      throw new IllegalArgumentException("Supported formats: txt, md, json");
    }
    return format;
  }

  public static String convert(String source, String from, String to) {
    format("file." + from);
    format("file." + to);
    checkSize(source);
    String result;
    if (from.equals("json")) {
      JsonElement parsed = Json.parse(source);
      if (to.equals("json")) result = Json.PRETTY.toJson(parsed) + "\n";
      else if (isTextEnvelope(parsed)) {
        JsonObject envelope = parsed.getAsJsonObject();
        return convert(Json.string(envelope, "text"), Json.string(envelope, "format"), to);
      } else if (to.equals("txt")) result = Json.PRETTY.toJson(parsed) + "\n";
      else result = fenced(Json.PRETTY.toJson(parsed), "json");
    } else if (to.equals("json")) {
      JsonObject envelope = new JsonObject();
      envelope.addProperty("format", from);
      envelope.addProperty("text", source);
      result = Json.PRETTY.toJson(envelope) + "\n";
    } else if (from.equals(to)) result = source;
    else if (from.equals("md")) {
      result = TextContentRenderer.builder().build().render(Parser.builder().build().parse(source));
    } else result = fenced(source, "text");
    checkSize(result);
    return result;
  }

  private static boolean isTextEnvelope(JsonElement element) {
    if (!element.isJsonObject()) return false;
    JsonObject o = element.getAsJsonObject();
    if (o.size() != 2 || !o.has("format") || !o.has("text")) return false;
    if (!o.get("format").isJsonPrimitive()
        || !o.get("format").getAsJsonPrimitive().isString()
        || !o.get("text").isJsonPrimitive()
        || !o.get("text").getAsJsonPrimitive().isString()) return false;
    return o.get("format").getAsString().equals("txt")
        || o.get("format").getAsString().equals("md");
  }

  private static String fenced(String text, String language) {
    int run = 0;
    int longest = 2;
    for (int i = 0; i < text.length(); i++) {
      run = text.charAt(i) == '`' ? run + 1 : 0;
      longest = Math.max(longest, run);
    }
    char[] ticks = new char[longest + 1];
    java.util.Arrays.fill(ticks, '`');
    String fence = new String(ticks);
    return fence + language + "\n" + text + "\n" + fence + "\n";
  }

  public static void checkSize(String text) {
    if (text.getBytes(StandardCharsets.UTF_8).length > FileStore.MAX_TEXT_BYTES) {
      throw new IllegalArgumentException("Text exceeds the 1 MiB limit");
    }
  }
}
