package io.localproxy.telegramws;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONException;
import org.json.JSONObject;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

final class ProxySettings {
    static final String DEFAULT_DC = "2:149.154.167.220\n4:149.154.167.220";
    private static final Pattern DOMAIN = Pattern.compile(
            "(?i)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?");
    final int port, pool;
    final String secret, dc, domains, workers;
    final boolean cloudflare, h2, testDc, debug;

    ProxySettings(int port, int pool, String secret, String dc, String domains, String workers,
                  boolean cloudflare, boolean h2, boolean testDc, boolean debug) {
        this.port = port;
        this.pool = pool;
        this.secret = secret;
        this.dc = dc;
        this.domains = domains;
        this.workers = workers;
        this.cloudflare = cloudflare;
        this.h2 = h2;
        this.testDc = testDc;
        this.debug = debug;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("proxy_settings", Context.MODE_PRIVATE);
    }

    static synchronized ProxySettings load(Context context) {
        SharedPreferences prefs = prefs(context);
        String secret = prefs.getString("secret", "");
        if (secret.isEmpty()) {
            secret = newSecret();
            prefs.edit().putString("secret", secret).apply();
        }
        return new ProxySettings(prefs.getInt("port", 1443), prefs.getInt("pool", 4),
                secret, prefs.getString("dc", DEFAULT_DC), prefs.getString("domains", ""),
                prefs.getString("workers", ""), prefs.getBoolean("cloudflare", false),
                prefs.getBoolean("h2", true), prefs.getBoolean("testDc", false),
                prefs.getBoolean("debug", false));
    }

    static String newSecret() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder(32);
        for (byte value : bytes) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

    static int number(String input, int min, int max, String label) {
        try {
            int value = Integer.parseInt(input.trim());
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException ignored) {
            // Show the same range error for malformed or out-of-range values.
        }
        throw new IllegalArgumentException(label + " должен быть от " + min + " до " + max);
    }

    void validate() {
        if (!secret.matches("[a-f0-9]{32}"))
            throw new IllegalArgumentException("Секрет должен содержать 32 шестнадцатеричных символа");
        for (String entry : entries(dc)) {
            if (entry.isEmpty()) continue;
            String[] pair = entry.split(":", -1);
            if (pair.length != 2 || !pair[0].matches("(?:[1-5]|203)"))
                throw new IllegalArgumentException("Укажите DC с номером 1–5 или 203 в формате DC:IPv4");
            String[] ip = pair[1].split("\\.", -1);
            if (ip.length != 4) throw new IllegalArgumentException("Неверный IPv4: " + pair[1]);
            for (String part : ip) {
                if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255)
                    throw new IllegalArgumentException("Неверный IPv4: " + pair[1]);
            }
        }
        for (String domain : entries(domains + " " + workers)) {
            if (!domain.isEmpty() && (domain.length() > 253 || !DOMAIN.matcher(domain).matches()))
                throw new IllegalArgumentException("Неверное имя домена: " + domain);
        }
    }

    private static String[] entries(String value) {
        return value.trim().split("[\\s,;]+");
    }

    void save(Context context) {
        validate();
        prefs(context).edit().putInt("port", port).putInt("pool", pool).putString("secret", secret)
                .putString("dc", dc).putString("domains", domains).putString("workers", workers)
                .putBoolean("cloudflare", cloudflare).putBoolean("h2", h2)
                .putBoolean("testDc", testDc).putBoolean("debug", debug).apply();
    }

    ProxySettings usingPort(Context context, int actualPort) {
        if (actualPort == port) return this;
        SharedPreferences prefs = prefs(context);
        if (prefs.getInt("port", 1443) == port) {
            prefs.edit().putInt("port", actualPort).apply();
        }
        return new ProxySettings(actualPort, pool, secret, dc, domains, workers,
                cloudflare, h2, testDc, debug);
    }

    String json() {
        try {
            return new JSONObject().put("port", port).put("pool", pool).put("secret", secret)
                    .put("dc", dc).put("domains", domains).put("workers", workers)
                    .put("cloudflare", cloudflare).put("h2", h2).put("testDc", testDc)
                    .put("debug", debug).toString();
        } catch (JSONException exc) {
            throw new IllegalStateException(exc);
        }
    }

    Uri link() {
        return new Uri.Builder().scheme("tg").authority("proxy")
                .appendQueryParameter("server", "127.0.0.1")
                .appendQueryParameter("port", Integer.toString(port))
                .appendQueryParameter("secret", "dd" + secret).build();
    }
}
