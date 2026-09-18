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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import com.amaze.filemanager.R;
import uk.pugyee.mcp.McpHttpServer;
import uk.pugyee.mcp.McpProtocol;
import uk.pugyee.mcp.OAuthManager;
import uk.pugyee.mcp.OwnerCredentials;

public final class McpService extends Service {
  public static final String PREFS = "pugyee_mcp";
  private static final String CHANNEL = "pugyee_mcp";
  private static final int NOTIFICATION = 8787;
  private static final long SESSION_MS = 60 * 60 * 1000L;
  static volatile McpService active;
  static volatile int status = R.string.mcp_stopped;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private McpHttpServer server;
  private OAuthManager auth;
  private PowerManager.WakeLock wakeLock;
  private final Runnable renewWakeLock =
      new Runnable() {
        @Override
        public void run() {
          if (wakeLock != null) wakeLock.acquire(SESSION_MS);
          handler.postDelayed(this, 50 * 60 * 1000L);
        }
      };

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && "stop".equals(intent.getAction())) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (server != null) return START_NOT_STICKY;
    try {
      NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      if (Build.VERSION.SDK_INT >= 26)
        manager.createNotificationChannel(
            new NotificationChannel(
                CHANNEL, getString(R.string.mcp_title), NotificationManager.IMPORTANCE_LOW));
      Intent open = new Intent(this, McpActivity.class);
      PendingIntent content =
          PendingIntent.getActivity(
              this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      PendingIntent stop =
          PendingIntent.getService(
              this,
              1,
              new Intent(this, McpService.class).setAction("stop"),
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      Notification notification =
          new NotificationCompat.Builder(this, CHANNEL)
              .setSmallIcon(R.drawable.ic_ftp_white_24dp)
              .setContentTitle(getString(R.string.mcp_title))
              .setContentText(getString(R.string.mcp_notification))
              .setContentIntent(content)
              .setOngoing(true)
              .addAction(0, getString(R.string.mcp_stop), stop)
              .build();
      ServiceCompat.startForeground(
          this,
          NOTIFICATION,
          notification,
          Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);
      SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
      DocumentTreeStore files = new DocumentTreeStore(this, Uri.parse(prefs.getString("tree", "")));
      boolean convert = prefs.getBoolean("convert", false);
      auth =
          new OAuthManager(
              prefs.getString("origin", ""),
              convert,
              prefs.getString("clients", "[]"),
              clients -> prefs.edit().putString("clients", clients).apply());
      OwnerCredentials owner =
          new OwnerCredentials(
              prefs.getString("profile", ""), prefs.getString("password_hash", ""));
      server =
          new McpHttpServer(
              McpHttpServer.PORT,
              auth,
              owner,
              new McpProtocol(files, convert),
              getString(R.string.mcp_login_html),
              getString(R.string.mcp_consent_html));
      server.start(5000, true);
      PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
      wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":mcp");
      wakeLock.setReferenceCounted(false);
      renewWakeLock.run();
      status = R.string.mcp_running;
      active = this;
    } catch (Exception e) {
      status = R.string.mcp_start_failed;
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  OAuthManager auth() {
    return auth;
  }

  @Override
  public void onDestroy() {
    active = null;
    if (status != R.string.mcp_start_failed) status = R.string.mcp_stopped;
    handler.removeCallbacksAndMessages(null);
    if (auth != null) auth.revokeAll();
    if (server != null) server.stop();
    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    stopForeground(true);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
