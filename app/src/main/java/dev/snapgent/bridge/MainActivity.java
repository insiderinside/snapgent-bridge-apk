package dev.snapgent.bridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private LinearLayout root, content, bottomNav;
    private TextView headerTitle;
    private Button headerAction;
    private EditText portInput;
    private String page = "home";
    private String logFilter = "ALL";
    private int bg, surface, textColor, muted, accent, border, green;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        applyColors();
        buildShell();
        renderPage();
        if (BridgeConfig.autoStart(this) && !BridgeService.running) startBridge();
    }

    private void applyColors() {
        boolean dark = BridgeConfig.darkTheme(this);
        bg = Color.parseColor(dark ? "#0B1020" : "#F3F5FA");
        surface = Color.parseColor(dark ? "#151D30" : "#FFFFFF");
        textColor = Color.parseColor(dark ? "#F1F5FC" : "#172033");
        muted = Color.parseColor(dark ? "#9AA8BE" : "#69758A");
        accent = Color.parseColor("#6978F5");
        border = Color.parseColor(dark ? "#29354C" : "#E2E7F0");
        green = Color.parseColor("#22C58B");
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(surface);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(16), dp(20), dp(12));

        TextView logo = text("S", 21, true);
        logo.setGravity(Gravity.CENTER);
        logo.setTextColor(Color.WHITE);
        logo.setBackground(round(accent, 15));
        header.addView(logo, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(12), 0, 0, 0);
        TextView name = text("Snapgent Bridge", 18, true);
        headerTitle = name;
        TextView subtitle = text("LOCAL MCP GATEWAY", 10, true);
        subtitle.setTextColor(muted);
        subtitle.setLetterSpacing(0.12f);
        brand.addView(name);
        brand.addView(subtitle);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));

        headerAction = button("⚙", false);
        headerAction.setTextSize(20);
        headerAction.setPadding(0, 0, 0, 0);
        headerAction.setMinWidth(dp(46));
        headerAction.setOnClickListener(v -> showPage(page.equals("home") ? "settings" : "home"));
        header.addView(headerAction, new LinearLayout.LayoutParams(dp(46), dp(46)));
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(12), dp(20), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        bottomNav = new LinearLayout(this);
        bottomNav.setGravity(Gravity.CENTER);
        bottomNav.setPadding(dp(14), dp(9), dp(14), dp(9));
        bottomNav.setBackgroundColor(surface);
        root.addView(bottomNav, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private void showPage(String next) {
        page = next;
        renderPage();
    }

    private void renderPage() {
        if (content == null) return;
        content.removeAllViews();
        headerTitle.setText(page.equals("settings") ? "Settings" : page.equals("logs") ? "Activity Logs" : "Snapgent Bridge");
        headerAction.setText(page.equals("home") ? "⚙" : "‹");
        bottomNav.removeAllViews();
        addNavButton("⌂ Overview", "home");
        addNavButton("≡ Logs", "logs");
        addNavButton("⚙ Settings", "settings");
        if (page.equals("settings")) renderSettings();
        else if (page.equals("logs")) renderLogs();
        else renderDashboard();
    }

    private void renderDashboard() {
        addLabel("YOUR LOCAL WORKSPACE");
        addText("Everything connected.", 25, true, textColor);
        addText("Your Android device is ready to act as a local MCP bridge.", 13, false, muted);
        space(18);

        LinearLayout statusCard = card();
        LinearLayout top = row();
        TextView dot = text(BridgeService.running ? "●" : "○", 20, true);
        dot.setTextColor(BridgeService.running ? green : muted);
        top.addView(dot);
        TextView state = text(BridgeService.running ? "  BRIDGE ONLINE" : "  BRIDGE OFFLINE", 12, true);
        state.setTextColor(BridgeService.running ? green : muted);
        top.addView(state);
        statusCard.addView(top);
        spaceIn(statusCard, 12);
        statusCard.addView(text(BridgeService.running ? "Your bridge is running" : "Your bridge is stopped", 22, true));
        statusCard.addView(text(BridgeService.running ? "Accepting local connections on this device." : "Start the bridge to enable MCP connections.", 13, false, muted));
        spaceIn(statusCard, 18);
        LinearLayout controls = row();
        Button start = button("▶  Start bridge", true);
        start.setOnClickListener(v -> startBridge());
        Button stop = button("■  Stop", false);
        stop.setOnClickListener(v -> stopBridge());
        controls.addView(start, new LinearLayout.LayoutParams(0, dp(48), 1));
        controls.addView(stop, new LinearLayout.LayoutParams(0, dp(48), 1));
        statusCard.addView(controls);
        addCard(statusCard);

        addSection("Connection endpoints", "Localhost services available on this device");
        LinearLayout endpoints = card();
        endpoint(endpoints, "MCP SERVER", "127.0.0.1:" + BridgeConfig.port(this), "Copy URL", BridgeConfig.connectionUrl(this));
        divider(endpoints);
        endpoint(endpoints, "SNAPGENTV3 BRIDGE", "127.0.0.1:" + BridgeConfig.NATIVE_PORT, "Copy", "ws://127.0.0.1:" + BridgeConfig.NATIVE_PORT);
        addCard(endpoints);

        addSection("Quick actions", "Manage your bridge connection");
        LinearLayout actions = card();
        actionRow(actions, "⧉", "Copy MCP connection URL", "Copy the authenticated endpoint", () -> copyText(BridgeConfig.connectionUrl(this), "MCP URL copied"));
        divider(actions);
        actionRow(actions, "⚙", "Configure bridge", "Port, appearance and security", () -> showPage("settings"));
        addCard(actions);

        LinearLayout security = card();
        security.addView(text("✦  PRIVATE BY DESIGN", 12, true, accent));
        spaceIn(security, 8);
        security.addView(text("Loopback-only networking", 15, true));
        security.addView(text("The bridge listens on 127.0.0.1. Filesystem tools are restricted to the app-private workspace.", 12, false, muted));
        addCard(security);
    }

    private void renderLogs() {
        addLabel("BRIDGE ACTIVITY");
        addText("Activity logs", 25, true, textColor);
        addText("Monitor MCP and SnapgentV3 connections and events.", 13, false, muted);
        space(18);

        LinearLayout toolbar = card();
        toolbar.addView(text(BridgeLog.size() + " / 500 events stored", 12, true, muted));
        spaceIn(toolbar, 12);

        LinearLayout filters = row();
        addLogFilterButton(filters, "ALL", "ALL");
        addLogFilterButton(filters, "INFO", "INFO");
        addLogFilterButton(filters, "WARN", "WARN");
        addLogFilterButton(filters, "ERROR", "ERROR");
        toolbar.addView(filters);
        spaceIn(toolbar, 12);

        LinearLayout actions = row();
        Button refresh = button("↻ Refresh", false);
        refresh.setOnClickListener(v -> renderPage());
        Button clear = button("Clear logs", false);
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Clear activity logs?")
                .setMessage("All stored log entries will be permanently cleared.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    BridgeLog.clear();
                    toast("Logs cleared");
                    renderPage();
                }).show());
        actions.addView(refresh, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        clearParams.leftMargin = dp(8);
        actions.addView(clear, clearParams);
        toolbar.addView(actions);
        addCard(toolbar);

        List<BridgeLog.Entry> entries = BridgeLog.snapshot();
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
        int shown = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            BridgeLog.Entry entry = entries.get(i);
            if (!"ALL".equals(logFilter) && !logFilter.equals(entry.level)) continue;

            int levelColor = "ERROR".equals(entry.level)
                    ? Color.parseColor("#F05D6C")
                    : "WARN".equals(entry.level)
                    ? Color.parseColor("#E7A23B") : green;

            LinearLayout item = card();
            item.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout metadata = row();
            TextView level = text(entry.level, 10, true, levelColor);
            metadata.addView(level);
            TextView source = text("  " + format.format(new Date(entry.timestamp)) + "  •  " + entry.source, 10, false, muted);
            metadata.addView(source, new LinearLayout.LayoutParams(0, -2, 1));
            item.addView(metadata);
            TextView message = text(entry.message, 13, false, textColor);
            message.setPadding(0, dp(7), 0, 0);
            item.addView(message);
            addCard(item);
            shown++;
        }

        if (shown == 0) {
            LinearLayout empty = card();
            empty.addView(text(entries.isEmpty()
                    ? "No activity yet. Bridge events will appear here."
                    : "No entries found for this filter.", 13, false, muted));
            addCard(empty);
        }
    }

    private void addLogFilterButton(LinearLayout parent, String label, String filter) {
        boolean selected = logFilter.equals(filter);
        Button filterButton = button(label, selected);
        filterButton.setTextSize(11);
        filterButton.setBackground(round(selected ? accent : bg, 10, selected ? accent : border));
        filterButton.setTextColor(selected ? Color.WHITE : muted);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        filterButton.setOnClickListener(v -> {
            logFilter = filter;
            renderPage();
        });
        parent.addView(filterButton, params);
    }

    private void renderSettings() {
        addLabel("PERSONALIZE YOUR BRIDGE");
        addText("Make it yours.", 25, true, textColor);
        addText("Manage local connection and app preferences.", 13, false, muted);
        space(18);

        addSection("Appearance", "Choose how the app looks");
        LinearLayout appearance = card();
        Switch dark = settingSwitch("Dark appearance", "Use the dark interface", BridgeConfig.darkTheme(this));
        appearance.addView(dark);
        dark.setOnCheckedChangeListener((button, checked) -> {
            BridgeConfig.setDarkTheme(this, checked);
            applyColors();
            recreate();
        });
        addCard(appearance);

        addSection("Connection", "Configure the local MCP server");
        LinearLayout connection = card();
        connection.addView(text("MCP server port", 15, true));
        connection.addView(text("Valid range: 1024–65535. Port 17613 is reserved for SnapgentV3.", 12, false, muted));
        spaceIn(connection, 12);
        portInput = new EditText(this);
        portInput.setSingleLine(true);
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        portInput.setText(String.valueOf(BridgeConfig.port(this)));
        portInput.setTextColor(textColor);
        portInput.setHintTextColor(muted);
        portInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        portInput.setBackground(round(surface, 12, border));
        connection.addView(portInput, new LinearLayout.LayoutParams(-1, dp(50)));
        spaceIn(connection, 12);
        Button savePort = button("Save connection port", true);
        savePort.setOnClickListener(v -> savePort());
        connection.addView(savePort, new LinearLayout.LayoutParams(-1, dp(46)));
        addCard(connection);

        addSection("Startup", "Control when the local service starts");
        LinearLayout startup = card();
        Switch auto = settingSwitch("Start when app opens", "Automatically start the bridge when you launch this app", BridgeConfig.autoStart(this));
        startup.addView(auto);
        auto.setOnCheckedChangeListener((button, checked) -> {
            BridgeConfig.setAutoStart(this, checked);
            toast(checked ? "Automatic startup enabled" : "Automatic startup disabled");
        });
        addCard(startup);

        addSection("Security", "Manage your private access credential");
        LinearLayout security = card();
        security.addView(text("Authentication token", 15, true));
        security.addView(text("The token protects the MCP WebSocket endpoint. Keep your connection URL private.", 12, false, muted));
        spaceIn(security, 14);
        Button copy = button("⧉  Copy MCP connection URL", false);
        copy.setOnClickListener(v -> copyText(BridgeConfig.connectionUrl(this), "Connection URL copied"));
        security.addView(copy, new LinearLayout.LayoutParams(-1, dp(46)));
        spaceIn(security, 8);
        Button regenerate = button("↻  Regenerate access token", false);
        regenerate.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Regenerate access token?")
                .setMessage("The existing MCP connection URL will stop working. You will need to copy the new URL to your client.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Regenerate", (dialog, which) -> {
                    BridgeConfig.regenerateToken(this);
                    if (BridgeService.running) restartBridge();
                    else toast("New token generated");
                }).show());
        security.addView(regenerate, new LinearLayout.LayoutParams(-1, dp(46)));
        addCard(security);

        addSection("About", "Application information");
        LinearLayout about = card();
        infoRow(about, "Application", "Snapgent Bridge");
        divider(about);
        infoRow(about, "Version", BridgeConfig.appVersion(this));
        divider(about);
        infoRow(about, "MCP protocol", "2025-06-18");
        divider(about);
        infoRow(about, "Storage", "App-private workspace");
        addCard(about);
    }

    private void savePort() {
        String value = portInput.getText().toString().trim();
        try {
            int port = Integer.parseInt(value);
            if (!BridgeConfig.setPort(this, port)) {
                toast("Invalid port. Use 1024–65535 except 17613.");
                return;
            }
            toast("Port saved: " + port);
            if (BridgeService.running) restartBridge();
            else renderPage();
        } catch (NumberFormatException e) {
            toast("Enter a valid port number");
        }
    }

    private void startBridge() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 120);
        }
        Intent intent = new Intent(this, BridgeService.class);
        intent.setAction(BridgeService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
            toast("Starting bridge...");
            new Handler(Looper.getMainLooper()).postDelayed(() -> { if (page.equals("home")) renderPage(); }, 800);
        } catch (Exception e) {
            toast("Cannot start bridge: " + e.getMessage());
        }
    }

    private void stopBridge() {
        Intent intent = new Intent(this, BridgeService.class);
        intent.setAction(BridgeService.ACTION_STOP);
        startService(intent);
        new Handler(Looper.getMainLooper()).postDelayed(() -> { if (page.equals("home")) renderPage(); }, 500);
    }

    private void restartBridge() {
        Intent restart = new Intent(this, BridgeService.class);
        restart.setAction(BridgeService.ACTION_RESTART);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(restart);
            else startService(restart);
            toast("Restarting bridge...");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (page.equals("home")) renderPage();
            }, 800);
        } catch (Exception e) {
            toast("Cannot restart bridge: " + e.getMessage());
        }
    }

    private void endpoint(LinearLayout parent, String label, String address, String action, String value) {
        LinearLayout item = new LinearLayout(this);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(0, dp(12), 0, dp(12));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView heading = text(label, 10, true, muted);
        heading.setLetterSpacing(0.08f);
        info.addView(heading);
        info.addView(text(address, 14, true));
        item.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        Button copy = button(action, false);
        copy.setTextSize(11);
        copy.setMinWidth(dp(62));
        copy.setOnClickListener(v -> copyText(value, "Endpoint copied"));
        item.addView(copy, new LinearLayout.LayoutParams(-2, dp(38)));
        parent.addView(item);
    }

    private void actionRow(LinearLayout parent, String icon, String title, String description, Runnable action) {
        LinearLayout item = row();
        TextView symbol = text(icon, 20, true, accent);
        symbol.setGravity(Gravity.CENTER);
        symbol.setBackground(round(bg, 12));
        item.addView(symbol, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, 0, 0);
        labels.addView(text(title, 14, true));
        labels.addView(text(description, 11, false, muted));
        item.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        item.setPadding(0, dp(10), 0, dp(10));
        item.setOnClickListener(v -> action.run());
        parent.addView(item);
    }

    private Switch settingSwitch(String title, String description, boolean checked) {
        Switch control = new Switch(this);
        control.setText(title + "\n" + description);
        control.setTextColor(textColor);
        control.setTextSize(14);
        control.setChecked(checked);
        control.setPadding(0, dp(8), 0, dp(8));
        control.setThumbTintList(ColorStateList.valueOf(checked ? accent : muted));
        control.setTrackTintList(ColorStateList.valueOf(border));
        return control;
    }

    private void infoRow(LinearLayout parent, String label, String value) {
        LinearLayout item = row();
        item.setPadding(0, dp(11), 0, dp(11));
        item.addView(text(label, 13, false, muted), new LinearLayout.LayoutParams(0, -2, 1));
        item.addView(text(value, 13, true));
        parent.addView(item);
    }

    private void addSection(String title, String subtitle) {
        space(13);
        content.addView(text(title, 16, true));
        content.addView(text(subtitle, 12, false, muted));
        space(7);
    }

    private void addLabel(String value) {
        TextView label = text(value, 10, true, accent);
        label.setLetterSpacing(0.13f);
        content.addView(label);
    }

    private void addText(String value, int size, boolean bold, int color) {
        content.addView(text(value, size, bold, color));
    }

    private TextView text(String value, int size, boolean bold) { return text(value, size, bold, textColor); }

    private TextView text(String value, int size, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(17), dp(16), dp(17), dp(16));
        layout.setBackground(round(surface, 18, border));
        return layout;
    }

    private void addCard(LinearLayout layout) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(10);
        content.addView(layout, params);
    }

    private Button button(String label, boolean primary) {
        Button view = new Button(this);
        view.setText(label);
        view.setTextSize(13);
        view.setAllCaps(false);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(primary ? Color.WHITE : textColor);
        view.setPadding(dp(12), dp(7), dp(12), dp(7));
        view.setMinHeight(dp(42));
        view.setBackground(round(primary ? accent : bg, 12, primary ? accent : border));
        view.setStateListAnimator(null);
        return view;
    }

    private void addNavButton(String label, String target) {
        Button nav = button(label, page.equals(target));
        nav.setTextSize(12);
        nav.setBackground(round(page.equals(target) ? accent : surface, 12));
        nav.setTextColor(page.equals(target) ? Color.WHITE : muted);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        nav.setOnClickListener(v -> showPage(target));
        bottomNav.addView(nav, params);
    }

    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(border);
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void space(int value) {
        View gap = new View(this);
        content.addView(gap, new LinearLayout.LayoutParams(1, dp(value)));
    }

    private void spaceIn(LinearLayout parent, int value) {
        View gap = new View(this);
        parent.addView(gap, new LinearLayout.LayoutParams(1, dp(value)));
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable drawable = round(color, radius);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private void copyText(String value, String message) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Snapgent Bridge", value));
        toast(message);
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    @Override protected void onResume() {
        super.onResume();
        if (content != null && page.equals("home")) renderPage();
    }
}
