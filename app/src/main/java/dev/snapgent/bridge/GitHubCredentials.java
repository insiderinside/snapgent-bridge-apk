package dev.snapgent.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;

/** Secure GitHub credential storage backed by the Android Keystore. */
public final class GitHubCredentials {
    private static final String PREFS = "snapgent_github_credentials";
    private static final String KEY_ALIAS = "snapgent_github_pat_v1";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_TOKEN = "encrypted_token";
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private GitHubCredentials() {}

    public static final class Credentials {
        public final String username;
        public final String token;

        private Credentials(String username, String token) {
            this.username = username;
            this.token = token;
        }
    }

    public static final class Validation {
        public final boolean authenticated;
        public final int statusCode;
        public final String login;
        public final String scopes;

        private Validation(boolean authenticated, int statusCode, String login, String scopes) {
            this.authenticated = authenticated;
            this.statusCode = statusCode;
            this.login = login;
            this.scopes = scopes;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isConfigured(Context context) {
        String encrypted = preferences(context).getString(KEY_TOKEN, null);
        return encrypted != null && !encrypted.isEmpty();
    }

    public static String username(Context context) {
        return preferences(context).getString(KEY_USERNAME, "");
    }

    /** An empty token preserves an already configured token. */
    public static void save(Context context, String username, String token) throws IOException {
        String cleanUsername = username == null ? "" : username.trim();
        String cleanToken = token == null ? "" : token.trim();
        if (cleanUsername.isEmpty() || cleanUsername.length() > 128) {
            throw new IOException("Enter a valid GitHub username");
        }
        if (cleanToken.length() > 512) throw new IOException("GitHub token is too long");
        SharedPreferences prefs = preferences(context);
        String existing = prefs.getString(KEY_TOKEN, null);
        if (cleanToken.isEmpty() && (existing == null || existing.isEmpty())) {
            throw new IOException("Enter a GitHub Personal Access Token");
        }
        SharedPreferences.Editor editor = prefs.edit().putString(KEY_USERNAME, cleanUsername);
        if (!cleanToken.isEmpty()) editor.putString(KEY_TOKEN, encrypt(cleanToken));
        if (!editor.commit()) throw new IOException("Cannot save GitHub credentials");
    }

    public static Credentials get(Context context) throws IOException {
        SharedPreferences prefs = preferences(context);
        String encrypted = prefs.getString(KEY_TOKEN, null);
        if (encrypted == null || encrypted.isEmpty()) return null;
        String username = prefs.getString(KEY_USERNAME, "");
        return new Credentials(username, decrypt(encrypted));
    }

    public static void clear(Context context) throws IOException {
        if (!preferences(context).edit().clear().commit()) {
            throw new IOException("Cannot clear GitHub credentials");
        }
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot clear GitHub encryption key", e);
        }
    }

    private static SecretKey encryptionKey() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        try {
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) {
                return (SecretKey) store.getKey(KEY_ALIAS, null);
            }
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            return generator.generateKey();
        } catch (java.security.cert.CertificateException e) {
            throw new IOException("Cannot access Android Keystore", e);
        }
    }

    private static String encrypt(String value) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.encodeToString(combined, Base64.NO_WRAP | Base64.URL_SAFE);
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot encrypt GitHub token", e);
        }
    }

    private static String decrypt(String value) throws IOException {
        try {
            byte[] combined = Base64.decode(value, Base64.NO_WRAP | Base64.URL_SAFE);
            if (combined.length < 13) throw new IOException("Invalid encrypted GitHub token");
            byte[] iv = Arrays.copyOfRange(combined, 0, 12);
            byte[] encrypted = Arrays.copyOfRange(combined, 12, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("Cannot decrypt GitHub token. Please configure it again.", e);
        }
    }

    /** Validate the stored PAT against GitHub's authenticated-user API. */
    public static Validation validate(Context context) throws IOException {
        Credentials credentials = get(context);
        if (credentials == null) throw new IOException("GitHub credentials are not configured");
        return validateToken(credentials.token);
    }

    /** Validate a candidate PAT before persisting it. */
    public static Validation validateToken(String token) throws IOException {
        if (token == null || token.trim().isEmpty() || token.length() > 512)
            throw new IOException("Enter a valid GitHub Personal Access Token");
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL("https://api.github.com/user").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Bearer " + token.trim());
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "Snapgent-Android-Bridge");

            int status = connection.getResponseCode();
            String login = "";
            if (status == 200) {
                String body = readLimited(connection.getInputStream());
                try { login = new JSONObject(body).optString("login", ""); }
                catch (JSONException e) { throw new IOException("Invalid response from GitHub"); }
                if (login.isEmpty()) throw new IOException("GitHub did not return an account login");
            }
            String scopes = connection.getHeaderField("X-OAuth-Scopes");
            return new Validation(status == 200, status, login, scopes == null ? "" : scopes);
        } catch (IOException e) {
            throw new IOException("GitHub connection failed: " + e.getClass().getSimpleName(), e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readLimited(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (output.size() + count > MAX_RESPONSE_BYTES) {
                    throw new IOException("GitHub response exceeds size limit");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
