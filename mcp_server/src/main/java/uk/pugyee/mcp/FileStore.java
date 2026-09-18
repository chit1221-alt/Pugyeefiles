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

import java.io.IOException;
import java.util.List;

/** Paths are relative to one owner-selected tree. Implementations never resolve arbitrary URIs. */
public interface FileStore {
  int MAX_TEXT_BYTES = 1024 * 1024;
  int MAX_ENTRIES = 500;

  List<Entry> list(String path) throws IOException;

  String read(String path) throws IOException;

  /** Creates a new file only. Returns its actual relative path, including provider renaming. */
  String create(String path, String text, String mime) throws IOException;

  static String validatePath(String path, boolean allowRoot) {
    if (path == null || path.length() > 1024) throw new IllegalArgumentException("Invalid path");
    if (path.isEmpty() && allowRoot) return path;
    String[] parts = path.split("/", -1);
    if (parts.length > 20) throw new IllegalArgumentException("Path too deep");
    for (String part : parts) {
      if (part.isEmpty() || part.equals(".") || part.equals("..") || part.length() > 200) {
        throw new IllegalArgumentException("Use a relative path inside the shared folder");
      }
      for (char c : part.toCharArray()) {
        if (c < 32 || c == 127 || c == '\\' || c == ':' || c == '%') {
          throw new IllegalArgumentException("Unsupported character in path");
        }
      }
    }
    return path;
  }

  final class Entry {
    public final String path;
    public final boolean directory;
    public final long bytes;

    public Entry(String path, boolean directory, long bytes) {
      this.path = path;
      this.directory = directory;
      this.bytes = bytes;
    }
  }
}
