package dev.snapgent.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Base64;
import java.security.SecureRandom;

public final class BridgeConfig {
    public static final int DEFAULT_PORT = 8765;
    public static final int NATIVE_PORT = 17613;
    private static final String PREFS = "snapgent_bridge";
    private static final String KEY_TOKEN = "auth_token";
    private static final String KEY_PORT = "mcp_port";
    private static final String KEY_DARK = "dark_theme";
    private static final String KEY_AUTO_START = "auto_start";

    private BridgeConfig() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int port(Context context) {
        int value = prefs(context).getInt(KEY_PORT, DEFAULT_PORT);
        return value >= 1024 && value <= 65535 ? value : DEFAULT_PORT;
    }

    public static boolean darkTheme(Context context) {
        return prefs(context).getBoolean(KEY_DARK, true);
    }

    public static boolean autoStart(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_START, false);
    }

    public static boolean setPort(Context context, int value) {
        if (value < 1024 || value > 65535 || value == NATIVE_PORT) return false;
        return prefs(context).edit().putInt(KEY_PORT, value).commit();
    }

    public static boolean setDarkTheme(Context context, boolean value) {
        return prefs(context).edit().putBoolean(KEY_DARK, value).commit();
    }

    public static boolean setAutoStart(Context context, boolean value) {
        return prefs(context).edit().putBoolean(KEY_AUTO_START, value).commit();
    }

    public static synchronized String token(Context context) {
        String existing = prefs(context).getString(KEY_TOKEN, null);
        if (existing != null && !existing.isEmpty()) return existing;
        return regenerateToken(context);
    }

    public static synchronized String regenerateToken(Context context) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String created = Base64.encodeToString(secret, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        if (!prefs(context).edit().putString(KEY_TOKEN, created).commit()) {
            throw new IllegalStateException("Cannot persist bridge authentication token");
        }
        return created;
    }

    public static String connectionUrl(Context context) {
        return "ws://127.0.0.1:" + port(context) + "/ws?token=" + token(context);
    }

    /** Installed versionName, read at runtime so no report can drift from build.gradle. */
    public static String appVersion(Context context) {
        try {
            String name = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return name == null || name.isEmpty() ? "unknown" : name;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }
}
