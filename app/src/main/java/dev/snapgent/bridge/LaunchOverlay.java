package dev.snapgent.bridge;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * A 1x1 invisible overlay window.
 *
 * <p>Android 10+ blocks an app from starting activities while it is in the
 * background. Having the "Display over other apps" grant is not enough by
 * itself on Android 14: the system also wants an actually visible window. A
 * one-pixel non-touchable overlay satisfies that check, which is what makes
 * {@code app_launch} work while the bridge sits behind another app.
 */
final class LaunchOverlay {
    private static volatile boolean attached = false;

    private LaunchOverlay() {
    }

    static boolean isAllowed(Context context) {
        return Settings.canDrawOverlays(context);
    }

    /** Attach the overlay on the main thread; safe to call repeatedly. */
    static void ensure(Context context) {
        if (attached || !isAllowed(context)) return;
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            if (attached) return;
            try {
                WindowManager manager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                if (manager == null) return;
                View view = new View(app);
                int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(1, 1, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.START;
                params.x = 0;
                params.y = 0;
                manager.addView(view, params);
                attached = true;
                BridgeLog.info("LaunchOverlay", "Overlay attached; background app launching enabled");
            } catch (Exception e) {
                BridgeLog.warn("LaunchOverlay", "Overlay attach failed: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
            }
        });
    }
}
