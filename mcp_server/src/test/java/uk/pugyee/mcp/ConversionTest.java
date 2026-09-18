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

import static org.junit.Assert.*;

import org.junit.Test;

public class ConversionTest {
  @Test
  public void textJsonEnvelopeRoundTripsUnicodeAndEscapes() {
    String source = "မင်္ဂလာပါ 😺\n\"quoted\" \\ slash\n";
    String json = TextConverter.convert(source, "txt", "json");
    assertEquals(source, Json.parse(json).getAsJsonObject().get("text").getAsString());
    assertEquals(source, TextConverter.convert(json, "json", "txt"));
  }

  @Test
  public void markdownIsRenderedAsText() {
    String text =
        TextConverter.convert(
            "# Heading\n\n**Bold** and [link](https://example.com).", "md", "txt");
    assertTrue(text.contains("Heading"));
    assertTrue(text.contains("Bold"));
    assertFalse(text.contains("**"));
    assertFalse(text.contains("# Heading"));
  }

  @Test
  public void literalMarkdownCannotBreakOutOfItsFence() {
    String converted = TextConverter.convert("````\n# unformatted text\n````", "txt", "md");
    assertTrue(converted.startsWith("`````text\n"));
    assertTrue(converted.endsWith("\n`````\n"));
  }

  @Test(timeout = 2000)
  public void longBacktickRunsStayBounded() {
    String input = new String(new char[200000]).replace('\0', '`');
    String result = TextConverter.convert(input, "txt", "md");
    assertTrue(result.length() < FileStore.MAX_TEXT_BYTES);
  }

  @Test
  public void ordinaryJsonKeepsAllFields() {
    String source = "{\"format\":\"txt\",\"text\":\"hello\",\"keep\":42}";
    assertTrue(TextConverter.convert(source, "json", "txt").contains("\"keep\": 42"));
    assertTrue(TextConverter.convert(source, "json", "md").startsWith("```json\n"));
  }

  @Test
  public void rejectsMalformedJsonDeepJsonAndOversizeText() {
    for (String source :
        new String[] {"{oops}", "{\"a\":1,}", "{\"a\":1} trailing", "/* comment */ {}", ""}) {
      assertThrows(RuntimeException.class, () -> TextConverter.convert(source, "json", "txt"));
    }
    String nested =
        new String(new char[65]).replace('\0', '[')
            + "0"
            + new String(new char[65]).replace('\0', ']');
    assertThrows(IllegalArgumentException.class, () -> Json.parse(nested));
    String large = new String(new char[FileStore.MAX_TEXT_BYTES + 1]).replace('\0', 'a');
    assertThrows(IllegalArgumentException.class, () -> TextConverter.convert(large, "txt", "json"));
  }

  @Test
  public void rejectsAbsoluteAndEncodedPaths() {
    for (String path :
        new String[] {
          "",
          "/a",
          "a//b",
          ".",
          "..",
          "a/..",
          "a/./b",
          "a/",
          "file:///tmp/a",
          "a\\b",
          "a\0b",
          "%2Fetc"
        }) {
      assertThrows(path, IllegalArgumentException.class, () -> FileStore.validatePath(path, false));
    }
    assertEquals("", FileStore.validatePath("", true));
    assertEquals(
        "The Scale/မင်္ဂလာပါ 😺.md", FileStore.validatePath("The Scale/မင်္ဂလာပါ 😺.md", false));
  }
}
