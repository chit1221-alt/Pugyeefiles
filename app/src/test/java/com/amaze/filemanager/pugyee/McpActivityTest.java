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

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowToast;

import com.amaze.filemanager.R;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.widget.EditText;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class McpActivityTest {
  @Before
  public void reset() {
    RuntimeEnvironment.getApplication()
        .getSharedPreferences(McpService.PREFS, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit();
    McpService.active = null;
    McpService.status = R.string.mcp_stopped;
  }

  @Test
  public void folderButtonUsesSystemTreePickerAndRequestsPersistentAccess() {
    try (ActivityController<McpActivity> controller =
        Robolectric.buildActivity(McpActivity.class).setup()) {
      McpActivity activity = controller.get();
      assertFalse(activity.findViewById(R.id.mcp_stop).isEnabled());
      assertFalse(activity.findViewById(R.id.mcp_convert).isEnabled());
      activity.findViewById(R.id.mcp_choose_folder).performClick();
      Intent picker = shadowOf(activity).getNextStartedActivityForResult().intent;
      assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, picker.getAction());
      assertNotEquals(0, picker.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
      assertNotEquals(0, picker.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
  }

  @Test
  public void hostingCannotStartWithoutHttpsAndAChosenFolder() {
    try (ActivityController<McpActivity> controller =
        Robolectric.buildActivity(McpActivity.class).setup()) {
      McpActivity activity = controller.get();
      ((EditText) activity.findViewById(R.id.mcp_origin)).setText("http://insecure.example");
      activity.findViewById(R.id.mcp_start).performClick();
      assertEquals(
          activity.getString(R.string.mcp_invalid_url), ShadowToast.getTextOfLatestToast());
      ((EditText) activity.findViewById(R.id.mcp_origin)).setText("https://phone.example");
      activity.findViewById(R.id.mcp_start).performClick();
      assertEquals(
          activity.getString(R.string.mcp_choose_folder_first), ShadowToast.getTextOfLatestToast());
      assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
    }
  }

  @Test
  public void consentPageHasActualHtmlAndNoOwnerPassword() {
    String page = RuntimeEnvironment.getApplication().getString(R.string.mcp_consent_html);
    assertTrue(page.startsWith("<!doctype html>"));
    assertTrue(page.contains("{{REQUEST}}"));
    assertTrue(page.contains("method=\"post\""));
    assertFalse(page.contains("type=\"password\""));
  }

  @Test
  public void pickerResultCannotChangeTheFolderAfterHostingStarts() {
    try (ActivityController<McpActivity> controller =
        Robolectric.buildActivity(McpActivity.class).setup()) {
      McpActivity activity = controller.get();
      McpService.active = new McpService();
      try {
        activity.onActivityResult(
            8787,
            android.app.Activity.RESULT_OK,
            new Intent().setData(android.net.Uri.parse("content://documents/tree/new")));
        assertEquals(
            activity.getString(R.string.mcp_stop_before_changes),
            ShadowToast.getTextOfLatestToast());
        assertFalse(
            activity.getSharedPreferences(McpService.PREFS, Context.MODE_PRIVATE).contains("tree"));
      } finally {
        McpService.active = null;
      }
    }
  }

  @Test
  public void resetClearsClientRegistrationsOnlyAfterConfirmation() {
    android.content.SharedPreferences prefs =
        RuntimeEnvironment.getApplication()
            .getSharedPreferences(McpService.PREFS, Context.MODE_PRIVATE);
    prefs.edit().putString("clients", "[]").putBoolean("convert", true).commit();
    try (ActivityController<McpActivity> controller =
        Robolectric.buildActivity(McpActivity.class).setup()) {
      controller.get().findViewById(R.id.mcp_reset_connections).performClick();
      assertTrue(prefs.contains("clients"));
      org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
          .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
          .performClick();
      shadowOf(android.os.Looper.getMainLooper()).idle();
      assertFalse(prefs.contains("clients"));
      assertTrue(prefs.getBoolean("convert", false));
    }
  }
}
