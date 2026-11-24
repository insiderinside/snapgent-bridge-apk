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
    // Files page state: path of the open folder ("" = workspace root) and the file being previewed.
    private String fileDir = "";
    private String fileView = null;
    private static final int REQUEST_IMPORT_FILES = 2201;
    private static final long MAX_IMPORT_BYTES = 256L * 1024 * 1024;
    private static final long MAX_PREVIEW_BYTES = 1024L * 1024;
    private static final int HEX_PREVIEW_BYTES = 1024;
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
        if (next.equals("files")) fileView = null;
        renderPage();
    }

    private void renderPage() {
        if (content == null) return;
        content.removeAllViews();
        boolean viewing = page.equals("files") && fileView != null;
        headerTitle.setText(viewing ? "File" : page.equals("settings") ? "Settings" : page.equals("logs") ? "Activity Logs"
                : page.equals("tools") ? "Tools" : page.equals("files") ? "Files" : "Snapgent Bridge");
        headerAction.setText(page.equals("home") ? "⚙" : "‹");
        if (viewing) {
            headerAction.setOnClickListener(v -> { fileView = null; renderPage(); });
        } else if (page.equals("files") && !fileDir.isEmpty()) {
            headerAction.setOnClickListener(v -> { fileDir = parentDir(fileDir); fileView = null; renderPage(); });
        } else {
            headerAction.setOnClickListener(v -> showPage(page.equals("home") ? "settings" : "home"));
        }
        bottomNav.removeAllViews();
        addNavButton("⌂", "home");
        addNavButton("⚒ Tools", "tools");
        addNavButton("▤ Files", "files");
        addNavButton("≡ Logs", "logs");
        if (page.equals("settings")) renderSettings();
        else if (page.equals("logs")) renderLogs();
        else if (page.equals("tools")) renderTools();
        else if (page.equals("files")) renderFiles();
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

        renderGitHubSettings();

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

    private void renderGitHubSettings() {
        addSection("GitHub", "Connect your GitHub account for remote repository operations");
        LinearLayout github = card();
        boolean configured = GitHubCredentials.isConfigured(this);

        github.addView(text("GitHub account", 15, true));
        github.addView(text("The username is detected from your validated token.", 12, false, muted));
        spaceIn(github, 10);

        EditText usernameInput = new EditText(this);
        usernameInput.setSingleLine(true);
        usernameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        usernameInput.setText(configured ? GitHubCredentials.username(this) : "");
        usernameInput.setHint("GitHub username");
        usernameInput.setEnabled(false);
        usernameInput.setTextColor(textColor);
        usernameInput.setHintTextColor(muted);
        usernameInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        usernameInput.setBackground(round(surface, 12, border));
        github.addView(usernameInput, new LinearLayout.LayoutParams(-1, dp(50)));
        spaceIn(github, 10);

        EditText tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setHint(configured ? "New Personal Access Token (optional)" : "GitHub Personal Access Token");
        tokenInput.setTextColor(textColor);
        tokenInput.setHintTextColor(muted);
        tokenInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        tokenInput.setBackground(round(surface, 12, border));
        github.addView(tokenInput, new LinearLayout.LayoutParams(-1, dp(50)));
        spaceIn(github, 8);

        TextView status = text(configured
                ? "Credential saved securely using Android Keystore."
                : "GitHub is not configured yet.", 12, false, muted);
        github.addView(status);
        spaceIn(github, 12);

        Button save = button("Validate and save GitHub account", true);
        save.setOnClickListener(v -> {
            String candidateToken = tokenInput.getText().toString().trim();
            save.setEnabled(false);
            save.setText("Validating...");
            status.setText("Connecting to GitHub...");

            new Thread(() -> {
                try {
                    GitHubCredentials.Validation validation;
                    if (candidateToken.isEmpty() && GitHubCredentials.isConfigured(this)) {
                        validation = GitHubCredentials.validate(this);
                    } else {
                        validation = GitHubCredentials.validateToken(candidateToken);
                    }
                    if (!validation.authenticated) {
                        throw new java.io.IOException("GitHub rejected the token (HTTP " + validation.statusCode + ")");
                    }
                    GitHubCredentials.save(this, validation.login, candidateToken);
                    runOnUiThread(() -> {
                        usernameInput.setText(validation.login);
                        tokenInput.setText("");
                        status.setText("Connected as @" + validation.login + (validation.scopes.isEmpty() ? "" : "\\nScopes: " + validation.scopes));
                        status.setTextColor(green);
                        save.setEnabled(true);
                        save.setText("Validate and save GitHub account");
                        toast("GitHub credentials saved securely");
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        status.setText("Validation failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                        status.setTextColor(Color.parseColor("#F05D6C"));
                        save.setEnabled(true);
                        save.setText("Validate and save GitHub account");
                    });
                }
            }, "github-credential-validation").start();
        });
        github.addView(save, new LinearLayout.LayoutParams(-1, dp(46)));

        if (configured) {
            spaceIn(github, 8);
            Button remove = button("Remove GitHub credentials", false);
            remove.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Remove GitHub account?")
                    .setMessage("GitHub clone, fetch, pull and push operations will stop working until credentials are configured again.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove", (dialog, which) -> {
                        try {
                            GitHubCredentials.clear(this);
                            toast("GitHub credentials removed");
                            showPage("settings");
                        } catch (Exception e) {
                            toast("Cannot remove credentials: " + e.getMessage());
                        }
                    }).show());
            github.addView(remove, new LinearLayout.LayoutParams(-1, dp(46)));
        }
        addCard(github);
    }

    // ---------------------------------------------------------------- tools page

    private void renderTools() {
        addLabel("TOOL REGISTRY");
        addText("Tools", 25, true, textColor);
        addText("Switch a tool off to hide it from MCP clients. Custom tools run on this device.", 13, false, muted);
        space(18);

        org.json.JSONArray summaries;
        int enabledCount = 0;
        int customCount = 0;
        try {
            summaries = new McpHandler(this).toolSummaries();
        } catch (Exception e) {
            addText("Cannot read the tool registry: " + e.getMessage(), 12, false, muted);
            return;
        }
        for (int i = 0; i < summaries.length(); i++) {
            org.json.JSONObject entry = summaries.optJSONObject(i);
            if (entry == null) continue;
            if (entry.optBoolean("enabled")) enabledCount++;
            if ("custom".equals(entry.optString("kind"))) customCount++;
        }

        LinearLayout summary = card();
        summary.addView(text(enabledCount + " of " + summaries.length() + " tools enabled", 18, true));
        summary.addView(text(customCount + " custom tool" + (customCount == 1 ? "" : "s") + " on this device", 12, false, muted));
        spaceIn(summary, 12);
        LinearLayout summaryActions = row();
        Button add = button("+  New tool", true);
        add.setOnClickListener(v -> showCustomToolDialog());
        Button enableAll = button("Enable all", false);
        enableAll.setOnClickListener(v -> {
            ToolSettings.setAllEnabled(this, true);
            toast("All tools enabled");
            renderPage();
        });
        summaryActions.addView(add, new LinearLayout.LayoutParams(0, dp(46), 1));
        summaryActions.addView(new View(this), new LinearLayout.LayoutParams(dp(8), dp(46)));
        summaryActions.addView(enableAll, new LinearLayout.LayoutParams(0, dp(46), 1));
        summary.addView(summaryActions);
        addCard(summary);

        addSection("Built-in tools", "Shipped with Snapgent Bridge");
        LinearLayout builtin = card();
        boolean firstBuiltin = true;
        for (int i = 0; i < summaries.length(); i++) {
            org.json.JSONObject entry = summaries.optJSONObject(i);
            if (entry == null || !"builtin".equals(entry.optString("kind"))) continue;
            if (!firstBuiltin) divider(builtin);
            firstBuiltin = false;
            toolRow(builtin, entry);
        }
        addCard(builtin);

        addSection("Custom tools", "Your own python or http tools, served over MCP");
        LinearLayout customCard = card();
        if (customCount == 0) {
            customCard.addView(text("No custom tool yet", 14, true));
            spaceIn(customCard, 6);
            customCard.addView(text("Create one here, or let your MCP client call tool_create.", 12, false, muted));
        } else {
            boolean firstCustom = true;
            for (int i = 0; i < summaries.length(); i++) {
                org.json.JSONObject entry = summaries.optJSONObject(i);
                if (entry == null || !"custom".equals(entry.optString("kind"))) continue;
                if (!firstCustom) divider(customCard);
                firstCustom = false;
                toolRow(customCard, entry);
            }
        }
        addCard(customCard);
    }

    private void toolRow(LinearLayout parent, org.json.JSONObject entry) {
        String name = entry.optString("name");
        LinearLayout item = row();
        item.setPadding(0, dp(9), 0, dp(9));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(name, 13, true);
        title.setSingleLine(true);
        TextView description = text(entry.optString("description", ""), 11, false, muted);
        description.setSingleLine(true);
        description.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(title);
        labels.addView(description);
        item.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        if ("custom".equals(entry.optString("kind"))) {
            Button remove = button("✕", false);
            remove.setTextSize(12);
            remove.setMinWidth(dp(36));
            remove.setPadding(0, 0, 0, 0);
            remove.setOnClickListener(v -> confirmDeleteCustomTool(name));
            item.addView(remove, new LinearLayout.LayoutParams(dp(36), dp(38)));
        }

        if ("management".equals(entry.optString("kind"))) {
            // Always available: these are the tools that manage the others.
            TextView always = text("always on", 11, true, green);
            always.setPadding(dp(8), 0, dp(4), 0);
            item.addView(always, new LinearLayout.LayoutParams(-2, -2));
            parent.addView(item);
            return;
        }

        Switch toggle = new Switch(this);
        toggle.setChecked(entry.optBoolean("enabled", true));
        toggle.setThumbTintList(ColorStateList.valueOf(toggle.isChecked() ? accent : muted));
        toggle.setTrackTintList(ColorStateList.valueOf(border));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            ToolSettings.setEnabled(this, name, checked);
            toast(name + (checked ? " enabled" : " disabled"));
        });
        item.addView(toggle, new LinearLayout.LayoutParams(-2, dp(42)));
        parent.addView(item);
    }

    private void showCustomToolDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);

        EditText nameInput = toolInput("Tool name, e.g. fetch_page", InputType.TYPE_CLASS_TEXT);
        EditText descriptionInput = toolInput("What it does", InputType.TYPE_CLASS_TEXT);
        EditText codeInput = toolInput("Python code (arguments are in the dict ARGS) or an https URL", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        codeInput.setMinLines(4);
        codeInput.setMaxLines(10);
        codeInput.setGravity(Gravity.TOP | Gravity.START);

        String[] kinds = {"python", "http"};
        final String[] selectedKind = {"python"};
        android.widget.Spinner spinner = new android.widget.Spinner(this);
        spinner.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Python code", "HTTP request"}));
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                selectedKind[0] = kinds[position];
                codeInput.setHint(position == 0
                        ? "Python: result = ARGS.get('topic', '')\nprint('hello ' + result)"
                        : "https://api.example.com/items/{{id}}");
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        form.addView(text("Name", 12, true, muted));
        form.addView(nameInput, new LinearLayout.LayoutParams(-1, dp(48)));
        spaceIn(form, 8);
        form.addView(text("Description", 12, true, muted));
        form.addView(descriptionInput, new LinearLayout.LayoutParams(-1, dp(48)));
        spaceIn(form, 8);
        form.addView(text("Kind", 12, true, muted));
        form.addView(spinner, new LinearLayout.LayoutParams(-1, dp(48)));
        spaceIn(form, 8);
        form.addView(text("Body", 12, true, muted));
        form.addView(codeInput, new LinearLayout.LayoutParams(-1, dp(150)));

        new AlertDialog.Builder(this)
                .setTitle("New custom tool")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    String description = descriptionInput.getText().toString().trim();
                    String body = codeInput.getText().toString();
                    new Thread(() -> {
                        try {
                            org.json.JSONObject args = new org.json.JSONObject()
                                    .put("name", name)
                                    .put("description", description.isEmpty() ? "Custom tool " + name : description)
                                    .put("kind", selectedKind[0]);
                            if ("python".equals(selectedKind[0])) args.put("code", body);
                            else args.put("url", body.trim());
                            java.util.Set<String> reserved = new java.util.HashSet<>();
                            org.json.JSONArray existing = new McpHandler(this).toolSummaries();
                            for (int i = 0; i < existing.length(); i++) {
                                org.json.JSONObject entry = existing.optJSONObject(i);
                                if (entry != null && "builtin".equals(entry.optString("kind"))) reserved.add(entry.optString("name"));
                            }
                            new CustomTools(this, new PythonTools(this)).execute("tool_create", args, reserved);
                            runOnUiThread(() -> {
                                toast("Tool created: " + name);
                                page = "tools";
                                renderPage();
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> toast("Cannot create tool: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
                        }
                    }, "custom-tool-create").start();
                })
                .show();
    }

    private EditText toolInput(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(false);
        input.setInputType(inputType);
        input.setTextColor(textColor);
        input.setHintTextColor(muted);
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        input.setBackground(round(surface, 12, border));
        return input;
    }

    private void confirmDeleteCustomTool(String name) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + name + "?")
                .setMessage("The definition is removed from this device and disappears from tools/list.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    try {
                        new CustomTools(this, new PythonTools(this)).execute("tool_delete",
                                new org.json.JSONObject().put("name", name), java.util.Set.<String>of());
                        toast("Tool deleted: " + name);
                        page = "tools";
                        renderPage();
                    } catch (Exception e) {
                        toast("Cannot delete: " + e.getMessage());
                    }
                }).show();
    }

    // ---------------------------------------------------------------- files page

    private void renderFiles() {
        if (fileView != null) { renderFileViewer(); return; }
        java.io.File root = getFilesDir();
        java.io.File current = currentDir();
        addLabel("WORKSPACE STORAGE");
        addText(fileDir.isEmpty() ? "Files" : fileNameOf(fileDir), 25, true, textColor);
        addText(fileDir.isEmpty()
                ? "App-private storage shared with the MCP tools. Import a voice-over or any asset here."
                : "Relative path: " + fileDir, 13, false, muted);
        space(18);

        LinearLayout toolbar = card();
        toolbar.addView(text(formatBytes(root.getUsableSpace()) + " free", 15, true));
        toolbar.addView(text(root.getAbsolutePath(), 11, false, muted));
        spaceIn(toolbar, 12);
        LinearLayout actions = row();
        Button importButton = button("⇩  Import files", true);
        importButton.setOnClickListener(v -> importFiles());
        Button folderButton = button("+  Folder", false);
        folderButton.setOnClickListener(v -> showNewFolderDialog());
        actions.addView(importButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        actions.addView(new View(this), new LinearLayout.LayoutParams(dp(8), dp(46)));
        actions.addView(folderButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        toolbar.addView(actions);
        addCard(toolbar);

        // Breadcrumb: every segment is tappable, so deep folders are always one tap away.
        LinearLayout crumbCard = card();
        LinearLayout crumbs = row();
        Button rootCrumb = button("workspace", fileDir.isEmpty());
        rootCrumb.setTextSize(11);
        rootCrumb.setOnClickListener(v -> { fileDir = ""; fileView = null; renderPage(); });
        crumbs.addView(rootCrumb, new LinearLayout.LayoutParams(-2, dp(36)));
        String accumulated = "";
        String[] segments = fileDir.isEmpty() ? new String[0] : fileDir.split("/");
        for (int i = 0; i < segments.length; i++) {
            accumulated = accumulated.isEmpty() ? segments[i] : accumulated + "/" + segments[i];
            final String target = accumulated;
            TextView slash = text("›", 13, false, muted);
            slash.setPadding(dp(6), 0, dp(6), 0);
            crumbs.addView(slash);
            Button crumb = button(segments[i], i == segments.length - 1);
            crumb.setTextSize(11);
            crumb.setOnClickListener(v -> { fileDir = target; fileView = null; renderPage(); });
            crumbs.addView(crumb, new LinearLayout.LayoutParams(-2, dp(36)));
        }
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(crumbs);
        crumbCard.addView(scroller);
        addCard(crumbCard);

        addSection("Contents", "Tap a folder to open it, a file to view it");
        java.io.File[] entries = current.listFiles();
        if (entries == null || entries.length == 0) {
            LinearLayout empty = card();
            empty.addView(text("This folder is empty", 14, true));
            spaceIn(empty, 6);
            empty.addView(text("Import a file, or let an MCP client write one with write_file / write_file_base64.", 12, false, muted));
            addCard(empty);
            return;
        }
        java.util.Arrays.sort(entries, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        LinearLayout list = card();
        boolean first = true;
        for (java.io.File entry : entries) {
            if (!first) divider(list);
            first = false;
            fileRow(list, root, entry);
        }
        addCard(list);
    }

    private void fileRow(LinearLayout parent, java.io.File root, java.io.File entry) {
        LinearLayout item = row();
        item.setPadding(0, dp(9), 0, dp(9));
        String relative;
        try {
            relative = root.getCanonicalFile().toPath().relativize(entry.getCanonicalFile().toPath()).toString();
        } catch (Exception e) {
            relative = entry.getName();
        }
        final String path = relative;
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = text((entry.isDirectory() ? "▸ " : "▤ ") + entry.getName(), 13, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        TextView meta = text(entry.isDirectory()
                ? "folder" + (entry.listFiles() == null ? "" : " · " + entry.listFiles().length + " items")
                : formatBytes(entry.length()) + " · " + mimeOf(entry), 11, false, muted);
        labels.addView(name);
        labels.addView(meta);
        item.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        TextView chevron = text(entry.isDirectory() ? "›" : "👁", 15, false, muted);
        chevron.setGravity(Gravity.CENTER);
        item.addView(chevron, new LinearLayout.LayoutParams(dp(26), dp(38)));

        Button remove = button("✕", false);
        remove.setTextSize(12);
        remove.setMinWidth(dp(36));
        remove.setPadding(0, 0, 0, 0);
        remove.setOnClickListener(v -> confirmDeleteFile(entry));
        item.addView(remove, new LinearLayout.LayoutParams(dp(36), dp(38)));
        item.setOnClickListener(v -> {
            if (entry.isDirectory()) {
                fileDir = path;
                fileView = null;
                renderPage();
            } else {
                fileView = path;
                renderPage();
            }
        });
        item.setBackground(round(surface, 12));
        parent.addView(item);
    }

    private String parentDir(String path) {
        int cut = path.lastIndexOf('/');
        return cut <= 0 ? "" : path.substring(0, cut);
    }

    private String fileNameOf(String path) {
        int cut = path.lastIndexOf('/');
        return cut < 0 ? path : path.substring(cut + 1);
    }

    /** Resolves a workspace-relative path, refusing anything that escapes the workspace. */
    private java.io.File workspaceFile(String relative) {
        try {
            java.io.File root = getFilesDir().getCanonicalFile();
            java.io.File target = new java.io.File(root, relative).getCanonicalFile();
            String rootPath = root.getPath();
            if (!target.getPath().equals(rootPath) && !target.getPath().startsWith(rootPath + java.io.File.separator)) return null;
            return target;
        } catch (Exception e) {
            return null;
        }
    }

    private java.io.File currentDir() {
        if (fileDir.isEmpty()) return getFilesDir();
        java.io.File dir = workspaceFile(fileDir);
        return dir != null && dir.isDirectory() ? dir : getFilesDir();
    }

    private void renderFileViewer() {
        java.io.File file = workspaceFile(fileView);
        if (file == null || !file.isFile()) {
            toast("File is gone: " + fileView);
            fileView = null;
            renderFiles();
            return;
        }
        addLabel("FILE");
        addText(file.getName(), 22, true, textColor);
        addText(fileView + " · " + formatBytes(file.length()) + " · " + mimeOf(file), 11, false, muted);
        space(14);

        LinearLayout actions = card();
        LinearLayout buttons = row();
        Button open = button("↗  Open with", true);
        open.setOnClickListener(v -> openWith(file));
        Button share = button("⇪  Share", false);
        share.setOnClickListener(v -> shareFile(file));
        Button copy = button("⧉", false);
        copy.setOnClickListener(v -> copyText(fileView, "Copied: " + fileView));
        buttons.addView(open, new LinearLayout.LayoutParams(0, dp(44), 3));
        buttons.addView(new View(this), new LinearLayout.LayoutParams(dp(8), dp(44)));
        buttons.addView(share, new LinearLayout.LayoutParams(0, dp(44), 2));
        buttons.addView(new View(this), new LinearLayout.LayoutParams(dp(8), dp(44)));
        buttons.addView(copy, new LinearLayout.LayoutParams(dp(44), dp(44)));
        actions.addView(buttons);
        spaceIn(actions, 10);
        actions.addView(text("Tap a breadcrumb to go back. Delete from the folder list.", 11, false, muted));
        addCard(actions);

        addSection("Preview", file.length() > MAX_PREVIEW_BYTES ? "Large file — showing the first " + formatBytes(MAX_PREVIEW_BYTES) : "Rendered in the app, nothing leaves the device");
        String mime = mimeOf(file);
        if (mime.startsWith("image/")) {
            android.graphics.Bitmap bitmap = decodeImage(file);
            if (bitmap != null) {
                LinearLayout holder = card();
                android.widget.ImageView view = new android.widget.ImageView(this);
                view.setAdjustViewBounds(true);
                view.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                view.setImageBitmap(bitmap);
                holder.addView(view);
                addCard(holder);
                return;
            }
        }
        if (isTextFile(file)) {
            String body = readTextPreview(file);
            LinearLayout holder = card();
            if (body == null) {
                holder.addView(text("Cannot read this file as text.", 13, false, muted));
            } else {
                TextView code = text(body, 11, false, textColor);
                code.setTypeface(Typeface.MONOSPACE);
                code.setTextIsSelectable(true);
                android.widget.HorizontalScrollView horizontal = new android.widget.HorizontalScrollView(this);
                horizontal.setHorizontalScrollBarEnabled(true);
                horizontal.addView(code);
                holder.addView(horizontal);
            }
            addCard(holder);
            return;
        }
        LinearLayout binary = card();
        binary.addView(text("Binary file", 14, true));
        spaceIn(binary, 6);
        binary.addView(text(mime + " · no in-app preview. Use Open with or Share to hand it to another app.", 12, false, muted));
        spaceIn(binary, 10);
        TextView dump = text(hexPreview(file), 10, false, muted);
        dump.setTypeface(Typeface.MONOSPACE);
        dump.setTextIsSelectable(true);
        android.widget.HorizontalScrollView horizontal = new android.widget.HorizontalScrollView(this);
        horizontal.addView(dump);
        binary.addView(horizontal);
        addCard(binary);
    }

    private String mimeOf(java.io.File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.US));
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    private boolean isTextFile(java.io.File file) {
        String mime = mimeOf(file);
        if (mime.startsWith("text/")) return true;
        String name = file.getName().toLowerCase(Locale.US);
        for (String extension : new String[]{".json", ".md", ".py", ".js", ".mjs", ".ts", ".java", ".kt", ".gradle", ".properties", ".yaml", ".yml", ".xml", ".csv", ".log", ".sh", ".txt", ".ini", ".conf", ".toml"}) {
            if (name.endsWith(extension)) return true;
        }
        return mime.equals("application/json") || mime.equals("application/xml") || mime.equals("application/javascript");
    }

    private String readTextPreview(java.io.File file) {
        long size = Math.min(file.length(), MAX_PREVIEW_BYTES);
        if (size <= 0) return size == 0 ? "" : null;
        byte[] buffer = new byte[(int) size];
        try (java.io.InputStream input = new java.io.FileInputStream(file)) {
            int read = 0;
            while (read < buffer.length) {
                int count = input.read(buffer, read, buffer.length - read);
                if (count <= 0) break;
                read += count;
            }
            return new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private android.graphics.Bitmap decodeImage(java.io.File file) {
        try {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / sample > 1400 || bounds.outHeight / sample > 1400) sample *= 2;
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError | Exception e) {
            return null;
        }
    }

    private String hexPreview(java.io.File file) {
        byte[] buffer = new byte[(int) Math.min(file.length(), HEX_PREVIEW_BYTES)];
        try (java.io.InputStream input = new java.io.FileInputStream(file)) {
            int read = 0;
            while (read < buffer.length) {
                int count = input.read(buffer, read, buffer.length - read);
                if (count <= 0) break;
                read += count;
            }
            StringBuilder out = new StringBuilder();
            for (int offset = 0; offset < read; offset += 16) {
                out.append(String.format(Locale.US, "%06X  ", offset));
                StringBuilder ascii = new StringBuilder();
                for (int i = 0; i < 16; i++) {
                    if (offset + i < read) {
                        int value = buffer[offset + i] & 0xFF;
                        out.append(String.format(Locale.US, "%02X ", value));
                        ascii.append(value >= 32 && value < 127 ? (char) value : '.');
                    } else {
                        out.append("   ");
                    }
                }
                out.append(" ").append(ascii).append('\n');
            }
            return out.toString();
        } catch (Exception e) {
            return "Cannot read this file.";
        }
    }

    private android.net.Uri fileUri(java.io.File file) {
        java.io.File root = getFilesDir();
        String relative;
        try {
            relative = root.getCanonicalFile().toPath().relativize(file.getCanonicalFile().toPath()).toString();
        } catch (Exception e) {
            relative = file.getName();
        }
        return android.net.Uri.parse("content://" + WorkspaceProvider.AUTHORITY + "/" + android.net.Uri.encode(relative, "/"));
    }

    private void openWith(java.io.File file) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(fileUri(file), mimeOf(file));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception e) {
            toast("No app can open " + mimeOf(file));
        }
    }

    private void shareFile(java.io.File file) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(mimeOf(file));
        intent.putExtra(Intent.EXTRA_STREAM, fileUri(file));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, "Share " + file.getName()));
        } catch (Exception e) {
            toast("Cannot share: " + e.getMessage());
        }
    }

    private void importFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQUEST_IMPORT_FILES);
        } catch (Exception e) {
            toast("No file picker available: " + e.getMessage());
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_FILES || resultCode != RESULT_OK || data == null) return;
        java.util.List<android.net.Uri> uris = new java.util.ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        toast("Importing " + uris.size() + " file(s)...");
        final java.io.File targetDir = currentDir();
        new Thread(() -> {
            int imported = 0;
            String failure = null;
            for (android.net.Uri uri : uris) {
                try {
                    copyIntoWorkspace(uri, targetDir);
                    imported++;
                } catch (Exception e) {
                    failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
            }
            final int count = imported;
            final String error = failure;
            runOnUiThread(() -> {
                toast(error == null ? "Imported " + count + " file(s)" : "Imported " + count + ", failed: " + error);
                page = "files";
                renderPage();
            });
        }, "workspace-import").start();
    }

    private void copyIntoWorkspace(android.net.Uri uri, java.io.File directory) throws java.io.IOException {
        String name = displayName(uri);
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "-");
        java.io.File target = new java.io.File(directory, uniqueName(safe, directory));
        long total = 0;
        try (java.io.InputStream input = getContentResolver().openInputStream(uri);
             java.io.OutputStream output = new java.io.FileOutputStream(target)) {
            if (input == null) throw new java.io.IOException("Cannot open " + name);
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_IMPORT_BYTES) throw new java.io.IOException(name + " is larger than " + formatBytes(MAX_IMPORT_BYTES));
                output.write(buffer, 0, count);
            }
            output.flush();
        }
    }

    private String displayName(android.net.Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(0);
                if (value != null && !value.trim().isEmpty()) return value;
            }
        } catch (Exception ignored) {
            // Fall through to the URI's last segment.
        }
        String fallback = uri.getLastPathSegment();
        return fallback == null ? "import" : fallback.substring(fallback.lastIndexOf('/') + 1);
    }

    private String uniqueName(String name, java.io.File directory) {
        java.io.File candidate = new java.io.File(directory, name);
        if (!candidate.exists()) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            String next = base + "-" + i + extension;
            if (!new java.io.File(directory, next).exists()) return next;
        }
        return base + "-" + System.currentTimeMillis() + extension;
    }

    private void showNewFolderDialog() {
        EditText input = toolInput("Folder name", InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("New folder")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (dialog, which) -> {
                    String name = input.getText().toString().trim().replaceAll("[\\\\/:*?\"<>|]", "-");
                    if (name.isEmpty()) { toast("Enter a folder name"); return; }
                    java.io.File folder = new java.io.File(currentDir(), name);
                    if (folder.mkdirs()) {
                        toast("Folder created: " + name);
                        fileDir = fileDir.isEmpty() ? name : fileDir + "/" + name;
                        fileView = null;
                        renderPage();
                    } else toast("Cannot create folder");
                }).show();
    }

    private void confirmDeleteFile(java.io.File entry) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + entry.getName() + "?")
                .setMessage(entry.isDirectory() ? "The folder and everything inside it will be removed." : "The file will be removed from app storage.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    if (deleteRecursively(entry)) { toast("Deleted: " + entry.getName()); renderPage(); }
                    else toast("Cannot delete: " + entry.getName());
                }).show();
    }

    private boolean deleteRecursively(java.io.File target) {
        if (target.isDirectory()) {
            java.io.File[] children = target.listFiles();
            if (children != null) for (java.io.File child : children) deleteRecursively(child);
        }
        return target.delete();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
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
