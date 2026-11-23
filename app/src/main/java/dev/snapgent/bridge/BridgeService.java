package dev.snapgent.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import java.io.IOException;

public class BridgeService extends Service {
    public static final String ACTION_START = "dev.snapgent.bridge.START";
    public static final String ACTION_STOP = "dev.snapgent.bridge.STOP";
    public static final String ACTION_RESTART = "dev.snapgent.bridge.RESTART";
    private static final String CHANNEL_ID = "snapgent_bridge_service";
    public static volatile boolean running = false;
    public static volatile String lastError = null;
    private LocalWebSocketServer server;
    private LocalWebSocketServer nativeServer;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            closeServers();
            running = false;
        }
        if (running) return START_NOT_STICKY;
        try {
            startForeground(11, buildNotification());
            String token = BridgeConfig.token(this);
            server = new LocalWebSocketServer(this, BridgeConfig.port(this), token);
            server.start();
            nativeServer = new LocalWebSocketServer(this, BridgeConfig.NATIVE_PORT, token, true);
            nativeServer.start();
            running = true;
            lastError = null;
            Log.i("SnapgentBridge", "MCP endpoint on 127.0.0.1:" + BridgeConfig.port(this) + "; snapgentV3 endpoint on 127.0.0.1:" + BridgeConfig.NATIVE_PORT);
        } catch (Exception e) {
            lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            running = false;
            closeServers();
            Log.e("SnapgentBridge", "Startup failed", e);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void closeServers() {
        if (server != null) {
            try { server.close(); } catch (IOException e) { Log.w("SnapgentBridge", "MCP server close failed", e); }
            server = null;
        }
        if (nativeServer != null) {
            try { nativeServer.close(); } catch (IOException e) { Log.w("SnapgentBridge", "Native server close failed", e); }
            nativeServer = null;
        }
    }

    private Notification buildNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Snapgent local bridge", NotificationManager.IMPORTANCE_LOW));
        }
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setContentTitle("Snapgent Bridge running")
                .setContentText("Local MCP server at 127.0.0.1:" + BridgeConfig.port(this))
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    @Override public void onDestroy() {
        running = false;
        closeServers();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
