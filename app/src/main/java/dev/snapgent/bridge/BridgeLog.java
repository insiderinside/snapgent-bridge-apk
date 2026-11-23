package dev.snapgent.bridge;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Thread-safe in-memory event log shared by bridge components and the UI. */
public final class BridgeLog {
    private static final int MAX_ENTRIES = 500;
    private static final ArrayDeque<Entry> entries = new ArrayDeque<>();

    private BridgeLog() {}

    public static final class Entry {
        public final long timestamp;
        public final String level;
        public final String source;
        public final String message;

        private Entry(long timestamp, String level, String source, String message) {
            this.timestamp = timestamp;
            this.level = level;
            this.source = source;
            this.message = message;
        }
    }

    public static synchronized void add(String level, String source, String message) {
        entries.addLast(new Entry(System.currentTimeMillis(), level, source, message));
        while (entries.size() > MAX_ENTRIES) entries.removeFirst();
    }

    public static void info(String source, String message) { add("INFO", source, message); }
    public static void warn(String source, String message) { add("WARN", source, message); }
    public static void error(String source, String message) { add("ERROR", source, message); }

    public static synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }

    public static synchronized int size() { return entries.size(); }

    public static synchronized void clear() { entries.clear(); }

    public static String formattedSnapshot() {
        List<Entry> copy = snapshot();
        StringBuilder output = new StringBuilder();
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
        for (Entry entry : copy) {
            output.append(format.format(new Date(entry.timestamp)))
                    .append("  ").append(entry.level)
                    .append("  [").append(entry.source).append("] ")
                    .append(entry.message).append('\n');
        }
        return output.length() == 0 ? "No activity yet.\nBridge events will appear here." : output.toString();
    }
}
