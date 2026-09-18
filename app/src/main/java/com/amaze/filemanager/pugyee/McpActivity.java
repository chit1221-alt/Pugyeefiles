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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.amaze.filemanager.R;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import uk.pugyee.mcp.FileStore;
import uk.pugyee.mcp.McpHttpServer;
import uk.pugyee.mcp.OAuthManager;
import uk.pugyee.mcp.OwnerCredentials;
import uk.pugyee.mcp.TextConverter;

public final class McpActivity extends Activity {
  private static final int PICK_FOLDER = 8787;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private SharedPreferences prefs;
  private EditText origin;
  private EditText profile;
  private EditText password;
  private EditText confirmPassword;
  private CheckBox conversions;
  private String source = "";
  private String pendingSignature = "";
  private boolean busy;
  private final Runnable refresh =
      new Runnable() {
        @Override
        public void run() {
          update();
          handler.postDelayed(this, 1000);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_mcp);
    View content = findViewById(android.R.id.content);
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
        content,
        (view, insets) -> {
          androidx.core.graphics.Insets bars =
              insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
          view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return insets;
        });
    prefs = getSharedPreferences(McpService.PREFS, MODE_PRIVATE);
    origin = findViewById(R.id.mcp_origin);
    origin.setText(prefs.getString("origin", ""));
    profile = findViewById(R.id.mcp_profile);
    profile.setText(prefs.getString("profile", ""));
    password = findViewById(R.id.mcp_password);
    confirmPassword = findViewById(R.id.mcp_confirm_password);
    if (prefs.contains("password_hash")) {
      password.setHint(R.string.mcp_password_unchanged);
      confirmPassword.setHint(R.string.mcp_password_unchanged);
    }
    conversions = findViewById(R.id.mcp_allow_conversion);
    conversions.setChecked(prefs.getBoolean("convert", false));
    Spinner target = findViewById(R.id.mcp_format);
    target.setAdapter(
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"txt", "md", "json"}));
    findViewById(R.id.mcp_back).setOnClickListener(v -> finish());
    findViewById(R.id.mcp_choose_folder).setOnClickListener(v -> chooseFolder());
    findViewById(R.id.mcp_start).setOnClickListener(v -> startHost());
    findViewById(R.id.mcp_stop)
        .setOnClickListener(v -> stopService(new Intent(this, McpService.class)));
    findViewById(R.id.mcp_copy_command)
        .setOnClickListener(
            v -> copy("cloudflared tunnel --url http://127.0.0.1:" + McpHttpServer.PORT));
    findViewById(R.id.mcp_copy_url)
        .setOnClickListener(
            v -> {
              try {
                copy(OAuthManager.validateOrigin(origin.getText().toString().trim()) + "/mcp");
              } catch (RuntimeException e) {
                toast(R.string.mcp_invalid_url);
              }
            });
    findViewById(R.id.mcp_reset_connections)
        .setOnClickListener(
            v -> {
              if (McpService.active != null) return;
              new AlertDialog.Builder(this)
                  .setMessage(R.string.mcp_reset_explanation)
                  .setPositiveButton(
                      R.string.mcp_reset_connections,
                      (dialog, which) -> {
                        prefs.edit().remove("clients").apply();
                        toast(R.string.mcp_connections_reset);
                      })
                  .setNegativeButton(android.R.string.cancel, null)
                  .show();
            });
    findViewById(R.id.mcp_choose_source).setOnClickListener(v -> browse(""));
    findViewById(R.id.mcp_convert).setOnClickListener(v -> convert());
    if (state != null) {
      source = state.getString("source", "");
      ((TextView) findViewById(R.id.mcp_source))
          .setText(source.isEmpty() ? getString(R.string.mcp_no_file) : source);
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putString("source", source);
    super.onSaveInstanceState(state);
  }

  @Override
  protected void onResume() {
    super.onResume();
    refresh.run();
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(refresh);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    worker.shutdownNow();
    super.onDestroy();
  }

  private void chooseFolder() {
    if (McpService.active != null) {
      toast(R.string.mcp_stop_before_changes);
      return;
    }
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    intent.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    try {
      startActivityForResult(intent, PICK_FOLDER);
    } catch (RuntimeException e) {
      toast(R.string.mcp_folder_failed);
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != PICK_FOLDER || result != RESULT_OK || data == null || data.getData() == null)
      return;
    try {
      if (McpService.active != null) {
        toast(R.string.mcp_stop_before_changes);
        return;
      }
      Uri uri = data.getData();
      int flags =
          data.getFlags()
              & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      getContentResolver().takePersistableUriPermission(uri, flags);
      new DocumentTreeStore(this, uri);
      prefs.edit().putString("tree", uri.toString()).apply();
      source = "";
      ((TextView) findViewById(R.id.mcp_source)).setText(R.string.mcp_no_file);
      update();
    } catch (Exception e) {
      toast(R.string.mcp_folder_failed);
    }
  }

  private void startHost() {
    if (McpService.active != null) return;
    try {
      String address = OAuthManager.validateOrigin(origin.getText().toString().trim());
      if (prefs.getString("tree", "").isEmpty()) {
        toast(R.string.mcp_choose_folder_first);
        return;
      }
      String ownerProfile = profile.getText().toString().trim();
      String suppliedPassword = password.getText().toString();
      String suppliedConfirmation = confirmPassword.getText().toString();
      if (!OwnerCredentials.validProfile(ownerProfile)) {
        toast(R.string.mcp_invalid_profile);
        return;
      }
      String passwordHash = prefs.getString("password_hash", "");
      if (!suppliedPassword.isEmpty()) {
        if (!OwnerCredentials.validPassword(suppliedPassword)) {
          toast(R.string.mcp_invalid_password);
          return;
        }
        if (!suppliedPassword.equals(suppliedConfirmation)) {
          toast(R.string.mcp_password_mismatch);
          return;
        }
        passwordHash = OwnerCredentials.hash(suppliedPassword);
      } else if (passwordHash.isEmpty()) {
        toast(R.string.mcp_password_required);
        return;
      }
      prefs
          .edit()
          .putString("origin", address)
          .putString("profile", ownerProfile)
          .putString("password_hash", passwordHash)
          .putBoolean("convert", conversions.isChecked())
          .apply();
      password.setText("");
      confirmPassword.setText("");
      password.setHint(R.string.mcp_password_unchanged);
      confirmPassword.setHint(R.string.mcp_password_unchanged);
      if (Build.VERSION.SDK_INT >= 33
          && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
              != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 7);
      }
      ContextCompat.startForegroundService(this, new Intent(this, McpService.class));
    } catch (RuntimeException e) {
      toast(R.string.mcp_invalid_url);
    }
  }

  private void update() {
    if (isFinishing() || isDestroyed()) return;
    McpService service = McpService.active;
    boolean running = service != null;
    TextView status = findViewById(R.id.mcp_status);
    status.setText(running ? getString(R.string.mcp_running) : getString(McpService.status));
    origin.setEnabled(!running);
    profile.setEnabled(!running);
    password.setEnabled(!running);
    confirmPassword.setEnabled(!running);
    conversions.setEnabled(!running);
    findViewById(R.id.mcp_choose_folder).setEnabled(!running && !busy);
    findViewById(R.id.mcp_start).setEnabled(!running && !busy);
    findViewById(R.id.mcp_stop).setEnabled(running);
    findViewById(R.id.mcp_reset_connections).setEnabled(!running);
    findViewById(R.id.mcp_convert).setEnabled(!busy && !source.isEmpty());
    String tree = prefs.getString("tree", "");
    TextView folder = findViewById(R.id.mcp_folder);
    if (tree.isEmpty()) folder.setText(R.string.mcp_no_folder);
    else {
      DocumentFile root = DocumentFile.fromTreeUri(this, Uri.parse(tree));
      folder.setText(root == null ? getString(R.string.mcp_no_folder) : root.getName());
    }
    List<OAuthManager.Pending> pending =
        running ? service.auth().pending() : java.util.Collections.emptyList();
    StringBuilder signature = new StringBuilder();
    for (OAuthManager.Pending p : pending) signature.append(p.id);
    if (!pendingSignature.equals(signature.toString())) {
      pendingSignature = signature.toString();
      LinearLayout container = findViewById(R.id.mcp_requests);
      container.removeAllViews();
      for (OAuthManager.Pending p : pending) {
        TextView label = new TextView(this);
        label.setText(
            getString(
                R.string.mcp_request_details,
                p.code(),
                p.name,
                p.redirect,
                getString(p.convert ? R.string.mcp_access_convert : R.string.mcp_access_read)));
        label.setPadding(0, 20, 0, 8);
        container.addView(label);
        Button approve = new Button(this);
        approve.setText(R.string.mcp_approve);
        approve.setOnClickListener(
            v -> {
              service.auth().approve(p.id);
              approve.setEnabled(false);
              approve.setText(R.string.mcp_approved);
            });
        container.addView(approve);
        Button deny = new Button(this);
        deny.setText(R.string.mcp_deny);
        deny.setOnClickListener(
            v -> {
              service.auth().deny(p.id);
              update();
            });
        container.addView(deny);
      }
    }
  }

  private DocumentTreeStore files() throws java.io.IOException {
    String tree = prefs.getString("tree", "");
    if (tree.isEmpty()) throw new java.io.IOException();
    return new DocumentTreeStore(this, Uri.parse(tree));
  }

  private void browse(String path) {
    if (busy) return;
    busy = true;
    worker.execute(
        () -> {
          try {
            List<FileStore.Entry> entries = files().list(path);
            String[] labels = new String[entries.size() + (path.isEmpty() ? 0 : 1)];
            int offset = path.isEmpty() ? 0 : 1;
            if (offset == 1) labels[0] = getString(R.string.mcp_parent_folder);
            for (int i = 0; i < entries.size(); i++)
              labels[i + offset] = entries.get(i).path + (entries.get(i).directory ? "/" : "");
            runOnUiThread(
                () -> {
                  busy = false;
                  if (isFinishing() || isDestroyed()) return;
                  new AlertDialog.Builder(this)
                      .setTitle(R.string.mcp_choose_source)
                      .setItems(
                          labels,
                          (dialog, which) -> {
                            if (which < offset) {
                              int slash = path.lastIndexOf('/');
                              browse(slash < 0 ? "" : path.substring(0, slash));
                              return;
                            }
                            FileStore.Entry entry = entries.get(which - offset);
                            if (entry.directory) {
                              browse(entry.path);
                              return;
                            }
                            try {
                              TextConverter.format(entry.path);
                              source = entry.path;
                              ((TextView) findViewById(R.id.mcp_source)).setText(source);
                              String name = source.substring(source.lastIndexOf('/') + 1);
                              ((EditText) findViewById(R.id.mcp_destination))
                                  .setText(name.substring(0, name.lastIndexOf('.')) + "-converted");
                            } catch (RuntimeException e) {
                              toast(R.string.mcp_supported_formats);
                            }
                            update();
                          })
                      .setNegativeButton(android.R.string.cancel, null)
                      .show();
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  busy = false;
                  toast(R.string.mcp_folder_failed);
                });
          }
        });
  }

  private void convert() {
    if (source.isEmpty() || busy) return;
    String name = ((EditText) findViewById(R.id.mcp_destination)).getText().toString().trim();
    String target = ((Spinner) findViewById(R.id.mcp_format)).getSelectedItem().toString();
    try {
      FileStore.validatePath(name, false);
      if (name.contains("/")) throw new IllegalArgumentException();
    } catch (RuntimeException e) {
      toast(R.string.mcp_invalid_name);
      return;
    }
    String input = source;
    int slash = input.lastIndexOf('/');
    String destination = (slash < 0 ? "" : input.substring(0, slash + 1)) + name + "." + target;
    busy = true;
    update();
    worker.execute(
        () -> {
          try {
            DocumentTreeStore store = files();
            String converted =
                TextConverter.convert(store.read(input), TextConverter.format(input), target);
            String saved =
                store.create(
                    destination,
                    converted,
                    target.equals("json")
                        ? "application/json"
                        : target.equals("md") ? "text/markdown" : "text/plain");
            runOnUiThread(
                () -> {
                  busy = false;
                  ((TextView) findViewById(R.id.mcp_conversion_result))
                      .setText(getString(R.string.mcp_saved, saved));
                  update();
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  busy = false;
                  toast(R.string.mcp_conversion_failed);
                  update();
                });
          }
        });
  }

  private void copy(String text) {
    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
        .setPrimaryClip(ClipData.newPlainText(getString(R.string.mcp_title), text));
    toast(R.string.mcp_copied);
  }

  private void toast(int message) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
  }
}
