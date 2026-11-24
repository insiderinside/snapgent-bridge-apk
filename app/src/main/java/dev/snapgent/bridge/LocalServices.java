package dev.snapgent.bridge;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loopback services that the user's own tools talk to.
 *
 * <p>A service has no manifest: it exists because something on the device listens on
 * a port. The bridge finds it from the tool definitions themselves, so the Services
 * page and the "service" badge on a tool are both derived from one source — what the
 * tool actually connects to. Two shapes count:
 *
 * <ul>
 *   <li>an address in the code, URL or body: {@code 127.0.0.1:18125}, {@code localhost:18126};</li>
 *   <li>an explicit declaration in a comment, which also names the service:
 *       {@code # snapgent-service: base=18125 small=18126}.</li>
 * </ul>
 *
 * <p>Names come only from a declaration, because guessing a name from a variable is
 * how a page ends up lying. A port found only as an address is shown as "Port 18125".
 *
 * <p>Nothing here starts or stops a service. The app may not exec binaries from its
 * own storage, so services run as the shell user; the page observes them and says so.
 */
final class LocalServices {

    private static final int MIN_PORT = 1024;
    private static final int MAX_PORT = 65535;
    private static final int PROBE_TIMEOUT_MS = 400;
    /** A cached answer older than this is re-probed on the next render. */
    private static final long CACHE_TTL_MS = 10_000L;

    /** {@code # snapgent-service: base=18125} (the {@code snapgent-} prefix is optional). */
    private static final Pattern DECLARATION = Pattern.compile(
            "(?m)^\\s*#\\s*(?:snapgent-)?service\\s*:\\s*(.+)$");
    private static final Pattern PAIR = Pattern.compile(
            "([A-Za-z][A-Za-z0-9_.-]*)\\s*=\\s*(\\d{2,5})");
    private static final Pattern ADDRESS = Pattern.compile(
            "(?i)(?:127\\.0\\.0\\.1|localhost)\\s*:\\s*(\\d{2,5})");

    private static final Map<Integer, Boolean> LIVE = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> CHECKED_AT = new ConcurrentHashMap<>();
    private static final AtomicBoolean PROBING = new AtomicBoolean(false);

    private LocalServices() {
    }

    /** One loopback port plus every custom tool that points at it. */
    static final class Service {
        final int port;
        final String label;
        final List<String> tools;
        final List<String> enabledTools;

        Service(int port, String label, List<String> tools, List<String> enabledTools) {
            this.port = port;
            this.label = label == null ? "" : label;
            this.tools = Collections.unmodifiableList(new ArrayList<>(tools));
            this.enabledTools = Collections.unmodifiableList(new ArrayList<>(enabledTools));
        }

        boolean named() {
            return !label.isEmpty();
        }

        /** What the page shows as the title: the declared name, otherwise the port. */
        String title() {
            return named() ? label : "Port " + port;
        }

        String endpoint() {
            return "127.0.0.1:" + port;
        }

        int enabledCount() {
            return enabledTools.size();
        }

        boolean allDisabled() {
            return enabledTools.isEmpty();
        }
    }

    /**
     * Every service the stored tool definitions point at, ordered by port.
     *
     * <p>Only definitions are read — no probe and no network — so this is safe to call
     * on the UI thread.
     */
    static List<Service> discover(Context context) {
        JSONArray definitions = ToolSettings.customTools(context);
        Map<Integer, String> labels = new TreeMap<>();
        Map<Integer, List<String>> tools = new TreeMap<>();
        Map<Integer, List<String>> enabled = new HashMap<>();

        for (int i = 0; i < definitions.length(); i++) {
            JSONObject definition = definitions.optJSONObject(i);
            if (definition == null) continue;
            String name = definition.optString("name", "").trim();
            if (name.isEmpty()) continue;
            Set<Integer> ports = portsOf(searchableText(definition), labels);
            if (ports.isEmpty()) continue;
            boolean on = ToolSettings.isEnabled(context, name);
            for (int port : ports) {
                List<String> names = tools.get(port);
                if (names == null) {
                    names = new ArrayList<>();
                    tools.put(port, names);
                }
                names.add(name);
                if (on) {
                    List<String> live = enabled.get(port);
                    if (live == null) {
                        live = new ArrayList<>();
                        enabled.put(port, live);
                    }
                    live.add(name);
                }
            }
        }

        List<Service> services = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> entry : tools.entrySet()) {
            int port = entry.getKey();
            List<String> on = enabled.get(port);
            services.add(new Service(port, labels.get(port), entry.getValue(),
                    on == null ? Collections.emptyList() : on));
        }
        return services;
    }

    /** Tool name → the services it connects to, for badges and for {@code tool_list}. */
    static Map<String, List<Service>> byTool(Context context) {
        Map<String, List<Service>> bound = new HashMap<>();
        for (Service service : discover(context)) {
            for (String tool : service.tools) {
                List<Service> list = bound.get(tool);
                if (list == null) {
                    list = new ArrayList<>();
                    bound.put(tool, list);
                }
                list.add(service);
            }
        }
        return bound;
    }

    /** JSON form used by {@code tool_list}: port, declared label, cached live state. */
    static JSONArray describe(Context context, String tool) {
        JSONArray out = new JSONArray();
        List<Service> services = byTool(context).get(tool);
        if (services == null) return out;
        for (Service service : services) out.put(json(service));
        return out;
    }

    static JSONObject json(Service service) {
        JSONObject out = new JSONObject();
        try {
            out.put("port", service.port);
            out.put("label", service.label);
            out.put("endpoint", service.endpoint());
            out.put("tools", new JSONArray(service.tools));
            Boolean live = live(service.port);
            // null means "not probed yet", which is different from "not answering".
            out.put("live", live == null ? JSONObject.NULL : live);
        } catch (org.json.JSONException e) {
            BridgeLog.warn("LocalServices", "Cannot describe service on " + service.port);
        }
        return out;
    }

    /** Cached answer, or null when this port has not been probed yet. */
    static Boolean live(int port) {
        return LIVE.get(port);
    }

    /** True when any port has no cached answer or one older than the TTL. */
    static boolean needsCheck(List<Service> services) {
        long now = System.currentTimeMillis();
        for (Service service : services) {
            if (!LIVE.containsKey(service.port)) return true;
            Long checked = CHECKED_AT.get(service.port);
            if (checked == null || now - checked > CACHE_TTL_MS) return true;
        }
        return false;
    }

    /** How many of {@code services} answered on their last probe. */
    static int liveCount(List<Service> services) {
        int live = 0;
        for (Service service : services) {
            if (Boolean.TRUE.equals(LIVE.get(service.port))) live++;
        }
        return live;
    }

    static boolean probed(List<Service> services) {
        for (Service service : services) {
            if (LIVE.containsKey(service.port)) return true;
        }
        return false;
    }

    /**
     * Probes every discovered port on a background thread, then posts {@code done}
     * to the main thread. Concurrent calls collapse into the running probe, so a
     * render loop cannot start threads faster than they finish.
     */
    static void refreshAsync(Context context, Runnable done) {
        if (!PROBING.compareAndSet(false, true)) return;
        final List<Service> services = discover(context);
        Thread worker = new Thread(() -> {
            try {
                long now = System.currentTimeMillis();
                Set<Integer> present = new LinkedHashSet<>();
                for (Service service : services) {
                    present.add(service.port);
                    LIVE.put(service.port, probe(service.port));
                    CHECKED_AT.put(service.port, now);
                }
                // Forget ports that no tool points at any more, so a removed tool cannot
                // leave a stale "live" dot behind.
                for (Integer port : new ArrayList<>(LIVE.keySet())) {
                    if (!present.contains(port)) {
                        LIVE.remove(port);
                        CHECKED_AT.remove(port);
                    }
                }
            } finally {
                PROBING.set(false);
            }
            if (done != null) new Handler(Looper.getMainLooper()).post(done);
        }, "snapgent-service-probe");
        worker.setDaemon(true);
        worker.start();
    }

    /** Opens a TCP connection to the loopback port; a refused connect means "not running". */
    static boolean probe(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /** The parts of a definition that can hold an address: everything the tool runs or requests. */
    private static String searchableText(JSONObject definition) {
        StringBuilder text = new StringBuilder();
        append(text, definition.optString("code", ""));
        text.append('\n');
        append(text, definition.optString("url", ""));
        text.append('\n');
        append(text, definition.optString("body", ""));
        JSONObject headers = definition.optJSONObject("headers");
        if (headers != null) {
            text.append('\n');
            append(text, headers.toString());
        }
        return text.toString();
    }

    private static void append(StringBuilder target, String value) {
        if (value != null && !value.isEmpty()) target.append(value);
    }

    /** Ports named by one definition; also fills {@code labels} from explicit declarations. */
    private static Set<Integer> portsOf(String text, Map<Integer, String> labels) {
        Set<Integer> ports = new LinkedHashSet<>();
        Matcher declaration = DECLARATION.matcher(text);
        while (declaration.find()) {
            Matcher pair = PAIR.matcher(declaration.group(1));
            while (pair.find()) {
                int port = parsePort(pair.group(2));
                if (port < 0) continue;
                ports.add(port);
                if (!labels.containsKey(port)) labels.put(port, pair.group(1));
            }
        }
        Matcher address = ADDRESS.matcher(text);
        while (address.find()) {
            int port = parsePort(address.group(1));
            if (port >= 0) ports.add(port);
        }
        return ports;
    }

    private static int parsePort(String raw) {
        try {
            int port = Integer.parseInt(raw);
            return port >= MIN_PORT && port <= MAX_PORT ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
