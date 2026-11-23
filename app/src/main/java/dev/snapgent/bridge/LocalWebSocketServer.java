package dev.snapgent.bridge;

import android.content.Context;
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalWebSocketServer implements Closeable {
    private static final String TAG = "SnapgentBridge";
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_MESSAGE = 1024 * 1024;
    private static final int MAX_CLIENTS = 8;

    private final int port;
    private final String token;
    private final McpHandler handler;
    private final SnapgentProtocolHandler snapgentHandler;
    private final boolean nativeMode;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Set<Socket> clients = Collections.synchronizedSet(new HashSet<>());
    private ServerSocket listener;
    private volatile boolean active;

    public LocalWebSocketServer(Context context, int port, String token) {
        this(context, port, token, false);
    }

    public LocalWebSocketServer(Context context, int port, String token, boolean nativeMode) {
        this.port = port;
        this.token = token;
        this.nativeMode = nativeMode;
        this.handler = new McpHandler(context.getApplicationContext());
        this.snapgentHandler = nativeMode ? new SnapgentProtocolHandler(handler) : null;
    }

    public synchronized void start() throws IOException {
        if (active) return;
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), MAX_CLIENTS);
        active = true;
        BridgeLog.info(source(), "Listening on 127.0.0.1:" + port);
        workers.execute(() -> {
            while (active) {
                try {
                    Socket client = listener.accept();
                    if (clients.size() >= MAX_CLIENTS) {
                        BridgeLog.warn(source(), "Connection rejected: maximum clients reached");
                        client.close();
                        continue;
                    }
                    clients.add(client);
                    workers.execute(() -> handleClient(client));
                } catch (IOException e) {
                    if (active) Log.e(TAG, "Accept failed", e);
                }
            }
        });
    }

    private void handleClient(Socket client) {
        boolean connected = false;
        try (Socket socket = client) {
            socket.setSoTimeout(10000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            if (!handshake(input, output)) return;
            connected = true;
            BridgeLog.info(source(), "WebSocket client connected");
            socket.setSoTimeout(0);
            if (nativeMode) {
                writeFrame(output, 1, snapgentHandler.connected().toString().getBytes(StandardCharsets.UTF_8));
            }
            DataInputStream data = new DataInputStream(input);
            ByteArrayOutputStream fragments = null;
            while (active && !socket.isClosed()) {
                int first = data.read();
                if (first < 0) break;
                int second = data.readUnsignedByte();
                if ((first & 0x70) != 0 || (second & 0x80) == 0) break;
                boolean fin = (first & 0x80) != 0;
                int opcode = first & 15;
                long length = second & 127;
                if (length == 126) length = data.readUnsignedShort();
                else if (length == 127) length = data.readLong();
                if (length < 0 || length > MAX_MESSAGE) break;
                if (opcode >= 8 && (!fin || length > 125)) break;
                byte[] mask = new byte[4];
                data.readFully(mask);
                byte[] payload = new byte[(int) length];
                data.readFully(payload);
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];

                if (opcode == 8) {
                    writeFrame(output, 8, payload);
                    break;
                }
                if (opcode == 9) {
                    writeFrame(output, 10, payload);
                    continue;
                }
                if (opcode == 10) continue;
                if (opcode == 1) {
                    if (fragments != null) break;
                    fragments = new ByteArrayOutputStream();
                } else if (opcode != 0 || fragments == null) {
                    break;
                }
                if (fragments.size() + payload.length > MAX_MESSAGE) break;
                fragments.write(payload);
                if (fin) {
                    String message = new String(fragments.toByteArray(), StandardCharsets.UTF_8);
                    fragments = null;
                    String response = nativeMode ? snapgentHandler.handle(message) : handler.handle(message);
                    if (response != null) writeFrame(output, 1, response.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException | RuntimeException | org.json.JSONException e) {
            if (active) {
                Log.d(TAG, "Client disconnected: " + e.getClass().getSimpleName());
                BridgeLog.warn(source(), "Client error: " + e.getClass().getSimpleName());
            }
        } finally {
            if (connected) BridgeLog.info(source(), "WebSocket client disconnected");
            clients.remove(client);
        }
    }

    private boolean handshake(InputStream input, OutputStream output) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int state = 0;
        while (header.size() < 8192 && state < 4) {
            int c = input.read();
            if (c < 0) return false;
            header.write(c);
            if (c == "\r\n\r\n".charAt(state)) state++;
            else state = c == '\r' ? 1 : 0;
        }
        if (state != 4) return false;
        String[] lines = header.toString("US-ASCII").split("\r\n");
        String[] request = lines[0].split(" ");
        if (request.length < 3 || !"GET".equals(request[0])) {
            BridgeLog.warn(source(), "Invalid WebSocket handshake request");
            reject(output, "400 Bad Request");
            return false;
        }
        if (!(nativeMode ? "/".equals(request[1]) : authorized(request[1]))) {
            BridgeLog.warn(source(), "WebSocket authentication rejected");
            reject(output, "403 Forbidden");
            return false;
        }
        String origin = null;
        String key = null;
        boolean upgrade = false;
        boolean connection = false;
        boolean version = false;
        for (int i = 1; i < lines.length; i++) {
            int separator = lines[i].indexOf(':');
            if (separator < 0) continue;
            String name = lines[i].substring(0, separator).trim().toLowerCase(Locale.US);
            String value = lines[i].substring(separator + 1).trim();
            if (name.equals("origin")) origin = value;
            if (name.equals("sec-websocket-key")) key = value;
            if (name.equals("upgrade")) upgrade = value.equalsIgnoreCase("websocket");
            if (name.equals("connection")) connection = value.toLowerCase(Locale.US).contains("upgrade");
            if (name.equals("sec-websocket-version")) version = value.equals("13");
        }
        if (!upgrade || !connection || !version || key == null) {
            BridgeLog.warn(source(), "Incomplete WebSocket handshake headers");
            reject(output, "400 Bad Request");
            return false;
        }
        // snapgentV3 does not yet send a pairing token. Restrict this legacy-compatible
        // endpoint to loopback and browser-extension origins; ordinary web pages are denied.
        if (nativeMode && (origin == null || !isExtensionOrigin(origin))) {
            BridgeLog.warn(source(), "Native bridge rejected an untrusted origin");
            reject(output, "403 Forbidden");
            return false;
        }
        try {
            if (Base64.decode(key, Base64.DEFAULT).length != 16) throw new IllegalArgumentException();
            byte[] digest = MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII));
            String accept = Base64.encodeToString(digest, Base64.NO_WRAP);
            String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n";
            output.write(response.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            return true;
        } catch (IllegalArgumentException | NoSuchAlgorithmException e) {
            reject(output, "400 Bad Request");
            return false;
        }
    }

    private String source() { return nativeMode ? "SNAPGENTV3" : "MCP"; }

    private static boolean isExtensionOrigin(String origin) {
        String value = origin.trim().toLowerCase(Locale.US);
        return value.matches("^(chrome-extension|moz-extension|safari-web-extension)://[a-z0-9._-]+/?$");
    }

    private boolean authorized(String resource) {
        if (!resource.startsWith("/ws?")) return false;
        String[] parameters = resource.substring(4).split("&");
        for (String parameter : parameters) {
            if (parameter.startsWith("token=")) {
                byte[] supplied = parameter.substring(6).getBytes(StandardCharsets.UTF_8);
                byte[] expected = token.getBytes(StandardCharsets.UTF_8);
                return MessageDigest.isEqual(supplied, expected);
            }
        }
        return false;
    }

    private static void reject(OutputStream output, String status) throws IOException {
        output.write(("HTTP/1.1 " + status + "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void writeFrame(OutputStream output, int opcode, byte[] payload) throws IOException {
        output.write(0x80 | opcode);
        int length = payload.length;
        if (length < 126) output.write(length);
        else if (length <= 65535) {
            output.write(126);
            output.write((length >>> 8) & 255);
            output.write(length & 255);
        } else {
            output.write(127);
            long extendedLength = length;
            for (int shift = 56; shift >= 0; shift -= 8) output.write((int) ((extendedLength >>> shift) & 255));
        }
        output.write(payload);
        output.flush();
    }

    @Override public synchronized void close() throws IOException {
        active = false;
        BridgeLog.info(source(), "Server stopped on port " + port);
        if (listener != null) listener.close();
        synchronized (clients) {
            for (Socket client : clients) {
                try { client.close(); } catch (IOException ignored) {}
            }
            clients.clear();
        }
        workers.shutdownNow();
    }
}
