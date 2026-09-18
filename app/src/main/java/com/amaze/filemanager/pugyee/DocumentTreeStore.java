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

package com.amaze.filemanager.pugyee;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import uk.pugyee.mcp.FileStore;
import uk.pugyee.mcp.TextConverter;

/** Android grants access to this tree independently of Amaze's all-files permission. */
public final class DocumentTreeStore implements FileStore {
  private final ContentResolver resolver;
  private final DocumentFile root;

  public DocumentTreeStore(Context context, Uri uri) throws IOException {
    resolver = context.getContentResolver();
    root = DocumentFile.fromTreeUri(context, uri);
    if (root == null || !root.isDirectory() || !root.canRead())
      throw new IOException("Choose an accessible folder");
  }

  private DocumentFile resolve(String path, boolean allowRoot) throws IOException {
    FileStore.validatePath(path, allowRoot);
    DocumentFile current = root;
    if (!path.isEmpty()) {
      for (String part : path.split("/")) {
        if (!current.isDirectory()) throw new IOException("Not a directory");
        current = current.findFile(part);
        if (current == null) throw new IOException("File not found");
      }
    }
    return current;
  }

  @Override
  public synchronized List<Entry> list(String path) throws IOException {
    DocumentFile dir = resolve(path, true);
    if (!dir.isDirectory() || !dir.canRead()) throw new IOException("Cannot list folder");
    DocumentFile[] children = dir.listFiles();
    if (children.length > MAX_ENTRIES) throw new IOException("Open a smaller subfolder");
    List<Entry> result = new ArrayList<>();
    for (DocumentFile child : children) {
      String name = child.getName();
      if (name != null)
        result.add(
            new Entry(
                path.isEmpty() ? name : path + "/" + name, child.isDirectory(), child.length()));
    }
    return result;
  }

  @Override
  public synchronized String read(String path) throws IOException {
    TextConverter.format(path);
    DocumentFile file = resolve(path, false);
    if (!file.isFile() || file.length() > MAX_TEXT_BYTES)
      throw new IOException("Not a supported text file");
    try (InputStream in = resolver.openInputStream(file.getUri());
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      if (in == null) throw new IOException("Cannot open file");
      byte[] buffer = new byte[8192];
      int count;
      while ((count = in.read(buffer)) != -1) {
        if (out.size() + count > MAX_TEXT_BYTES) throw new IOException("Text exceeds 1 MiB");
        out.write(buffer, 0, count);
      }
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(out.toByteArray()))
              .toString();
      if (text.indexOf('\0') >= 0) throw new IOException("Binary data is not text");
      return text.startsWith("\ufeff") ? text.substring(1) : text;
    }
  }

  @Override
  public synchronized String create(String path, String text, String mime) throws IOException {
    FileStore.validatePath(path, false);
    TextConverter.checkSize(text);
    int slash = path.lastIndexOf('/');
    String parent = slash < 0 ? "" : path.substring(0, slash);
    String name = path.substring(slash + 1);
    DocumentFile dir = resolve(parent, true);
    if (!dir.isDirectory() || !dir.canWrite() || dir.findFile(name) != null)
      throw new IOException("Choose an unused destination name");
    DocumentFile created = dir.createFile(mime, name);
    if (created == null) throw new IOException("Cannot create file");
    boolean complete = false;
    try {
      try (OutputStream out = resolver.openOutputStream(created.getUri(), "w")) {
        if (out == null) throw new IOException("Cannot write file");
        out.write(text.getBytes(StandardCharsets.UTF_8));
      }
      complete = true;
      String actual = created.getName();
      return parent.isEmpty() ? actual : parent + "/" + actual;
    } finally {
      if (!complete) created.delete();
    }
  }
}
